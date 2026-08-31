package com.acert.chatbot.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Uma entrada do cache semântico de classificação de mensagens (ver
 * {@code SemanticCacheService}): guarda o embedding (vetor 384 floats,
 * serializado como texto) de uma mensagem já classificada por
 * {@code AiService.classifyProductQuery}, junto com o resultado dessa
 * classificação, pra evitar chamar a IA de novo quando uma mensagem nova é
 * semanticamente parecida o suficiente com uma já vista antes (frases
 * diferentes, mesma intenção — ex: "quanto custa o arroz" vs "qual o preço
 * do arroz hoje").
 */
@Entity
@Table(name = "tb_semantic_query_cache")
public class SemanticQueryCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Guardada só pra debug/auditoria (não é usada pra comparação — quem
    // decide semelhança é o embedding).
    @Column(name = "mensagem_normalizada", nullable = false, length = 500)
    private String mensagemNormalizada;

    // Vetor de 384 floats (modelo paraphrase-multilingual-MiniLM-L12-v2),
    // serializado como floats separados por vírgula.
    @Lob
    @Column(name = "embedding", nullable = false)
    private String embedding;

    @Column(name = "is_produto_query", nullable = false)
    private boolean isProdutoQuery;

    @Column(name = "termo", length = 200)
    private String termo;

    @Column(name = "hits", nullable = false)
    private int hits = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMensagemNormalizada() {
        return mensagemNormalizada;
    }

    public void setMensagemNormalizada(String mensagemNormalizada) {
        this.mensagemNormalizada = mensagemNormalizada;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public boolean isProdutoQuery() {
        return isProdutoQuery;
    }

    public void setProdutoQuery(boolean produtoQuery) {
        isProdutoQuery = produtoQuery;
    }

    public String getTermo() {
        return termo;
    }

    public void setTermo(String termo) {
        this.termo = termo;
    }

    public int getHits() {
        return hits;
    }

    public void setHits(int hits) {
        this.hits = hits;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
