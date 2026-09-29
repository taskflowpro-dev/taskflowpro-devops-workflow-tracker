package com.taskflow.backend.dto;

public record AuthResponse(String token, String tokenType, long expiresIn, UserResponse user) { }
