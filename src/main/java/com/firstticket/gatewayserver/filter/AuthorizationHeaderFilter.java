package com.firstticket.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JWT 클레임에서 사용자 정보를 추출해 다운스트림 서비스 헤더에 주입하는 GlobalFilter
 *
 * 처리 흐름:
 *   1. Spring Security가 JWT 서명 검증 후 SecurityContext에 JwtAuthenticationToken 저장
 *   2. 이 필터가 SecurityContext에서 토큰을 꺼내 sub(userId), roles를 추출
 *   3. X-User-Id, X-User-Role 헤더를 추가한 뒤 다음 필터(라우팅)로 전달
 *
 * - 다운스트림 서비스는 이 헤더를 신뢰하며 자체 JWT 재검증을 하지 않음
 */
@Component
public class AuthorizationHeaderFilter implements GlobalFilter, Ordered {

    // 시스템 전용 역할 - Keycloak 기본 realm 역할이므로 다운스트림 헤더에서 제외
    private static final Set<String> KEYCLOAK_DEFAULT_ROLES = Set.of(
            "default-roles-first-ticket",
            "offline_access",
            "uma_authorization"
    );

    // Keycloak JWT에서 realm 레벨 역할을 담는 클레임 키
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    // realm_access 맵 내부에서 역할 목록을 담는 키
    private static final String ROLES_KEY = "roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                // SecurityContext에서 Authentication 꺼내기
                .map(SecurityContext::getAuthentication)
                // 익명 사용자(Authentication이 유효한 JwtAuthenticationToken이 아닌 경우) 필터링
                // 공개 경로(/signup, /login 등)는 JwtAuthenticationToken이 없으므로 switchIfEmpty로 처리
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .flatMap(auth -> {
                    // JWT의 'sub' 클레임 = Keycloak 내부 사용자 UUID
                    String userId = auth.getToken().getSubject();

                    // realm_access.roles 에서 애플리케이션 역할만 추출
                    String roles = extractAppRoles(auth);

                    // 원본 요청을 변경 불가능(Immutable)하므로 mutate()로 새 요청 생성
                    // 헤더 주입 구간
                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-Id", userId)     // 다운스트림 서비스가 사용자 식별에 사용
                            .header("X-User-Role", roles)    // 다운스트림 서비스가 권한 검증에 사용
                            .build();

                    // 변경된 요청으로 교환 객체를 다시 빌드해 다음 필터로 전달
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                // JWT가 없는 요청(공개 경로) - 헤더 추가 없이 그대로 통과
                .switchIfEmpty(chain.filter(exchange));
    }

    /**
     * Keycloak JWT의 realm_access.roles 에서 앱 역할(CUSTOMER, HOST, ADMIN)만 추출.
     * 역할이 없거나 클레임이 없으면 빈 문자열 반환.
     */
    @SuppressWarnings("unchecked")
    private String extractAppRoles(JwtAuthenticationToken auth) {
        // realm_access 클레임은 Map<String, Object> 형태
        Map<String, Object> realmAccess = auth.getToken().getClaim(REALM_ACCESS_CLAIM);

        if (realmAccess == null || !realmAccess.containsKey(ROLES_KEY)) {
            return "";  // 역할 클레임 없음 — 빈 문자열로 다운스트림 전달
        }

        // Keycloak이 역할을 List<String>으로 제공
        List<String> allRoles = (List<String>) realmAccess.get(ROLES_KEY);

        // Keycloak 기본 역할(offline_access 등)을 제외하고 앱 역할만 남김
        return allRoles.stream()
                .filter(role -> !KEYCLOAK_DEFAULT_ROLES.contains(role))
                .collect(Collectors.joining(","));  // "CUSTOMER,HOST" 형태로 조인
    }

    /**
     * GlobalFilter 실행 순서.
     * Spring Security WebFilter는 -100 근처에서 실행 (SecurityContext 설정 완료)
     * 해당 필터는 0 에서 실행 -> Security 이후, NettyRoutingFilter(Integer.MAX_VALUE) 이전 보장
     */
    @Override
    public int getOrder() {
        return 0;
    }
}