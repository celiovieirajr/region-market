package com.acert.chatbot.service;

import com.acert.chatbot.model.ChatMessage;
import com.acert.chatbot.model.CitySource;
import com.acert.chatbot.model.ProdutoCache;
import com.acert.chatbot.model.SiteCache;
import com.acert.chatbot.model.User;
import com.acert.chatbot.repository.ChatMessageRepository;
import com.acert.chatbot.repository.CitySourceRepository;
import com.acert.chatbot.repository.ProdutoCacheRepository;
import com.acert.chatbot.repository.SiteCacheRepository;
import com.acert.chatbot.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String TERM_PLACEHOLDER = "{termo}";

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final CitySourceRepository citySourceRepository;
    private final SiteCacheRepository siteCacheRepository;
    private final ProdutoCacheRepository produtoCacheRepository;
    private final AiService aiService;
    private final PageFetcherService pageFetcherService;
    private final CacheWarmerService cacheWarmerService;
    private final SemanticCacheService semanticCacheService;
    private final TransactionTemplate transactionTemplate;
    private final int maxSitesPerQuery;

    public ChatService(ChatMessageRepository chatMessageRepository,
                        UserRepository userRepository,
                        CitySourceRepository citySourceRepository,
                        SiteCacheRepository siteCacheRepository,
                        ProdutoCacheRepository produtoCacheRepository,
                        AiService aiService,
                        PageFetcherService pageFetcherService,
                        CacheWarmerService cacheWarmerService,
                        SemanticCacheService semanticCacheService,
                        PlatformTransactionManager transactionManager,
                        @Value("${app.fetch.max-sites-per-query:3}") int maxSitesPerQuery) {
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.citySourceRepository = citySourceRepository;
        this.siteCacheRepository = siteCacheRepository;
        this.produtoCacheRepository = produtoCacheRepository;
        this.aiService = aiService;
        this.pageFetcherService = pageFetcherService;
        this.cacheWarmerService = cacheWarmerService;
        this.semanticCacheService = semanticCacheService;
        // TransactionTemplate em vez de @Transactional nos métodos privados
        // saveUserMessage/saveAiReply: chamada interna (this.saveX(...)) não
        // passa pelo proxy do Spring, então @Transactional seria ignorado
        // silenciosamente (self-invocation) — TransactionTemplate abre/fecha
        // a transação manualmente, sem depender de proxy.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxSitesPerQuery = maxSitesPerQuery;
    }

    // Sem @Transactional no método inteiro de propósito: generateReply
    // (chamada HTTP à IA) e resolveSiteContexts (scraping via Playwright)
    // são I/O externo que pode levar dezenas de segundos — prendiam a
    // conexão de banco aberta o tempo todo se estivessem dentro de uma
    // única transação. Cada gravação no banco usa sua própria transação
    // curta (via transactionTemplate), então uma falha de I/O externo
    // nunca deixa a transação de banco marcada como rollback-only.
    public ChatMessage sendMessage(String username, String userMessage) {
        User user = getUser(username);
        saveUserMessage(user, userMessage);

        List<ChatMessage> history = chatMessageRepository.findByUserOrderByCreatedAtAsc(user);
        List<SiteContext> siteContexts = resolveSiteContexts(user, userMessage);
        String aiReply = aiService.generateReply(userMessage, history, siteContexts);

        return saveAiReply(user, aiReply);
    }

    private void saveUserMessage(User user, String userMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatMessage userChat = new ChatMessage();
            userChat.setUser(user);
            userChat.setRole("USER");
            userChat.setContent(userMessage);
            chatMessageRepository.save(userChat);
        });
    }

    private ChatMessage saveAiReply(User user, String aiReply) {
        return transactionTemplate.execute(status -> {
            ChatMessage aiChat = new ChatMessage();
            aiChat.setUser(user);
            aiChat.setRole("ASSISTANT");
            aiChat.setContent(aiReply);
            chatMessageRepository.save(aiChat);
            return aiChat;
        });
    }

    private List<SiteContext> resolveSiteContexts(User user, String userMessage) {
        String city = user.getCity();
        if (city == null || city.isBlank()) {
            return List.of();
        }

        // Classifica a mensagem ANTES de tocar em CitySource/Playwright: só
        // pedidos de preço/produto de mercado justificam scraping. Mensagens
        // como saudações ("boa noite"), agradecimentos ou perguntas genéricas
        // não devem disparar nenhuma busca (nem estática, nem dinâmica) em
        // nenhum site da cidade — ver DECISIONS.md.
        AiService.ProductQueryIntent intent = semanticCacheService.classify(userMessage);
        if (!intent.isProdutoQuery()) {
            return List.of();
        }

        List<CitySource> citySources = citySourceRepository.findByCityIgnoreCase(city.trim());
        if (citySources.isEmpty()) {
            return List.of();
        }

        List<CitySource> limited = citySources.stream().limit(maxSitesPerQuery).toList();

        String searchTerm = intent.termo();
        String normalizedTerm = CacheWarmerService.normalizeTerm(searchTerm);

        // Separa os sources em: já respondidos pelo cache (sem tocar no
        // Playwright) e os que precisam mesmo de busca ao vivo. Isso preserva
        // 100% do comportamento atual pra sources sem cache habilitado — eles
        // sempre caem direto no fetch ao vivo, como antes.
        List<SiteContext> results = new ArrayList<>();
        List<CitySource> liveSources = new ArrayList<>();
        List<FetchTarget> liveTargets = new ArrayList<>();

        for (CitySource source : limited) {
            Optional<SiteContext> cached = source.isCacheEnabled()
                    ? findFreshContext(source, normalizedTerm)
                    : Optional.empty();
            if (cached.isPresent()) {
                results.add(cached.get());
            } else {
                liveSources.add(source);
                liveTargets.add(new FetchTarget(source.getLabel(), resolveUrl(source.getUrl(), searchTerm)));
            }
        }

        if (!liveTargets.isEmpty()) {
            List<SiteContext> fetched = pageFetcherService.fetchAll(liveTargets);
            for (int i = 0; i < fetched.size(); i++) {
                SiteContext ctx = fetched.get(i);
                CitySource source = liveSources.get(i);
                boolean ok = !ctx.getText().startsWith(PageFetcherService.FETCH_ERROR_PREFIX);

                if (!ok && ctx.getText().contains(PageFetcherService.BOT_CHECK_MARKER)) {
                    log.warn("Conteúdo suspeito de bot-check em {}/{}, ignorado", source.getLabel(), normalizedTerm);
                }

                // Aproveita o resultado ao vivo pra alimentar os dois caches
                // desse source (site_cache raw + tb_produto_cache
                // estruturado) — barato, evita esperar o próximo ciclo do
                // job de warm-up — só se o cache estiver habilitado nesse
                // source.
                if (source.isCacheEnabled()) {
                    try {
                        cacheWarmerService.upsertCache(source, normalizedTerm, ctx.getText(),
                                ok ? SiteCache.STATUS_OK : SiteCache.STATUS_FALHOU);
                        cacheWarmerService.upsertProdutoCache(source, normalizedTerm, ctx, ok);
                    } catch (Exception ex) {
                        log.warn("Falha ao gravar cache oportunista de '{}'", source.getLabel(), ex);
                    }
                }

                // Sites que falharam ao carregar (erro de rede/timeout) nem
                // chegam a virar contexto para a IA — não faz sentido ela
                // "explicar" que um site deu erro técnico.
                if (ok) {
                    results.add(ctx);
                }
            }
        }

        return results;
    }

    /**
     * Prioridade de contexto pra um source com cache habilitado: primeiro
     * tenta o cache ESTRUTURADO ({@code tb_produto_cache} — lista limpa de
     * produtos já parseados), e só se não houver nada lá cai pro
     * {@code site_cache} raw (texto/JSON bruto da página). Se nenhum dos
     * dois tiver entrada válida, retorna vazio e o chamador cai no fetch ao
     * vivo normalmente.
     */
    private Optional<SiteContext> findFreshContext(CitySource source, String normalizedTerm) {
        Optional<SiteContext> structured = findFreshProdutoCache(source, normalizedTerm);
        if (structured.isPresent()) {
            return structured;
        }
        return findFreshCache(source, normalizedTerm);
    }

    /**
     * Monta um {@link SiteContext} com uma lista limpa "Nome — R$ preço" a
     * partir das linhas OK de {@code tb_produto_cache} dentro do TTL, em vez
     * de mandar o texto/JSON bruto da página pra IA. Retorna vazio se não
     * houver nenhuma linha estruturada válida pra esse (source, termo).
     */
    private Optional<SiteContext> findFreshProdutoCache(CitySource source, String normalizedTerm) {
        LocalDateTime since = LocalDateTime.now().minusHours(source.getCacheTtlHours());
        List<ProdutoCache> rows = produtoCacheRepository.findFreshOk(source.getId(), normalizedTerm, since);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (ProdutoCache p : rows) {
            sb.append(p.getNomeProduto());
            if (p.getPreco() != null) {
                sb.append(" — R$ ").append(p.getPreco());
            }
            sb.append("\n");
        }
        return Optional.of(new SiteContext(source.getLabel(), source.getUrl(), sb.toString()));
    }

    /**
     * Procura no cache (`site_cache`) uma entrada válida (status OK e dentro
     * do TTL configurado no source) pra esse termo. Se não existir, estiver
     * vencida ou tiver falhado da última vez, retorna vazio — o chamador cai
     * no fluxo de busca ao vivo normalmente (fallback transparente).
     */
    private Optional<SiteContext> findFreshCache(CitySource source, String normalizedTerm) {
        return siteCacheRepository.findByCitySourceIdAndTermIgnoreCase(source.getId(), normalizedTerm)
                .filter(c -> SiteCache.STATUS_OK.equals(c.getStatus()))
                .filter(c -> c.getFetchedAt().isAfter(LocalDateTime.now().minusHours(source.getCacheTtlHours())))
                .map(c -> new SiteContext(source.getLabel(), source.getUrl(), c.getContent()));
    }

    private String resolveUrl(String urlTemplate, String searchTerm) {
        if (!urlTemplate.contains(TERM_PLACEHOLDER)) {
            return urlTemplate;
        }
        String safeTerm = (searchTerm == null || searchTerm.isBlank()) ? "" : urlEncode(searchTerm.trim());
        return urlTemplate.replace(TERM_PLACEHOLDER, safeTerm);
    }

    // Percent-encoding "de verdade" (funciona tanto num segmento de path como
    // dentro de um valor de query string/JSON já url-encoded, ex: a API
    // GraphQL do Atacadão). URLEncoder usa "+" para espaço, que só é válido em
    // query string — trocamos por %20 pra funcionar em path também.
    private String urlEncode(String term) {
        return URLEncoder.encode(term, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getHistory(String username) {
        User user = getUser(username);
        return chatMessageRepository.findByUserOrderByCreatedAtAsc(user);
    }

    @Transactional
    public void clearHistory(String username) {
        User user = getUser(username);
        chatMessageRepository.deleteByUser(user);
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado: " + username));
    }
}
