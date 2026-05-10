package com.learnthink.web.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RagServiceConfig {

    @Value("${rag.service.url}")
    private String ragServiceUrl;

    @Value("${rag.service.connect-timeout}")
    private Duration connectTimeout;

    @Value("${rag.service.read-timeout}")
    private Duration readTimeout;

    @Bean
    public RestTemplate ragRestTemplate(RestTemplateBuilder builder) {
        return builder
            .rootUri(ragServiceUrl)
            .connectTimeout(connectTimeout)
            .readTimeout(readTimeout)
            .build();
    }
}
