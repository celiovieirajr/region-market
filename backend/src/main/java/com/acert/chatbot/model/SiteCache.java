package com.acert.chatbot.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Uma entrada de cache de scraping: o conteúdo já extraído pelo
 * {@code PageFetcherService} pra um termo específico de um {@link CitySource},
 * pra evitar reabrir o Playwright em toda pergunta quando o cache do site
 * está habilitado e ainda válido (ver {@code CacheWarmerService}).
 */
@Entity
@Table(name = "site_cache", uniqueConstraints = @UniqueConstraint(name = "uk_site_cache_source_term",
        columnNames = {"city_source_id", "term"}))
public class SiteCache {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_FALHOU = "FALHOU";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_source_id", nullable = false)
    private CitySource citySource;

    @Column(nullable = false, length = 200)
    private String term;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Column(nullable = false, length = 20)
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CitySource getCitySource() {
        return citySource;
    }

    public void setCitySource(CitySource citySource) {
        this.citySource = citySource;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(LocalDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
