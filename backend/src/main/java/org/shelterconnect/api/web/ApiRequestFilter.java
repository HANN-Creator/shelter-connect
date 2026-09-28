package org.shelterconnect.api.web;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.shelterconnect.api.auth.SecurityErrors;
import tools.jackson.databind.json.JsonMapper;

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
	private static final Logger log=LoggerFactory.getLogger(ApiRequestFilter.class);
	private final ApiTrafficPolicy traffic;
	private final JsonMapper json;
	public ApiRequestFilter(ApiTrafficPolicy traffic,JsonMapper json) { this.traffic=traffic; this.json=json; }
	static void error(JsonMapper json,HttpServletRequest request,HttpServletResponse response,int status,String code,String message) throws IOException {
		response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
		json.writeValue(response.getOutputStream(),new SecurityErrors.Error(code,message,requestId(request)));
	}
	static void tooMany(JsonMapper json,HttpServletRequest request,HttpServletResponse response,int seconds) throws IOException {
		response.setHeader("Retry-After",Integer.toString(seconds));
		error(json,request,response,429,"API_RATE_LIMITED","요청이 많아요. Retry-After에 안내된 시간 뒤 다시 시도해 주세요.");
	}

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
		if(ApiInputFilter.api(request)) {
			int wait=traffic.admitGlobal();
			if(wait>0) { tooMany(json,request,response,wait); return; }
		}
		try { chain.doFilter(request, response); }
		catch(ServletException | RuntimeException ex) {
			// Exception messages/causes may contain SQL, private text or credentials.
			log.error("Unhandled request failure; requestId={}, type={}",requestId,ex.getClass().getSimpleName());
			if(!response.isCommitted()) { response.resetBuffer(); error(json,request,response,500,"INTERNAL_ERROR","잠시 뒤 다시 시도해 주세요."); }
		}
	}
}
