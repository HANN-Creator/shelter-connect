package org.shelterconnect.api.auth;

import java.util.List;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
	private static final String[] PUBLIC_READS = {"/actuator/health", "/actuator/health/liveness",
			"/actuator/health/readiness", "/v1/shelters", "/v1/shelters/{shelterId}",
			"/v1/shelters/{shelterId}/dogs", "/v1/dogs/{dogId}"};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, SecurityErrors errors) {
		return http
				// Only explicit Authorization headers authenticate; no cookie, form or server session login.
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(rules -> rules
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
						.requestMatchers(HttpMethod.HEAD, PUBLIC_READS).permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/me").authenticated()
						.requestMatchers(HttpMethod.GET, "/v1/me", "/v1/me/shelters",
								"/v1/shelter-admin/shelters/{shelterId}/access",
								"/v1/shelter-admin/dogs/{dogId}/access").authenticated()
						.anyRequest().denyAll())
				.exceptionHandling(handling -> handling.authenticationEntryPoint(errors).accessDeniedHandler(errors))
				.oauth2ResourceServer(resource -> resource
						.authenticationEntryPoint(errors).accessDeniedHandler(errors)
						.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(token ->
								new JwtAuthenticationToken(token, List.of(), token.getSubject()))))
				.build();
	}
}
