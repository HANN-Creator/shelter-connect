package org.shelterconnect.api.asset;

final class AssetException extends RuntimeException {
    final int status;
    final String code;
    final tools.jackson.databind.JsonNode diagnostics;
    AssetException(int status, String code) { this(status,code,null); }
    AssetException(int status,String code,tools.jackson.databind.JsonNode diagnostics) { super(code);this.status=status;this.code=code;this.diagnostics=diagnostics; }
    static AssetException invalid() { return new AssetException(400,"INVALID_ASSET_REQUEST"); }
    static AssetException unavailable() { return new AssetException(503,"ASSET_GENERATION_UNAVAILABLE"); }
}
