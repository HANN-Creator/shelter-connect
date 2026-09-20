package org.shelterconnect.api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupabaseProperties.class)
public class SupabaseJwtConfiguration {
	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	@Bean
	JwtDecoder jwtDecoder(SupabaseProperties properties) {
		var requests = new SimpleClientHttpRequestFactory();
		requests.setConnectTimeout(Duration.ofSeconds(3));
		requests.setReadTimeout(Duration.ofSeconds(3));
		return decoder(properties, new RestTemplate(requests));
	}

	public static NimbusJwtDecoder decoder(SupabaseProperties properties, RestOperations http) {
		var decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUrl())
				.jwsAlgorithm(SignatureAlgorithm.ES256).restOperations(http).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(properties.issuer()), SupabaseJwtConfiguration::validateUser));
		return decoder;
	}

	private static OAuth2TokenValidatorResult validateUser(Jwt jwt) {
		boolean valid = jwt.getSubject() != null && UUID_FORMAT.matcher(jwt.getSubject()).matches()
				&& jwt.getAudience() != null && jwt.getAudience().contains("authenticated")
				&& "authenticated".equals(jwt.getClaims().get("role"))
				&& Boolean.FALSE.equals(jwt.getClaims().get("is_anonymous"))
				&& jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
				&& jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
				&& !jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(60));
		return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
				new OAuth2Error("invalid_token", "A valid Supabase user access token is required", null));
	}
}
