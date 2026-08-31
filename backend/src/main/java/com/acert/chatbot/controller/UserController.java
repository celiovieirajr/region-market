package com.acert.chatbot.controller;

import com.acert.chatbot.dto.UpdateCityRequest;
import com.acert.chatbot.dto.UserProfileResponse;
import com.acert.chatbot.model.User;
import com.acert.chatbot.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(Authentication authentication) {
        return ResponseEntity.ok(new UserProfileResponse(getUser(authentication)));
    }

    @PutMapping("/city")
    public ResponseEntity<UserProfileResponse> updateCity(@RequestBody UpdateCityRequest request,
                                                            Authentication authentication) {
        User user = getUser(authentication);
        String city = request.getCity() == null ? null : request.getCity().trim();
        user.setCity(city == null || city.isEmpty() ? null : city);
        userRepository.save(user);
        return ResponseEntity.ok(new UserProfileResponse(user));
    }

    private User getUser(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado"));
    }
}
