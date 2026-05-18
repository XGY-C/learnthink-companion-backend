package com.learnthink.web.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web配置
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    
    private final AuthInterceptor authInterceptor;
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册认证拦截器，排除公开接口
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/chat/**", "/tasks/**", "/test/**", "/example/**", "/user/**", "/resource-packs/**", "/resources/**", "/courses/**", "/admin/**")
                .excludePathPatterns("/auth/**", "/chat/*/send/stream", "/tasks/*/events");
    }
}
