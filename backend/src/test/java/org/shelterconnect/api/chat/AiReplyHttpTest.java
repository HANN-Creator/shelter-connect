package org.shelterconnect.api.chat;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.web.ApiRequestFilter;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiReplyController.class)
@Import({AiReplyService.class,ChatErrorHandler.class,AiGrounding.class,ApiRequestFilter.class,
		SecurityConfiguration.class,SecurityErrors.class,JwtTestConfiguration.class})
class AiReplyHttpTest {
	@Autowired MockMvc mvc; @Autowired JwtTestSupport tokens;
	@MockitoBean AiReplyStore store; @MockitoBean AiProvider provider;
	private final UUID subject=UUID.randomUUID(),session=UUID.randomUUID(),message=UUID.randomUUID();
	private String path() { return "/v1/chat-sessions/"+session+"/messages/"+message+"/reply"; }
	@Test void endpointAndUnsupportedMethodsRemainProtected() throws Exception {
		mvc.perform(post(path())).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
		mvc.perform(get(path()).header("Authorization","Bearer "+tokens.token(subject))).andExpect(status().isForbidden());
		verifyNoInteractions(store,provider);
	}
	@Test void invalidInputsDoNotClaimWork() throws Exception {
		for(String body:List.of("null","[]","{\"retry\":\"true\"}","{\"dogId\":\"forged\"}","{\"retry\":true,\"text\":\"forged\"}"))
			mvc.perform(post(path()).header("Authorization","Bearer "+tokens.token(subject)).contentType("application/json").content(body)).andExpect(status().isBadRequest());
		mvc.perform(post(path()).header("Authorization","Bearer "+tokens.token(subject)).contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());
		verifyNoInteractions(store,provider);
	}
	@Test void notConfiguredIsAnExplicit503WithoutCallingProvider() throws Exception {
		when(store.start(subject,session,message,false)).thenThrow(new ChatException(503,"AI_NOT_CONFIGURED","아직 답변을 준비 중이에요."));
		mvc.perform(post(path()).header("Authorization","Bearer "+tokens.token(subject))).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED")).andExpect(jsonPath("$.requestId").isNotEmpty());
		verifyNoInteractions(provider);
	}
}
