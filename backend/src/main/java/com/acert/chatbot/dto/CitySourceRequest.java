package com.acert.chatbot.dto;

import jakarta.validation.constraints.NotBlank;

public class CitySourceRequest {

    @NotBlank(message = "Cidade é obrigatória")
    private String city;

    @NotBlank(message = "Rótulo é obrigatório")
    private String label;

    @NotBlank(message = "URL é obrigatória")
    private String url;

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
}
