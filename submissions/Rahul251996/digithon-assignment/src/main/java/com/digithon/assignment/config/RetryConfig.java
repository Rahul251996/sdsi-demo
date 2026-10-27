package com.digithon.assignment.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class RetryConfig {
    // Retry configuration. Actual retry logic is implemented in RetryHelper.
    // We avoid @EnableRetry + @Retryable to ensure compatibility with Spring Boot 4.x.
}