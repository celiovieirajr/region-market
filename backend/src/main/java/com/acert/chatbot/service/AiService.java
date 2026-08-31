package com.acert.chatbot.service;

import com.acert.chatbot.model.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Chama a API de chat da DeepSeek (compatível com o formato OpenAI) para
 * gerar as respostas do chatbot. Se algum site de referência foi selecionado
 * (ver {@link SiteContext}), o texto das páginas é enviado como contexto para
 * a IA "pesquisar" nele antes de responder.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public AiService(@Value("${app.ai.api-key}") String apiKey,
                      @Value("${app.ai.base-url}") String baseUrl,
                      @Value("${app.ai.model}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public String generateReply(String userMessage, List<ChatMessage> history, List<SiteContext> siteContexts) {
        try {
            String systemPrompt = "Você é um assistente útil que responde em português do Brasil. "
                    + "Quando o usuário perguntar sobre preços ou informações de uma cidade e forem fornecidos "
                    + "trechos de sites de referência, use esses trechos para responder com os produtos e preços "
                    + "encontrados. "
                    + "IMPORTANTE - formato da resposta: separe os resultados POR LOJA, usando o nome do site "
                    + "como um subtítulo (ex: '**Nome do Site**' seguido da lista de produtos/preços daquela "
                    + "loja). Se mais de uma loja tiver resultados, mostre cada uma em sua própria seção, "
                    + "nessa ordem. Ao final, se fizer sentido, indique qual foi o preço mais barato encontrado "
                    + "no total e em qual loja. "
                    + "IMPORTANTE: se um site NÃO tiver a informação pedida (produto/preço não aparece nos "
                    + "trechos dele), simplesmente IGNORE esse site na resposta — não o cite, não diga que ele "
                    + "'não trouxe informações' ou 'não pôde ser acessado'. Mencione APENAS os sites onde você "
                    + "realmente encontrou a informação pedida. Só diga que não encontrou a informação em lugar "
                    + "nenhum se NENHUM dos sites tiver o dado.";

            StringBuilder userPrompt = new StringBuilder();
            if (siteContexts != null && !siteContexts.isEmpty()) {
                userPrompt.append("Trechos dos sites de referência da cidade:\n\n");
                for (SiteContext ctx : siteContexts) {
                    userPrompt.append("--- Site: ").append(ctx.getLabel())
                            .append(" (").append(ctx.getUrl()).append(") ---\n")
                            .append(ctx.getText())
                            .append("\n\n");
                }
                userPrompt.append("Pergunta do usuário: ").append(userMessage);
            } else {
                userPrompt.append(userMessage);
            }

            return chatCompletion(systemPrompt, userPrompt.toString(), 0.4, null);
        } catch (Exception ex) {
            return "Não consegui falar com a IA agora (verifique a API key/conexão). Detalhe técnico: " + ex.getMessage();
        }
    }

    /**
     * Resultado da classificação de intenção de uma mensagem do usuário:
     * indica se ela é de fato um pedido de preço/produto de mercado e, se
     * for, o termo de busca já extraído (ex: "fraldinha"). Usado por
     * {@link ChatService#resolveSiteContexts} para decidir se vale a pena
     * disparar scraping (Playwright) nos sites da cidade — mensagens como
     * saudações, agradecimentos ou perguntas genéricas não devem disparar
     * nenhum scraping.
     */
    public record ProductQueryIntent(boolean isProdutoQuery, String termo) {
    }

    private static final ProductQueryIntent NOT_PRODUCT_QUERY = new ProductQueryIntent(false, null);

    /**
     * Classifica, numa única chamada à IA, se a mensagem do usuário é
     * realmente um pedido de preço/produto de mercado e, em caso positivo,
     * já extrai o termo de busca do produto — usado para montar a URL de
     * busca dos sites que suportam busca dinâmica (ver {@code CitySource.url}
     * com o placeholder {@code {termo}}) e para decidir se o scraping deve
     * rodar. Fail-safe: qualquer erro (rede, parse de JSON, etc.) é tratado
     * como "não é pedido de produto", pra nunca travar o chat nem lançar
     * scraping desnecessário por causa de uma resposta inesperada da IA.
     */
    public ProductQueryIntent classifyProductQuery(String userMessage) {
        try {
            String systemPrompt = "Você classifica mensagens de um chat sobre preços de mercado. "
                    + "Responda APENAS um JSON estrito, sem markdown, sem explicações, no formato: "
                    + "{\"isProdutoQuery\": true|false, \"termo\": \"nome do produto\"|null}. "
                    + "isProdutoQuery deve ser true SOMENTE se a mensagem for um pedido de preço/produto de "
                    + "mercado (ex: perguntar o preço, valor ou custo de um produto). "
                    + "Se a mensagem for uma saudação (ex: 'boa noite', 'olá'), agradecimento, despedida, "
                    + "pergunta genérica, ou qualquer coisa que não seja um pedido de preço/produto de mercado, "
                    + "isProdutoQuery deve ser false e termo deve ser null. "
                    + "Quando isProdutoQuery for true, termo deve ser o nome do produto em minúsculas, sem "
                    + "acentuação e sem pontuação. "
                    + "Exemplos: 'Qual o preço da fraldinha?' -> {\"isProdutoQuery\": true, \"termo\": \"fraldinha\"}. "
                    + "'quanto custa 1kg de arroz tipo 1?' -> {\"isProdutoQuery\": true, \"termo\": \"arroz tipo 1\"}. "
                    + "'Olá, boa noite' -> {\"isProdutoQuery\": false, \"termo\": null}. "
                    + "'obrigado, até mais' -> {\"isProdutoQuery\": false, \"termo\": null}.";

            String raw = chatCompletion(systemPrompt, userMessage, 0.0, 60);
            if (raw == null || raw.isBlank()) {
                return NOT_PRODUCT_QUERY;
            }

            String json = raw.trim();
            // Fallback defensivo: se a IA envolver o JSON em cercas de código
            // markdown mesmo tendo sido instruída a não fazer isso, extrai só
            // o miolo entre a primeira '{' e a última '}'.
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }

            JsonNode node = objectMapper.readTree(json);
            boolean isProdutoQuery = node.path("isProdutoQuery").asBoolean(false);
            if (!isProdutoQuery) {
                return NOT_PRODUCT_QUERY;
            }
            JsonNode termoNode = node.path("termo");
            String termo = (termoNode.isMissingNode() || termoNode.isNull()) ? null : termoNode.asText().trim();
            return new ProductQueryIntent(true, termo);
        } catch (Exception ex) {
            log.warn("Falha ao classificar intenção da mensagem '{}', tratando como não-produto", userMessage, ex);
            return NOT_PRODUCT_QUERY;
        }
    }

    private String chatCompletion(String systemPrompt, String userContent, double temperature, Integer maxTokens) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", temperature);
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens);
        }

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(root)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("API retornou status " + response.statusCode() + ": " + response.body());
        }

        JsonNode body = objectMapper.readTree(response.body());
        JsonNode content = body.path("choices").path(0).path("message").path("content");

        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Resposta da IA em formato inesperado: " + response.body());
        }

        return content.asText();
    }
}
