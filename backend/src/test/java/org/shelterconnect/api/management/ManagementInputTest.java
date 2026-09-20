package org.shelterconnect.api.management;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.shelterconnect.api.management.ManagementResponses.*;

class ManagementInputTest {
	private final JsonMapper json = JsonMapper.builder().build();
	private final String basics = "\"name\":\" 봄이 \",\"avatarKey\":\"bomi\"";

	@Test void createsSafeDefaultsAndPreservesUnknownValues() {
		var dog = ManagementInput.dogFields(json.readTree("{" + basics + "}"), DogFields.defaults());
		assertThat(dog.name()).isEqualTo("봄이");
		assertThat(dog.isPublic()).isFalse();
		assertThat(dog.adoptionStatus()).isEqualTo("PAUSED");
		assertThat(dog.sex()).isEqualTo("UNKNOWN");
		assertThat(dog.neutered()).isNull();
		assertThat(dog.birthDate()).isNull();
		assertThat(dog.traitLabels()).isEmpty();
	}
	@Test void omittedFieldsRemainAndExplicitNullClearsOnlyNullableValues() {
		var dog = ManagementInput.dogFields(json.readTree("{" + basics + ",\"breed\":\"믹스\",\"neutered\":false,\"weightKg\":5.25}"), DogFields.defaults());
		var changed = ManagementInput.dogFields(json.readTree("{\"name\":\"여름이\",\"neutered\":null,\"weightKg\":null}"), dog);
		assertThat(changed.name()).isEqualTo("여름이");
		assertThat(changed.breed()).isEqualTo("믹스");
		assertThat(changed.neutered()).isNull();
		assertThat(changed.weightKg()).isNull();
	}
	@ParameterizedTest
	@ValueSource(strings = {"\"name\":null", "\"name\":\" \"", "\"name\":1", "\"sex\":\"DOG\"", "\"sex\":null",
			"\"isPublic\":\"false\"", "\"isPublic\":null", "\"neutered\":0", "\"avatarKey\":\"../secret\"",
			"\"weightKg\":0", "\"weightKg\":-1", "\"weightKg\":10000", "\"weightKg\":1.234", "\"weightKg\":\"5.1\"",
			"\"adoptionStatus\":\"PENDING\"", "\"traitLabels\":null", "\"traitLabels\":[null]", "\"traitLabels\":[\"\"]",
			"\"traitLabels\":[\"산책\",\" 산책 \" ]", "\"birthDate\":\"2020-02-30\"",
			"\"birthDate\":\"2020-01-01\"", "\"birthDatePrecision\":\"YEAR\"",
			"\"birthDatePrecision\":\"YEAR\",\"birthDate\":\"2020-02-01\",\"birthDateEstimated\":true",
			"\"birthDatePrecision\":\"MONTH\",\"birthDate\":\"2020-02-02\",\"birthDateEstimated\":true",
			"\"birthDatePrecision\":\"DAY\",\"birthDate\":\"9999-01-01\",\"birthDateEstimated\":false",
			"\"birthDatePrecision\":\"DAY\",\"birthDate\":\"0000-01-01\",\"birthDateEstimated\":false"})
	void rejectsInvalidDogValues(String fields) {
		var body = json.readTree("{" + basics + "}").deepCopy();
		for (var property : json.readTree("{" + fields + "}").properties()) ((tools.jackson.databind.node.ObjectNode) body).set(property.getKey(), property.getValue());
		assertThatThrownBy(() -> ManagementInput.dogFields(body, DogFields.defaults())).isInstanceOf(ManagementException.class);
	}
	@Test void validatesUnicodeLengthTagsAndBirthPrecisionTogether() {
		var body = json.createObjectNode().put("name", "강".repeat(81)).put("avatarKey", "test");
		assertThatThrownBy(() -> ManagementInput.dogFields(body, DogFields.defaults())).isInstanceOf(ManagementException.class);
		body.put("name", "봄이"); body.putArray("traitLabels").add("강".repeat(41));
		assertThatThrownBy(() -> ManagementInput.dogFields(body, DogFields.defaults())).isInstanceOf(ManagementException.class);
		var labels = body.putArray("traitLabels"); for (int i=0;i<9;i++) labels.add("특징"+i);
		assertThatThrownBy(() -> ManagementInput.dogFields(body, DogFields.defaults())).isInstanceOf(ManagementException.class);
		var known = ManagementInput.dogFields(json.readTree("{" + basics + ",\"birthDate\":\"2020-01-01\",\"birthDatePrecision\":\"YEAR\",\"birthDateEstimated\":true}"), DogFields.defaults());
		var unknown = ManagementInput.dogFields(json.readTree("{\"birthDate\":null,\"birthDatePrecision\":\"UNKNOWN\",\"birthDateEstimated\":null}"), known);
		assertThat(unknown.birthDate()).isNull();
	}
	@ParameterizedTest
	@ValueSource(strings = {"id", "shelterId", "recordedBy", "confirmedBy", "archivedAt", "species", "role", "unexpected"})
	void patchCannotChangeOwnershipOrServerFields(String field) {
		var body = json.createObjectNode().put(field, "forged").put("expectedUpdatedAt", Instant.now().toString());
		assertThatThrownBy(() -> ManagementInput.dogBody(body, false)).isInstanceOf(ManagementException.class);
	}
	@ParameterizedTest
	@ValueSource(strings = {"null", "[]", "\"text\"", "{}", "{\"expectedUpdatedAt\":\"2026-01-01T00:00:00Z\"}"})
	void emptyOrNonObjectPatchIsRejected(String body) {
		assertThatThrownBy(() -> ManagementInput.dogBody(json.readTree(body), false)).isInstanceOf(ManagementException.class);
	}
	@ParameterizedTest
	@MethodSource("badObservations")
	void invalidObservationIsRejected(String body) {
		assertThatThrownBy(() -> ManagementInput.observationFields(json.readTree(body), null)).isInstanceOf(ManagementException.class);
	}
	static Stream<String> badObservations() {
		return Stream.of("{}", "{\"category\":\"UNKNOWN\",\"content\":\"a\",\"observedAt\":\"2020-01-01T00:00:00Z\"}",
				"{\"category\":\"PLAY\",\"content\":\" \",\"observedAt\":\"2020-01-01T00:00:00Z\"}",
				"{\"category\":\"PLAY\",\"content\":\"a\",\"observedAt\":\"2099-01-01T00:00:00Z\"}",
				"{\"category\":\"PLAY\",\"content\":\"a\",\"observedAt\":\"2020-01-01\"}",
				"{\"category\":\"PLAY\",\"content\":\"a\",\"observedAt\":\"2020-01-01T00:00:00Z\",\"status\":\"RETRACTED\"}");
	}
	@Test void staleVersionAndCrossResourceCursorsAreRejected() {
		Instant now = Instant.now();
		assertThatThrownBy(() -> ManagementInput.version(json.readTree("{\"expectedUpdatedAt\":\"2020-01-01T00:00:00Z\"}"), now))
				.isInstanceOfSatisfying(ManagementException.class, ex -> assertThat(ex.code).isEqualTo("STALE_RESOURCE"));
		UUID shelter = UUID.randomUUID(), last = UUID.randomUUID();
		String cursor = ManagementInput.cursor("admin-dogs", shelter, last);
		assertThat(ManagementInput.after(cursor, "admin-dogs", shelter)).isEqualTo(last);
		assertThatThrownBy(() -> ManagementInput.after(cursor, "admin-observations", shelter)).isInstanceOf(ManagementException.class);
		assertThatThrownBy(() -> ManagementInput.after(cursor, "admin-dogs", UUID.randomUUID())).isInstanceOf(ManagementException.class);
	}
}
