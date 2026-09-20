package org.shelterconnect.api.photo;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.auth.AccountAccessException;
import org.shelterconnect.api.auth.SecurityErrors;
import org.shelterconnect.api.web.ApiRequestFilter;

@RestControllerAdvice(assignableTypes = PhotoController.class)
public class PhotoErrorHandler {
	private static final Logger log = LoggerFactory.getLogger(PhotoErrorHandler.class);
	@ExceptionHandler(PhotoException.class)
	ResponseEntity<SecurityErrors.Error> photo(PhotoException ex, HttpServletRequest request) {
		return error(ex.status(), ex.code(), ex.getMessage(), request);
	}
	@ExceptionHandler(AccountAccessException.class)
	ResponseEntity<SecurityErrors.Error> account(AccountAccessException ex, HttpServletRequest request) {
		return error(ex.status(), ex.code(), ex.getMessage(), request);
	}
	@ExceptionHandler({ConcurrencyFailureException.class, QueryTimeoutException.class})
	ResponseEntity<SecurityErrors.Error> conflict(HttpServletRequest request) {
		return error(409, "PHOTO_SET_CHANGED", "사진 정보가 바뀌었어요. 목록을 다시 조회해 주세요.", request);
	}
	@ExceptionHandler(Exception.class)
	ResponseEntity<SecurityErrors.Error> unexpected(Exception ex, HttpServletRequest request) {
		// Neither provider bodies, signed tokens nor secret headers belong in logs.
		log.error("Photo request failed; requestId={}, type={}", ApiRequestFilter.requestId(request), ex.getClass().getSimpleName());
		return error(500, "INTERNAL_ERROR", "사진을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.", request);
	}
	private ResponseEntity<SecurityErrors.Error> error(int status, String code, String message, HttpServletRequest request) {
		return ResponseEntity.status(status).body(new SecurityErrors.Error(code, message, ApiRequestFilter.requestId(request)));
	}
}
