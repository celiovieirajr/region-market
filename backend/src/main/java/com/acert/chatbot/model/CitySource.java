package com.acert.chatbot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "city_sources")
public class CitySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 150)
    private String label;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Cache de scraping OPCIONAL por site: quando habilitado, o chat tenta
    // responder com o conteúdo já cacheado (site_cache) antes de abrir o
    // Playwright, com fallback transparente pra busca ao vivo se não houver
    // cache válido. Ver CacheWarmerService e memory/DECISIONS.md.
    @Column(name = "cache_enabled", nullable = false)
    private boolean cacheEnabled = false;

    @Column(name = "cache_ttl_hours", nullable = false)
    private int cacheTtlHours = 6;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public int getCacheTtlHours() {
        return cacheTtlHours;
    }

    public void setCacheTtlHours(int cacheTtlHours) {
        this.cacheTtlHours = cacheTtlHours;
    }
}
