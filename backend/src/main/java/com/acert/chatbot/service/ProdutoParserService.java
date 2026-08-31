package com.acert.chatbot.service;

import com.acert.chatbot.model.CitySource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai produto+preço do conteúdo (bruto, já obtido pelo {@link PageFetcherService}
 * — sem fazer nenhum fetch novo) de um {@link CitySource}, específico por
 * site. Reaproveitado tanto pelo warm-up ({@code CacheWarmerService}) quanto
 * pelo upsert oportunista feito pelo {@code ChatService} numa busca ao vivo.
 *
 * <p><b>Atacadão</b>: o conteúdo já é a resposta JSON da API GraphQL — parse
 * direto dos campos, sem regex chutado (ver memory/SITE_INTEGRATIONS.md).</p>
 *
 * <p><b>Rondon e Pão de Açúcar</b>: o conteúdo é texto renderizado da
 * página. O parser é uma heurística por regex baseada no padrão observado
 * (documentado em memory/DECISIONS.md) — é esperado que quebre se o site
 * mudar o layout; nesse caso o parse simplesmente retorna lista vazia e o
 * chamador cai no fallback do {@code site_cache} raw (comportamento
 * aceitável e já é o padrão do projeto, ver DECISIONS.md item 2).</p>
 */
@Service
public class ProdutoParserService {

    private static final Logger log = LoggerFactory.getLogger(ProdutoParserService.class);

    /** Limite de itens gravados por termo, pra não deixar a tabela crescer sem controle. */
    private static final int MAX_ITEMS_PER_TERM = 15;

    // IMPORTANTE: os sites renderizam "R$" seguido de um ESPAÇO NÃO-QUEBRÁVEL
    // (U+00A0), não um espaço ASCII normal — confirmado inspecionando o
    // conteúdo real capturado (codepoint 160 logo após "R$"). O `\s` padrão
    // do Java (sem UNICODE_CHARACTER_CLASS) só reconhece espaço ASCII e NÃO
    // casa com U+00A0, então os dois patterns abaixo são compilados com essa
    // flag pra `\s` também cobrir NBSP — sem ela, os regex simplesmente nunca
    // casavam com o conteúdo real (foi um bug descoberto e corrigido durante
    // a validação desta feature).
    private static final int UNICODE_FLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    // Rondon: bloco repetido no texto renderizado é
    //   <sku numérico>
    //   <nome do produto>
    //   [De R$ X,XX por]      <- opcional, quando tem desconto
    //   R$ Y,YY un
    //   [Preço por quilo: R$Z,ZZ]  <- opcional
    //   Adicionar
    // Capturamos o nome (grupo 1) e o preço "de verdade" já com desconto
    // aplicado, grupo 2 (a linha "R$ Y,YY un", nunca a linha "De R$... por").
    private static final Pattern RONDON_PATTERN = Pattern.compile(
            "\\d+\\s*\\n([^\\n]+)\\n(?:De R\\$[^\\n]*\\n)?R\\$\\s*([0-9]+[.,][0-9]{2})\\s*un", UNICODE_FLAGS);

    // Pão de Açúcar: bloco repetido é
    //   R$ X,XX
    //   (linha(s) em branco)
    //   <nome do produto>
    // (preço primeiro, nome depois — SEPARADOS POR LINHA EM BRANCO, não uma
    // quebra simples, daí o "\n+"). Quando o produto está em promoção, o
    // bloco vem com um badge de desconto no meio (ex: "R$ 11,79\n\n-5%\n\nR$
    // 12,49\n\n<nome>") — os dois lookaheads negativos fazem o match na
    // primeira ocorrência de R$ FALHAR quando o que vem em seguida não é o
    // nome de verdade (é outra linha de preço ou o badge "-N%"), então esse
    // preço "solto" é simplesmente pulado e só o par (preço, nome) que
    // realmente fica adjacente é capturado — não é perfeito (pode pegar o
    // preço "errado" dos dois num desconto, ver DECISIONS.md), mas cobre a
    // maioria dos itens (sem desconto) corretamente.
    private static final Pattern PAO_DE_ACUCAR_PATTERN = Pattern.compile(
            "R\\$\\s*([0-9]+[.,][0-9]{2})\\n+(?!R\\$)(?!-\\d+%)([^\\n]+)", UNICODE_FLAGS);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ParsedProduto> parse(CitySource source, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String kind = detectSiteKind(source);
        try {
            return switch (kind) {
                case "atacadao" -> parseAtacadao(content);
                case "rondon" -> parseTextPattern(content, RONDON_PATTERN, true);
                case "paodeacucar" -> parseTextPattern(content, PAO_DE_ACUCAR_PATTERN, false);
                default -> {
                    log.warn("Site '{}' sem parser estruturado conhecido — só o site_cache raw será usado", source.getLabel());
                    yield List.of();
                }
            };
        } catch (Exception ex) {
            log.warn("Falha ao fazer parse estruturado do site '{}': {}", source.getLabel(), ex.toString());
            return List.of();
        }
    }

    private String detectSiteKind(CitySource source) {
        String normalized = normalize((source.getLabel() == null ? "" : source.getLabel()) + " "
                + (source.getUrl() == null ? "" : source.getUrl()));
        if (normalized.contains("atacadao")) {
            return "atacadao";
        }
        if (normalized.contains("rondon")) {
            return "rondon";
        }
        if (normalized.contains("acucar")) {
            return "paodeacucar";
        }
        return "desconhecido";
    }

    private String normalize(String s) {
        String noAccents = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccents;
    }

    /**
     * Atacadão: resposta JSON da API GraphQL (ProductsQuery). Estrutura
     * observada: {@code data.search.products.edges[].node} com campos
     * {@code name}, {@code offers.offers[0].price} (preço do vendedor pra
     * quantidade mínima 1, o que o cliente comum paga por unidade) e
     * {@code breadcrumbList.itemListElement} (usamos o penúltimo item, que é
     * a categoria imediatamente acima do produto — o último é o próprio
     * produto).
     */
    private List<ParsedProduto> parseAtacadao(String jsonContent) throws Exception {
        JsonNode root = objectMapper.readTree(jsonContent);
        JsonNode edges = root.path("data").path("search").path("products").path("edges");
        List<ParsedProduto> result = new ArrayList<>();
        if (!edges.isArray()) {
            return result;
        }
        for (JsonNode edge : edges) {
            if (result.size() >= MAX_ITEMS_PER_TERM) {
                break;
            }
            JsonNode node = edge.path("node");
            String nome = node.path("name").asText(null);
            if (nome == null || nome.isBlank()) {
                continue;
            }

            BigDecimal preco = null;
            JsonNode offersArr = node.path("offers").path("offers");
            if (offersArr.isArray() && !offersArr.isEmpty()) {
                JsonNode firstOffer = offersArr.get(0);
                if (firstOffer.hasNonNull("price")) {
                    preco = BigDecimal.valueOf(firstOffer.path("price").asDouble())
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }

            String categoria = null;
            JsonNode breadcrumbs = node.path("breadcrumbList").path("itemListElement");
            if (breadcrumbs.isArray() && breadcrumbs.size() >= 2) {
                categoria = breadcrumbs.get(breadcrumbs.size() - 2).path("name").asText(null);
            }

            result.add(new ParsedProduto(nome.trim(), preco, categoria));
        }
        return result;
    }

    private List<ParsedProduto> parseTextPattern(String content, Pattern pattern, boolean nameBeforePrice) {
        List<ParsedProduto> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find() && result.size() < MAX_ITEMS_PER_TERM) {
            String nome = nameBeforePrice ? matcher.group(1) : matcher.group(2);
            String precoStr = nameBeforePrice ? matcher.group(2) : matcher.group(1);

            nome = nome == null ? "" : nome.trim();
            if (nome.isEmpty()) {
                continue;
            }

            BigDecimal preco = parsePrecoBr(precoStr);
            if (preco == null) {
                continue;
            }
            result.add(new ParsedProduto(nome, preco, null));
        }
        return result;
    }

    // Formato brasileiro: milhar com ponto, decimal com vírgula (ex:
    // "1.234,56"). Removemos o ponto de milhar antes de trocar a vírgula
    // decimal por ponto, senão o BigDecimal interpretaria errado.
    private BigDecimal parsePrecoBr(String precoStr) {
        try {
            String normalized = precoStr.replace(".", "").replace(",", ".");
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record ParsedProduto(String nome, BigDecimal preco, String categoria) {
    }
}
