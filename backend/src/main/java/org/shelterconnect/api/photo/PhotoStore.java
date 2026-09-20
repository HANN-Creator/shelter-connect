package org.shelterconnect.api.photo;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.AccountService;
import org.shelterconnect.api.catalog.CatalogResponses.Page;
import org.shelterconnect.api.chat.ChatRepository;
import static org.shelterconnect.api.photo.PhotoTypes.*;

@Service
@Transactional(timeout = 10)
public class PhotoStore {
	private final AccountService accounts;
	private final ChatRepository chats;
	private final PhotoRepository photos;
	public PhotoStore(AccountService accounts, ChatRepository chats, PhotoRepository photos) {
		this.accounts = accounts; this.chats = chats; this.photos = photos;
	}
	public Snapshot load(UUID subject, UUID dog, String cursor, int size) {
		UUID user = authorize(subject, dog);
		var after = PhotoQuery.after(cursor, user, dog);
		var rows = photos.photos(dog, after, size + 1);
		var selected = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
		return new Snapshot(subject, user, dog, selected, rows.size() > size ? PhotoQuery.cursor(user, dog, selected.getLast()) : null);
	}
	public Page<Photo> finish(Snapshot snapshot, Map<UUID, Signed> signed) {
		UUID user = authorize(snapshot.subject(), snapshot.dogId());
		if (!user.equals(snapshot.userId())) throw new PhotoException(403, "PHOTO_ACCESS_CHANGED", "다시 로그인한 뒤 확인해 주세요.");
		// Re-read with locks after external I/O; never publish URLs for revoked or replaced records.
		for (var photo : snapshot.photos()) {
			if (!photos.current(snapshot.dogId(), photo.id()).filter(photo::equals).isPresent())
				throw new PhotoException(409, "PHOTO_SET_CHANGED", "사진 정보가 바뀌었어요. 목록을 다시 조회해 주세요.");
		}
		if (signed.size() != snapshot.photos().size()) throw PhotoException.unavailable();
		var result = new ArrayList<Photo>();
		for (var photo : snapshot.photos()) {
			var link = signed.get(photo.id());
			if (link == null || !link.expiresAt().isAfter(Instant.now())) throw PhotoException.unavailable();
			result.add(new Photo(photo.id(), photo.dogId(), photo.sortOrder(), photo.caption(), link.url(), link.expiresAt()));
		}
		return new Page<>(List.copyOf(result), snapshot.nextCursor());
	}
	private UUID authorize(UUID subject, UUID dog) {
		UUID user = accounts.lockProfile(subject, false).id();
		if (!chats.lockAvailableDog(dog)) throw new PhotoException(404, "DOG_NOT_FOUND", "강아지 정보를 찾을 수 없어요.");
		if (!photos.hasConversation(user, dog)) throw new PhotoException(403, "PHOTO_LOCKED", "이 친구와 먼저 이야기를 나눠 주세요.");
		return user;
	}
}
