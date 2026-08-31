package com.acert.chatbot.controller;

import com.acert.chatbot.dto.ChatMessageRequest;
import com.acert.chatbot.dto.ChatMessageResponse;
import com.acert.chatbot.model.ChatMessage;
import com.acert.chatbot.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/history")
    public ResponseEntity<List<ChatMessageResponse>> history(Authentication authentication) {
        List<ChatMessageResponse> history = chatService.getHistory(authentication.getName())
                .stream()
                .map(ChatMessageResponse::new)
                .toList();
        return ResponseEntity.ok(history);
    }

    @PostMapping("/send")
    public ResponseEntity<ChatMessageResponse> send(@Valid @RequestBody ChatMessageRequest request,
                                                      Authentication authentication) {
        ChatMessage reply = chatService.sendMessage(authentication.getName(), request.getMessage());
        return ResponseEntity.ok(new ChatMessageResponse(reply));
    }

    @DeleteMapping("/clear")
    public ResponseEntity<Void> clear(Authentication authentication) {
        chatService.clearHistory(authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
