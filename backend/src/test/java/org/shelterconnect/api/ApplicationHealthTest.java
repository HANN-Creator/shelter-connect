package org.shelterconnect.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationHealthTest {

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private JsonMapper json;

	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5)).build();

	@ParameterizedTest
	@ValueSource(strings = {"", "/liveness", "/readiness"})
	void healthReportsOnlyPublicStatus(String suffix) throws Exception {
		var response = get("/actuator/health" + suffix);
		assertThat(response.statusCode()).isEqualTo(200);
		var expected = suffix.isEmpty()
				? Map.of("status", "UP", "groups", List.of("liveness", "readiness"))
				: Map.of("status", "UP");
		assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(expected));
	}

	@ParameterizedTest
	@ValueSource(strings = {"env", "configprops", "beans"})
	void internalManagementEndpointsAreNotExposed(String endpoint) throws Exception {
		assertThat(get("/actuator/" + endpoint).statusCode()).isEqualTo(401);
	}

	@Test
	void applicationCanQueryItsDatabase() {
		assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
	}

	@Test
	void refusingTrafficFailsReadinessWithoutFailingLiveness() throws Exception {
		AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
		try {
			assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
			assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
		}
		finally {
			AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
		}
	}

	private HttpResponse<String> get(String path) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(10)).GET().build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
