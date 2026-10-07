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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.core.env.Environment;
import org.shelterconnect.api.web.ApiTrafficPolicy;
import org.shelterconnect.api.web.ApiInputFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
	private static final String[] PUBLIC_READS = {"/v1/registration-policy", "/v1/shelter-discovery", "/actuator/health", "/actuator/health/liveness",
			"/actuator/health/readiness", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/v1/shelters", "/v1/shelters/{shelterId}",
			"/v1/shelters/{shelterId}/dogs", "/v1/dogs/{dogId}", "/v1/dogs/{dogId}/behavior", "/v1/dogs/{dogId}/assets"};

	@Bean
	ApiTrafficPolicy apiTrafficPolicy(Environment environment) { return new ApiTrafficPolicy(environment); }

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, SecurityErrors errors, ApiTrafficPolicy traffic, JsonMapper json) {
		return http
				.addFilterAfter(new ApiInputFilter(traffic,json),AuthorizationFilter.class)
				// Only explicit Authorization headers authenticate; no cookie, form or server session login.
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(rules -> rules
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
						.requestMatchers(HttpMethod.HEAD, PUBLIC_READS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/inquiry-rooms", "/v1/inquiry-rooms/{roomId}", "/v1/inquiry-rooms/{roomId}/messages").authenticated()
                        .requestMatchers(HttpMethod.POST, "/v1/community/posts/{postId}/inquiries", "/v1/inquiry-rooms/{roomId}/messages").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/v1/inquiry-rooms/{roomId}/read").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v1/me/community-region", "/v1/me/community-posts", "/v1/community/posts", "/v1/community/posts/{postId}", "/v1/community/posts/{postId}/comments", "/v1/community/media/{mediaId}", "/v1/operations/community-reports").authenticated()
                        .requestMatchers(HttpMethod.POST, "/v1/community/posts", "/v1/community/posts/{postId}/publish", "/v1/community/posts/{postId}/comments", "/v1/community/posts/{postId}/reports", "/v1/community/media").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/v1/community/posts/{postId}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/v1/me/community-region", "/v1/community/posts/{postId}/status", "/v1/operations/community-reports/{reportId}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/v1/community/posts/{postId}", "/v1/community/posts/{postId}/comments/{commentId}").authenticated()
						.requestMatchers(HttpMethod.GET, "/v1/me/preferences", "/v1/me/consents", "/v1/me/saved-dogs", "/v1/me/saved-dogs/{dogId}", "/v1/me/dog-conversations").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/v1/me/profile").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/v1/me/preferences", "/v1/me/consents", "/v1/me/saved-dogs/{dogId}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/v1/me/saved-dogs/{dogId}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/v1/me").authenticated()
						.requestMatchers(HttpMethod.GET, "/v1/me/adoption-notes", "/v1/me/adoption-notes/{dogId}").authenticated()
						.requestMatchers(HttpMethod.PUT, "/v1/me/adoption-notes/{dogId}").authenticated()
						.requestMatchers(HttpMethod.GET, "/v1/shelter-admin/dogs/{dogId}/behavior").authenticated()
						.requestMatchers(HttpMethod.PUT, "/v1/shelter-admin/dogs/{dogId}/behavior").authenticated()
						.requestMatchers(HttpMethod.POST, "/v1/shelter-admin/dogs/{dogId}/behavior/confirmation").authenticated()
						.requestMatchers(HttpMethod.POST, "/v1/shelter-admin/dogs/{dogId}/behavior/suggestions", "/v1/shelter-admin/dogs/{dogId}/photos").authenticated()
						.requestMatchers(HttpMethod.GET, "/v1/shelter-admin/dogs/{dogId}/behavior/suggestions/{suggestionId}", "/v1/shelter-admin/dogs/{dogId}/photos").authenticated()
						.requestMatchers(HttpMethod.GET, "/v1/dogs/{dogId}/photos").authenticated()
						.requestMatchers(HttpMethod.POST, "/v1/dogs/{dogId}/chat-sessions",
								"/v1/chat-sessions/{sessionId}/messages",
								"/v1/chat-sessions/{sessionId}/messages/{messageId}/reply").authenticated()
						.requestMatchers(HttpMethod.GET, "/v1/me/chat-sessions", "/v1/chat-sessions/{sessionId}",
								"/v1/chat-sessions/{sessionId}/messages").authenticated()
						.requestMatchers(HttpMethod.POST, "/v1/shelter-admin/dogs",
								"/v1/shelter-admin/dogs/{dogId}/observations").authenticated()
						.requestMatchers(HttpMethod.PATCH, "/v1/shelter-admin/dogs/{dogId}",
								"/v1/shelter-admin/dogs/{dogId}/observations/{observationId}").authenticated()
						.requestMatchers(HttpMethod.GET, "/v1/shelter-admin/shelters/{shelterId}/dogs",
								"/v1/shelter-admin/dogs/{dogId}",
								"/v1/shelter-admin/dogs/{dogId}/observations").authenticated()
						.requestMatchers(HttpMethod.POST, "/v1/shelter-admin/dogs/{dogId}/styled-assets", "/v1/shelter-admin/dogs/{dogId}/styled-seed-examples", "/v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/seed-review", "/v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/review", "/v1/operations/styled-asset-jobs/{jobId}/recover", "/v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/repair", "/v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/quality-recheck", "/v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/repair-continuation", "/v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/learning-repair").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}", "/v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/preview").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v1/me", "/v1/me/shelters",
								"/v1/shelter-admin/shelters/{shelterId}/access",
								"/v1/shelter-admin/dogs/{dogId}/access").authenticated()
						.requestMatchers(HttpMethod.POST, "/v1/operations/asset-permissions", "/v1/operations/asset-imports",
                                "/v1/operations/asset-jobs/{jobId}/reconcile", "/v1/operations/asset-jobs/{jobId}/retry",
                                "/v1/shelter-admin/dogs/{dogId}/assets", "/v1/shelter-admin/dogs/{dogId}/assets/{jobId}/review",
                                "/v1/shelter-admin/dogs/{dogId}/assets/{jobId}/rig/confirm").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/v1/operations/asset-permissions/{permissionId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v1/shelter-admin/dogs/{dogId}/assets/{jobId}",
                                "/v1/shelter-admin/dogs/{dogId}/assets/{jobId}/preview", "/v1/shelter-admin/dogs/{dogId}/assets/{jobId}/rig").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v1/operations/styled-quality-lessons", "/v1/operations/styled-quality-lessons/{id}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/v1/operations/styled-quality-lessons/{id}/disable").authenticated()
                        .anyRequest().denyAll())
				.exceptionHandling(handling -> handling.authenticationEntryPoint(errors).accessDeniedHandler(errors))
				.oauth2ResourceServer(resource -> resource
						.authenticationEntryPoint(errors).accessDeniedHandler(errors)
						.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(token ->
								new JwtAuthenticationToken(token, List.of(), token.getSubject()))))
				.build();
	}
}
