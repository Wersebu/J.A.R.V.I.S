package com.jarvis.inference;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Configuration
@EnableConfigurationProperties(InferenceProperties.class)
public class InferenceConfiguration {}
