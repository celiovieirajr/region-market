package com.acert.chatbot.repository;

import com.acert.chatbot.model.CitySource;
import com.acert.chatbot.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CitySourceRepository extends JpaRepository<CitySource, Long> {
    List<CitySource> findByUserOrderByCityAscLabelAsc(User user);
    Optional<CitySource> findByIdAndUser(Long id, User user);

    // Os sites são cadastrados pelo admin e valem para qualquer usuário que
    // selecione aquela cidade no perfil — por isso a busca por cidade é global.
    List<CitySource> findByCityIgnoreCase(String city);

    // Sources com cache de scraping habilitado (ver CacheWarmerService).
    List<CitySource> findByCacheEnabledTrue();

    @Query("select distinct c.city from CitySource c order by c.city")
    List<String> findDistinctCities();
}
