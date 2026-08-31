package com.acert.chatbot.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * Gera embeddings de frase (vetor 384 dimensões) usando o modelo
 * `Xenova/paraphrase-multilingual-MiniLM-L12-v2` (sentence-transformers
 * exportado em ONNX, quantizado) — usado por {@code SemanticCacheService}
 * pra comparar mensagens do chat por similaridade semântica, em vez de
 * apenas texto literal.
 *
 * O modelo (tokenizer.json + model_quantized.onnx, ~120MB no total) é
 * baixado do Hugging Face Hub sob demanda (lazy, no primeiro
 * {@link #embed(String)} chamado — nunca no boot do Spring, pra não
 * atrasar a subida da aplicação) e cacheado em disco em
 * {@code backend/data/models/}; downloads seguintes reaproveitam o arquivo
 * já baixado (idempotente, mesmo conceito do install do Playwright já
 * existente no projeto).
 *
 * Qualquer falha (rede indisponível, arquivo corrompido, etc.) é logada e
 * propagada como {@link EmbeddingUnavailableException} — quem chama
 * ({@code SemanticCacheService}) trata isso como cache miss e cai no
 * fallback normal de chamar a IA, nunca deixando o erro chegar ao usuário.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String MODEL_DIR = "data/models";
    private static final String TOKENIZER_FILE = "tokenizer.json";
    private static final String MODEL_FILE = "model_quantized.onnx";

    private static final String TOKENIZER_URL =
            "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json";
    private static final String MODEL_URL =
            "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/onnx/model_quantized.onnx";

    private static final int EMBEDDING_DIM = 384;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private volatile HuggingFaceTokenizer tokenizer;
    private volatile OrtEnvironment ortEnvironment;
    private volatile OrtSession ortSession;

    /**
     * Erro genérico pra qualquer falha ao gerar embedding (download,
     * tokenização, inferência ONNX) — mantido "unchecked" pra não poluir a
     * assinatura de quem chama; sempre tratado como miss por
     * {@code SemanticCacheService}.
     */
    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Gera o embedding normalizado (norma L2 = 1) de {@code text}, com mean
     * pooling ponderado pela attention mask (ignora tokens de padding).
     * `synchronized` porque `OrtSession.run` não é garantidamente
     * thread-safe pra chamadas concorrentes com os mesmos buffers, e o
     * volume de chamadas aqui é baixo (uma por mensagem de chat) — não vale
     * o risco.
     */
    public synchronized float[] embed(String text) {
        try {
            ensureModelLoaded();

            Encoding encoding = tokenizer.encode(text);
            long[] ids = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();

            long[] shape = {1, ids.length};
            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(ids), shape);
                 OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(attentionMask), shape);
                 OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(typeIds), shape)) {

                Map<String, OnnxTensor> inputs = Map.of(
                        "input_ids", inputIdsTensor,
                        "attention_mask", attentionMaskTensor,
                        "token_type_ids", tokenTypeIdsTensor);

                try (OrtSession.Result result = ortSession.run(inputs)) {
                    // Output esperado: last_hidden_state, shape [1, seqLen, 384]
                    float[][][] lastHiddenState = (float[][][]) result.get(0).getValue();
                    return meanPoolAndNormalize(lastHiddenState[0], attentionMask);
                }
            }
        } catch (EmbeddingUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EmbeddingUnavailableException("Falha ao gerar embedding", ex);
        }
    }

    private float[] meanPoolAndNormalize(float[][] tokenEmbeddings, long[] attentionMask) {
        float[] pooled = new float[EMBEDDING_DIM];
        long validTokens = 0;
        for (int t = 0; t < tokenEmbeddings.length; t++) {
            if (attentionMask[t] == 0) {
                continue;
            }
            validTokens++;
            float[] tokenVec = tokenEmbeddings[t];
            for (int d = 0; d < EMBEDDING_DIM; d++) {
                pooled[d] += tokenVec[d];
            }
        }
        if (validTokens == 0) {
            validTokens = 1;
        }
        for (int d = 0; d < EMBEDDING_DIM; d++) {
            pooled[d] /= validTokens;
        }

        double norm = 0.0;
        for (float v : pooled) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < EMBEDDING_DIM; d++) {
                pooled[d] = (float) (pooled[d] / norm);
            }
        }
        return pooled;
    }

    private void ensureModelLoaded() {
        if (tokenizer != null && ortSession != null) {
            return;
        }
        synchronized (this) {
            if (tokenizer != null && ortSession != null) {
                return;
            }
            try {
                Path modelDir = Paths.get(MODEL_DIR);
                Files.createDirectories(modelDir);

                Path tokenizerPath = modelDir.resolve(TOKENIZER_FILE);
                Path modelPath = modelDir.resolve(MODEL_FILE);

                long start = System.currentTimeMillis();
                boolean downloadedSomething = false;
                if (!Files.exists(tokenizerPath) || Files.size(tokenizerPath) == 0) {
                    downloadFile(TOKENIZER_URL, tokenizerPath);
                    downloadedSomething = true;
                }
                if (!Files.exists(modelPath) || Files.size(modelPath) == 0) {
                    downloadFile(MODEL_URL, modelPath);
                    downloadedSomething = true;
                }
                if (downloadedSomething) {
                    log.info("Download do modelo de embedding concluído em {}ms", System.currentTimeMillis() - start);
                }

                this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath, Collections.emptyMap());

                this.ortEnvironment = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                this.ortSession = ortEnvironment.createSession(modelPath.toString(), options);
            } catch (Exception ex) {
                log.error("Não foi possível carregar o modelo de embedding (download ou inicialização falhou)", ex);
                throw new EmbeddingUnavailableException("Modelo de embedding indisponível", ex);
            }
        }
    }

    private void downloadFile(String url, Path destination) throws IOException, InterruptedException {
        Path tmp = destination.resolveSibling(destination.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download falhou (" + url + "), status " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
    }
}
