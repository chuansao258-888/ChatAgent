package com.yulong.chatagent.agent.user.application;

import com.yulong.chatagent.agent.user.model.dto.JwtClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtTokenService {

    private final String issuer;
    private final long accessTtlMinutes;
    private final SecretKey signingKey;

    public JwtTokenService(@Value("${auth.jwt.secret}") String secret,
                           @Value("${auth.jwt.issuer}") String issuer,
                           @Value("${auth.jwt.access-ttl-minutes}") long accessTtlMinutes) {
        this.issuer = issuer;
        this.accessTtlMinutes = accessTtlMinutes;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(JwtClaims claims) {
        if (claims == null) {
            throw new IllegalArgumentException("claims cannot be null");
        }
        if (claims.getUserId() == null || claims.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId cannot be blank");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTtlMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(claims.getUserId())
                .claim("username", claims.getUsername())
                .claim("role", claims.getRole())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public JwtClaims parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token cannot be blank");
        }

        Claims payload = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return JwtClaims.builder()
                .userId(payload.getSubject())
                .username(payload.get("username", String.class))
                .role(payload.get("role", String.class))
                .build();
    }
    public boolean isAccessTokenValid(String token) {
        try {
            parseAccessToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
