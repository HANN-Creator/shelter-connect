package org.shelterconnect.api.adoption;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.auth.AccountAccessException;
import org.shelterconnect.api.auth.SecurityErrors;
import org.shelterconnect.api.web.ApiRequestFilter;

@RestControllerAdvice(assignableTypes = AdoptionNoteController.class)
public class AdoptionNoteErrorHandler {
	private static final Logger log = LoggerFactory.getLogger(AdoptionNoteErrorHandler.class);
	@ExceptionHandler(AdoptionNoteException.class)
	ResponseEntity<SecurityErrors.Error> invalid(AdoptionNoteException ex, HttpServletRequest request) {
		return error(ex.status, ex.code, ex.getMessage(), request);
	}
	@ExceptionHandler(AccountAccessException.class)
	ResponseEntity<SecurityErrors.Error> denied(AccountAccessException ex, HttpServletRequest request) {
		return error(ex.status(), ex.code(), ex.getMessage(), request);
	}
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<SecurityErrors.Error> malformed(HttpServletRequest request) {
		return error(400, "INVALID_REQUEST", "요청 본문을 올바른 JSON으로 보내 주세요.", request);
	}
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<SecurityErrors.Error> media(HttpServletRequest request) {
		return error(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type은 application/json을 사용해 주세요.", request);
	}
	@ExceptionHandler({ConcurrencyFailureException.class, QueryTimeoutException.class})
	ResponseEntity<SecurityErrors.Error> conflict(HttpServletRequest request) {
		return error(409, "WRITE_CONFLICT", "다른 작업이 진행 중이에요. 다시 조회한 뒤 시도해 주세요.", request);
	}
	@ExceptionHandler(Exception.class)
	ResponseEntity<SecurityErrors.Error> unexpected(Exception ex, HttpServletRequest request) {
		// Database exception text can contain the author's private note. Keep it out of logs.
		log.error("Adoption note request failed; requestId={}; type={}", ApiRequestFilter.requestId(request), ex.getClass().getSimpleName());
		return error(500, "INTERNAL_ERROR", "메모를 저장하거나 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.", request);
	}
	private ResponseEntity<SecurityErrors.Error> error(int status, String code, String message, HttpServletRequest request) {
		return ResponseEntity.status(status).body(new SecurityErrors.Error(code, message, ApiRequestFilter.requestId(request)));
	}
}
