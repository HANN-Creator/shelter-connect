package org.shelterconnect.api.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.shelterconnect.api.catalog.CatalogResponses.*;

@WebMvcTest(PublicCatalogController.class)
@Import({CatalogService.class, CatalogRequestFilter.class, CatalogErrorHandler.class})
class PublicCatalogHttpTest {
	private static final UUID FIRST = UUID.fromString("02100000-0000-4000-8000-000000000001");
	private static final UUID SECOND = UUID.fromString("02100000-0000-4000-8000-000000000002");
	@Autowired MockMvc mvc;
	@Autowired JsonMapper json;
	@MockitoBean CatalogRepository repository;

	@ParameterizedTest
	@ValueSource(strings = {"", "0", "-1", "51", "9999999999999", "1.5", "hello"})
	void invalidLimitIsAClientErrorWithoutDatabaseQueries(String limit) throws Exception {
		mvc.perform(get("/v1/shelters").param("limit", limit))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "02100000-0000-4000-8000-0000000000010"})
	void malformedIdsAreRejected(String id) throws Exception {
		for (String path : List.of("/v1/dogs/", "/v1/shelters/")) {
			mvc.perform(get(path + id)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "!not-a-cursor", "a", "bm9wZQ"})
	void brokenCursorsAreRejected(String cursor) throws Exception {
		mvc.perform(get("/v1/shelters").param("cursor", cursor))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		verifyNoInteractions(repository);
	}

	@Test
	void cursorCannotBeReusedForAnotherRegionOrResource() throws Exception {
		when(repository.shelters("서울", null, 2)).thenReturn(List.of(shelter(FIRST), shelter(SECOND)));
		var first = mvc.perform(get("/v1/shelters").param("region", " 서울 ").param("limit", "1"))
				.andExpect(status().isOk()).andReturn();
		String cursor = json.readTree(first.getResponse().getContentAsString()).get("nextCursor").asText();
		mvc.perform(get("/v1/shelters").param("region", "경기").param("cursor", cursor))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		mvc.perform(get("/v1/shelters/" + FIRST + "/dogs").param("cursor", cursor))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		when(repository.shelters("서울", FIRST, 21)).thenReturn(List.of(shelter(SECOND)));
		mvc.perform(get("/v1/shelters").param("region", "서울").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(SECOND.toString()))
				.andExpect(content().json("{\"nextCursor\":null}"));
	}

	@Test
	void cursorFromAnotherShelterOrVersionIsRejected() throws Exception {
		for (String raw : List.of("1\ndogs\n" + SECOND + "\n" + FIRST,
				"2\ndogs\n" + FIRST + "\n" + FIRST)) {
			String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
			mvc.perform(get("/v1/shelters/" + FIRST + "/dogs").param("cursor", cursor))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void oversizedOrControlCharacterInputIsRejected() throws Exception {
		mvc.perform(get("/v1/shelters").param("region", "가".repeat(101))).andExpect(status().isBadRequest());
		mvc.perform(get("/v1/shelters").param("region", "서울\n경기")).andExpect(status().isBadRequest());
		mvc.perform(get("/v1/shelters").param("cursor", "a".repeat(1025))).andExpect(status().isBadRequest());
		verifyNoInteractions(repository);
	}

	@Test
	void emptyPageAndUnknownValuesKeepTheirJsonShape() throws Exception {
		when(repository.shelters("", null, 21)).thenReturn(List.of());
		mvc.perform(get("/v1/shelters")).andExpect(status().isOk())
				.andExpect(content().json("{\"data\":[],\"nextCursor\":null}"))
				.andExpect(header().string("Cache-Control", "no-store"));
		when(repository.shelter(FIRST)).thenReturn(Optional.of(new ShelterDetail(
				FIRST, "온기", "서울", null, null, null, null, null, null, 0)));
		var response = mvc.perform(get("/v1/shelters/" + FIRST)).andExpect(status().isOk()).andReturn();
		var data = json.readTree(response.getResponse().getContentAsString()).get("data");
		assertThat(data.has("latitude")).isTrue();
		assertThat(data.get("latitude").isNull()).isTrue();
		assertThat(data.get("contactPhone").isNull()).isTrue();
	}

	@Test
	void notFoundAndServerErrorsHaveRequestIdsAndNeverExposeDatabaseDetails() throws Exception {
		when(repository.dog(FIRST)).thenReturn(Optional.empty());
		var missing = mvc.perform(get("/v1/dogs/" + FIRST).header("X-Request-ID", "untrusted-client-id"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andReturn().getResponse();
		String id = json.readTree(missing.getContentAsString()).get("requestId").asText();
		assertThat(UUID.fromString(id)).isNotNull();
		assertThat(missing.getHeader("X-Request-ID")).isEqualTo(id);
		when(repository.dog(FIRST)).thenThrow(new DataAccessResourceFailureException("private-db-host password secret SQL"));
		var failed = mvc.perform(get("/v1/dogs/" + FIRST)).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse();
		assertThat(failed.getContentAsString()).doesNotContain("private-db-host", "password", "secret", "SQL");
		assertThat(json.readTree(failed.getContentAsString()).get("requestId").asText())
				.isEqualTo(failed.getHeader("X-Request-ID")).isNotEqualTo(id);
	}

	private ShelterSummary shelter(UUID id) {
		return new ShelterSummary(id, "온기", "서울", null, null, "sunny-yard", 3);
	}
}
