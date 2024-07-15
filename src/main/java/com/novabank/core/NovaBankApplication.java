package com.novabank.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class NovaBankApplication {
    public static void main(String[] args) {
        SpringApplication.run(NovaBankApplication.class, args);
    }
}
