package com.acert.chatbot.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class CacheSettingsRequest {

    @NotNull(message = "cacheEnabled é obrigatório")
    private Boolean cacheEnabled;

    @NotNull(message = "cacheTtlHours é obrigatório")
    @Min(value = 1, message = "cacheTtlHours precisa ser pelo menos 1")
    private Integer cacheTtlHours;

    public Boolean getCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(Boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public Integer getCacheTtlHours() {
        return cacheTtlHours;
    }

    public void setCacheTtlHours(Integer cacheTtlHours) {
        this.cacheTtlHours = cacheTtlHours;
    }
}
