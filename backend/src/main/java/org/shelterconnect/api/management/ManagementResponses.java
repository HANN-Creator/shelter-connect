package org.shelterconnect.api.management;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ManagementResponses {
	private ManagementResponses() {}
	public record DogFields(String name, String sex, String breed, LocalDate birthDate, String birthDatePrecision,
			Boolean birthDateEstimated, BigDecimal weightKg, Boolean neutered, String adoptionStatus,
			boolean isPublic, String avatarKey, List<String> traitLabels, String introduction) {
		static DogFields defaults() {
			return new DogFields(null, "UNKNOWN", null, null, "UNKNOWN", null, null, null, "PAUSED", false, null, List.of(), null);
		}
	}
	public record Dog(UUID id, UUID shelterId, String species, String name, String sex, String breed,
			LocalDate birthDate, String birthDatePrecision, Boolean birthDateEstimated, BigDecimal weightKg,
			Boolean neutered, String adoptionStatus, boolean isPublic, String avatarKey, List<String> traitLabels,
			String introduction, Instant archivedAt, Instant createdAt, Instant updatedAt) {
		DogFields fields() {
			return new DogFields(name, sex, breed, birthDate, birthDatePrecision, birthDateEstimated,
					weightKg, neutered, adoptionStatus, isPublic, avatarKey, traitLabels, introduction);
		}
	}
	public record Observation(UUID id, UUID dogId, String category, String content, Instant observedAt,
			UUID recordedBy, String sourceNote, String status, UUID confirmedBy, Instant confirmedAt,
			Instant createdAt, Instant updatedAt) {}
	public record ObservationFields(String category, String content, Instant observedAt, String sourceNote, String status) {}
}
