package org.shelterconnect.api.auth;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.shelterconnect.api.web.ApiRequestFilter;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
	public record Error(String code, String message, String requestId) {}
	private final JsonMapper json;

	public SecurityErrors(JsonMapper json) { this.json = json; }

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException {
		response.setHeader("WWW-Authenticate", "Bearer");
		write(request, response, 401, "UNAUTHENTICATED", "로그인이 필요하거나 로그인 정보가 만료됐어요.");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		write(request, response, 403, "FORBIDDEN", "이 작업을 수행할 권한이 없어요.");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		json.writeValue(response.getOutputStream(), new Error(code, message, ApiRequestFilter.requestId(request)));
	}
}
