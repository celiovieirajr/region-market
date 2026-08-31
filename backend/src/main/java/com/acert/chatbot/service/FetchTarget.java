package com.acert.chatbot.service;

/**
 * Um site já com a URL final resolvida (placeholder {@code {termo}} já
 * substituído, se houver) pronto pra ser aberto pelo {@link PageFetcherService}.
 */
public record FetchTarget(String label, String url) {
}
