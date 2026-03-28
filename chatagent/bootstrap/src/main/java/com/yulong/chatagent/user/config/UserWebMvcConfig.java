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
    private final com.yulong.chatagent.access.RequireRoleInterceptor requireRoleInterceptor;

    public UserWebMvcConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor,
                            com.yulong.chatagent.access.RequireRoleInterceptor requireRoleInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
        this.requireRoleInterceptor = requireRoleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Protect every application API route except the auth endpoints that are
        // responsible for issuing and rotating tokens.
        registry.addInterceptor(jwtAuthenticationInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
        registry.addInterceptor(requireRoleInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }
}
