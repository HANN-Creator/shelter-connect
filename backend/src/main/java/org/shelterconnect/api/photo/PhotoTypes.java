package org.shelterconnect.api.photo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PhotoTypes {
	private PhotoTypes() {}
	public record Photo(UUID id, UUID dogId, int sortOrder, String caption, String url, Instant expiresAt) {}
	public record Stored(UUID id, UUID dogId, String bucket, String key, int sortOrder, String caption, Instant updatedAt) {}
	public record Signed(String url, Instant expiresAt) {}
	public record Snapshot(UUID subject, UUID userId, UUID dogId, List<Stored> photos, String nextCursor) {}
}
