package org.shelterconnect.api.chat;

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

@WebMvcTest(ChatController.class)
@Import({ChatService.class,ChatErrorHandler.class,AccountService.class,ApiRequestFilter.class,
		SecurityConfiguration.class,SecurityErrors.class,JwtTestConfiguration.class})
class ChatHttpTest {
	private static final UUID SUBJECT=UUID.randomUUID(),DOG=UUID.randomUUID(),SESSION=UUID.randomUUID(),USER=UUID.randomUUID();
	@Autowired MockMvc mvc; @Autowired JwtTestSupport tokens;
	@MockitoBean ChatRepository repository; @MockitoBean AccountRepository accounts;
	@Test void everyChatRouteRequiresVerifiedAuthentication() throws Exception {
		for(var request:List.of(post("/v1/dogs/"+DOG+"/chat-sessions"),get("/v1/me/chat-sessions"),
				get("/v1/chat-sessions/"+SESSION),get(path()),post(path())))
			mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
					.andExpect(header().string("Cache-Control","no-store"));
		verifyNoInteractions(repository,accounts);
	}
	@Test void onlyImplementedMethodsAreOpened() throws Exception {
		for(var request:List.of(delete("/v1/chat-sessions/"+SESSION),patch(path()),post(path()+"/assistant")))
			mvc.perform(auth(request)).andExpect(status().isForbidden());
		verifyNoInteractions(repository,accounts);
	}
	@Test void malformedInputAndMediaReturnClientErrors() throws Exception {
		mvc.perform(auth(post(path()).contentType("application/json").content("{broken"))).andExpect(status().isBadRequest());
		mvc.perform(auth(post(path()).contentType("text/plain").content("{}"))).andExpect(status().isUnsupportedMediaType());
		mvc.perform(auth(get("/v1/chat-sessions/not-uuid"))).andExpect(status().isBadRequest());
		mvc.perform(auth(post(path()).contentType("application/json").content("{\"text\":\"hello\",\"clientMessageId\":\"r1\",\"dogId\":\"another\"}"))).andExpect(status().isBadRequest());
		verifyNoInteractions(repository,accounts);
	}
	@Test void registeredAccountAndOwnershipAreRequiredBeforeMessagesAreRead() throws Exception {
		mvc.perform(auth(get(path()))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_REGISTERED"));
		verifyNoInteractions(repository);
		when(accounts.account(SUBJECT)).thenReturn(Optional.of(new AccountRepository.Account(USER,"운영자","OPERATOR",false)));
		mvc.perform(auth(get(path()))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT_NOT_FOUND"));
		verify(repository).session(USER,SESSION,false);
		verifyNoMoreInteractions(repository);
	}
	private String path() { return "/v1/chat-sessions/"+SESSION+"/messages"; }
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req) { return req.header("Authorization","Bearer "+tokens.token(SUBJECT)); }
}
