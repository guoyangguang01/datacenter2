package com.sdncustom.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SdncustomApplication {

    public static void main(String[] args) {
        SpringApplication.run(SdncustomApplication.class, args);
    }
}
