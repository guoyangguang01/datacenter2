package com.sdncustom.server.security;

import com.sdncustom.server.config.SecurityProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final int expireHours;

    public JwtService(SecurityProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            // 开发兜底：随机密钥，重启后所有已发 token 失效
            this.key = Jwts.SIG.HS256.key().build();
            log.warn("sdncustom.security.jwt.secret not configured, using a random key. "
                    + "Tokens will be invalidated on restart. Set SDNCUSTOM_JWT_SECRET in production.");
        } else {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "sdncustom.security.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        }
        this.expireHours = properties.getJwt().getExpireHours();
    }

    public String generateToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TimeUnit.HOURS.toMillis(expireHours)))
                .signWith(key)
                .compact();
    }

    public String extractUsername(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean isTokenValid(String token) {
        try {
            extractUsername(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getExpireMillis() {
        return TimeUnit.HOURS.toMillis(expireHours);
    }
}
