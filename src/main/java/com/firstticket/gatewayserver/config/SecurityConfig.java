package com.firstticket.gatewayserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

// Gateway는 WebFlux 기반이므로 @EnableWebFluxSecurity 사용
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // 토큰 검증 없이 접근 가능한 공개 경로 상수 정의 (매직스트링 금지)
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/signup",           // 회원가입 - 토큰 불필요
            "/api/v1/auth/login",            // 로그인 - 토큰 불필요
            "/api/v1/auth/token/refresh",    // 토큰 재발급 - Refresh Token을 Body로 전달
            "/actuator/health",              // 헬스체크 - ALB 타겟 그룹 헬스체크용
            "/actuator/info"                 // 서비스 정보
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Gateway는 REST API 서버이므로 CSRF비활성화
                // SameSite 쿠키 전략으로 대체
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                .authorizeExchange(exchanges -> exchanges
                        // 공개 경로는 인증 없이 허용
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        // 그 외 모든 요청은 JWT 필수
                        .anyExchange().authenticated()
                )

                // OAuth2 Resource Server 활성화
                // application.yml의 jwk-set-uri를 사용해 Keycloak 공개키로 JWT 서명 검증
                // 검증 성공 시 JwtAuthenticationToken이 SecurityContext에 저장됨
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                )

                .build();
    }
}