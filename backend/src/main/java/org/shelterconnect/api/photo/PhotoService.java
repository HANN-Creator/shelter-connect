package org.shelterconnect.api.photo;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.shelterconnect.api.catalog.CatalogResponses.Page;
import org.shelterconnect.api.photo.PhotoTypes.Photo;

@Service
public class PhotoService {
	private final PhotoStore store;
	private final PhotoStorage storage;
	public PhotoService(PhotoStore store, PhotoStorage storage) { this.store = store; this.storage = storage; }
	// The storage request runs between short transactions, without holding a DB connection.
	public Page<Photo> list(UUID subject, String dogId, String cursor, String limit) {
		var snapshot = store.load(subject, PhotoQuery.id(dogId), cursor, PhotoQuery.limit(limit));
		if (snapshot.photos().isEmpty()) return new Page<>(java.util.List.of(), null);
		return store.finish(snapshot, storage.sign(snapshot.photos()));
	}
}
