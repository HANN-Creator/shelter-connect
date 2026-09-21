package org.shelterconnect.api.adoption;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.web.ApiRequestFilter;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdoptionNoteController.class)
@Import({AdoptionNoteService.class, AdoptionNoteErrorHandler.class, AccountService.class, ApiRequestFilter.class,
		SecurityConfiguration.class, SecurityErrors.class, JwtTestConfiguration.class})
class AdoptionNoteHttpTest {
	private static final UUID SUBJECT = UUID.randomUUID(), DOG = UUID.randomUUID(), USER = UUID.randomUUID();
	private static final String ROOT = "/v1/me/adoption-notes", PATH = ROOT + "/" + DOG;
	@Autowired MockMvc mvc; @Autowired JwtTestSupport tokens;
	@MockitoBean AdoptionNoteRepository repository; @MockitoBean AccountRepository accounts;
	@Test void allRoutesRequireAuthenticationAndUnsupportedMethodsStayClosed() throws Exception {
		for (var request : List.of(get(ROOT), get(PATH), put(PATH)))
			mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
					.andExpect(header().string("Cache-Control", "no-store"));
		for (var request : List.of(delete(PATH), patch(PATH), post(ROOT), post(PATH)))
			mvc.perform(auth(request)).andExpect(status().isForbidden());
		verifyNoInteractions(repository, accounts);
	}
	@Test void invalidBodiesAndPathsNeverReachStorage() throws Exception {
		mvc.perform(auth(put(PATH).contentType("application/json").content("{broken"))).andExpect(status().isBadRequest());
		mvc.perform(auth(put(PATH).contentType("text/plain").content("{}"))).andExpect(status().isUnsupportedMediaType());
		mvc.perform(auth(put(PATH).contentType("application/json").content("{}"))).andExpect(status().isBadRequest());
		mvc.perform(auth(put(PATH).contentType("application/json").content("null"))).andExpect(status().isBadRequest());
		mvc.perform(auth(get(ROOT + "/not-uuid"))).andExpect(status().isBadRequest());
		verifyNoInteractions(repository, accounts);
	}
	@Test void operatorHasNoBypassAndMissingNoteDoesNotRevealOtherOwners() throws Exception {
		mvc.perform(auth(get(PATH))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_REGISTERED"));
		when(accounts.account(SUBJECT)).thenReturn(Optional.of(new AccountRepository.Account(USER, "운영자", "OPERATOR", false)));
		mvc.perform(auth(get(PATH))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTE_NOT_FOUND"));
		verify(repository).note(USER, DOG, false); verifyNoMoreInteractions(repository);
	}
	@Test void errorsDoNotExposePrivateNotesOrStorageMessages() throws Exception {
		when(accounts.account(SUBJECT)).thenReturn(Optional.of(new AccountRepository.Account(USER, "사용자", "USER", false)));
		when(repository.note(USER, DOG, false)).thenThrow(new IllegalStateException("private care plan and database details"));
		mvc.perform(auth(get(PATH))).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private care"))));
	}
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req) {
		return req.header("Authorization", "Bearer " + tokens.token(SUBJECT));
	}
}
