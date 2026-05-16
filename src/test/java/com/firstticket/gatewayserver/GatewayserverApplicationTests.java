package com.firstticket.gatewayserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(properties = {
	"spring.cloud.config.enabled=false",
	"eureka.client.enabled=false",
	"spring.data.redis.host=localhost"
})
class GatewayserverApplicationTests {

	@MockitoBean
	ReactiveJwtDecoder jwtDecoder;

	@Test
	void contextLoads() {
	}
}