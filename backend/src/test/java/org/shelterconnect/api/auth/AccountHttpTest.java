package org.shelterconnect.api.auth;

import java.util.Optional;
import java.util.Date;
import java.util.UUID;
import java.time.Instant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.shelterconnect.api.web.ApiRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
@Import({AccountService.class, ShelterAccessService.class, AccountErrorHandler.class, ApiRequestFilter.class,
		SecurityConfiguration.class, SecurityErrors.class, JwtTestConfiguration.class})
class AccountHttpTest {
	private static final UUID SUBJECT = UUID.randomUUID(), APP_ID = UUID.randomUUID();
	@Autowired MockMvc mvc;
	@Autowired JwtTestSupport tokens;
	@Autowired JsonMapper json;
	@MockitoBean AccountRepository repository;

	@Test
	void missingOrInvalidTokenReturnsJsonBeforeDatabaseAccess() throws Exception {
		for (var request : new MockHttpServletRequestBuilder[] {get("/v1/me"), get("/v1/me").header("Authorization", "Bearer bad"),
				post("/v1/me"), get("/v1/me/shelters"), get("/v1/shelter-admin/dogs/" + UUID.randomUUID() + "/access")}) {
			var response = mvc.perform(request.header("X-Request-ID", "client-controlled"))
					.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
					.andExpect(header().string("WWW-Authenticate", "Bearer"))
					.andExpect(header().string("Cache-Control", "no-store"))
					.andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().doesNotExist("Location"))
					.andReturn().getResponse();
			String id = json.readTree(response.getContentAsString()).get("requestId").asText();
			assertThat(UUID.fromString(id)).isNotNull();
			assertThat(response.getHeader("X-Request-ID")).isEqualTo(id);
		}
		verifyNoInteractions(repository);
	}

	@Test
	void signedButExpiredOrWrongAudienceTokensCannotReachTheDatabase() throws Exception {
		for (String token : new String[] {
				tokens.token(SUBJECT, b -> b.issueTime(Date.from(Instant.now().minusSeconds(600)))
						.expirationTime(Date.from(Instant.now().minusSeconds(120)))),
				tokens.token(SUBJECT, b -> b.audience("anon"))}) {
			mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
					.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void successfulRequestDoesNotCreateAReusableServerLogin() throws Exception {
		when(repository.account(SUBJECT)).thenReturn(Optional.of(new AccountRepository.Account(APP_ID, "방문자", "USER", false)));
		mvc.perform(auth(get("/v1/me"))).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
		mvc.perform(get("/v1/me")).andExpect(status().isUnauthorized());
		verify(repository).account(SUBJECT);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void cookiesAndQueryParametersCannotAuthenticate() throws Exception {
		String token = tokens.token(SUBJECT);
		mvc.perform(get("/v1/me").cookie(new Cookie("access_token", token)).param("access_token", token))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(repository);
	}

	@Test
	void registrationUsesOnlyVerifiedSubjectAndIgnoresClientRoleAndUserId() throws Exception {
		when(repository.account(SUBJECT)).thenReturn(Optional.of(new AccountRepository.Account(APP_ID, "방문자", "USER", false)));
		mvc.perform(auth(post("/v1/me").contentType(MediaType.APPLICATION_JSON)
				.content("{\"role\":\"OPERATOR\",\"userId\":\"" + UUID.randomUUID() + "\"}")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(APP_ID.toString()))
				.andExpect(jsonPath("$.data.role").value("USER")).andExpect(header().doesNotExist("Set-Cookie"));
		verify(repository).register(SUBJECT);
		verify(repository).account(SUBJECT);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void unregisteredAndDisabledAccountsAreDenied() throws Exception {
		when(repository.account(SUBJECT)).thenReturn(Optional.empty());
		mvc.perform(auth(get("/v1/me"))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_REGISTERED"));
		when(repository.account(SUBJECT)).thenReturn(Optional.of(new AccountRepository.Account(APP_ID, "방문자", "USER", true)));
		mvc.perform(auth(get("/v1/me/shelters"))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
		verify(repository, never()).shelters(SUBJECT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-uuid", "1-1-1-1-1"})
	void invalidResourceIdIsAClientError(String id) throws Exception {
		mvc.perform(auth(get("/v1/shelter-admin/dogs/" + id + "/access"))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(repository);
	}

	@Test
	void futureRoutesAndWriteMethodsAreClosedEvenToAValidUser() throws Exception {
		for (var request : new MockHttpServletRequestBuilder[] {post("/v1/dogs"), put("/v1/me"),
				delete("/v1/shelters/" + UUID.randomUUID()), get("/actuator/env"), get("/v1/future-admin")}) {
			mvc.perform(auth(request)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void databaseFailureDoesNotExposeInternalDetails() throws Exception {
		when(repository.account(SUBJECT)).thenThrow(new DataAccessResourceFailureException("private-host secret SQL"));
		var response = mvc.perform(auth(get("/v1/me"))).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse();
		assertThat(response.getContentAsString()).doesNotContain("private-host", "secret", "SQL");
		assertThat(json.readTree(response.getContentAsString()).get("requestId").asText()).isEqualTo(response.getHeader("X-Request-ID"));
	}

	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer " + tokens.token(SUBJECT));
	}
}
