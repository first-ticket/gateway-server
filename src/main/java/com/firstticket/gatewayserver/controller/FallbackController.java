package com.firstticket.gatewayserver.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 서킷브레이커 OPEN or Timeout 발생 시 호출되는 Fallback 컨트롤러
 * Gateway는 WebFlux 기반이므로 Mono 반환이 필수적
 * 각 서비스별 엔드포인트를 분리하여 원인 서비스를 명확히 식별 가능하게 했습니다.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

	/**
	 * 공통 fallback 응답 구조 생성
	 * 팀 공통 ApiResponse 형식과 동일한 구조를 수동으로 구성
	 * (Gateway는 common 모듈 의존 x - 순환 의존 방지)
	 *
	 * @param serviceName 장애 발생 서비스명 (로그·응답 식별용)
	 * @param message     사용자에게 노출할 메시지
	 */
	private Mono<Map<String, Object>> fallbackResponse(String serviceName, String message) {
		return Mono.just(Map.of(
			"success",   false,
			"code",      "SERVICE_UNAVAILABLE",
			"message",   message,
			"timestamp", LocalDateTime.now().toString(),
			"data",      Map.of("service", serviceName)
		));
	}

	@RequestMapping("/user-service")
	public Mono<Map<String, Object>> userServiceFallback(ServerWebExchange exchange) {
		// 상태코드를 503으로 직접 설정
		exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
		return fallbackResponse("user-service", "사용자 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요.");
	}

	@RequestMapping("/booking-service")
	public Mono<Map<String, Object>> bookingServiceFallback(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
		return fallbackResponse("booking-service", "예매 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요.");
	}

	@RequestMapping("/payment-service")
	public Mono<Map<String, Object>> paymentServiceFallback(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
		return fallbackResponse("payment-service", "결제 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요.");
	}

	@RequestMapping("/program-service")
	public Mono<Map<String, Object>> programServiceFallback(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
		return fallbackResponse("program-service", "공연 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요.");
	}

	@RequestMapping("/venue-service")
	public Mono<Map<String, Object>> venueServiceFallback(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
		return fallbackResponse("venue-service", "공연장 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요.");
	}

	@RequestMapping("/queue-service")
	public Mono<Map<String, Object>> queueServiceFallback(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
		return fallbackResponse("queue-service", "대기열 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요.");
	}
}