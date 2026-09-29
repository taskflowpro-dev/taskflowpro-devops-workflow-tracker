package com.taskflow.backend.security;

import com.taskflow.backend.entity.User;
import com.taskflow.backend.dto.AuthResponse;
import com.taskflow.backend.dto.UserResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final long expirationMs;

    public JwtService(JwtEncoder encoder, @Value("${app.jwt.expiration}") long expirationMs) {
        this.encoder = encoder;
        this.expirationMs = expirationMs;
    }

    public AuthResponse issueToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(expirationMs);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("taskflow-pro")
                .issuedAt(now).expiresAt(expiresAt).subject(user.getId().toString())
                .claim("email", user.getEmail()).build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AuthResponse(token, "Bearer", expirationMs / 1000,
                new UserResponse(user.getId(), user.getName(), user.getEmail()));
    }
}
