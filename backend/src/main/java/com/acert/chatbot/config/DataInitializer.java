package com.acert.chatbot.config;

import com.acert.chatbot.model.CitySource;
import com.acert.chatbot.model.User;
import com.acert.chatbot.repository.CitySourceRepository;
import com.acert.chatbot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Locale;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public CommandLineRunner seedAdminUser(UserRepository userRepository,
                                            PasswordEncoder passwordEncoder,
                                            @Value("${app.admin.username}") String adminUsername,
                                            @Value("${app.admin.password}") String adminPassword) {
        return args -> {
            if (!userRepository.existsByUsername(adminUsername)) {
                User admin = new User();
                admin.setUsername(adminUsername);
                admin.setPassword(passwordEncoder.encode(adminPassword));
                admin.setRole("ADMIN");
                userRepository.save(admin);
            }
        };
    }

    /**
     * Habilita o cache de scraping nos sources que têm catálogo real com
     * busca dinâmica funcional: Pão de Açúcar (o mercado mais lento,
     * ~4-20s — ver DECISIONS.md item 2), Rondon e Atacadão. Busca por trecho
     * do label (não por id fixo, que pode mudar) e é um no-op silencioso se
     * o source ainda não existir. Amigão e Assaí ficam de fora de propósito
     * — não têm busca dinâmica real (páginas institucionais estáticas, ver
     * memory/SITE_INTEGRATIONS.md), cachear neles não faz sentido.
     *
     * Não força se o admin já mexeu manualmente: só seta na primeira vez que
     * encontra o source com cache ainda desligado no valor padrão.
     */
    @Bean
    public CommandLineRunner seedCacheEnabledSources(CitySourceRepository citySourceRepository) {
        List<String> labelFragments = List.of("acucar", "rondon", "atacadao");
        return args -> {
            List<CitySource> candidates = citySourceRepository.findAll().stream()
                    .filter(s -> s.getLabel() != null)
                    .filter(s -> {
                        String normalized = s.getLabel().toLowerCase(Locale.ROOT)
                                .replace("ã", "a").replace("ç", "c").replace("ô", "o");
                        return labelFragments.stream().anyMatch(normalized::contains);
                    })
                    .toList();

            for (CitySource source : candidates) {
                if (!source.isCacheEnabled()) {
                    source.setCacheEnabled(true);
                    citySourceRepository.save(source);
                    log.info("Cache de scraping habilitado por seed em '{}' (id={})",
                            source.getLabel(), source.getId());
                }
            }
        };
    }
}
