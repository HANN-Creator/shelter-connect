package org.shelterconnect.api.management;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.ShelterAccessService;
import org.shelterconnect.api.catalog.CatalogResponses.Page;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.management.ManagementResponses.*;

@Service
@Transactional(readOnly = true)
public class ManagementService {
	private final ShelterAccessService access;
	private final ManagementRepository repository;
	public ManagementService(ShelterAccessService access, ManagementRepository repository) { this.access = access; this.repository = repository; }

	public Page<Dog> dogs(UUID subject, String shelter, String cursor, String limit) {
		UUID id = ManagementInput.id(shelter);
		access.requireShelter(subject, id);
		int size = ManagementInput.limit(limit);
		return page(repository.dogs(id, ManagementInput.after(cursor, "admin-dogs", id), size + 1), size,
				row -> ManagementInput.cursor("admin-dogs", id, row.id()));
	}
	public Dog dog(UUID subject, String dog) {
		UUID id = ManagementInput.id(dog);
		var owner = access.requireDog(subject, id);
		return repository.dog(id, owner.shelterId()).orElseThrow(ManagementException::missing);
	}
	@Transactional(timeout = 10)
	public Dog createDog(UUID subject, JsonNode body) {
		ManagementInput.dogBody(body, true);
		UUID shelter = ManagementInput.shelterId(body);
		access.requireShelterForWrite(subject, shelter);
		return repository.createDog(shelter, ManagementInput.dogFields(body, DogFields.defaults()));
	}
	@Transactional(timeout = 10)
	public Dog updateDog(UUID subject, String dog, JsonNode body) {
		UUID id = ManagementInput.id(dog);
		var owner = access.requireDogForWrite(subject, id);
		Dog current = repository.dog(id, owner.shelterId()).orElseThrow(ManagementException::missing);
		editable(current);
		ManagementInput.dogBody(body, false);
		ManagementInput.version(body, current.updatedAt());
		return repository.updateDog(current, ManagementInput.dogFields(body, current.fields()));
	}
	public Page<Observation> observations(UUID subject, String dog, String cursor, String limit) {
		UUID id = ManagementInput.id(dog);
		var owner = access.requireDog(subject, id);
		int size = ManagementInput.limit(limit);
		return page(repository.observations(id, owner.shelterId(), ManagementInput.after(cursor, "admin-observations", id), size + 1), size,
				row -> ManagementInput.cursor("admin-observations", id, row.id()));
	}
	@Transactional(timeout = 10)
	public Observation createObservation(UUID subject, String dog, JsonNode body) {
		UUID id = ManagementInput.id(dog);
		var writer = access.requireDogForWrite(subject, id);
		editable(repository.dog(id, writer.shelterId()).orElseThrow(ManagementException::missing));
		ManagementInput.observationBody(body, true);
		return repository.createObservation(id, writer.userId(), ManagementInput.observationFields(body, null));
	}
	@Transactional(timeout = 10)
	public Observation updateObservation(UUID subject, String dog, String observation, JsonNode body) {
		UUID id = ManagementInput.id(dog), observationId = ManagementInput.id(observation);
		var writer = access.requireDogForWrite(subject, id);
		editable(repository.dog(id, writer.shelterId()).orElseThrow(ManagementException::missing));
		Observation current = repository.lockObservation(id, observationId).orElseThrow(ManagementException::missing);
		ManagementInput.observationBody(body, false);
		ManagementInput.version(body, current.updatedAt());
		if ("RETRACTED".equals(current.status()) || ("CONFIRMED".equals(current.status())
				&& (body.size() != 2 || !body.has("status") || !body.get("status").isString()
						|| !"RETRACTED".equals(body.get("status").asString())))) {
			throw ManagementException.conflict("OBSERVATION_LOCKED", "확인된 기록은 철회 후 새로 작성해 주세요. 철회된 기록은 수정할 수 없어요.");
		}
		return repository.updateObservation(current, writer.userId(), ManagementInput.observationFields(body, current));
	}
	private void editable(Dog dog) {
		if (dog.archivedAt() != null) throw ManagementException.conflict("DOG_ARCHIVED", "보관된 강아지 정보는 수정할 수 없어요.");
	}
	private static <T> Page<T> page(List<T> rows, int size, Function<T, String> cursor) {
		var data = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
		return new Page<>(data, rows.size() > size ? cursor.apply(data.getLast()) : null);
	}
}
