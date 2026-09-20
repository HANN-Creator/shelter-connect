package org.shelterconnect.api.management;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

@WebMvcTest(ManagementController.class)
@Import({ManagementService.class, ManagementErrorHandler.class, ShelterAccessService.class, AccountService.class,
		ApiRequestFilter.class, SecurityConfiguration.class, SecurityErrors.class, JwtTestConfiguration.class})
class ManagementHttpTest {
	private static final UUID SUBJECT=UUID.randomUUID(), USER=UUID.randomUUID(), SHELTER=UUID.randomUUID(), DOG=UUID.randomUUID();
	@Autowired MockMvc mvc;
	@Autowired JwtTestSupport tokens;
	@MockitoBean ManagementRepository repository;
	@MockitoBean AccountRepository accounts;

	@Test void everyManagementRouteRequiresAuthentication() throws Exception {
		for (var request : List.of(post("/v1/shelter-admin/dogs"), patch("/v1/shelter-admin/dogs/"+DOG),
				get("/v1/shelter-admin/dogs/"+DOG), get("/v1/shelter-admin/shelters/"+SHELTER+"/dogs"),
				get("/v1/shelter-admin/dogs/"+DOG+"/observations"), post("/v1/shelter-admin/dogs/"+DOG+"/observations"),
				patch("/v1/shelter-admin/dogs/"+DOG+"/observations/"+UUID.randomUUID()))) {
			mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
					.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.requestId").isNotEmpty());
		}
		verifyNoInteractions(repository, accounts);
	}
	@Test void malformedBodyAndUnsupportedMediaReturnClientErrors() throws Exception {
		mvc.perform(auth(post("/v1/shelter-admin/dogs").contentType("application/json").content("{broken")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(auth(post("/v1/shelter-admin/dogs").contentType("text/plain").content("{}")))
				.andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
		mvc.perform(auth(get("/v1/shelter-admin/dogs/not-a-uuid"))).andExpect(status().isBadRequest());
		verifyNoInteractions(repository, accounts);
	}
	@Test void missingMembershipDeniesWritesBeforeTouchingDogData() throws Exception {
		when(accounts.account(SUBJECT)).thenReturn(Optional.of(new AccountRepository.Account(USER,"방문자","USER",false)));
		mvc.perform(auth(post("/v1/shelter-admin/dogs").contentType("application/json")
				.content("{\"shelterId\":\""+SHELTER+"\",\"name\":\"봄이\",\"avatarKey\":\"bomi\"}")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(auth(patch("/v1/shelter-admin/dogs/"+DOG).contentType("application/json").content("{}")))
				.andExpect(status().isForbidden());
		verifyNoInteractions(repository);
	}
	@Test void deletingOrChangingUnsupportedResourcesStaysClosed() throws Exception {
		mvc.perform(auth(delete("/v1/shelter-admin/dogs/"+DOG))).andExpect(status().isForbidden());
		mvc.perform(auth(post("/v1/shelter-admin/shelters"))).andExpect(status().isForbidden());
		verifyNoInteractions(repository, accounts);
	}
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer "+tokens.token(SUBJECT));
	}
}
