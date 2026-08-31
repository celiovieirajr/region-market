package com.acert.chatbot.repository;

import com.acert.chatbot.model.SiteCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SiteCacheRepository extends JpaRepository<SiteCache, Long> {

    Optional<SiteCache> findByCitySourceIdAndTermIgnoreCase(Long citySourceId, String term);

    List<SiteCache> findByCitySourceId(Long citySourceId);

    @Query("select max(s.fetchedAt) from SiteCache s where s.citySource.id = :citySourceId")
    Optional<LocalDateTime> findLatestFetchedAt(@Param("citySourceId") Long citySourceId);

    @Query("select min(s.fetchedAt) from SiteCache s where s.citySource.id = :citySourceId")
    Optional<LocalDateTime> findOldestFetchedAt(@Param("citySourceId") Long citySourceId);
}
