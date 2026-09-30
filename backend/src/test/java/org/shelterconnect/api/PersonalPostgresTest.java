package org.shelterconnect.api;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest(properties={"app.registration.terms-version=2026-10-01","app.registration.privacy-version=2026-10-01"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class) @Transactional
class PersonalPostgresTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JwtTestSupport tokens; @Autowired JsonMapper json;
    UUID subject=UUID.randomUUID(),other=UUID.randomUUID(),user=UUID.randomUUID(),shelter=UUID.randomUUID(),far=UUID.randomUUID(),dog=UUID.randomUUID(),dog2=UUID.randomUUID();
    @BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
    @BeforeEach void fixtures() {
        for(var pair:List.of(new UUID[]{user,subject},new UUID[]{UUID.randomUUID(),other}))
            jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'방문자',?,?)",pair[0],tokens.properties.providerKey(),pair[1].toString());
        for(var s:List.of(shelter,far)) jdbc.update("INSERT INTO shelter.shelters(id,name,region,approval_status,is_public,reviewed_by,reviewed_at,latitude,longitude) VALUES (?,'온기','강원','APPROVED',true,?,now(),?,127)",s,user,s.equals(shelter)?37:38);
        for(var d:List.of(dog,dog2)) jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'두부','dubu',true,'AVAILABLE')",d,shelter);
    }
    @Test void guestsBrowseButCannotReadOrWritePersonalData() throws Exception {
        mvc.perform(get("/v1/shelter-discovery")).andExpect(status().isOk());
        mvc.perform(get("/v1/registration-policy")).andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true));
        for(var request:List.of(get("/v1/me/saved-dogs"),get("/v1/me/preferences"),get("/v1/me/consents"),get("/v1/me/dog-conversations"),put("/v1/me/saved-dogs/"+dog),patch("/v1/me/profile").contentType("application/json").content("{}")))
            mvc.perform(request).andExpect(status().isUnauthorized());
    }
    @Test void profileUsesVerifiedIdentityAndRejectsAuthorityFields() throws Exception {
        body(auth(patch("/v1/me/profile").contentType("application/json").content("{\"displayName\":\"  두부네  \"}")));
        assertThat(body(auth(get("/v1/me"))).at("/data/displayName").asText()).isEqualTo("두부네");
        mvc.perform(auth(patch("/v1/me/profile").contentType("application/json").content("{\"displayName\":\"이웃\",\"role\":\"OPERATOR\"}"))).andExpect(status().isBadRequest());
        assertThat(body(as(get("/v1/me"),other)).at("/data/displayName").asText()).isEqualTo("방문자");
    }
    @Test void savingRetriesAreStableIsolatedAndUnsavePreservesChat() throws Exception {
        jdbc.execute("SET LOCAL ROLE shelter_runtime");
        body(auth(put("/v1/me/saved-dogs/"+dog)));
        var before=body(auth(get("/v1/me/saved-dogs")));
        body(auth(put("/v1/me/saved-dogs/"+dog)));
        assertThat(body(auth(get("/v1/me/saved-dogs")))).isEqualTo(before);
        assertThat(body(as(get("/v1/me/saved-dogs"),other)).get("data").size()).isZero();
        assertThat(body(as(get("/v1/me/saved-dogs/"+dog),other)).at("/data/saved").asBoolean()).isFalse();
        mvc.perform(auth(post("/v1/dogs/"+dog+"/chat-sessions"))).andExpect(status().isCreated());
        body(auth(delete("/v1/me/saved-dogs/"+dog)));body(auth(delete("/v1/me/saved-dogs/"+dog)));
        assertThat(body(auth(get("/v1/me/saved-dogs"))).get("data").size()).isZero();
        assertThat(body(auth(get("/v1/me/dog-conversations"))).get("data").size()).isEqualTo(1);
    }
    @Test void privateDogsAreHiddenAndCursorsCannotCrossAccountsOrFilters() throws Exception {
        body(auth(put("/v1/me/saved-dogs/"+dog)));body(auth(put("/v1/me/saved-dogs/"+dog2)));
        var first=body(auth(get("/v1/me/saved-dogs").param("limit","1")));String cursor=first.get("nextCursor").asText();
        var second=body(auth(get("/v1/me/saved-dogs").param("limit","1").param("cursor",cursor)));
        assertThat(first.at("/data/0/dogId").asText()).isNotEqualTo(second.at("/data/0/dogId").asText());
        mvc.perform(as(get("/v1/me/saved-dogs").param("cursor",cursor),other)).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/v1/me/saved-dogs").param("cursor",cursor).param("q","두부"))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",shelter);
        assertThat(body(auth(get("/v1/me/saved-dogs"))).get("data").size()).isZero();
        mvc.perform(auth(put("/v1/me/saved-dogs/"+dog))).andExpect(status().isNotFound());
        body(auth(delete("/v1/me/saved-dogs/"+dog)));
    }
    @Test void currentShelterChecksPublicationAndDoesNotChangeOtherUsers() throws Exception {
        jdbc.execute("SET LOCAL ROLE shelter_runtime");
        var result=body(auth(put("/v1/me/preferences").contentType("application/json").content("{\"currentShelterId\":\""+shelter+"\"}")));
        assertThat(result.at("/data/currentShelterId").asText()).isEqualTo(shelter.toString());
        assertThat(body(as(get("/v1/me/preferences"),other)).at("/data/currentShelterId").isNull()).isTrue();
        jdbc.execute("RESET ROLE");jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",shelter);
        assertThat(body(auth(get("/v1/me/preferences"))).at("/data/currentShelterId").isNull()).isTrue();
        body(auth(put("/v1/me/preferences").contentType("application/json").content("{\"currentShelterId\":null}")));
    }
    @Test void nearbyDistanceOrderingAndCursorAreAccurate() throws Exception {
        var first=body(get("/v1/shelter-discovery").param("latitude","37").param("longitude","127").param("limit","1"));
        assertThat(first.at("/data/0/id").asText()).isEqualTo(shelter.toString());
        assertThat(first.at("/data/0/distanceMeters").asDouble()).isLessThan(1);
        var second=body(get("/v1/shelter-discovery").param("latitude","37").param("longitude","127").param("limit","1").param("cursor",first.get("nextCursor").asText()));
        assertThat(second.at("/data/0/id").asText()).isEqualTo(far.toString());
        assertThat(second.at("/data/0/distanceMeters").asDouble()).isBetween(111000.0,112000.0);
        assertThat(body(get("/v1/shelter-discovery").param("q","없는이름")).get("data").size()).isZero();
        assertThat(body(get("/v1/shelter-discovery")).at("/data/0/distanceMeters").isNull()).isTrue();
    }
    @Test void invalidCoordinatesQueriesAndLimitsAreClientErrors() throws Exception {
        for(var request:List.of(get("/v1/shelter-discovery").param("latitude","37"),get("/v1/shelter-discovery").param("latitude","NaN").param("longitude","127"),get("/v1/shelter-discovery").param("limit","51"),get("/v1/shelter-discovery").param("q","a\nb"),get("/v1/shelter-discovery").param("latitude","91").param("longitude","0")))
            mvc.perform(request).andExpect(status().isBadRequest());
    }
    @Test void consentsRequireBothChecksCurrentVersionAndKeepOriginalTimestamp() throws Exception {
        jdbc.execute("SET LOCAL ROLE shelter_runtime");
        String payload="{\"termsVersion\":\"2026-10-01\",\"privacyVersion\":\"2026-10-01\",\"termsAccepted\":true,\"privacyAccepted\":true}";
        var first=body(auth(put("/v1/me/consents").contentType("application/json").content(payload)));
        assertThat(body(auth(put("/v1/me/consents").contentType("application/json").content(payload)))).isEqualTo(first);
        assertThat(body(as(get("/v1/me/consents"),other)).path("data").isNull()).isTrue();
        mvc.perform(auth(put("/v1/me/consents").contentType("application/json").content(payload.replace("true","false")))).andExpect(status().isBadRequest());
        mvc.perform(auth(put("/v1/me/consents").contentType("application/json").content(payload.replace("2026-10-01","2025-01-01")))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'shelter.user_consents','UPDATE')",Boolean.class)).isFalse();
    }
    @Test void recentConversationUsesLatestActivityAndPrivateNamesAreRedacted() throws Exception {
        UUID session=UUID.randomUUID(),old=UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.chat_sessions(id,user_id,dog_id,created_at,updated_at) VALUES (?,?,?,'2020-01-01','2026-09-30'),(?,?,?,'2026-01-01','2026-09-29')",session,user,dog,old,user,dog2);
        jdbc.update("INSERT INTO shelter.chat_messages(session_id,dog_id,role,content,client_message_id,processing_status) VALUES (?,?,'USER','안녕','personal-test','PENDING')",session,dog);
        var result=body(auth(get("/v1/me/dog-conversations")));
        assertThat(result.at("/data/0/sessionId").asText()).isEqualTo(session.toString());
        assertThat(result.at("/data/0/lastMessage").asText()).isEqualTo("안녕");
        assertThat(body(as(get("/v1/me/dog-conversations"),other)).get("data").size()).isZero();
        body(auth(put("/v1/me/saved-dogs/"+dog)));
        assertThat(body(auth(get("/v1/me/dog-conversations").param("savedOnly","true"))).get("data").size()).isEqualTo(1);
        jdbc.update("UPDATE shelter.dogs SET is_public=false,name='비공개 이름' WHERE id=?",dog);
        var hidden=body(auth(get("/v1/me/dog-conversations")));
        assertThat(hidden.toString()).doesNotContain("비공개 이름");
        assertThat(hidden.at("/data/0/available").asBoolean()).isFalse();
        assertThat(hidden.at("/data/0/avatarKey").isNull()).isTrue();
    }
    @Test void disabledAccountsCannotUsePersonalRoutes() throws Exception {
        jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
        for(var request:List.of(get("/v1/me/saved-dogs"),get("/v1/me/dog-conversations"),put("/v1/me/saved-dogs/"+dog)))
            mvc.perform(auth(request)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) { return as(request,subject); }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,UUID subject) { return request.header("Authorization","Bearer "+tokens.token(subject)); }
    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception { return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
}
