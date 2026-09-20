package org.shelterconnect.api.photo;

public class PhotoException extends RuntimeException {
	private final int status;
	private final String code;
	public PhotoException(int status, String code, String message) {
		super(message); this.status = status; this.code = code;
	}
	public int status() { return status; }
	public String code() { return code; }
	static PhotoException invalid() { return new PhotoException(400, "INVALID_REQUEST", "사진 조회 조건을 확인해 주세요."); }
	static PhotoException cursor() { return new PhotoException(400, "INVALID_CURSOR", "사진 목록을 처음부터 다시 조회해 주세요."); }
	static PhotoException unavailable() { return new PhotoException(502, "PHOTO_STORAGE_UNAVAILABLE", "사진을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요."); }
}
