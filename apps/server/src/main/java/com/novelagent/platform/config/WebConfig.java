package com.novelagent.platform.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import com.novelagent.platform.web.HttpRequestLogInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;
    private final HttpRequestLogInterceptor requestLog;

    public WebConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins, HttpRequestLogInterceptor requestLog) {
        this.allowedOrigins = allowedOrigins;
        this.requestLog = requestLog;
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLog).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("ETag", "Location", "X-Request-Id");
    }
}
