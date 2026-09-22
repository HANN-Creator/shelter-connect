package org.shelterconnect.api.asset;

final class AssetException extends RuntimeException {
    final int status;
    final String code;
    AssetException(int status, String code) { super(code); this.status=status; this.code=code; }
    static AssetException invalid() { return new AssetException(400,"INVALID_ASSET_REQUEST"); }
    static AssetException unavailable() { return new AssetException(503,"ASSET_GENERATION_UNAVAILABLE"); }
}
