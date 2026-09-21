package org.shelterconnect.api.adoption;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.AccountService;
import org.shelterconnect.api.catalog.CatalogResponses.Page;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.adoption.AdoptionNoteResponses.*;

@Service
@Transactional(readOnly = true)
public class AdoptionNoteService {
	private final AccountService accounts;
	private final AdoptionNoteRepository repository;
	public AdoptionNoteService(AccountService accounts, AdoptionNoteRepository repository) {
		this.accounts = accounts; this.repository = repository;
	}
	public Note get(UUID subject, String dogId) {
		UUID dog = AdoptionNoteInput.id(dogId), user = accounts.profile(subject).id();
		return repository.note(user, dog, false).orElseThrow(AdoptionNoteException::missing);
	}
	public Page<Note> list(UUID subject, String cursor, String limit) {
		int count = AdoptionNoteInput.limit(limit);
		UUID user = accounts.profile(subject).id();
		var rows = repository.notes(user, AdoptionNoteInput.cursor(cursor, user), count + 1);
		var data = List.copyOf(rows.subList(0, Math.min(count, rows.size())));
		String next = rows.size() > count ? AdoptionNoteInput.cursor(user, data.getLast().createdAt(), data.getLast().id()) : null;
		return new Page<>(data, next);
	}
	@Transactional(timeout = 10)
	public Stored save(UUID subject, String dogId, JsonNode body) {
		UUID dog = AdoptionNoteInput.id(dogId);
		var input = AdoptionNoteInput.save(body);
		// Serialize even the first save, when there is no note row to lock yet.
		UUID user = accounts.lockProfile(subject, true).id();
		var current = repository.note(user, dog, true);
		if (current.isEmpty()) {
			if (input.expectedUpdatedAt() != null) throw AdoptionNoteException.missing();
			if (!repository.lockAvailableDog(dog))
				throw new AdoptionNoteException(404, "DOG_NOT_FOUND", "메모를 새로 작성할 수 있는 강아지를 찾을 수 없어요.");
			return new Stored(repository.create(user, dog, input), true);
		}
		var old = current.get();
		if (!old.updatedAt().equals(input.expectedUpdatedAt())) throw AdoptionNoteException.conflict();
		// A private note remains the author's even after the dog is no longer public.
		if (old.questions().equals(input.questions()) && old.carePlan().equals(input.carePlan())
				&& old.checklist().equals(input.checklist())) return new Stored(old, false);
		return new Stored(repository.update(user, dog, input), false);
	}
}
