package org.shelterconnect.api.web;

public class FeatureException extends RuntimeException {
    private final int status;
    private final String code;
    public FeatureException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static FeatureException invalid() { return new FeatureException(400, "INVALID_REQUEST", "입력 항목을 확인해 주세요."); }
    public static FeatureException missing() { return new FeatureException(404, "NOT_FOUND", "정보를 찾을 수 없어요."); }
    public static FeatureException conflict() { return new FeatureException(409, "VERSION_CONFLICT", "정보가 변경됐어요. 다시 조회해 주세요."); }
}
