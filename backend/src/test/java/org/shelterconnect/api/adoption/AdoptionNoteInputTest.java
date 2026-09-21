package org.shelterconnect.api.adoption;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AdoptionNoteInputTest {
	private final JsonMapper json = new JsonMapper();
	private tools.jackson.databind.node.ObjectNode body() {
		return json.createObjectNode().put("questions", "").put("carePlan", "").putNull("expectedUpdatedAt")
				.set("checklist", json.createObjectNode());
	}
	@Test void emptyAndUnicodeContentAndFalseChecksArePreserved() {
		var body = body().put("questions", " 🐶\n질문 ").put("carePlan", "밥과 산책");
		body.set("checklist", json.createObjectNode().put("budgetPlanned", false).put("housingChecked", true));
		var input = AdoptionNoteInput.save(body);
		assertThat(input.questions()).isEqualTo(" 🐶\n질문 ");
		assertThat(input.checklist()).containsEntry("budgetPlanned", false).containsEntry("housingChecked", true);
		assertThat(input.expectedUpdatedAt()).isNull();
		assertThat(AdoptionNoteInput.save(body()).carePlan()).isEmpty();
	}
	@ParameterizedTest @ValueSource(strings = {"userId", "dogId", "id", "updatedAt", "submitted"})
	void identitiesAndUnknownFieldsCannotBeSupplied(String field) {
		assertThatThrownBy(() -> AdoptionNoteInput.save(body().put(field, "spoof"))).isInstanceOf(AdoptionNoteException.class);
	}
	@ParameterizedTest @ValueSource(strings = {"questions", "carePlan", "checklist", "expectedUpdatedAt"})
	void everyFieldIsRequired(String field) {
		var body = body(); body.remove(field);
		assertThatThrownBy(() -> AdoptionNoteInput.save(body)).isInstanceOf(AdoptionNoteException.class);
	}
	@Test void limitsCountCodePointsAndRejectNullBytesAndWrongTypes() {
		assertThat(AdoptionNoteInput.save(body().put("questions", "🐶".repeat(5000))).questions()).hasSize(10000);
		assertThat(AdoptionNoteInput.save(body().put("carePlan", "가".repeat(10000))).carePlan()).hasSize(10000);
		for (var value : java.util.List.of(body().put("questions", "가".repeat(5001)), body().put("carePlan", "가".repeat(10001)),
				body().put("questions", "a\0b"), body().putNull("carePlan"), body().put("questions", 1), body().put("expectedUpdatedAt", 1)))
			assertThatThrownBy(() -> AdoptionNoteInput.save(value)).isInstanceOf(AdoptionNoteException.class);
	}
	@ParameterizedTest @ValueSource(strings = {"null", "[]", "{\"budgetPlanned\":null}", "{\"budgetPlanned\":1}",
			"{\"budgetPlanned\":\"true\"}", "{\"unknown\":true}", "{\"housingChecked\":{}}"})
	void checklistHasKnownBooleanKeysOnly(String value) {
		var body = body().set("checklist", json.readTree(value));
		assertThatThrownBy(() -> AdoptionNoteInput.save(body)).isInstanceOf(AdoptionNoteException.class);
	}
	@ParameterizedTest @ValueSource(strings = {"not-a-date", "2026-09-21", "1969-01-01T00:00:00Z", "2026-09-21T00:00:00.000000001Z"})
	void invalidVersionsAreRejected(String value) {
		assertThatThrownBy(() -> AdoptionNoteInput.save(body().put("expectedUpdatedAt", value))).isInstanceOf(AdoptionNoteException.class);
	}
	@Test void cursorRoundTripsAndIsBoundToTheCurrentUser() {
		UUID user = UUID.randomUUID(), id = UUID.randomUUID(); Instant at = Instant.parse("2026-09-21T00:00:00.123456Z");
		String cursor = AdoptionNoteInput.cursor(user, at, id);
		assertThat(AdoptionNoteInput.cursor(cursor, user)).isEqualTo(new AdoptionNoteInput.Cursor(at, id));
		assertThatThrownBy(() -> AdoptionNoteInput.cursor(cursor, UUID.randomUUID())).isInstanceOf(AdoptionNoteException.class);
		assertThatThrownBy(() -> AdoptionNoteInput.cursor("!", user)).isInstanceOf(AdoptionNoteException.class);
		assertThat(AdoptionNoteInput.cursor(null, user)).isNull();
	}
	@ParameterizedTest @ValueSource(strings = {"0", "51", "-1", "abc", "100", ""})
	void invalidPageSizesAreRejected(String value) {
		assertThatThrownBy(() -> AdoptionNoteInput.limit(value)).isInstanceOf(AdoptionNoteException.class);
	}
	@Test void idsMustBeCanonicalAndPagesAreBounded() {
		assertThatThrownBy(() -> AdoptionNoteInput.id("1-1-1-1-1")).isInstanceOf(AdoptionNoteException.class);
		assertThat(AdoptionNoteInput.limit(null)).isEqualTo(20);
		assertThat(AdoptionNoteInput.limit("50")).isEqualTo(50);
	}
}
