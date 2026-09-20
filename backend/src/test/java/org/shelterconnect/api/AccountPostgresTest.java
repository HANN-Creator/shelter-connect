package org.shelterconnect.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.shelterconnect.api.auth.JwtTestSupport;
import org.shelterconnect.sample.SampleDataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtTestConfiguration.class)
@Transactional
class AccountPostgresTest {
	private static final UUID OPERATOR = id("020", 1), STAFF = id("020", 2), OTHER_STAFF = id("020", 3), VISITOR = id("020", 4);
	private static final UUID ONGI = id("021", 1), DAON = id("021", 2), BOMI = id("022", 1), HAERI = id("022", 5);
	// Auth subjects deliberately differ from the application's user IDs.
	private static final UUID STAFF_SUB = UUID.randomUUID(), OTHER_SUB = UUID.randomUUID(), VISITOR_SUB = UUID.randomUUID(), OPERATOR_SUB = UUID.randomUUID();
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource dataSource;
	@Autowired JsonMapper json;
	@Autowired JwtTestSupport tokens;

	@BeforeAll static void prepareSchema() throws Exception { SchemaMigrationTest.migratePostgres(); }

	@BeforeEach
	void fixtures() throws Exception {
		var connection = DataSourceUtils.getConnection(dataSource);
		try { SampleDataLoader.insert(connection, SampleDataLoader.read(Path.of("sample-data/dataset.json"))); }
		finally { DataSourceUtils.releaseConnection(connection, dataSource); }
		link(STAFF, STAFF_SUB); link(OTHER_STAFF, OTHER_SUB); link(VISITOR, VISITOR_SUB); link(OPERATOR, OPERATOR_SUB);
	}

	@Test
	void registrationIsIdempotentDefaultsToUserAndNeverCreatesMemberships() throws Exception {
		UUID subject = UUID.randomUUID();
		mvc.perform(auth(get("/v1/me"), subject)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_REGISTERED"));
		var first = body(auth(post("/v1/me").contentType("application/json")
				.content("{\"role\":\"OPERATOR\",\"shelterId\":\"" + ONGI + "\"}"), subject));
		assertThat(first.at("/data/role").asText()).isEqualTo("USER");
		assertThat(first.at("/data/id").asText()).isNotEqualTo(subject.toString());
		assertThat(body(auth(post("/v1/me"), subject))).isEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.app_users WHERE auth_provider = ? AND auth_subject = ?",
				Integer.class, tokens.properties.providerKey(), subject.toString())).isEqualTo(1);
		assertThat(body(auth(get("/v1/me/shelters"), subject)).get("data").isEmpty()).isTrue();
		denied(auth(get(shelter(ONGI)), subject));
	}

	@Test
	void meUsesVerifiedSubjectNotRequestParametersOrMetadata() throws Exception {
		String token = tokens.token(VISITOR_SUB, b -> b.claim("user_metadata", Map.of("role", "OPERATOR", "userId", STAFF.toString()))
				.claim("app_metadata", Map.of("shelterId", ONGI.toString(), "role", "MANAGER")));
		var me = body(get("/v1/me").param("userId", STAFF.toString()).header("Authorization", "Bearer " + token));
		assertThat(me.at("/data/id").asText()).isEqualTo(VISITOR.toString());
		assertThat(me.at("/data/role").asText()).isEqualTo("USER");
		assertThat(me.toString()).doesNotContain("authSubject", "authProvider", "disabledAt");
		denied(get(shelter(ONGI)).header("Authorization", "Bearer " + token));
	}

	@Test
	void repeatedRegistrationPreservesExistingRoleAndCannotReactivateDisabledAccount() throws Exception {
		assertThat(body(auth(post("/v1/me"), OPERATOR_SUB)).at("/data/role").asText()).isEqualTo("OPERATOR");
		jdbc.update("UPDATE shelter.app_users SET disabled_at = now() WHERE id = ?", STAFF);
		for (var request : new MockHttpServletRequestBuilder[] {post("/v1/me"), get("/v1/me"), get("/v1/me/shelters"), get(dog(BOMI))}) {
			mvc.perform(auth(request, STAFF_SUB)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
		}
		assertThat(jdbc.queryForObject("SELECT disabled_at IS NOT NULL FROM shelter.app_users WHERE id = ?", Boolean.class, STAFF)).isTrue();
	}

	@Test
	void identicalSubjectFromAnotherProjectCannotClaimExistingAccount() throws Exception {
		jdbc.update("UPDATE shelter.app_users SET auth_provider = 'other-provider' WHERE id = ?", STAFF);
		mvc.perform(auth(get("/v1/me"), STAFF_SUB)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_REGISTERED"));
		var registered = body(auth(post("/v1/me"), STAFF_SUB));
		assertThat(registered.at("/data/id").asText()).isNotEqualTo(STAFF.toString());
		assertThat(body(auth(get("/v1/me/shelters"), STAFF_SUB)).get("data").isEmpty()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"MANAGER", "STAFF"})
	void activeMembersCanManageOnlyTheirApprovedShelterAndItsDogs(String role) throws Exception {
		jdbc.update("UPDATE shelter.shelter_memberships SET role = ? WHERE user_id = ?", role, STAFF);
		var memberships = body(auth(get("/v1/me/shelters"), STAFF_SUB)).get("data");
		assertThat(memberships.size()).isEqualTo(1);
		assertThat(memberships.get(0).get("shelterId").asText()).isEqualTo(ONGI.toString());
		assertThat(body(auth(get(shelter(ONGI)), STAFF_SUB)).at("/data/memberRole").asText()).isEqualTo(role);
		assertThat(body(auth(get(dog(BOMI)), STAFF_SUB)).at("/data/dogId").asText()).isEqualTo(BOMI.toString());
		denied(auth(get(shelter(DAON)), STAFF_SUB));
		denied(auth(get(dog(HAERI)).param("shelterId", ONGI.toString()), STAFF_SUB));
		denied(auth(get(dog(UUID.randomUUID())), STAFF_SUB));
		denied(auth(get(shelter(UUID.randomUUID())), STAFF_SUB));
	}

	@Test
	void publicVisibilityDoesNotRemoveOwnersManagementAccess() throws Exception {
		jdbc.update("UPDATE shelter.shelters SET is_public = false WHERE id = ?", DAON);
		// Haeri is private and PAUSED; the approved shelter must still be able to manage it.
		body(auth(get(dog(HAERI)), OTHER_SUB));
		body(auth(get(shelter(DAON)), OTHER_SUB));
		assertThat(body(auth(get("/v1/me/shelters"), OTHER_SUB)).get("data").size()).isEqualTo(1);
		mvc.perform(get("/v1/dogs/" + HAERI)).andExpect(status().isNotFound());
	}

	@ParameterizedTest
	@ValueSource(strings = {"INVITED", "REVOKED"})
	void inactiveMembershipImmediatelyLosesAllAccess(String status) throws Exception {
		String token = tokens.token(STAFF_SUB);
		body(get(dog(BOMI)).header("Authorization", "Bearer " + token));
		jdbc.update("UPDATE shelter.shelter_memberships SET status = ? WHERE user_id = ?", status, STAFF);
		denied(get(dog(BOMI)).header("Authorization", "Bearer " + token));
		denied(auth(get(shelter(ONGI)), STAFF_SUB));
		assertThat(body(auth(get("/v1/me/shelters"), STAFF_SUB)).get("data").isEmpty()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"PENDING", "REJECTED", "SUSPENDED"})
	void unapprovedSheltersRejectEvenActiveStaff(String status) throws Exception {
		jdbc.update("UPDATE shelter.shelters SET is_public = false, approval_status = ? WHERE id = ?", status, ONGI);
		denied(auth(get(shelter(ONGI)), STAFF_SUB));
		denied(auth(get(dog(BOMI)), STAFF_SUB));
		assertThat(body(auth(get("/v1/me/shelters"), STAFF_SUB)).get("data").isEmpty()).isTrue();
	}

	@Test
	void operatorRoleAloneDoesNotBypassShelterMembership() throws Exception {
		denied(auth(get(shelter(ONGI)), OPERATOR_SUB));
		denied(auth(get(dog(BOMI)), OPERATOR_SUB));
		assertThat(body(auth(get("/v1/me/shelters"), OPERATOR_SUB)).get("data").isEmpty()).isTrue();
	}

	@Test
	void ownershipChangesAreCheckedOnEveryRequest() throws Exception {
		String token = tokens.token(STAFF_SUB);
		body(get(dog(BOMI)).header("Authorization", "Bearer " + token));
		jdbc.update("UPDATE shelter.dogs SET shelter_id = ? WHERE id = ?", DAON, BOMI);
		denied(get(dog(BOMI)).header("Authorization", "Bearer " + token));
		body(auth(get(dog(BOMI)), OTHER_SUB));
	}

	@Test
	void publicReadsNeedNoTokenButInvalidBearerIsNeverSilentlyAccepted() throws Exception {
		body(get("/v1/shelters"));
		mvc.perform(get("/v1/shelters").header("Authorization", "Bearer invalid"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	private void link(UUID appId, UUID subject) {
		jdbc.update("UPDATE shelter.app_users SET auth_provider = ?, auth_subject = ? WHERE id = ?",
				tokens.properties.providerKey(), subject.toString(), appId);
	}
	private static UUID id(String prefix, int suffix) { return UUID.fromString(prefix + "00000-0000-4000-8000-" + String.format("%012d", suffix)); }
	private String shelter(UUID id) { return "/v1/shelter-admin/shelters/" + id + "/access"; }
	private String dog(UUID id) { return "/v1/shelter-admin/dogs/" + id + "/access"; }
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, UUID subject) {
		return request.header("Authorization", "Bearer " + tokens.token(subject));
	}
	private void denied(MockHttpServletRequestBuilder request) throws Exception {
		mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}
	private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
		return json.readTree(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse().getContentAsString());
	}
}
