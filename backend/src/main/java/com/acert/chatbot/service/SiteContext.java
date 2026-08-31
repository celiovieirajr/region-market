package com.acert.chatbot.service;

public class SiteContext {

    private final String label;
    private final String url;
    private final String text;

    public SiteContext(String label, String url, String text) {
        this.label = label;
        this.url = url;
        this.text = text;
    }

    public String getLabel() {
        return label;
    }

    public String getUrl() {
        return url;
    }

    public String getText() {
        return text;
    }
}
