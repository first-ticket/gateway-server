package com.firstticket.gatewayserver.filter;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Zipkin 분산 추적을 위해 traceId를 요청/응답 헤더에 주입하는 GlobalFilter
 *
 * currentSpan() null 문제 원인:
 *   Netty epoll 스레드는 Brave의 ThreadLocal이 자동 전파되지 않음
 *   → ContextSnapshotFactory로 Reactor Context에 저장된 Brave 컨텍스트를
 *     현재 스레드의 ThreadLocal로 명시적 복원 후 currentSpan() 호출
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceIdFilter implements GlobalFilter, Ordered {

	private final Tracer tracer;

	private final ContextSnapshotFactory snapshotFactory =
		ContextSnapshotFactory.builder().build();

	private static final String HEADER_TRACE_ID = "X-Trace-Id";

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

		ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
			.headers(h -> h.remove(HEADER_TRACE_ID))
			.build();
		ServerWebExchange sanitizedExchange = exchange.mutate()
			.request(sanitizedRequest)
			.build();

		return Mono.deferContextual(contextView -> {

			ContextSnapshot snapshot = snapshotFactory.captureFrom(contextView);

			try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {

				Span currentSpan = tracer.currentSpan();

				if (currentSpan == null) {
					log.debug("[trace] currentSpan is null - X-Trace-Id 주입 생략");
					return chain.filter(sanitizedExchange);
				}

				String traceId = currentSpan.context().traceId();

				ServerWebExchange mutatedExchange = sanitizedExchange.mutate()
					.request(sanitizedExchange.getRequest().mutate()
						.header(HEADER_TRACE_ID, traceId)
						.build())
					.build();

				mutatedExchange.getResponse().getHeaders().set(HEADER_TRACE_ID, traceId);

				return chain.filter(mutatedExchange);
			}
		});
	}

	@Override
	public int getOrder() {
		return 1;
	}
}