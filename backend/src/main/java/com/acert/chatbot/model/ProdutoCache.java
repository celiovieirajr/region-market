package com.acert.chatbot.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Uma linha de produto/preço já extraído (parse estruturado) do conteúdo
 * bruto de um {@link CitySource} com cache habilitado, pra um termo de
 * busca específico — ver {@code ProdutoParserService} e
 * {@code CacheWarmerService}.
 *
 * Existe separado do {@link SiteCache} (que guarda o texto/JSON bruto da
 * página) porque nem todo termo/site tem parse estruturado bem-sucedido
 * (site pode mudar layout, JSON pode ter formato inesperado) — quando o
 * parse falha mas o fetch bruto foi válido, o {@code site_cache} raw
 * continua servindo de fallback pra IA, sem depender do parse ter
 * funcionado. Cada linha aqui é um produto individual (não um blob de
 * texto), o que permite {@code ChatService} montar uma lista limpa
 * "Nome — R$ preço" pra IA em vez de mandar o texto/JSON bruto da página.
 */
@Entity
@Table(name = "tb_produto_cache")
public class ProdutoCache {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_FALHOU = "FALHOU";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_source_id", nullable = false)
    private CitySource citySource;

    @Column(name = "termo_busca", nullable = false, length = 200)
    private String termoBusca;

    @Column(name = "nome_produto", length = 300)
    private String nomeProduto;

    @Column(name = "preco", precision = 10, scale = 2)
    private BigDecimal preco;

    // Categoria real do site quando dá pra extrair (ex: breadcrumb do
    // Atacadão); quando não dá (Rondon/Pão de Açúcar, ver DECISIONS.md),
    // usamos o próprio termo_busca como valor — decisão deliberada pra
    // sempre ter algo preenchido em vez de null.
    @Column(name = "categoria", length = 200)
    private String categoria;

    // Denormalizado do CitySource.label, pra ficar legível sem precisar de
    // join só pra exibir/logar o nome da loja.
    @Column(name = "loja", length = 150)
    private String loja;

    @Column(name = "url_origem", length = 500)
    private String urlOrigem;

    @Column(name = "capturado_em", nullable = false)
    private LocalDateTime capturadoEm;

    @Column(name = "status", nullable = false, length = 20)
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

    public String getTermoBusca() {
        return termoBusca;
    }

    public void setTermoBusca(String termoBusca) {
        this.termoBusca = termoBusca;
    }

    public String getNomeProduto() {
        return nomeProduto;
    }

    public void setNomeProduto(String nomeProduto) {
        this.nomeProduto = nomeProduto;
    }

    public BigDecimal getPreco() {
        return preco;
    }

    public void setPreco(BigDecimal preco) {
        this.preco = preco;
    }

    public String getCategoria() {
        return categoria;
    }

    public void setCategoria(String categoria) {
        this.categoria = categoria;
    }

    public String getLoja() {
        return loja;
    }

    public void setLoja(String loja) {
        this.loja = loja;
    }

    public String getUrlOrigem() {
        return urlOrigem;
    }

    public void setUrlOrigem(String urlOrigem) {
        this.urlOrigem = urlOrigem;
    }

    public LocalDateTime getCapturadoEm() {
        return capturadoEm;
    }

    public void setCapturadoEm(LocalDateTime capturadoEm) {
        this.capturadoEm = capturadoEm;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
