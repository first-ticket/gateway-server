# Gateway Server

### 작성일 : 2026-05-07
### 작성자 : 박동진 (straycat405@gmail.com)

First-Ticket MSA의 단일 진입점(API Gateway) 서비스입니다.
모든 외부 요청은 이 서버를 통해 각 마이크로서비스로 라우팅됩니다.

## 역할

### 1. JWT 검증
Keycloak JWK Set URI를 사용해 Access Token 서명을 검증합니다.
검증은 Spring Security OAuth2 Resource Server가 담당하며,
서비스 기동 시 Keycloak에 즉시 연결하지 않고 **첫 JWT 검증 요청 시 Lazy하게** 공개키를 가져옵니다.

```
클라이언트 → Gateway (JWT 서명 검증) → 다운스트림 서비스
```

### 2. 보안 헤더 주입 (AuthorizationHeaderFilter)
JWT 검증 후 클레임에서 사용자 정보를 추출해 다운스트림 서비스 헤더에 주입합니다.
다운스트림 서비스는 자체 JWT 재검증 없이 이 헤더를 신뢰합니다.

| 헤더 | 값 | 출처 |
|---|---|---|
| `X-User-Id` | Keycloak 사용자 UUID | JWT `sub` 클레임 |
| `X-User-Role` | 앱 역할 (CUSTOMER, HOST, ADMIN) | JWT `realm_access.roles` 클레임 |

> **헤더 스푸핑 방지**: 모든 요청에서 클라이언트가 보낸 `X-User-Id`, `X-User-Role` 헤더를 먼저 제거한 뒤 재주입합니다.

### 3. 라우팅

| 서비스 | 경로 |
|---|---|
| user-service | `/api/v1/auth/**` `/api/v1/users/**` `/api/v1/admin/users/**` `/api/v1/admin/host-requests/**` |
| program-service | `/api/v1/programs/**` |
| venue-service | `/api/v1/venues/**` |
| booking-service | `/api/v1/bookings/**` `/api/v1/tickets/**` `/api/v1/admin/bookings/**` `/api/v1/seats/**` |
| payment-service | `/api/v1/payments/**` `/api/v1/admin/payments/**` |
| queue-service | `/api/v1/queues/**` `/api/v1/admin/queues/**` |

### 4. 서킷브레이커 (Resilience4j)
다운스트림 서비스 장애 시 즉시 fallback 응답을 반환해 연쇄 장애를 방지합니다.
자세한 파라미터는 ([config-repo/gateway-server.yml](https://github.com/first-ticket/config-repo/blob/main/gateway-server/gateway-server.yml)) 참고.

### 5. 공개 경로 (인증 불필요)

| 경로 | 설명 |
|---|---|
| `POST /api/v1/auth/signup` | 회원가입 |
| `POST /api/v1/auth/login` | 로그인 |
| `POST /api/v1/auth/token/refresh` | Access Token 재발급 |
| `GET /actuator/health` | ALB 헬스체크 |
| `GET /actuator/info` | 서비스 정보 |

---

## 기술 스택

| 항목 | 내용 |
|---|---|
| Framework | Spring Boot 3.5.13 / Spring Cloud 2025.0.2 |
| Gateway | Spring Cloud Gateway (WebFlux / Netty 기반) |
| 인증 | Spring Security OAuth2 Resource Server + Keycloak |
| 서비스 디스커버리 | Spring Cloud Netflix Eureka Client |
| 설정 관리 | Spring Cloud Config Client |
| 서킷브레이커 | Resilience4j (Reactive) |
| 모니터링 | Spring Boot Actuator + Micrometer + Prometheus |
| 런타임 포트 | 8080 |

---

## 로컬 테스트 환경 설정

### 사전 조건

아래 서비스가 먼저 실행 중이어야 합니다.

```
1. 인프라 (PostgreSQL, Redis, Kafka, Keycloak...)
   cd infra && docker compose -f docker/docker-compose.yml --env-file .env up -d

2. Eureka Server  → http://localhost:8761
3. Config Server  → http://localhost:8888
```

### 환경변수 설정 (`.env`)

Run Configuration에서 Environment Variables를 주입합니다.

```dotenv
# Config Server 인증
CONFIG_SERVER_USERNAME=admin
CONFIG_SERVER_PASSWORD=admin1234

# Config Server 주소
CONFIG_SERVER_URL=http://localhost:8888

# Eureka Server URL
EUREKA_SERVER_URL=localhost

# 실행 프로파일 (dev | prod)
SPRING_PROFILES_ACTIVE=dev
```

### 실행

IntelliJ에서 `GatewayserverApplication`을 실행하거나:

```bash
./gradlew bootRun
```

### 실행 확인

```bash
# 헬스체크
curl http://localhost:8080/actuator/health

# 서킷브레이커 상태 확인
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

---

## 설정 구조

설정은 Config Server를 통해 `config-repo/gateway-server/`에서 로드됩니다.

```
config-repo/gateway-server/
├── gateway-server.yml         # 공통 기본값 (라우팅, 서킷브레이커, Actuator)
├── gateway-server-dev.yml     # 로컬 개발 오버라이드 (localhost 주소, DEBUG 로그)
└── gateway-server-prod.yml    # 운영 오버라이드 (환경변수 기반 주소, Prometheus)
```

로컬 `application.yaml`은 Config Server 접속에 필요한 최소 정보만 포함합니다.

```
로컬 application.yaml 로드
  └─ .env 로드 (CONFIG_SERVER_USERNAME 등)
       └─ Config Server 접속 → gateway-server.yml + gateway-server-dev.yml 수신
```

---

## 서킷브레이커 설정

`config-repo/gateway-server/gateway-server.yml` 기준

### 공통 파라미터 (전 서비스 동일)

| 파라미터 | 값 | 설명 |
|---|---|---|
| `sliding-window-size` | 10 | 실패율 계산 기준 최근 호출 수 |
| `failure-rate-threshold` | 50% | OPEN 전환 실패율 임계값 (10번 중 5번 실패 시) |
| `minimum-number-of-calls` | 5 | OPEN 전환 전 최소 호출 수 (미달 시 실패율 계산 안 함) |
| `wait-duration-in-open-state` | 10s | OPEN 유지 시간 → 경과 후 HALF_OPEN 전환 |
| `permitted-number-of-calls-in-half-open-state` | 5 | HALF_OPEN 상태에서 허용할 시험 요청 수 |

### TimeLimiter (응답 타임아웃)

| 서비스 | timeout | 비고 |
|---|---|---|
| user-service | 3s | 기본값 |
| program-service | 3s | 기본값 |
| venue-service | 3s | 기본값 |
| booking-service | 3s | 기본값 |
| payment-service | **10s** | PG사 연동 처리 시간 고려 |
| queue-service | 3s | 기본값 |

### 상태 흐름

```
CLOSED (정상) → [실패율 ≥ 50%] → OPEN (차단, fallback 반환)
                                        ↓ 10초 경과
                              HALF_OPEN (시험 요청 5개)
                                ↙           ↘
                           성공            실패
                         CLOSED           OPEN
```

### Fallback 응답 예시

서킷 OPEN 또는 타임아웃 발생 시 `/fallback/{service-name}`으로 내부 전달됩니다.

```json
{
  "success": false,
  "code": "SERVICE_UNAVAILABLE",
  "message": "사용자 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요.",
  "timestamp": "2026-05-07T13:00:00",
  "data": { "service": "user-service" }
}
```
