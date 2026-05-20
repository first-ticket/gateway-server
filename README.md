# gateway-service

First Ticket 프로젝트의 단일 진입점(API Gateway) 서비스.  
모든 외부 요청은 이 서버를 통해 각 마이크로서비스로 라우팅된다.

---

## 📌 핵심 기능

### 1. JWT 검증

Spring Security OAuth2 Resource Server 가 Keycloak JWK Set URI 로 Access Token 서명을 검증한다.  
서비스 기동 시 Keycloak 에 즉시 연결하지 않고, **첫 JWT 검증 요청 시 Lazy 하게** 공개키를 가져온다.

```
클라이언트 → Gateway (JWT 서명 검증) → 다운스트림 서비스
```

### 2. 보안 헤더 주입 (`AuthorizationHeaderFilter`)

JWT 검증 후 클레임에서 사용자 정보를 추출해 다운스트림 서비스 헤더에 주입한다.  
다운스트림 서비스는 자체 JWT 재검증 없이 이 헤더를 신뢰한다.

| 헤더 | 값 | 출처 |
| --- | --- | --- |
| `X-User-Id` | Keycloak 사용자 UUID | JWT `sub` 클레임 |
| `X-User-Role` | 앱 역할 (CUSTOMER · HOST · ADMIN) | JWT `realm_access.roles` 클레임 |
| `X-Jti` | JWT 고유 식별자 | JWT `jti` 클레임 |
| `X-Token-Exp` | 토큰 만료 시각 (epoch 초) | JWT `exp` 클레임 |

> **헤더 스푸핑 방지**: 인바운드 보안 헤더(`X-User-Id`, `X-User-Role`, `X-Jti`, `X-Token-Exp`)를 먼저 전량 제거한 뒤 재주입한다.

#### Blacklist 조회 (L1 Caffeine → L2 Redis)

로그아웃한 토큰 차단을 위해 2-tier 캐시로 Redis blacklist 를 조회한다.

| 계층 | 구현 | TTL | 비고 |
| --- | --- | --- | --- |
| L1 | Caffeine (In-Process) | 5초 | Redis 왕복 ~99% 감소 |
| L2 | Redis `blacklist:{jti}` | AT 잔여 TTL | user-service logout 시 등록 |

Redis 장애 시 **fail-open** (WARN 로그 + 통과) — 가용성 우선.

### 3. 분산 추적 헤더 주입 (`TraceIdFilter`)

Zipkin 분산 추적을 위해 Brave traceId 를 `X-Trace-Id` 요청·응답 헤더에 주입한다.  
Netty epoll 스레드의 ThreadLocal 미전파 문제를 `ContextSnapshotFactory` 로 해결한다.

### 4. 라우팅

| 서비스 | 경로 패턴 |
| --- | --- |
| user-service | `/api/v1/auth/**` `/api/v1/users/**` `/api/v1/admin/users/**` `/api/v1/admin/host-requests/**` |
| program-service | `/api/v1/programs/**` |
| venue-service | `/api/v1/venues/**` |
| booking-service | `/api/v1/bookings/**` `/api/v1/tickets/**` `/api/v1/admin/bookings/**` `/api/v1/seats/**` |
| payment-service | `/api/v1/payments/**` `/api/v1/admin/payments/**` |
| queue-service | `/api/v1/queues/**` `/api/v1/admin/queues/**` |

### 5. 서킷브레이커 (Resilience4j Reactive)

다운스트림 서비스 장애 시 즉시 fallback 응답을 반환해 연쇄 장애를 방지한다.

**공통 파라미터**

| 파라미터 | 값 | 설명 |
| --- | --- | --- |
| `sliding-window-size` | 10 | 실패율 계산 기준 최근 호출 수 |
| `failure-rate-threshold` | 50% | OPEN 전환 실패율 임계값 |
| `minimum-number-of-calls` | 5 | OPEN 전환 전 최소 호출 수 |
| `wait-duration-in-open-state` | 10s | OPEN 유지 후 HALF_OPEN 전환 |
| `permitted-calls-in-half-open-state` | 5 | HALF_OPEN 시험 요청 수 |

**TimeLimiter (응답 타임아웃)**

| 서비스 | timeout |
| --- | --- |
| 기본값 (전 서비스) | 3s |
| payment-service | **10s** (PG사 연동 고려) |

**상태 흐름**

```
CLOSED → [실패율 ≥ 50%] → OPEN (fallback 반환)
                                ↓ 10초
                          HALF_OPEN (시험 5회)
                            ↙         ↘
                         성공         실패
                        CLOSED       OPEN
```

상세 파라미터 → [config-repo/gateway-server.yml](https://github.com/first-ticket/config-repo/blob/main/gateway-server/gateway-server.yml)

### 6. 공개 경로 (인증 불필요)

| 경로 | 설명 |
| --- | --- |
| `POST /api/v1/auth/signup` | 회원가입 |
| `POST /api/v1/auth/login` | 로그인 |
| `POST /api/v1/auth/token/refresh` | Access Token 재발급 |
| `GET /actuator/health` | ALB 헬스체크 |
| `GET /actuator/info` | 서비스 정보 |

---

## 🛠 기술 스택

| 항목 | 기술 |
| --- | --- |
| 런타임 | Spring Cloud Gateway (WebFlux / Netty 기반, non-blocking) |
| 인증 | Spring Security OAuth2 Resource Server + Keycloak JWK |
| Blacklist | Redis Reactive (L2) + Caffeine (L1, 5s TTL) |
| 서킷브레이커 | Resilience4j Reactive (`spring-cloud-starter-circuitbreaker-reactor-resilience4j`) |
| LB 캐시 | Caffeine (`spring.cloud.loadbalancer.cache.ttl` 제어) |
| 분산 추적 | Micrometer Tracing + Brave + Zipkin |

공통 기술 스택은 [공통 README](https://github.com/first-ticket/.github/blob/main/profile/README.md) 참고.

---

## 📁 패키지 구조

```text
com.firstticket.gatewayserver
├── config/
│   └── SecurityConfig.java          # OAuth2 Resource Server 설정, 공개 경로 허용
├── filter/
│   ├── AuthorizationHeaderFilter.java  # JWT 검증 후 보안 헤더 주입, blacklist 확인
│   └── TraceIdFilter.java              # X-Trace-Id 주입 (Zipkin 연동)
└── controller/
    └── FallbackController.java      # 서킷브레이커 fallback 응답 처리
```

---

## 🌐 포트

| 환경  | 포트               |
| ----- | ------------------ |
| local | 8080               |
| prod  | 80 (컨테이너 내부) |

---

## 🚀 로컬 실행

### 사전 조건

다음 인프라가 먼저 실행되어 있어야 한다.

1. **infra 레포의 docker-compose** - Redis, Kafka, PostgreSQL, Keycloak, Zipkin
2. **eureka-server** - 서비스 디스커버리
3. **config-server** - 설정 관리 서버

### 환경변수 설정

```dotenv
CONFIG_SERVER_USERNAME=
CONFIG_SERVER_PASSWORD=
CONFIG_SERVER_URL=http://localhost:8888
EUREKA_SERVER_URL=localhost
SPRING_PROFILES_ACTIVE=dev
```

`.env.example` 파일을 참고하여 `.env` 를 작성한다.

### 외부 설정

설정은 [config-repo](https://github.com/first-ticket/config-repo) 에서 관리한다.

**공통 (`application.yml`)**
- Redis / Kafka 연결 정보
- Zipkin, Prometheus

**gateway-server 전용 (`gateway-server.yml`)**
- 라우팅 규칙: `spring.cloud.gateway.routes`
- 서킷브레이커 파라미터: `resilience4j.*`
- Keycloak JWK Set URI: `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`

**환경별 (`gateway-server-{profile}.yml`)**
- 서비스 주소 (localhost vs ECS 내부 IP), 로그 레벨

---

## 🔍 헬스체크

```bash
curl http://localhost:8080/actuator/health
# → {"status":"UP"}

# 서킷브레이커 상태 확인
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
