package com.firstticket.gatewayserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

// Eureka Client 활성화 - Eureka Server에 'gateway-server'로 등록
// lb://user-service 같은 LoadBalancer URI 사용을 위해 필수
@EnableCaching
@EnableDiscoveryClient
@SpringBootApplication
public class GatewayserverApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayserverApplication.class, args);
	}
}