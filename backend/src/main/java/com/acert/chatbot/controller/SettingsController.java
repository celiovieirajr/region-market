package com.acert.chatbot.controller;

import com.acert.chatbot.dto.CacheSettingsRequest;
import com.acert.chatbot.dto.CitySourceRequest;
import com.acert.chatbot.dto.CitySourceResponse;
import com.acert.chatbot.model.CitySource;
import com.acert.chatbot.service.CitySourceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Toda essa rota ("/api/settings/**") já é ADMIN-only via SecurityConfig
// (authorizeHttpRequests -> hasRole("ADMIN")) — não precisa reforçar aqui.
@RestController
@RequestMapping("/api/settings/sources")
public class SettingsController {

    private final CitySourceService citySourceService;

    public SettingsController(CitySourceService citySourceService) {
        this.citySourceService = citySourceService;
    }

    @GetMapping
    public ResponseEntity<List<CitySourceResponse>> list(Authentication authentication) {
        List<CitySourceResponse> sources = citySourceService.list(authentication.getName())
                .stream()
                .map(citySourceService::toResponse)
                .toList();
        return ResponseEntity.ok(sources);
    }

    @PostMapping
    public ResponseEntity<CitySourceResponse> create(@Valid @RequestBody CitySourceRequest request,
                                                       Authentication authentication) {
        var source = citySourceService.create(authentication.getName(), request);
        return ResponseEntity.ok(citySourceService.toResponse(source));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        citySourceService.delete(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Atualiza só as configurações de cache (cacheEnabled/cacheTtlHours) de
     * um source existente — o mecanismo é genérico, qualquer CitySource pode
     * ligar o cache, não só o Pão de Açúcar.
     */
    @PutMapping("/{id}")
    public ResponseEntity<CitySourceResponse> updateCacheSettings(@PathVariable Long id,
                                                                    @Valid @RequestBody CacheSettingsRequest request) {
        CitySource source = citySourceService.updateCacheSettings(id, request.getCacheEnabled(),
                request.getCacheTtlHours());
        return ResponseEntity.ok(citySourceService.toResponse(source));
    }

    /**
     * Força um warm-up imediato do cache desse source, ignorando o TTL —
     * ação manual do admin, síncrona (pode demorar, é aceitável).
     */
    @PostMapping("/{id}/cache/refresh")
    public ResponseEntity<CitySourceResponse> refreshCache(@PathVariable Long id) {
        CitySource source = citySourceService.refreshCache(id);
        return ResponseEntity.ok(citySourceService.toResponse(source));
    }
}
