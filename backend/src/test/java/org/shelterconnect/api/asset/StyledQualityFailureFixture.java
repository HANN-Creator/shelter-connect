package org.shelterconnect.api.asset;

/** Preserve the production exception boundary in cross-package worker/DB tests. */
public final class StyledQualityFailureFixture {
    private StyledQualityFailureFixture() {}
    public static RuntimeException failure(String code) { return new AssetException(503,code); }
}
