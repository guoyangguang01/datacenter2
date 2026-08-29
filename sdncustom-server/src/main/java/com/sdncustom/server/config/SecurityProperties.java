package com.sdncustom.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "sdncustom.security")
public class SecurityProperties {

    private String username = "admin";
    private String password = "changeme";
    private Jwt jwt = new Jwt();

    @Data
    public static class Jwt {
        private String secret = "";
        private int expireHours = 24;
    }
}
