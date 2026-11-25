package com.jarvis.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Headless Spring Boot entry point for Jarvis.
 */
@SpringBootApplication(scanBasePackages = "com.jarvis")
@org.springframework.context.annotation.ComponentScan(basePackages = "com.jarvis", excludeFilters = {
        // Retain Boot's standard exclusions, then reject development launchers even
        // if an integration dependency or stale class is accidentally on the classpath.
        @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.CUSTOM,
                classes = {org.springframework.boot.context.TypeExcludeFilter.class,
                        org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter.class}),
        @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                pattern = "com\\.jarvis\\.integration\\..*")
})
@EnableScheduling
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
