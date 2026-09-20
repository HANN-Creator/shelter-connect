package org.shelterconnect.api;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.sample.SampleDataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CatalogPostgresTest {
	private static final UUID ONGI = UUID.fromString("02100000-0000-4000-8000-000000000001");
	private static final UUID DAON = UUID.fromString("02100000-0000-4000-8000-000000000002");
	private static final UUID BOMI = UUID.fromString("02200000-0000-4000-8000-000000000001");
	private static final UUID BAMI = UUID.fromString("02200000-0000-4000-8000-000000000004");
	private static final UUID HAERI = UUID.fromString("02200000-0000-4000-8000-000000000005");
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource dataSource;
	@Autowired JsonMapper json;

	@BeforeAll
	static void prepareSchema() throws Exception {
		SchemaMigrationTest.migratePostgres();
	}

	@BeforeEach
	void addSampleFixtures() throws Exception {
		var connection = DataSourceUtils.getConnection(dataSource);
		try {
			SampleDataLoader.insert(connection, SampleDataLoader.read(Path.of("sample-data/dataset.json")));
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	@Test
	void shelterPagesUseRealRowsAndLiteralRegionPrefixes() throws Exception {
		var first = body(get("/v1/shelters").param("limit", "1"));
		assertThat(first.get("data").size()).isEqualTo(1);
		assertThat(first.at("/data/0/id").asText()).isEqualTo(ONGI.toString());
		assertThat(first.at("/data/0/dogCount").asInt()).isEqualTo(3);
		var second = body(get("/v1/shelters").param("limit", "1").param("cursor", first.get("nextCursor").asText()));
		assertThat(second.at("/data/0/id").asText()).isEqualTo(DAON.toString());
		assertThat(second.at("/data/0/dogCount").asInt()).isEqualTo(1);
		assertThat(second.get("nextCursor").isNull()).isTrue();
		assertThat(body(get("/v1/shelters").param("region", " 서울 ")).get("data").size()).isEqualTo(1);
		assertThat(body(get("/v1/shelters").param("region", "경기")).at("/data/0/id").asText()).isEqualTo(DAON.toString());
		for (String region : List.of("제주", "%", "_", "서울' OR 1=1 --")) {
			var empty = body(get("/v1/shelters").param("region", region));
			assertThat(empty.get("data").isEmpty()).isTrue();
			assertThat(empty.get("nextCursor").isNull()).isTrue();
		}
	}

	@Test
	void dogPagesHaveNoDuplicatesAndKeepInProgressDogs() throws Exception {
		String path = "/v1/shelters/" + ONGI + "/dogs";
		var first = body(get(path).param("limit", "2"));
		assertThat(first.get("data").size()).isEqualTo(2);
		assertThat(first.at("/data/0/name").asText()).isEqualTo("봄이");
		assertThat(first.at("/data/1/name").asText()).isEqualTo("두부");
		var last = body(get(path).param("limit", "2").param("cursor", first.get("nextCursor").asText()));
		assertThat(last.get("data").size()).isEqualTo(1);
		assertThat(last.at("/data/0/name").asText()).isEqualTo("콩이");
		assertThat(last.at("/data/0/adoptionStatus").asText()).isEqualTo("IN_PROGRESS");
		assertThat(last.get("nextCursor").isNull()).isTrue();
		assertThat(body(get("/v1/shelters/" + DAON + "/dogs")).get("data").size()).isEqualTo(1);
	}

	@Test
	void removingCursorRowDoesNotBreakContinuation() throws Exception {
		String path = "/v1/shelters/" + ONGI + "/dogs";
		String cursor = body(get(path).param("limit", "1")).get("nextCursor").asText();
		jdbc.update("UPDATE shelter.dogs SET is_public = false WHERE id = ?", BOMI);
		var next = body(get(path).param("cursor", cursor));
		assertThat(next.get("data").size()).isEqualTo(2);
		assertThat(next.at("/data/0/name").asText()).isEqualTo("두부");
	}

	@ParameterizedTest
	@ValueSource(strings = {"PENDING", "REJECTED", "SUSPENDED", "APPROVED"})
	void nonPublicParentHidesItsOwnDetailsAndAllDogs(String approval) throws Exception {
		jdbc.update("UPDATE shelter.shelters SET approval_status = ?, is_public = false WHERE id = ?", approval, ONGI);
		assertThat(body(get("/v1/shelters")).get("data").size()).isEqualTo(1);
		assertMissing("/v1/shelters/" + ONGI);
		assertMissing("/v1/shelters/" + ONGI + "/dogs");
		assertMissing("/v1/dogs/" + BOMI);
	}

	@ParameterizedTest
	@ValueSource(strings = {"ADOPTED", "PAUSED"})
	void completedOrPausedDogsAreExcludedFromListsDetailsAndCounts(String adoptionStatus) throws Exception {
		jdbc.update("UPDATE shelter.dogs SET adoption_status = ? WHERE id = ?", adoptionStatus, BOMI);
		assertMissing("/v1/dogs/" + BOMI);
		assertThat(body(get("/v1/shelters/" + ONGI + "/dogs")).get("data").size()).isEqualTo(2);
		assertThat(body(get("/v1/shelters/" + ONGI)).at("/data/dogCount").asInt()).isEqualTo(2);
	}

	@Test
	void hiddenArchivedAndMissingDogsCannotBeLoadedById() throws Exception {
		assertMissing("/v1/dogs/" + HAERI);
		jdbc.update("UPDATE shelter.dogs SET archived_at = now() WHERE id = ?", BOMI);
		assertMissing("/v1/dogs/" + BOMI);
		assertThat(body(get("/v1/shelters/" + ONGI)).at("/data/dogCount").asInt()).isEqualTo(2);
		assertMissing("/v1/dogs/" + UUID.randomUUID());
		assertMissing("/v1/shelters/" + UUID.randomUUID());
		assertMissing("/v1/shelters/" + UUID.randomUUID() + "/dogs");
	}

	@Test
	void publicShelterWithoutVisibleDogsHasAnEmptyList() throws Exception {
		jdbc.update("UPDATE shelter.dogs SET is_public = false WHERE shelter_id = ?", DAON);
		mvc.perform(get("/v1/shelters/" + DAON + "/dogs")).andExpect(status().isOk())
				.andExpect(content().json("{\"data\":[],\"nextCursor\":null}"));
		assertThat(body(get("/v1/shelters/" + DAON)).at("/data/dogCount").asInt()).isZero();
	}

	@Test
	void profilePreservesBirthPrecisionNullsAndTraitsWithoutPrivateFields() throws Exception {
		jdbc.update("INSERT INTO shelter.dog_photos(dog_id, storage_bucket, storage_key, sort_order) VALUES (?, 'private-photos', 'secret.jpg', 0)", BOMI);
		var bomi = body(get("/v1/dogs/" + BOMI)).get("data");
		assertThat(bomi.get("birthDate").asText()).isEqualTo("2023-01-01");
		assertThat(bomi.get("birthDatePrecision").asText()).isEqualTo("YEAR");
		assertThat(bomi.get("birthDateEstimated").asBoolean()).isTrue();
		assertThat(bomi.get("traitLabels").size()).isEqualTo(2);
		assertThat(bomi.get("weightKg").asDouble()).isEqualTo(6.8);
		var bami = body(get("/v1/dogs/" + BAMI)).get("data");
		for (String field : List.of("birthDate", "birthDateEstimated", "neutered")) {
			assertThat(bami.has(field)).as(field).isTrue();
			assertThat(bami.get(field).isNull()).as(field).isTrue();
		}
		assertThat(bami.get("birthDatePrecision").asText()).isEqualTo("UNKNOWN");
		jdbc.update("UPDATE shelter.dogs SET trait_labels = '{}' WHERE id = ?", BAMI);
		assertThat(body(get("/v1/dogs/" + BAMI)).at("/data/traitLabels").isEmpty()).isTrue();
		for (String path : List.of("/v1/shelters", "/v1/shelters/" + ONGI,
				"/v1/shelters/" + ONGI + "/dogs", "/v1/dogs/" + BOMI)) {
			String output = body(get(path)).toString();
			assertThat(output).doesNotContain("photo", "storage", "secret.jpg", "reviewedBy", "isPublic",
					"observations", "recordedBy", "confirmedBy", "sourceNote", "authSubject");
		}
	}

	private void assertMissing(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
		return json.readTree(mvc.perform(request).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andReturn().getResponse().getContentAsString());
	}
}
