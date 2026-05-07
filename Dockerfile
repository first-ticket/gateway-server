# gateway-server/Dockerfile
# Stage 1: 빌드 스테이지
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Gradle Wrapper 및 의존성 정의 파일을 먼저 복사
# -> src보다 먼저 복사하면 의존성이 변경되지 않는 한 Docker 레이어 캐시 재사용 가능
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

# 소스코드 복사
COPY src src

# bootJar 실행 - GitHub Packages 없이 mavenCentral만 사용하므로 인증 불필요
# -x test: 이미지 빌드 단계에서 테스트 생략 (CI에서 별도 실행 전제)
RUN ./gradlew clean bootJar --no-daemon -x test

# Stage 2: 런타임 스테이지
# JDK 대신 JRE만 포함 → 이미지 크기 대폭 감소
FROM eclipse-temurin:21-jre-jammy

# curl 설치: healthcheck에서 actuator/health 호출에 사용
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 빌드 스테이지에서 생성된 JAR만 복사 (소스 코드, JDK는 포함되지 않음)
COPY --from=builder /app/build/libs/app.jar app.jar

# 문서화 목적의 포트 선언 (실제 포트 오픈은 docker-compose에서 담당)
EXPOSE 8080

# JVM 실행
ENTRYPOINT ["java", "-jar", "app.jar"]