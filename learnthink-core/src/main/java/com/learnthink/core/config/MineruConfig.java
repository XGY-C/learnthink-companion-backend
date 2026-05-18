package com.learnthink.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class MineruConfig {

    @Value("${mineru.api.base-url}")
    private String baseUrl;

    @Value("${mineru.api.token}")
    private String token;

    @Bean
    public RestTemplate mineruRestTemplate(RestTemplateBuilder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());

        if (token != null && !token.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + token);
        }

        return builder
                .rootUri(baseUrl)
                .requestFactory(() -> factory)
                .additionalMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
