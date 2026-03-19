package com.yulong.chatagent.user.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
/**
 * Registers MVC components for the user/authentication module.
 */
public class UserWebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;

    public UserWebMvcConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Only endpoints under /api/user/** require the access-token interceptor
        // at the moment. Auth endpoints manage refresh tokens separately.
        registry.addInterceptor(jwtAuthenticationInterceptor)
                .addPathPatterns("/api/user/**");
    }
}
