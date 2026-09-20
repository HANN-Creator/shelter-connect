package org.shelterconnect.api.catalog;

import org.springframework.http.HttpStatus;

final class CatalogException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	private CatalogException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	static CatalogException invalid(String message) {
		return new CatalogException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
	}

	static CatalogException invalidCursor() {
		return new CatalogException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "목록의 첫 페이지부터 다시 조회해 주세요.");
	}

	static CatalogException notFound() {
		return new CatalogException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 정보를 찾을 수 없어요.");
	}

	HttpStatus status() { return status; }
	String code() { return code; }
}
