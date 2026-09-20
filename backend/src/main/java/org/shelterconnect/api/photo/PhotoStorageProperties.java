package org.shelterconnect.api.photo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PhotoStorageProperties {
	public static final int URL_SECONDS = 60;
	private final boolean enabled;
	private final String secretKey;
	private final String bucket;
	private final int timeoutSeconds;
	public PhotoStorageProperties(@Value("${app.photos.enabled:false}") boolean enabled,
			@Value("${app.photos.secret-key:}") String secretKey,
			@Value("${app.photos.bucket:dog-photos}") String bucket,
			@Value("${app.photos.timeout-seconds:5}") int timeoutSeconds) {
		if (enabled && (secretKey == null || !secretKey.matches("sb_secret_[A-Za-z0-9_-]+")))
			throw new IllegalArgumentException("SUPABASE_SECRET_KEY must be a server secret key when PHOTO_STORAGE_ENABLED=true");
		if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9_-]{0,62}")) throw new IllegalArgumentException("PHOTO_STORAGE_BUCKET is invalid");
		if (timeoutSeconds < 1 || timeoutSeconds > 10) throw new IllegalArgumentException("PHOTO_STORAGE_TIMEOUT_SECONDS must be 1..10");
		this.enabled = enabled; this.secretKey = secretKey; this.bucket = bucket; this.timeoutSeconds = timeoutSeconds;
	}
	public boolean enabled() { return enabled; }
	String secretKey() { return secretKey; }
	String bucket() { return bucket; }
	int timeoutSeconds() { return timeoutSeconds; }
}
