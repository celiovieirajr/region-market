package com.acert.chatbot.dto;

import com.acert.chatbot.model.ChatMessage;

import java.time.LocalDateTime;

public class ChatMessageResponse {

    private Long id;
    private String role;
    private String content;
    private LocalDateTime createdAt;

    public ChatMessageResponse(ChatMessage message) {
        this.id = message.getId();
        this.role = message.getRole();
        this.content = message.getContent();
        this.createdAt = message.getCreatedAt();
    }

    public Long getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
