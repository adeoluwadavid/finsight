package com.finsight.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()

                // Auth service routes
                // StripPrefix=1 removes /api from the path before forwarding
                // So /api/auth/login becomes /auth/login on auth-service
                .route("auth-service", r -> r
                        .path("/api/auth/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("http://localhost:8081"))

                // Document service routes
                // /api/documents/upload becomes /documents/upload on document-service
                .route("document-service", r -> r
                        .path("/api/documents/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("http://localhost:8082"))

                // Conversation service routes
                // /api/conversations/** becomes /conversations/** on conversation-service
                .route("conversation-service", r -> r
                        .path("/api/conversations/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("http://localhost:8083"))

                .build();
    }
}