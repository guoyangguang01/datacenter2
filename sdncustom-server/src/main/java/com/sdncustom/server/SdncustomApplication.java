package com.sdncustom.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan("com.sdncustom")
@EntityScan("com.sdncustom.common.model")
@EnableJpaRepositories("com.sdncustom.server.repository")
public class SdncustomApplication {

    public static void main(String[] args) {
        SpringApplication.run(SdncustomApplication.class, args);
    }
}
