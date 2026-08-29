package com.sdncustom.server.security;

import com.sdncustom.server.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET = "this-is-a-test-secret-key-at-least-32-bytes!!";

    private JwtService createService(int expireHours) {
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setSecret(SECRET);
        properties.getJwt().setExpireHours(expireHours);
        return new JwtService(properties);
    }

    @Test
    void generateAndExtract_returnsSameUsername() {
        JwtService service = createService(24);
        String token = service.generateToken("admin");
        assertEquals("admin", service.extractUsername(token));
        assertTrue(service.isTokenValid(token));
    }

    @Test
    void expiredToken_isInvalid() {
        JwtService service = createService(-1);
        String token = service.generateToken("admin");
        assertFalse(service.isTokenValid(token));
    }

    @Test
    void tamperedToken_isInvalid() {
        JwtService service = createService(24);
        String token = service.generateToken("admin");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertFalse(service.isTokenValid(tampered));
    }

    @Test
    void garbageToken_isInvalid() {
        JwtService service = createService(24);
        assertFalse(service.isTokenValid("not-a-jwt"));
        assertFalse(service.isTokenValid(""));
    }

    @Test
    void tokenSignedByDifferentKey_isInvalid() {
        JwtService serviceA = createService(24);
        SecurityProperties other = new SecurityProperties();
        other.getJwt().setSecret("another-secret-key-which-is-also-long-enough!!");
        other.getJwt().setExpireHours(24);
        JwtService serviceB = new JwtService(other);

        String token = serviceA.generateToken("admin");
        assertFalse(serviceB.isTokenValid(token));
    }

    @Test
    void shortSecret_failsFast() {
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setSecret("too-short");
        assertThrows(IllegalStateException.class, () -> new JwtService(properties));
    }

    @Test
    void blankSecret_generatesRandomKey() {
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setSecret("");
        JwtService service = new JwtService(properties);
        String token = service.generateToken("admin");
        assertTrue(service.isTokenValid(token));
    }
}
