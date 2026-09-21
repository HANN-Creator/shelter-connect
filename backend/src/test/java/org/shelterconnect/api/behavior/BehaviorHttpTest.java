package org.shelterconnect.api.behavior;

import java.util.List;
import java.util.UUID;
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

@WebMvcTest(BehaviorController.class)
@Import({BehaviorErrorHandler.class, ApiRequestFilter.class,SecurityConfiguration.class,SecurityErrors.class,JwtTestConfiguration.class})
class BehaviorHttpTest {
	private final UUID dog=UUID.randomUUID(),subject=UUID.randomUUID();
	private final String path="/v1/shelter-admin/dogs/"+dog+"/behavior",pub="/v1/dogs/"+dog+"/behavior";
	@Autowired MockMvc mvc; @Autowired JwtTestSupport tokens;
	@MockitoBean BehaviorService service;
	@Test void adminRequiresJwtAndUnsupportedMethodsStayClosed() throws Exception {
		for(var req:List.of(get(path),put(path),post(path+"/confirmation"))) mvc.perform(req).andExpect(status().isUnauthorized());
		mvc.perform(put(path).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
		for(var req:List.of(delete(path),patch(path),put(pub),head(path),post(pub+"/confirmation")))
			mvc.perform(req.header("Authorization","Bearer "+tokens.token(subject))).andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}
	@Test void publicReadsNeedNoLoginAndExposeOnlyPlayback() throws Exception {
		when(service.playback(dog.toString())).thenReturn(new BehaviorTypes.Playback(dog,1,"DEFAULT",null,BehaviorInput.defaults()));
		mvc.perform(get(pub)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
			.andExpect(jsonPath("$.data.settings.actions.WALK.weight").value(30)).andExpect(jsonPath("$.data.evidenceObservationIds").doesNotExist())
			.andExpect(jsonPath("$.data.confirmedBy").doesNotExist());
		mvc.perform(head(pub)).andExpect(status().isOk());
		mvc.perform(get(pub).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
	}
	@Test void malformedJsonAndMediaAreClientErrors() throws Exception {
		mvc.perform(put(path).header("Authorization","Bearer "+tokens.token(subject)).contentType("application/json").content("{broken"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(put(path).header("Authorization","Bearer "+tokens.token(subject)).contentType("text/plain").content("{}"))
			.andExpect(status().isUnsupportedMediaType());
		verifyNoInteractions(service);
	}
	@Test void errorsIncludeRequestIdWithoutInternalData() throws Exception {
		when(service.playback(dog.toString())).thenThrow(new BehaviorException(404,"DOG_NOT_FOUND","강아지를 찾을 수 없어요."));
		mvc.perform(get(pub)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOG_NOT_FOUND"))
			.andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(header().string("Cache-Control","no-store"));
	}
}
