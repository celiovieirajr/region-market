package com.acert.chatbot.repository;

import com.acert.chatbot.model.ProdutoCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProdutoCacheRepository extends JpaRepository<ProdutoCache, Long> {

    @Query("select p from ProdutoCache p where p.citySource.id = :citySourceId "
            + "and lower(p.termoBusca) = lower(:termo) and p.status = 'OK' and p.capturadoEm > :since "
            + "order by p.nomeProduto asc")
    List<ProdutoCache> findFreshOk(@Param("citySourceId") Long citySourceId,
                                    @Param("termo") String termo,
                                    @Param("since") LocalDateTime since);

    // Busca (não apaga) as linhas antigas desse (source, termo) — o
    // CHAMADOR (CacheWarmerService.upsertProdutoCache) apaga via
    // `deleteAll(List)`, herdado direto de SimpleJpaRepository. Motivo de
    // não usar um método `deleteBy...`/`@Modifying` aqui: esses exigem que
    // o CHAMADOR já esteja numa transação ativa, o que quebra em
    // auto-invocação dentro da mesma classe (CacheWarmerService.warmCache
    // chamando seu próprio método @Transactional não passa pelo proxy do
    // Spring, então a transação nunca é aberta — erro real encontrado:
    // "No EntityManager with actual transaction available"). `deleteAll`
    // (método base de CRUD, não derivado) É garantidamente transacional no
    // proxy do PRÓPRIO repositório, então funciona independente de quem
    // chama.
    List<ProdutoCache> findByCitySourceIdAndTermoBuscaIgnoreCase(Long citySourceId, String termo);

    @Query("select max(p.capturadoEm) from ProdutoCache p where p.citySource.id = :citySourceId")
    Optional<LocalDateTime> findLatestCapturadoEm(@Param("citySourceId") Long citySourceId);
}
