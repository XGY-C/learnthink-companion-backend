package com.learnthink.web.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;

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
        // 创建自定义的请求工厂
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        
        return builder
            .rootUri(ragServiceUrl)
            .requestFactory(() -> factory)
            .additionalMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
    }
}
