package com.acert.chatbot.controller;

import com.acert.chatbot.repository.CitySourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cities")
public class CityController {

    private final CitySourceRepository citySourceRepository;

    public CityController(CitySourceRepository citySourceRepository) {
        this.citySourceRepository = citySourceRepository;
    }

    @GetMapping
    public ResponseEntity<List<String>> list() {
        return ResponseEntity.ok(citySourceRepository.findDistinctCities());
    }
}
