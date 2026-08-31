package com.acert.chatbot.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Abre cada site num navegador headless real (Playwright/Chromium) e lê o
 * texto já renderizado (depois do JavaScript rodar) para servir de contexto
 * à IA. Isso resolve sites em React/Angular/Vue que só mostram preço depois
 * de uma chamada JS — algo que um HTTP GET simples não capturaria.
 *
 * O Playwright não é thread-safe entre threads, então todo o trabalho roda
 * numa única thread dedicada; chamadas concorrentes esperam na fila.
 */
@Service
public class PageFetcherService {

    private static final Logger log = LoggerFactory.getLogger(PageFetcherService.class);

    public static final String FETCH_ERROR_PREFIX = "[não foi possível acessar este site agora";

    /**
     * Marcador embutido na mensagem de erro quando o conteúdo obtido foi
     * identificado como uma página de verificação anti-bot (não é o
     * catálogo real) — permite que quem recebe o {@link SiteContext} (ex:
     * {@code ChatService}, {@code CacheWarmerService}) diferencie esse caso
     * de um erro de rede comum pra logar/soar um alerta específico. Ver
     * {@link #isSuspiciousBotCheckContent(String)} e memory/DECISIONS.md.
     */
    public static final String BOT_CHECK_MARKER = "conteúdo suspeito de verificação anti-bot";

    /**
     * Sinais textuais (case-insensitive, pt-BR e en) de que a página obtida é
     * um desafio de verificação humana/anti-bot, não o catálogo de produtos
     * real. Lista deliberadamente simples e literal — NUNCA usada para tentar
     * resolver/contornar o desafio, só para detectar e descartar o conteúdo
     * (ver limite documentado em memory/DECISIONS.md: nunca burlar
     * bot-detection, só falhar graciosamente e cair no fallback).
     */
    private static final List<String> BOT_CHECK_SIGNALS = List.of(
            "não sou um robô",
            "nao sou um robo",
            "confirme seu acesso",
            "confirme que você é humano",
            "confirme que voce e humano",
            "verifique que você é humano",
            "verifique que voce e humano",
            "captcha",
            "unusual traffic");

    /**
     * Conteúdo mais curto que isso é suspeito demais pra ser um catálogo de
     * produtos real (páginas de bot-check costumam ser bem curtas).
     */
    private static final int MIN_VALID_CONTENT_LENGTH = 300;

    private final int timeoutMs;
    private final int maxCharsPerSite;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "playwright-fetcher");
        t.setDaemon(true);
        return t;
    });

    private Playwright playwright;
    private Browser browser;

    public PageFetcherService(@Value("${app.fetch.timeout-ms:15000}") int timeoutMs,
                               @Value("${app.fetch.max-chars-per-site:4000}") int maxCharsPerSite) {
        this.timeoutMs = timeoutMs;
        this.maxCharsPerSite = maxCharsPerSite;
    }

    public List<SiteContext> fetchAll(List<FetchTarget> targets) {
        try {
            Future<List<SiteContext>> future = executor.submit(() -> fetchAllOnBrowserThread(targets));
            // +25s por site pra cobrir o teto da espera adaptativa (polling até
            // estabilizar) além do navigate em si.
            long overallTimeoutMs = (long) (timeoutMs + 25_000) * Math.max(1, targets.size()) + 15_000;
            return future.get(overallTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.warn("Falha ao buscar sites (lote inteiro): {}", ex.toString());
            List<SiteContext> fallback = new ArrayList<>();
            for (FetchTarget target : targets) {
                fallback.add(new SiteContext(target.label(), target.url(),
                        FETCH_ERROR_PREFIX + ": " + ex.getMessage() + "]"));
            }
            return fallback;
        }
    }

    private List<SiteContext> fetchAllOnBrowserThread(List<FetchTarget> targets) {
        List<SiteContext> results = new ArrayList<>();
        for (FetchTarget target : targets) {
            results.add(fetchOne(target));
        }
        return results;
    }

    private void ensureBrowserStarted() {
        if (browser != null && browser.isConnected()) {
            return;
        }
        log.info("(Re)iniciando o navegador Playwright/Chromium");
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {
                // navegador já pode estar num estado inconsistente
            }
        }
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    private SiteContext fetchOne(FetchTarget target) {
        try {
            // Garante o navegador vivo antes de CADA site: se um crash no meio do
            // lote não deve derrubar os sites seguintes da mesma consulta.
            ensureBrowserStarted();

            try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setLocale("pt-BR"))) {

                Page page = context.newPage();
                page.navigate(target.url(), new Page.NavigateOptions()
                        .setTimeout(timeoutMs)
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));

                waitForContentToStabilize(page);

                String text = (String) page.evaluate("() => document.body ? document.body.innerText : ''");
                if (text == null) {
                    text = "";
                }

                if (isSuspiciousBotCheckContent(text)) {
                    log.warn("Conteúdo suspeito de bot-check em '{}' ({}) — tratado como falha de fetch, "
                            + "não será cacheado", target.label(), target.url());
                    return new SiteContext(target.label(), target.url(),
                            FETCH_ERROR_PREFIX + ": " + BOT_CHECK_MARKER + "]");
                }

                // Respostas JSON puras (ex: API GraphQL do Atacadão) não podem
                // ser truncadas por contagem de caracteres — cortar no meio
                // quebraria a estrutura e inviabilizaria o parse estruturado
                // (ver ProdutoParserService). Só truncamos texto HTML
                // renderizado, que é o que o limite foi pensado pra controlar
                // (tamanho do prompt da IA).
                if (text.length() > maxCharsPerSite && !looksLikeJson(text)) {
                    text = text.substring(0, maxCharsPerSite);
                }
                log.info("Site '{}' ({}) lido com sucesso: {} caracteres", target.label(), target.url(), text.length());
                return new SiteContext(target.label(), target.url(), text);
            }
        } catch (Exception ex) {
            log.warn("Falha ao acessar site '{}' ({}): {}", target.label(), target.url(), ex.toString());
            return new SiteContext(target.label(), target.url(),
                    FETCH_ERROR_PREFIX + ": " + ex.getMessage() + "]");
        }
    }

    /**
     * Guarda de validação de conteúdo (proteção geral, vale pra qualquer
     * site): detecta se o texto obtido é, na verdade, uma página de
     * verificação anti-bot/CAPTCHA em vez do catálogo real — pra nunca
     * cachear ou repassar isso como se fosse dado de produto válido.
     *
     * IMPORTANTE — limite absoluto do projeto: esse método só DETECTA e
     * descarta; nunca tentamos resolver, clicar ou burlar o desafio de
     * verificação de nenhum site, aqui ou em qualquer outro lugar do
     * código. Se um site continuar bloqueando mesmo após espaçar as
     * requisições, a resposta correta é marcar como falha e cair no
     * fallback ao vivo já existente — nunca contornar. Ver memory/DECISIONS.md.
     */
    public static boolean isSuspiciousBotCheckContent(String text) {
        if (text == null) {
            return true;
        }
        String trimmed = text.trim();
        if (trimmed.length() < MIN_VALID_CONTENT_LENGTH) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String signal : BOT_CHECK_SIGNALS) {
            if (lower.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeJson(String text) {
        String trimmed = text.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * Espera ADAPTATIVA em vez de tempo fixo: faz polling do tamanho do texto
     * da página e para assim que ele "estabilizar" (parar de crescer por 2
     * checagens seguidas) — ou ao bater o teto de tempo. Funciona tanto para
     * HTML renderizado via JS (ex: Pão de Açúcar, que só decide a loja padrão
     * numa sessão nova sem cookies e demora mais que outros) quanto para
     * respostas de API/JSON que já chegam prontas de uma vez (ex: Atacadão),
     * sem depender de procurar um texto específico como "R$" que nem sempre
     * existe (JSON usa preço numérico, não string formatada).
     */
    private void waitForContentToStabilize(Page page) {
        int pollIntervalMs = 1000;
        int maxWaitMs = 25_000;
        int elapsed = 0;
        int lastLength = -1;
        int stableChecks = 0;

        while (elapsed < maxWaitMs) {
            page.waitForTimeout(pollIntervalMs);
            elapsed += pollIntervalMs;

            int currentLength = ((Number) page.evaluate(
                    "() => document.body ? document.body.innerText.length : 0")).intValue();

            if (currentLength > 50 && currentLength == lastLength) {
                stableChecks++;
                if (stableChecks >= 2) {
                    return;
                }
            } else {
                stableChecks = 0;
            }
            lastLength = currentLength;
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.submit(() -> {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
        });
        executor.shutdown();
    }
}
