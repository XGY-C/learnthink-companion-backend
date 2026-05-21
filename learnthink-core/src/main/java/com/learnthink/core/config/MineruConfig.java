package com.learnthink.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
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

        // 日志拦截器：打印实际发出的请求头和body片段，用于调试Mineru API问题
        ClientHttpRequestInterceptor loggingInterceptor = (HttpRequest request, byte[] body,
                ClientHttpRequestExecution execution) -> {
            Logger log = LoggerFactory.getLogger(getClass());
            log.info("Mineru request: {} {}", request.getMethod(), request.getURI());
            log.info("Mineru request headers: {}", request.getHeaders());
            String bodyPreview = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            log.info("Mineru request body: {}", bodyPreview.length() > 500
                    ? bodyPreview.substring(0, 500) + "... (" + body.length + " bytes)"
                    : bodyPreview);
            return execution.execute(request, body);
        };

        return builder
                .rootUri(baseUrl)
                .requestFactory(() -> factory)
                .additionalMessageConverters(new MappingJackson2HttpMessageConverter())
                .additionalInterceptors(loggingInterceptor)
                .build();
    }
}
