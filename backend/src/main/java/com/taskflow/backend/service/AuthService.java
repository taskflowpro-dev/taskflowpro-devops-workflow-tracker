package com.taskflow.backend.service;

import com.taskflow.backend.dto.AuthResponse;
import com.taskflow.backend.dto.LoginRequest;
import com.taskflow.backend.dto.RegisterRequest;
import com.taskflow.backend.dto.UserResponse;
import com.taskflow.backend.entity.User;
import com.taskflow.backend.exception.DuplicateEmailException;
import com.taskflow.backend.repository.UserRepository;
import com.taskflow.backend.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwords,
                       AuthenticationManager authenticationManager, JwtService jwtService) {
        this.users = users;
        this.passwords = passwords;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) throw new DuplicateEmailException();
        User saved = users.save(new User(request.name().trim(), email, passwords.encode(request.password())));
        return toResponse(saved);
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        User user = users.findByEmail(email).orElseThrow();
        return jwtService.issueToken(user);
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(Long id) {
        User user = users.findById(id).orElseThrow();
        return toResponse(user);
    }

    private String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private UserResponse toResponse(User user) { return new UserResponse(user.getId(), user.getName(), user.getEmail()); }
}
