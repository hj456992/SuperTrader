package com.supertrader.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point. This is the ONLY backend and the ONLY HTTP service.
 * It serves the three read-only diagnosis endpoints and orchestrates the
 * native CTP probe as a subprocess. It can NEVER place, cancel or recover
 * orders.
 */
@SpringBootApplication
public class SuperTraderApplication {
    public static void main(String[] args) {
        SpringApplication.run(SuperTraderApplication.class, args);
    }
}
