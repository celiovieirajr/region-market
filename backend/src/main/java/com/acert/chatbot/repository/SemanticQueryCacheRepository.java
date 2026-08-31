package com.acert.chatbot.repository;

import com.acert.chatbot.model.SemanticQueryCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Volume esperado é pequeno (algumas centenas de linhas — uma por
 * "pergunta única" já vista), então {@code SemanticCacheService} carrega
 * tudo em memória via {@link #findAll()} pra calcular similaridade de
 * cosseno contra o embedding da mensagem nova, em vez de tentar fazer essa
 * conta no banco.
 */
public interface SemanticQueryCacheRepository extends JpaRepository<SemanticQueryCache, Long> {

    @Override
    List<SemanticQueryCache> findAll();
}
