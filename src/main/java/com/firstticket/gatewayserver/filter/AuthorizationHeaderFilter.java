package com.firstticket.gatewayserver.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
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
 *   1. 보안 헤더 위조 방지 — 모든 인바운드 보안 헤더를 먼저 제거 (스푸핑 차단)
 *   2. SecurityContext에서 검증 완료된 JwtAuthenticationToken 추출
 *   3. Redis blacklist 조회 — jti 등록 여부 확인, 등록 시 401 반환
 *   4. sub, roles, jti, exp를 다운스트림 헤더에 주입하고 전달
 *   5. JWT가 없는 공개 경로는 헤더 추가 없이 통과
 *
 * blacklist 설계:
 *   - user-service logout() → Redis SET blacklist:{jti} "1" EX {잔여TTL}
 *   - 이 필터   → Redis EXISTS blacklist:{jti} 조회
 *   - Redis 장애 시 fail-open (WARN 로그 + 통과) — 가용성 > 즉시 무효화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationHeaderFilter implements GlobalFilter, Ordered {

    // ReactiveStringRedisTemplate - WebFlux non-blocking Redis 클라이언트
    // StringRedisTemplate(MVC 동기)와 달리 Mono를 반환하여 리액티브 체인에서 사용 가능
    private final ReactiveStringRedisTemplate redisTemplate;

    // Keycloak 기본 realm 역할 - 다운스트림에 전달할 필요 없으므로 제외
    private static final Set<String> KEYCLOAK_DEFAULT_ROLES = Set.of(
        "default-roles-first-ticket",
        "offline_access",
        "uma_authorization"
    );

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY          = "roles";

    // 보안 헤더 상수 - 제거·주입 위치에서 동일 문자열 사용 보장
    private static final String HEADER_USER_ID   = "X-User-Id";    // Keycloak sub (UUID)
    private static final String HEADER_USER_ROLE = "X-User-Role";  // 앱 역할 (CUSTOMER,HOST...)
    private static final String HEADER_JTI       = "X-Jti";        // JWT ID - blacklist key
    private static final String HEADER_TOKEN_EXP = "X-Token-Exp";  // JWT exp (epoch 초) - TTL 계산용

    // user-service AccessTokenBlacklistImpl의 key prefix와 반드시 일치해야 함
    private static final String BLACKLIST_KEY_PREFIX = "blacklist:";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // 1. 인바운드 보안 헤더 전량 제거
        // 클라이언트가 X-User-Id, X-Jti 등을 임의 주입하는 헤더 스푸핑 공격을 차단하기 위함
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
            .headers(headers -> {
                headers.remove(HEADER_USER_ID);
                headers.remove(HEADER_USER_ROLE);
                headers.remove(HEADER_JTI);
                headers.remove(HEADER_TOKEN_EXP);
            })
            .build();

        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitizedRequest).build();

        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            // JwtAuthenticationToken이 아닌 경우(공개 경로)는 switchIfEmpty로 처리
            .filter(auth -> auth instanceof JwtAuthenticationToken)
            .cast(JwtAuthenticationToken.class)
            .flatMap(auth -> {
                // 2. JWT 클레임 추출
                String userId = auth.getToken().getSubject(); // sub: Keycloak 사용자 UUID
                String roles  = extractAppRoles(auth);        // realm_access.roles 필터링
                String jti    = auth.getToken().getId();      // jti: JWT 고유 식별자

                // expiresAt()이 null이면 0 처리 - 이미 만료로 간주
                long exp = auth.getToken().getExpiresAt() != null
                    ? auth.getToken().getExpiresAt().getEpochSecond()
                    : 0L;

                // 3. Redis blacklist 조회 (non-blocking)
                return redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + jti)
                    // Redis 장애 처리 — flatMap 이전에 배치하여 Redis 에러만 처리
                    // onErrorResume이 flatMap 이후에 있으면 setComplete() 에러까지 잡아 500 유발
                    .onErrorResume(e -> {
                        log.warn("[blacklist] Redis 조회 실패 - fail-open 적용, jtiPrefix: {}, error: {}",
                            abbreviated(jti), e.getMessage());
                        return Mono.just(false); // Redis 장애 시 not-blacklisted로 처리 후 통과
                    })
                    .flatMap(isBlacklisted -> {
                        if (Boolean.TRUE.equals(isBlacklisted)) {
                            log.warn("[blacklist] 무효화된 토큰 요청 차단 - jtiPrefix: {}",
                                abbreviated(jti));
                            ServerHttpResponse response = exchange.getResponse();
                            response.setStatusCode(HttpStatus.UNAUTHORIZED);
                            DataBuffer buffer = response.bufferFactory().wrap(new byte[0]);
                            return response.writeWith(Mono.just(buffer));
                        }

                        // 정상 토큰 — 다운스트림 헤더 주입 후 전달
                        return chain.filter(
                            withSecurityHeaders(sanitizedExchange, userId, roles, jti, exp)
                        );
                    })
                    // 5. Redis 장애 시 fail-open 정책
                    // Access Token TTL이 짧아 비즈니스 임팩트 제한적 → 가용성 우선
                    .onErrorResume(e -> {
                        log.warn("[blacklist] Redis 조회 실패 - fail-open 적용, jtiPrefix: {}, error: {}",
                            abbreviated(jti), e.getMessage());
                        // Redis 장애에도 서비스 중단 없이 요청 통과
                        return chain.filter(
                            withSecurityHeaders(sanitizedExchange, userId, roles, jti, exp)
                        );
                    });
            })
            // JWT가 없는 요청(signup, login 등 공개 경로) - 헤더 추가 없이 통과
            .switchIfEmpty(chain.filter(sanitizedExchange));
    }

    /**
     * 보안 헤더를 주입한 새 교환 객체 반환
     * 정상 흐름과 Redis fail-open 두 곳에서 동일하게 사용하므로 메서드로 추출
     */
    private ServerWebExchange withSecurityHeaders(
        ServerWebExchange base,
        String userId, String roles, String jti, long exp
    ) {
        ServerHttpRequest mutatedRequest = base.getRequest().mutate()
            .header(HEADER_USER_ID,   userId)              // 사용자 식별 — 다운스트림 신뢰
            .header(HEADER_USER_ROLE, roles)               // 권한 정보 — 다운스트림 신뢰
            .header(HEADER_JTI,       jti)                 // logout 시 blacklist 저장 키
            .header(HEADER_TOKEN_EXP, String.valueOf(exp)) // Redis TTL 계산용 (epoch 초)
            .build();
        return base.mutate().request(mutatedRequest).build();
    }

    // Keycloak JWT의 realm_access.roles 에서 앱 역할(CUSTOMER, HOST, ADMIN)만 추출
    @SuppressWarnings("unchecked")
    private String extractAppRoles(JwtAuthenticationToken auth) {
        Map<String, Object> realmAccess = auth.getToken().getClaim(REALM_ACCESS_CLAIM);

        if (realmAccess == null || !realmAccess.containsKey(ROLES_KEY)) {
            return "";
        }

        List<String> allRoles = (List<String>) realmAccess.get(ROLES_KEY);

        return allRoles.stream()
            .filter(role -> !KEYCLOAK_DEFAULT_ROLES.contains(role))
            .collect(Collectors.joining(","));
    }

    // 로그용 jti 앞 8자만 출력 - 식별자 원문 노출 방지 (CodeRabbit 마스킹 지침)
    private String abbreviated(String jti) {
        if (jti == null || jti.length() <= 8) return "****";
        return jti.substring(0, 8) + "...";
    }

    @Override
    public int getOrder() {
        return 0; // Spring Security WebFilter(-100) 이후, NettyRoutingFilter(MAX_VALUE) 이전 실행
    }
}