package com.jarvis.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Headless Spring Boot entry point for Jarvis.
 */
@SpringBootApplication(scanBasePackages = "com.jarvis")
public class JarvisApplication {

    /**
     * Starts the Jarvis backend.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(JarvisApplication.class, args);
    }
}
