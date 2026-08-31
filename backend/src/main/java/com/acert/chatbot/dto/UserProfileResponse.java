package com.acert.chatbot.dto;

import com.acert.chatbot.model.User;

public class UserProfileResponse {

    private String username;
    private String role;
    private String city;

    public UserProfileResponse(User user) {
        this.username = user.getUsername();
        this.role = user.getRole();
        this.city = user.getCity();
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public String getCity() {
        return city;
    }
}
