package com.acert.chatbot.repository;

import com.acert.chatbot.model.ChatMessage;
import com.acert.chatbot.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByUserOrderByCreatedAtAsc(User user);
    void deleteByUser(User user);
}
