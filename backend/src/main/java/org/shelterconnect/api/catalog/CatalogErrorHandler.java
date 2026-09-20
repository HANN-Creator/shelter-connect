package org.shelterconnect.api.catalog;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PublicCatalogController.class)
public class CatalogErrorHandler {
	private static final Logger log = LoggerFactory.getLogger(CatalogErrorHandler.class);

	public record ApiError(String code, String message, String requestId) {}

	@ExceptionHandler(CatalogException.class)
	ResponseEntity<ApiError> catalog(CatalogException exception, HttpServletRequest request) {
		return ResponseEntity.status(exception.status()).body(new ApiError(
				exception.code(), exception.getMessage(), requestId(request)));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
		log.error("Catalog request failed; requestId={}", requestId(request), exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
				"INTERNAL_ERROR", "정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.", requestId(request)));
	}

	private String requestId(HttpServletRequest request) {
		return (String) request.getAttribute(CatalogRequestFilter.REQUEST_ID);
	}
}
