package org.shelterconnect.api.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CatalogResponses {

	private CatalogResponses() {}

	public record Item<T>(T data) {}
	public record Page<T>(List<T> data, String nextCursor) {}

	public record ShelterSummary(UUID id, String name, String region, BigDecimal latitude,
			BigDecimal longitude, String mapKey, long dogCount) {}

	public record ShelterDetail(UUID id, String name, String region, String address,
			BigDecimal latitude, BigDecimal longitude, String contactPhone, String websiteUrl,
			String mapKey, long dogCount) {}

	public record DogSummary(UUID id, UUID shelterId, String name, String species,
			String adoptionStatus, String avatarKey, List<String> traitLabels) {}

	public record DogDetail(UUID id, UUID shelterId, String name, String species,
			String sex, String breed, LocalDate birthDate, String birthDatePrecision,
			Boolean birthDateEstimated, BigDecimal weightKg, Boolean neutered,
			String adoptionStatus, String avatarKey, List<String> traitLabels, String introduction) {}
}
