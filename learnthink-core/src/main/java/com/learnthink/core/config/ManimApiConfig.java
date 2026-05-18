package com.learnthink.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Manim视频API配置类
 * 为Manim视频渲染服务创建专用的RestTemplate，配置合理的超时时间
 */
@Configuration
public class ManimApiConfig {

    @Value("${manim.video.api.connect-timeout:30s}")
    private Duration connectTimeout;

    @Value("${manim.video.api.read-timeout:300s}")
    private Duration readTimeout;

    /**
     * 创建Manim API专用的RestTemplate
     * 连接超时：30秒（默认）
     * 读取超时：300秒/5分钟（默认，视频渲染是耗时操作）
     * 
     * @param builder RestTemplate构建器
     * @return 配置好的RestTemplate实例
     */
    @Bean("manimRestTemplate")
    public RestTemplate manimRestTemplate(RestTemplateBuilder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        
        return builder
            .requestFactory(() -> factory)
            .build();
    }
}
