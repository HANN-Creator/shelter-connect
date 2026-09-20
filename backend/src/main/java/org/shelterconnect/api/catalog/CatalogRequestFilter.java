package org.shelterconnect.api.catalog;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CatalogRequestFilter extends OncePerRequestFilter {
	static final String REQUEST_ID = CatalogRequestFilter.class.getName() + ".requestId";

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(request.getContextPath() + "/v1/");
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
