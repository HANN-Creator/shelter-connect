package org.shelterconnect.api.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRequestFilter extends OncePerRequestFilter {
	private static final String REQUEST_ID = ApiRequestFilter.class.getName() + ".requestId";

	public static String requestId(HttpServletRequest request) {
		return (String) request.getAttribute(REQUEST_ID);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		request.setAttribute(REQUEST_ID, requestId);
		response.setHeader("X-Request-ID", requestId);
		// Re-check current publication/adoption status on every request.
		response.setHeader("Cache-Control", "no-store");
		chain.doFilter(request, response);
	}
}
