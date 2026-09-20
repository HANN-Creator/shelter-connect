package org.shelterconnect.api.catalog;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static org.shelterconnect.api.catalog.CatalogResponses.*;

@Service
@Transactional(readOnly = true)
public class CatalogService {
	private final CatalogRepository repository;

	public CatalogService(CatalogRepository repository) {
		this.repository = repository;
	}

	public Page<ShelterSummary> shelters(String region, String cursor, String limit) {
		String scope = CatalogQuery.region(region);
		int size = CatalogQuery.limit(limit);
		UUID after = CatalogQuery.after(cursor, "shelters", scope);
		return page(repository.shelters(scope, after, size + 1), size,
				item -> CatalogQuery.cursor("shelters", scope, item.id()));
	}

	public ShelterDetail shelter(String id) {
		return repository.shelter(CatalogQuery.id(id)).orElseThrow(CatalogException::notFound);
	}

	public Page<DogSummary> dogs(String shelterId, String cursor, String limit) {
		UUID id = CatalogQuery.id(shelterId);
		int size = CatalogQuery.limit(limit);
		UUID after = CatalogQuery.after(cursor, "dogs", id.toString());
		repository.shelter(id).orElseThrow(CatalogException::notFound);
		return page(repository.dogs(id, after, size + 1), size,
				item -> CatalogQuery.cursor("dogs", id.toString(), item.id()));
	}

	public DogDetail dog(String id) {
		return repository.dog(CatalogQuery.id(id)).orElseThrow(CatalogException::notFound);
	}

	private static <T> Page<T> page(List<T> rows, int size, Function<T, String> cursor) {
		boolean hasMore = rows.size() > size;
		List<T> data = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
		return new Page<>(data, hasMore ? cursor.apply(data.getLast()) : null);
	}
}
