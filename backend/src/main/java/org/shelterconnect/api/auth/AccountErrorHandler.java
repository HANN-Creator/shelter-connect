package org.shelterconnect.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.shelterconnect.api.web.ApiRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AccountController.class)
public class AccountErrorHandler {
	private static final Logger log = LoggerFactory.getLogger(AccountErrorHandler.class);

	@ExceptionHandler(AccountAccessException.class)
	ResponseEntity<SecurityErrors.Error> account(AccountAccessException exception, HttpServletRequest request) {
		return ResponseEntity.status(exception.status()).body(new SecurityErrors.Error(
				exception.code(), exception.getMessage(), ApiRequestFilter.requestId(request)));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<SecurityErrors.Error> unexpected(Exception exception, HttpServletRequest request) {
		log.error("Account request failed; requestId={}", ApiRequestFilter.requestId(request), exception);
		return ResponseEntity.internalServerError().body(new SecurityErrors.Error(
				"INTERNAL_ERROR", "정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.", ApiRequestFilter.requestId(request)));
	}
}
