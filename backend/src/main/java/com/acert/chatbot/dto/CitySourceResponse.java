package com.acert.chatbot.dto;

import com.acert.chatbot.model.CitySource;

import java.time.LocalDateTime;

public class CitySourceResponse {

    private Long id;
    private String city;
    private String label;
    private String url;
    private LocalDateTime createdAt;
    private boolean cacheEnabled;
    private int cacheTtlHours;
    private LocalDateTime cacheLastUpdatedAt;

    public CitySourceResponse(CitySource source) {
        this(source, null);
    }

    public CitySourceResponse(CitySource source, LocalDateTime cacheLastUpdatedAt) {
        this.id = source.getId();
        this.city = source.getCity();
        this.label = source.getLabel();
        this.url = source.getUrl();
        this.createdAt = source.getCreatedAt();
        this.cacheEnabled = source.isCacheEnabled();
        this.cacheTtlHours = source.getCacheTtlHours();
        this.cacheLastUpdatedAt = cacheLastUpdatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getCity() {
        return city;
    }

    public String getLabel() {
        return label;
    }

    public String getUrl() {
        return url;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public int getCacheTtlHours() {
        return cacheTtlHours;
    }

    public LocalDateTime getCacheLastUpdatedAt() {
        return cacheLastUpdatedAt;
    }
}
