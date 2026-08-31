package com.acert.chatbot.service;

import com.acert.chatbot.dto.CitySourceRequest;
import com.acert.chatbot.dto.CitySourceResponse;
import com.acert.chatbot.model.CitySource;
import com.acert.chatbot.model.User;
import com.acert.chatbot.repository.CitySourceRepository;
import com.acert.chatbot.repository.SiteCacheRepository;
import com.acert.chatbot.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CitySourceService {

    private final CitySourceRepository citySourceRepository;
    private final UserRepository userRepository;
    private final SiteCacheRepository siteCacheRepository;
    private final CacheWarmerService cacheWarmerService;

    public CitySourceService(CitySourceRepository citySourceRepository,
                              UserRepository userRepository,
                              SiteCacheRepository siteCacheRepository,
                              CacheWarmerService cacheWarmerService) {
        this.citySourceRepository = citySourceRepository;
        this.userRepository = userRepository;
        this.siteCacheRepository = siteCacheRepository;
        this.cacheWarmerService = cacheWarmerService;
    }

    @Transactional(readOnly = true)
    public List<CitySource> list(String username) {
        return citySourceRepository.findByUserOrderByCityAscLabelAsc(getUser(username));
    }

    @Transactional
    public CitySource create(String username, CitySourceRequest request) {
        User user = getUser(username);
        CitySource source = new CitySource();
        source.setUser(user);
        source.setCity(request.getCity().trim());
        source.setLabel(request.getLabel().trim());
        source.setUrl(normalizeUrl(request.getUrl().trim()));
        return citySourceRepository.save(source);
    }

    @Transactional
    public void delete(String username, Long id) {
        User user = getUser(username);
        CitySource source = citySourceRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new EntityNotFoundException("Fonte não encontrada"));
        if (!source.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("Você não pode remover essa fonte");
        }
        citySourceRepository.delete(source);
    }

    /**
     * Compõe o DTO de resposta com o `cacheLastUpdatedAt` calculado a partir
     * da entrada de cache mais recente desse source (null se nunca cacheou).
     */
    @Transactional(readOnly = true)
    public CitySourceResponse toResponse(CitySource source) {
        LocalDateTime lastUpdated = siteCacheRepository.findLatestFetchedAt(source.getId()).orElse(null);
        return new CitySourceResponse(source, lastUpdated);
    }

    @Transactional
    public CitySource updateCacheSettings(Long id, boolean cacheEnabled, int cacheTtlHours) {
        CitySource source = getById(id);
        source.setCacheEnabled(cacheEnabled);
        source.setCacheTtlHours(cacheTtlHours);
        return citySourceRepository.save(source);
    }

    /**
     * Força o warm-up imediato do cache desse source, ignorando o TTL — ação
     * manual do admin. Síncrono mesmo que demore (Playwright fila única).
     *
     * Sem @Transactional aqui de propósito: o fetch ao vivo (Playwright) pode
     * demorar minutos, e não queremos segurar uma transação/conexão de banco
     * aberta esse tempo todo. O upsert de cada termo (upsertCache) já abre
     * sua própria transação curta.
     */
    public CitySource refreshCache(Long id) {
        CitySource source = getById(id);
        cacheWarmerService.warmCache(source);
        return source;
    }

    @Transactional(readOnly = true)
    public CitySource getById(Long id) {
        return citySourceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Fonte não encontrada"));
    }

    private String normalizeUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado: " + username));
    }
}
