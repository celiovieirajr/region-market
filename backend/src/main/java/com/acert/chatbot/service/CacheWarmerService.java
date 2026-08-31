package com.acert.chatbot.service;

import com.acert.chatbot.model.CitySource;
import com.acert.chatbot.model.ProdutoCache;
import com.acert.chatbot.model.SiteCache;
import com.acert.chatbot.repository.CitySourceRepository;
import com.acert.chatbot.repository.ProdutoCacheRepository;
import com.acert.chatbot.repository.SiteCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Aquece (pré-popula) o cache de scraping (`site_cache`) de um {@link CitySource}
 * que tenha {@code cacheEnabled=true}, buscando ao vivo (via
 * {@link PageFetcherService} — a mesma thread única do Playwright, sem
 * paralelizar) uma lista fixa de termos monitorados.
 *
 * Isso é OPCIONAL: sources sem cache habilitado nunca passam por aqui, e o
 * fluxo de busca ao vivo do {@link ChatService} continua idêntico pra eles.
 */
@Service
public class CacheWarmerService {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmerService.class);

    private static final String TERM_PLACEHOLDER = "{termo}";

    /**
     * Termos monitorados pro warm-up automático do cache. Lista fixa por
     * enquanto — cobre os itens de cesta básica mais perguntados no chat.
     */
    public static final List<String> MONITORED_TERMS = List.of(
            "arroz", "feijao", "leite", "oleo de soja", "acucar",
            "cafe", "carne", "frango", "macarrao", "papel higienico");

    /**
     * Pausa entre termos consecutivos do MESMO source durante o warm-up, pra
     * não martelar o site em rajada (ver memory/DECISIONS.md — foi assim que
     * o Pão de Açúcar acabou devolvendo uma página de verificação anti-bot
     * numa rodada anterior: 10 sessões novas em poucos segundos, sem pausa
     * nenhuma). Continua tudo serial na mesma thread única do Playwright —
     * só adiciona um respiro entre uma chamada e outra.
     */
    private static final long WARM_UP_PAUSE_MS = 4000L;

    private final SiteCacheRepository siteCacheRepository;
    private final ProdutoCacheRepository produtoCacheRepository;
    private final CitySourceRepository citySourceRepository;
    private final PageFetcherService pageFetcherService;
    private final ProdutoParserService produtoParserService;

    public CacheWarmerService(SiteCacheRepository siteCacheRepository,
                               ProdutoCacheRepository produtoCacheRepository,
                               CitySourceRepository citySourceRepository,
                               PageFetcherService pageFetcherService,
                               ProdutoParserService produtoParserService) {
        this.siteCacheRepository = siteCacheRepository;
        this.produtoCacheRepository = produtoCacheRepository;
        this.citySourceRepository = citySourceRepository;
        this.pageFetcherService = pageFetcherService;
        this.produtoParserService = produtoParserService;
    }

    /**
     * Roda a cada 30 minutos: para cada source com cache habilitado cujo
     * cache esteja vencido (ou nunca populado), revalida o source inteiro.
     * Trata a expiração por source (não por termo individual) pra simplificar
     * — se qualquer termo já venceu o TTL, o source inteiro é revalidado.
     */
    @Scheduled(fixedRate = 30 * 60 * 1000L)
    public void scheduledWarmUp() {
        List<CitySource> enabled = citySourceRepository.findByCacheEnabledTrue();
        for (CitySource source : enabled) {
            try {
                if (isExpired(source)) {
                    log.info("Cache de '{}' vencido ou inexistente — revalidando", source.getLabel());
                    warmCache(source);
                }
            } catch (Exception ex) {
                log.warn("Falha ao revalidar cache de '{}': {}", source.getLabel(), ex.toString());
            }
        }
    }

    private boolean isExpired(CitySource source) {
        List<SiteCache> cached = siteCacheRepository.findByCitySourceId(source.getId());
        if (cached.size() < MONITORED_TERMS.size()) {
            return true;
        }
        LocalDateTime oldest = siteCacheRepository.findOldestFetchedAt(source.getId()).orElse(null);
        if (oldest == null) {
            return true;
        }
        return oldest.isBefore(LocalDateTime.now().minusHours(source.getCacheTtlHours()));
    }

    /**
     * Busca ao vivo (thread única do Playwright, via {@link PageFetcherService})
     * todos os termos monitorados pra esse source e faz upsert no cache —
     * status OK com o conteúdo, ou FALHOU se der erro, sempre atualizando
     * {@code fetched_at}. Usado tanto pelo job agendado quanto pelo refresh
     * manual do admin (ignorando TTL) e pelo fallback oportunista do chat.
     *
     * <p>Termo por termo (não num lote só), com uma pausa entre cada um (ver
     * {@link #WARM_UP_PAUSE_MS}) — continua tudo serial, cada chamada a
     * {@code fetchAll} enfileira na mesma thread dedicada do Playwright, só
     * que agora espaçadas no tempo em vez de uma rajada de N requisições
     * sem pausa nenhuma.</p>
     */
    public void warmCache(CitySource source) {
        for (int i = 0; i < MONITORED_TERMS.size(); i++) {
            String term = MONITORED_TERMS.get(i);
            FetchTarget target = new FetchTarget(source.getLabel(), resolveUrl(source.getUrl(), term));

            List<SiteContext> results = pageFetcherService.fetchAll(List.of(target));
            SiteContext ctx = results.get(0);
            boolean ok = !ctx.getText().startsWith(PageFetcherService.FETCH_ERROR_PREFIX);

            if (!ok && ctx.getText().contains(PageFetcherService.BOT_CHECK_MARKER)) {
                log.warn("Conteúdo suspeito de bot-check em {}/{}, ignorado", source.getLabel(), term);
            }

            upsertCache(source, term, ctx.getText(), ok ? SiteCache.STATUS_OK : SiteCache.STATUS_FALHOU);
            upsertProdutoCache(source, term, ctx, ok);

            boolean isLast = i == MONITORED_TERMS.size() - 1;
            if (!isLast) {
                try {
                    Thread.sleep(WARM_UP_PAUSE_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("Warm-up de '{}' interrompido antes de terminar todos os termos", source.getLabel());
                    return;
                }
            }
        }
    }

    /**
     * Upsert de uma entrada de cache pra (source, termo). Reaproveitado pelo
     * warm-up e pelo fallback oportunista do {@code ChatService} (quando uma
     * busca ao vivo funciona pra um source com cache habilitado, o resultado
     * já é aproveitado pra não esperar o próximo ciclo do job).
     */
    // REQUIRES_NEW: isola numa transação própria, separada da transação do
    // ChatService.sendMessage que chama isso oportunisticamente — se der
    // erro aqui, só essa transação de cache é desfeita, sem marcar a
    // transação do chat inteira como rollback-only (o que fazia o chat
    // inteiro falhar com UnexpectedRollbackException mesmo com o erro sendo
    // pego e logado pelo chamador).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertCache(CitySource source, String term, String content, String status) {
        String normalizedTerm = normalizeTerm(term);
        SiteCache cache = siteCacheRepository.findByCitySourceIdAndTermIgnoreCase(source.getId(), normalizedTerm)
                .orElseGet(SiteCache::new);
        cache.setCitySource(source);
        cache.setTerm(normalizedTerm);
        cache.setContent(content);
        cache.setStatus(status);
        cache.setFetchedAt(LocalDateTime.now());
        siteCacheRepository.save(cache);
    }

    /**
     * Parse estruturado (ver {@link ProdutoParserService}) do conteúdo já
     * obtido (sem fetch novo) e upsert em {@code tb_produto_cache} — sempre
     * apaga as linhas antigas desse (source, termo) antes de gravar as
     * novas, pra tabela não acumular produtos desatualizados de rodadas
     * anteriores.
     *
     * <ul>
     *   <li>Fetch falhou (erro de rede ou bot-check): grava UMA linha
     *   {@code FALHOU} (sem nome/preço) só pra registrar a tentativa —
     *   nunca conteúdo de página de verificação.</li>
     *   <li>Fetch OK mas parse estruturado não encontrou nenhum item: não
     *   grava nada aqui (loga aviso); o {@code site_cache} raw gravado por
     *   {@link #upsertCache} continua servindo de fallback pra IA — não é
     *   tratado como erro fatal do termo inteiro.</li>
     *   <li>Fetch OK e parse encontrou itens: grava uma linha OK por
     *   produto.</li>
     * </ul>
     */
    // REQUIRES_NEW pelo mesmo motivo de upsertCache acima.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertProdutoCache(CitySource source, String term, SiteContext ctx, boolean fetchOk) {
        String normalizedTerm = normalizeTerm(term);
        List<ProdutoCache> existing = produtoCacheRepository
                .findByCitySourceIdAndTermoBuscaIgnoreCase(source.getId(), normalizedTerm);
        if (!existing.isEmpty()) {
            produtoCacheRepository.deleteAll(existing);
        }

        LocalDateTime now = LocalDateTime.now();

        if (!fetchOk) {
            ProdutoCache falhou = new ProdutoCache();
            falhou.setCitySource(source);
            falhou.setTermoBusca(normalizedTerm);
            falhou.setLoja(source.getLabel());
            falhou.setUrlOrigem(ctx.getUrl());
            falhou.setCapturadoEm(now);
            falhou.setStatus(ProdutoCache.STATUS_FALHOU);
            produtoCacheRepository.save(falhou);
            return;
        }

        List<ProdutoParserService.ParsedProduto> parsed = produtoParserService.parse(source, ctx.getText());
        if (parsed.isEmpty()) {
            log.warn("Parse estruturado não encontrou produtos pra '{}'/{} — mantendo só o site_cache raw como fallback",
                    source.getLabel(), normalizedTerm);
            return;
        }

        List<ProdutoCache> rows = new ArrayList<>();
        for (ProdutoParserService.ParsedProduto p : parsed) {
            ProdutoCache row = new ProdutoCache();
            row.setCitySource(source);
            row.setTermoBusca(normalizedTerm);
            row.setNomeProduto(p.nome());
            row.setPreco(p.preco());
            // Categoria real do site quando o parser extraiu uma (Atacadão);
            // senão usa o próprio termo buscado (Rondon/Pão de Açúcar — ver
            // memory/DECISIONS.md).
            row.setCategoria(p.categoria() != null && !p.categoria().isBlank() ? p.categoria() : term);
            row.setLoja(source.getLabel());
            row.setUrlOrigem(ctx.getUrl());
            row.setCapturadoEm(now);
            row.setStatus(ProdutoCache.STATUS_OK);
            rows.add(row);
        }
        produtoCacheRepository.saveAll(rows);
    }

    public static String normalizeTerm(String term) {
        return (term == null || term.isBlank()) ? "" : term.trim().toLowerCase();
    }

    private String resolveUrl(String urlTemplate, String term) {
        if (!urlTemplate.contains(TERM_PLACEHOLDER)) {
            return urlTemplate;
        }
        return urlTemplate.replace(TERM_PLACEHOLDER, urlEncode(term));
    }

    private String urlEncode(String term) {
        return URLEncoder.encode(term, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
