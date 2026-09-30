package org.shelterconnect.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.shelterconnect.api.auth.AccountAccessException;
import org.shelterconnect.api.auth.SecurityErrors;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = {"org.shelterconnect.api.personal", "org.shelterconnect.api.community", "org.shelterconnect.api.inquiry"})
public class FeatureErrorHandler {
    @ExceptionHandler(FeatureException.class)
    ResponseEntity<SecurityErrors.Error> feature(FeatureException ex, HttpServletRequest request) {
        return error(ex.status(), ex.code(), ex.getMessage(), request);
    }
    @ExceptionHandler(AccountAccessException.class)
    ResponseEntity<SecurityErrors.Error> account(AccountAccessException ex, HttpServletRequest request) {
        return error(ex.status(), ex.code(), ex.getMessage(), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<SecurityErrors.Error> invalid(Exception ex, HttpServletRequest request) {
        return feature(FeatureException.invalid(), request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<SecurityErrors.Error> unexpected(Exception ex, HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Feature request failed; requestId={}, type={}", ApiRequestFilter.requestId(request), ex.getClass().getSimpleName());
        return error(500, "INTERNAL_ERROR", "잠시 뒤 다시 시도해 주세요.", request);
    }
    private ResponseEntity<SecurityErrors.Error> error(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new SecurityErrors.Error(code, message, ApiRequestFilter.requestId(request)));
    }
}
