package org.shelterconnect.api.asset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AssetProperties {
    final boolean enabled, autoImport;
    final String apiKey, storageSecret, photoBucket, assetBucket;
    public AssetProperties(boolean enabled,boolean autoImport,String apiKey,String storageSecret,String photoBucket,String assetBucket) {
        this(enabled,autoImport,apiKey,storageSecret,photoBucket,assetBucket,"supabase",false);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AssetProperties(@Value("${app.assets.enabled:false}") boolean enabled,
            @Value("${app.assets.auto-import:false}") boolean autoImport,
            @Value("${app.assets.api-key:}") String apiKey,
            @Value("${app.assets.storage-secret:}") String storageSecret,
            @Value("${app.assets.photo-bucket:dog-photos}") String photoBucket,
            @Value("${app.assets.bucket:dog-assets}") String assetBucket,
            @Value("${app.storage.backend:supabase}") String storageBackend,
            @Value("${app.assets.worker-enabled:false}") boolean workerEnabled) {
        boolean local="local".equals(storageBackend);
        if (!safeBucket(photoBucket) || !safeBucket(assetBucket)
                || photoBucket.equals(assetBucket) || (autoImport && !enabled)
                || (enabled && ((!local || workerEnabled || autoImport) && apiKey.isBlank()
                    || apiKey.contains("\n") || apiKey.contains("\r")
                    || !local && !storageSecret.matches("sb_secret_[A-Za-z0-9_-]+"))))
            throw new IllegalArgumentException("Invalid asset generation settings");
        this.enabled=enabled; this.autoImport=autoImport; this.apiKey=apiKey; this.storageSecret=storageSecret;
        this.photoBucket=photoBucket; this.assetBucket=assetBucket;
    }
    void requireEnabled() { if (!enabled) throw AssetException.unavailable(); }
    static boolean safeBucket(String s) { return s.matches("[a-z0-9][a-z0-9_-]{0,62}"); }
}
