package org.shelterconnect.api;

import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.mock.web.MockPart;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.community.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class) @Transactional
class CommunityPostgresTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired JwtTestSupport tokens;@Autowired JsonMapper json;
    @MockitoBean CommunityStorage storage;
    UUID subject=UUID.randomUUID(),other=UUID.randomUUID(),operator=UUID.randomUUID(),user=UUID.randomUUID(),otherUser=UUID.randomUUID(),operatorUser=UUID.randomUUID();
    @BeforeAll static void migrate()throws Exception{SchemaMigrationTest.migratePostgres();}
    @BeforeEach void fixture(){
        for(var pair:List.of(new UUID[]{user,subject},new UUID[]{otherUser,other},new UUID[]{operatorUser,operator}))jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject,role) VALUES (?,'커뮤니티 검사',?,?,?)",pair[0],tokens.properties.providerKey(),pair[1].toString(),pair[1].equals(operator)?"OPERATOR":"USER");
        when(storage.sign(anyString())).thenReturn("https://storage.example.invalid/signed?token=fixture");
    }
    @Test void guestsCannotReadCommunityButSheltersRemainPublic()throws Exception{
        for(var request:List.of(get("/v1/community/posts"),get("/v1/me/community-posts"),get("/v1/community/posts/"+UUID.randomUUID()),post("/v1/community/posts").contentType("application/json").content("{}")))mvc.perform(request).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/shelters")).andExpect(status().isOk());
    }
    @Test void draftsStayPrivateAndRequireCompleteFieldsBeforePublication()throws Exception{
        jdbc.execute("SET LOCAL ROLE shelter_runtime");var created=body(post("/v1/community/posts").contentType("application/json").content(payload("DRAFT","LOST","")),subject);String id=created.at("/data/id").asText();
        assertThat(body(get("/v1/community/posts"),subject).path("data").size()).isZero();
        assertThat(body(get("/v1/me/community-posts").param("publication","DRAFT"),subject).path("data").size()).isEqualTo(1);
        mvc.perform(as(get("/v1/community/posts/"+id),other)).andExpect(status().isNotFound());
        mvc.perform(as(post("/v1/community/posts/"+id+"/publish").contentType("application/json").content("{\"version\":1}"),subject)).andExpect(status().isBadRequest());
        String complete="{\"version\":1,\"category\":\"LOST\",\"title\":\"친구를 찾아요\",\"text\":\"가상 검사\",\"regionLabel\":\"춘천시\",\"location\":{\"label\":\"가상 공원\",\"latitude\":37.8,\"longitude\":127.7,\"occurredAt\":\"2026-09-30T00:00:00Z\"}}";
        body(patch("/v1/community/posts/"+id).contentType("application/json").content(complete),subject);
        assertThat(body(post("/v1/community/posts/"+id+"/publish").contentType("application/json").content("{\"version\":2}"),subject).at("/data/publication").asText()).isEqualTo("PUBLISHED");
        assertThat(body(get("/v1/community/posts/"+id),other).at("/data/content/location/latitude").asDouble()).isEqualTo(37.8);
    }
    @Test void duplicateCreateDoesNotDuplicateAndPayloadChangesConflict()throws Exception{
        String payload=payload("PUBLISHED","NEIGHBOR_NEWS","소식");var first=body(post("/v1/community/posts").contentType("application/json").content(payload),subject);
        assertThat(body(post("/v1/community/posts").contentType("application/json").content(payload),subject)).isEqualTo(first);
        mvc.perform(as(post("/v1/community/posts").contentType("application/json").content(payload.replace("소식","다른 소식")),subject)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }
    @Test void ownershipAndVersionProtectEditsAndSoftDeletion()throws Exception{
        String id=createPost("NEIGHBOR_NEWS");String edit="{\"version\":1,\"category\":\"NEIGHBOR_NEWS\",\"title\":\"수정\",\"text\":\"내용\",\"regionLabel\":\"춘천시\"}";
        mvc.perform(as(patch("/v1/community/posts/"+id).contentType("application/json").content(edit),other)).andExpect(status().isNotFound());
        body(patch("/v1/community/posts/"+id).contentType("application/json").content(edit),subject);
        mvc.perform(as(patch("/v1/community/posts/"+id).contentType("application/json").content(edit),subject)).andExpect(status().isConflict());
        body(delete("/v1/community/posts/"+id).param("version","2"),subject);body(delete("/v1/community/posts/"+id).param("version","2"),subject);
        mvc.perform(as(get("/v1/community/posts/"+id),subject)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.community_posts WHERE id=? AND deleted_at IS NOT NULL",Integer.class,UUID.fromString(id))).isEqualTo(1);
    }
    @Test void feedFiltersAndPagingCannotCrossUsersOrQueries()throws Exception{
        String a=createPost("NEIGHBOR_NEWS"),b=createPost("FOUND");
        var page=body(get("/v1/community/posts").param("limit","1"),subject);String cursor=page.path("nextCursor").asText();
        var next=body(get("/v1/community/posts").param("limit","1").param("cursor",cursor),subject);
        assertThat(page.at("/data/0/id").asText()).isNotEqualTo(next.at("/data/0/id").asText());
        assertThat(body(get("/v1/community/posts").param("category","FOUND").param("region","춘천"),other).path("data").size()).isEqualTo(1);
        assertThat(body(get("/v1/community/posts").param("q","없는 제목"),other).path("data").size()).isZero();
        mvc.perform(as(get("/v1/community/posts").param("cursor",cursor),other)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/v1/community/posts").param("cursor",cursor).param("q","다른"),subject)).andExpect(status().isBadRequest());
    }
    @Test void sightingsRequireLocationAndClosedPostsKeepHistoryButRejectNewSightings()throws Exception{
        String id=createPost("LOST");String payload=sighting();
        var first=body(post("/v1/community/posts/"+id+"/comments").contentType("application/json").content(payload),other);
        body(put("/v1/community/posts/"+id+"/status").contentType("application/json").content("{\"version\":1,\"status\":\"REUNITED\"}"),subject);
        assertThat(body(post("/v1/community/posts/"+id+"/comments").contentType("application/json").content(payload),other)).isEqualTo(first);
        mvc.perform(as(post("/v1/community/posts/"+id+"/comments").contentType("application/json").content(sighting()),other)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIGHTINGS_CLOSED"));
        assertThat(body(get("/v1/community/posts/"+id+"/comments"),subject).path("data").size()).isEqualTo(1);
        assertThat(body(get("/v1/community/posts/"+id),subject).at("/data/sightingCount").asLong()).isEqualTo(1);
    }
    @Test void repliesAreOneLevelWithinSamePostAndDeletedCommentIsATombstone()throws Exception{
        String id=createPost("FOUND"),otherPost=createPost("FOUND");String parent=addComment(id,other,null);
        String child=addComment(id,subject,parent);
        mvc.perform(as(post("/v1/community/posts/"+otherPost+"/comments").contentType("application/json").content(commentPayload(parent)),subject)).andExpect(status().isNotFound());
        mvc.perform(as(post("/v1/community/posts/"+id+"/comments").contentType("application/json").content(commentPayload(child)),subject)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/v1/community/posts/"+id+"/comments/"+parent),subject)).andExpect(status().isNotFound());
        body(delete("/v1/community/posts/"+id+"/comments/"+parent),other);
        var comments=body(get("/v1/community/posts/"+id+"/comments"),subject);
        assertThat(comments.at("/data/0/deleted").asBoolean()).isTrue();assertThat(comments.at("/data/0/content").isNull()).isTrue();assertThat(comments.path("data").size()).isEqualTo(2);
    }
    @Test void reportsArePrivateDeduplicatedAndOnlyOperatorsMayHidePosts()throws Exception{
        String id=createPost("FOUND"),payload="{\"reason\":\"SPAM\",\"details\":\"신고 전용 비공개 설명\"}";
        var receipt=body(post("/v1/community/posts/"+id+"/reports").contentType("application/json").content(payload),other);
        assertThat(body(post("/v1/community/posts/"+id+"/reports").contentType("application/json").content(payload),other)).isEqualTo(receipt);
        assertThat(body(get("/v1/community/posts/"+id),subject).toString()).doesNotContain("신고 전용 비공개 설명");
        mvc.perform(as(get("/v1/operations/community-reports"),subject)).andExpect(status().isForbidden());
        String report=receipt.at("/data/id").asText();assertThat(body(get("/v1/operations/community-reports"),operator).at("/data/0/details").asText()).contains("비공개");
        jdbc.execute("SET LOCAL ROLE shelter_runtime");
        body(put("/v1/operations/community-reports/"+report).contentType("application/json").content("{\"version\":1,\"action\":\"HIDE\",\"note\":\"검사\"}"),operator);
        mvc.perform(as(get("/v1/community/posts/"+id),other)).andExpect(status().isNotFound());
        assertThat(body(get("/v1/community/posts/"+id),subject).at("/data/hidden").asBoolean()).isTrue();
    }
    @Test void regionIsIndependentFromShelterAndOtherUsers()throws Exception{
        jdbc.execute("SET LOCAL ROLE shelter_runtime");body(put("/v1/me/community-region").contentType("application/json").content("{\"regionLabel\":\"춘천시\"}"),subject);
        assertThat(body(get("/v1/me/community-region"),subject).at("/data/regionLabel").asText()).isEqualTo("춘천시");
        assertThat(body(get("/v1/me/preferences"),subject).at("/data/currentShelterId").isNull()).isTrue();
        assertThat(body(get("/v1/me/community-region"),other).at("/data/regionLabel").isNull()).isTrue();
    }
    @Test void normalizedUploadIsPrivateUntilBoundAndRevokedWhenPostIsDeleted()throws Exception{
        jdbc.execute("SET LOCAL ROLE shelter_runtime");UUID request=UUID.randomUUID();byte[] png=png();String media=upload(request,png,subject).at("/data/id").asText();
        assertThat(upload(request,png,subject).at("/data/id").asText()).isEqualTo(media);verify(storage,times(1)).put(anyString(),any());
        mvc.perform(as(get("/v1/community/media/"+media),other)).andExpect(status().isNotFound());
        String p=payload("PUBLISHED","NEIGHBOR_NEWS","사진 소식").replace("\"mediaIds\":[]","\"mediaIds\":[\""+media+"\"]");String post=body(post("/v1/community/posts").contentType("application/json").content(p),subject).at("/data/id").asText();
        assertThat(body(get("/v1/community/media/"+media),other).at("/data/url").asText()).startsWith("https://");
        body(delete("/v1/community/posts/"+post).param("version","1"),subject);
        mvc.perform(as(get("/v1/community/media/"+media),other)).andExpect(status().isNotFound());
        body(get("/v1/community/media/"+media),subject);
    }
    @Test void otherUsersMediaAndMediaAlreadyBoundElsewhereAreRejected()throws Exception{
        String media=upload(UUID.randomUUID(),png(),other).at("/data/id").asText();String p=payload("PUBLISHED","NEIGHBOR_NEWS","소식").replace("\"mediaIds\":[]","\"mediaIds\":[\""+media+"\"]");
        mvc.perform(as(post("/v1/community/posts").contentType("application/json").content(p),subject)).andExpect(status().isNotFound());
        body(post("/v1/community/posts").contentType("application/json").content(p),other);
        mvc.perform(as(post("/v1/community/posts").contentType("application/json").content(p.replace(json.readTree(p).path("clientRequestId").asText(),UUID.randomUUID().toString())),other)).andExpect(status().isConflict());
    }
    @Test void uploadRejectsUnknownPartsAndNonImages()throws Exception{
        var file=new MockPart("file","x.svg","<svg/>".getBytes());var request=new MockPart("clientRequestId",UUID.randomUUID().toString().getBytes());
        mvc.perform(as(multipart("/v1/community/media").part(file,request),subject)).andExpect(status().isBadRequest());
        mvc.perform(as(multipart("/v1/community/media").part(new MockPart("file","x.png",png()),request,new MockPart("ownerId",otherUser.toString().getBytes())),subject)).andExpect(status().isBadRequest());
        verify(storage,never()).put(anyString(),any());
    }
    @Test void disabledAccountsCannotCreateOrSignAndInvalidInputDoesNotWrite()throws Exception{
        String p=payload("PUBLISHED","NEIGHBOR_NEWS","소식");
        mvc.perform(as(post("/v1/community/posts").contentType("text/plain").content(p),subject)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(as(post("/v1/community/posts").contentType("application/json").content(p.replace("\"publication\":\"PUBLISHED\"","\"publication\":\"PUBLISHED\",\"authorId\":\""+otherUser+"\"")),subject)).andExpect(status().isBadRequest());
        String lost=payload("PUBLISHED","LOST","찾기").replace("2026-09-30T00:00:00Z","2099-01-01T00:00:00Z");
        mvc.perform(as(post("/v1/community/posts").contentType("application/json").content(lost),subject)).andExpect(status().isBadRequest());
        jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
        mvc.perform(as(post("/v1/community/posts").contentType("application/json").content(p),subject)).andExpect(status().isForbidden());
    }
    private String createPost(String category)throws Exception{return body(post("/v1/community/posts").contentType("application/json").content(payload("PUBLISHED",category,"가상 소식")),subject).at("/data/id").asText();}
    private String payload(String publication,String category,String title){return "{\"clientRequestId\":\""+UUID.randomUUID()+"\",\"category\":\""+category+"\",\"publication\":\""+publication+"\",\"title\":\""+title+"\",\"text\":\"가상 검사 내용\",\"regionLabel\":\"춘천시\",\"mediaIds\":[]"+(!category.equals("NEIGHBOR_NEWS")?",\"location\":{\"label\":\"가상 공원\",\"occurredAt\":\"2026-09-30T00:00:00Z\"}":"")+"}";}
    private String sighting(){return "{\"clientRequestId\":\""+UUID.randomUUID()+"\",\"kind\":\"SIGHTING\",\"text\":\"목격했어요\",\"location\":{\"label\":\"가상 공원\",\"occurredAt\":\"2026-09-30T00:00:00Z\"}}";}
    private String commentPayload(String parent){return "{\"clientRequestId\":\""+UUID.randomUUID()+"\",\"kind\":\"COMMENT\",\"text\":\"댓글 내용\""+(parent==null?"":",\"parentId\":\""+parent+"\"")+"}";}
    private String addComment(String post,UUID who,String parent)throws Exception{return body(post("/v1/community/posts/"+post+"/comments").contentType("application/json").content(commentPayload(parent)),who).at("/data/id").asText();}
    private byte[] png()throws Exception{var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",output);return output.toByteArray();}
    private JsonNode upload(UUID request,byte[] bytes,UUID who)throws Exception{return body(multipart("/v1/community/media").part(new MockPart("file","fixture.png",bytes),new MockPart("clientRequestId",request.toString().getBytes())),who);}
    private AbstractMockHttpServletRequestBuilder<?> as(AbstractMockHttpServletRequestBuilder<?> request,UUID who){return request.header("Authorization","Bearer "+tokens.token(who));}
    private JsonNode body(AbstractMockHttpServletRequestBuilder<?> request,UUID who)throws Exception{return json.readTree(mvc.perform(as(request,who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
}
