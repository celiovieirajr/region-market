package com.acert.chatbot.service;

import com.acert.chatbot.model.SemanticQueryCache;
import com.acert.chatbot.repository.SemanticQueryCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.StringJoiner;

/**
 * Cache semântico na frente de {@code AiService.classifyProductQuery}: antes
 * de chamar a IA pra classificar se uma mensagem é pedido de
 * preço/produto, compara o embedding da mensagem nova contra os embeddings
 * de mensagens já classificadas antes (guardadas em
 * {@code tb_semantic_query_cache}). Se a similaridade de cosseno com a
 * entrada mais parecida bater o threshold configurado, reusa o resultado
 * salvo e PULA a chamada à IA — reduz custo/latência pra perguntas
 * repetidas com fraseado diferente (ex: "quanto custa o arroz" vs "qual o
 * preço do arroz hoje").
 *
 * Fail-safe: qualquer falha ao gerar o embedding (rede indisponível,
 * modelo corrompido etc. — ver {@link EmbeddingService}) é tratada como
 * cache miss, cai direto no fallback de chamar a IA normalmente, e não
 * grava nada no cache (não faz sentido persistir uma linha sem embedding
 * válido).
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final EmbeddingService embeddingService;
    private final SemanticQueryCacheRepository semanticQueryCacheRepository;
    private final AiService aiService;
    private final double threshold;

    public SemanticCacheService(EmbeddingService embeddingService,
                                 SemanticQueryCacheRepository semanticQueryCacheRepository,
                                 AiService aiService,
                                 @Value("${app.semantic-cache.threshold:0.90}") double threshold) {
        this.embeddingService = embeddingService;
        this.semanticQueryCacheRepository = semanticQueryCacheRepository;
        this.aiService = aiService;
        this.threshold = threshold;
    }

    @Transactional
    public AiService.ProductQueryIntent classify(String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.trim().toLowerCase();

        float[] embedding = null;
        try {
            embedding = embeddingService.embed(normalized);
        } catch (Exception ex) {
            log.warn("Falha ao gerar embedding pra cache semântico, caindo no fallback de IA para '{}'",
                    userMessage, ex);
        }

        if (embedding != null) {
            SemanticQueryCache best = null;
            double bestSimilarity = -1.0;

            for (SemanticQueryCache candidate : semanticQueryCacheRepository.findAll()) {
                float[] candidateEmbedding = deserialize(candidate.getEmbedding());
                if (candidateEmbedding == null || candidateEmbedding.length != embedding.length) {
                    continue;
                }
                double similarity = cosineSimilarity(embedding, candidateEmbedding);
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = candidate;
                }
            }

            if (best != null && bestSimilarity >= threshold) {
                best.setHits(best.getHits() + 1);
                best.setLastUsedAt(LocalDateTime.now());
                semanticQueryCacheRepository.save(best);
                log.debug("Cache semântico HIT (similaridade {}) para '{}' -> reusando resultado de '{}'",
                        bestSimilarity, userMessage, best.getMensagemNormalizada());
                return new AiService.ProductQueryIntent(best.isProdutoQuery(), best.getTermo());
            }
        }

        AiService.ProductQueryIntent intent = aiService.classifyProductQuery(userMessage);

        if (embedding != null) {
            SemanticQueryCache entry = new SemanticQueryCache();
            entry.setMensagemNormalizada(normalized);
            entry.setEmbedding(serialize(embedding));
            entry.setProdutoQuery(intent.isProdutoQuery());
            entry.setTermo(intent.termo());
            entry.setHits(1);
            LocalDateTime now = LocalDateTime.now();
            entry.setCreatedAt(now);
            entry.setLastUsedAt(now);
            semanticQueryCacheRepository.save(entry);
        }

        return intent;
    }

    /**
     * Vetores já vêm normalizados (norma L2 = 1) de {@link EmbeddingService},
     * então o produto escalar já É a similaridade de cosseno — mas calcula
     * de forma defensiva (divide pelas normas reais) pra não depender
     * silenciosamente dessa premissa.
     */
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String serialize(float[] embedding) {
        StringJoiner joiner = new StringJoiner(",");
        for (float v : embedding) {
            joiner.add(Float.toString(v));
        }
        return joiner.toString();
    }

    private float[] deserialize(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return null;
        }
        try {
            String[] parts = serialized.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i]);
            }
            return result;
        } catch (Exception ex) {
            log.warn("Falha ao desserializar embedding do cache semântico, ignorando linha", ex);
            return null;
        }
    }
}
