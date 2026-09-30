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
import org.shelterconnect.api.community.CommunityStorage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class) @Transactional
class InquiryPostgresTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired JwtTestSupport tokens;@Autowired JsonMapper json;
    @MockitoBean CommunityStorage storage;
    UUID author=UUID.randomUUID(),requester=UUID.randomUUID(),third=UUID.randomUUID(),authorUser=UUID.randomUUID(),requesterUser=UUID.randomUUID(),thirdUser=UUID.randomUUID();
    @BeforeAll static void migrate()throws Exception{SchemaMigrationTest.migratePostgres();}
    @BeforeEach void setup(){
        for(var pair:List.of(new UUID[]{authorUser,author},new UUID[]{requesterUser,requester},new UUID[]{thirdUser,third}))jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject,role) VALUES (?,?,?,?,?)",pair[0],pair[1].equals(author)?"별빛 이웃":"문의 이웃",tokens.properties.providerKey(),pair[1].toString(),pair[1].equals(third)?"OPERATOR":"USER");
        when(storage.sign(anyString())).thenReturn("https://storage.example.invalid/inquiry?token=fixture");
    }
    @Test void guestAndThirdPartyCannotAccessEvenIfOperator()throws Exception{
        String p=createPost(),r=open(p,requester);
        for(var req:List.of(get("/v1/inquiry-rooms"),get(path(r)),get(path(r)+"/messages"),post("/v1/community/posts/"+p+"/inquiries")))mvc.perform(req).andExpect(status().isUnauthorized());
        for(var req:List.of(get(path(r)),get(path(r)+"/messages"),post(path(r)+"/messages").contentType("application/json").content(text("비공개")),put(path(r)+"/read").contentType("application/json").content("{\"upToSequence\":0}")))mvc.perform(as(req,third)).andExpect(status().isNotFound());
        assertThat(body(get("/v1/inquiry-rooms"),third).path("data").size()).isZero();
    }
    @Test void openReusesOnlySamePostAndRequesterAndDoesNotAllowOwnPost()throws Exception{
        jdbc.execute("SET LOCAL ROLE shelter_runtime");String p=createPost(),r=open(p,requester);
        assertThat(open(p,requester)).isEqualTo(r);assertThat(open(p,third)).isNotEqualTo(r);assertThat(open(createPost(),requester)).isNotEqualTo(r);
        mvc.perform(as(post("/v1/community/posts/"+p+"/inquiries"),author)).andExpect(status().isBadRequest());
        var room=body(get(path(r)),requester).path("data");assertThat(room.at("/counterpart/nickname").asText()).isEqualTo("별빛 이웃");assertThat(room.at("/post/title").asText()).isEqualTo("친구 찾기");
    }
    @Test void messagesAreIdempotentAndCountOnlyIncomingUnread()throws Exception{
        jdbc.execute("SET LOCAL ROLE shelter_runtime");String r=open(createPost(),requester),payload=text("문의할게요");
        var first=send(r,requester,payload);assertThat(send(r,requester,payload)).isEqualTo(first);
        mvc.perform(as(post(path(r)+"/messages").contentType("application/json").content(payload.replace("문의할게요","다른 내용")),requester)).andExpect(status().isConflict());
        assertThat(body(get(path(r)),author).at("/data/unreadCount").asLong()).isEqualTo(1);assertThat(body(get(path(r)),requester).at("/data/unreadCount").asLong()).isZero();
        send(r,author,text("답변"));var room=body(get(path(r)),requester).path("data");assertThat(room.path("unreadCount").asLong()).isEqualTo(1);assertThat(room.path("lastSequence").asLong()).isEqualTo(2);assertThat(room.at("/lastMessage/mine").asBoolean()).isFalse();
    }
    @Test void sequencePagesAreOrderedAndReadingDoesNotImplicitlyMarkRead()throws Exception{
        String r=open(createPost(),requester);for(int i=0;i<5;i++)send(r,author,text("메시지 "+i));
        var latest=body(get(path(r)+"/messages").param("limit","2"),requester);assertThat(latest.at("/data/0/sequence").asLong()).isEqualTo(4);assertThat(latest.at("/data/1/sequence").asLong()).isEqualTo(5);assertThat(latest.path("olderBeforeSequence").asLong()).isEqualTo(4);
        var older=body(get(path(r)+"/messages").param("limit","2").param("beforeSequence","4"),requester);assertThat(older.at("/data/0/sequence").asLong()).isEqualTo(2);assertThat(older.at("/data/1/sequence").asLong()).isEqualTo(3);
        var next=body(get(path(r)+"/messages").param("limit","2").param("afterSequence","1"),requester);assertThat(next.at("/data/0/sequence").asLong()).isEqualTo(2);assertThat(next.path("nextAfterSequence").asLong()).isEqualTo(3);assertThat(next.path("hasMore").asBoolean()).isTrue();
        assertThat(body(get(path(r)),requester).at("/data/unreadCount").asLong()).isEqualTo(5);
        mvc.perform(as(get(path(r)+"/messages").param("beforeSequence","3").param("afterSequence","1"),requester)).andExpect(status().isBadRequest());
        mvc.perform(as(get(path(r)+"/messages").param("afterSequence","-1"),requester)).andExpect(status().isBadRequest());
    }
    @Test void readSequenceNeverMovesBackwardOrAcknowledgesFutureMessages()throws Exception{
        jdbc.execute("SET LOCAL ROLE shelter_runtime");String r=open(createPost(),requester);for(int i=0;i<3;i++)send(r,author,text("내용"));
        assertThat(read(r,requester,2).at("/data/unreadCount").asLong()).isEqualTo(1);assertThat(read(r,requester,1).at("/data/readSequence").asLong()).isEqualTo(2);
        assertThat(body(get(path(r)),author).at("/data/counterpartReadSequence").asLong()).isEqualTo(2);
        mvc.perform(as(put(path(r)+"/read").contentType("application/json").content("{\"upToSequence\":4}"),requester)).andExpect(status().isBadRequest());
        send(r,author,text("새 메시지"));assertThat(body(get(path(r)),requester).at("/data/unreadCount").asLong()).isEqualTo(2);
    }
    @Test void listSearchUnreadRoomCountAndCursorAreScopedToCurrentUser()throws Exception{
        String r1=open(createPost(),requester),r2=open(createPost(),requester);send(r1,author,text("답변1"));send(r1,author,text("답변2"));send(r2,author,text("답변3"));
        var page=body(get("/v1/inquiry-rooms").param("q","별빛").param("limit","1"),requester);assertThat(page.path("unreadRoomCount").asLong()).isEqualTo(2);assertThat(page.at("/data/0/id").asText()).isEqualTo(r2);
        String cursor=page.path("nextCursor").asText();assertThat(body(get("/v1/inquiry-rooms").param("q","별빛").param("limit","1").param("cursor",cursor),requester).at("/data/0/id").asText()).isEqualTo(r1);
        mvc.perform(as(get("/v1/inquiry-rooms").param("q","별빛").param("cursor",cursor),author)).andExpect(status().isBadRequest());
        read(r1,requester,2);var unread=body(get("/v1/inquiry-rooms").param("unreadOnly","true"),requester);assertThat(unread.path("data").size()).isEqualTo(1);assertThat(unread.path("unreadRoomCount").asLong()).isEqualTo(1);
        assertThat(body(get("/v1/inquiry-rooms").param("q","없는 이름"),requester).path("unreadRoomCount").asLong()).isEqualTo(1);
    }
    @Test void closedPostRetainsExistingConversationsButRejectsNewRequesters()throws Exception{
        String p=createPost(),r=open(p,requester);body(put("/v1/community/posts/"+p+"/status").contentType("application/json").content("{\"version\":1,\"status\":\"REUNITED\"}"),author);
        assertThat(open(p,requester)).isEqualTo(r);mvc.perform(as(post("/v1/community/posts/"+p+"/inquiries"),third)).andExpect(status().isConflict());
        send(r,requester,text("찾아서 다행이에요"));assertThat(body(get(path(r)),requester).at("/data/post/status").asText()).isEqualTo("REUNITED");
    }
    @Test void deletedAndHiddenPostsRetainPrivateHistoryButBecomeReadOnly()throws Exception{
        for(boolean hidden:List.of(false,true)){
            String p=createPost(),r=open(p,requester),payload=text("이전 기록");var sent=send(r,requester,payload);
            if(hidden)jdbc.update("UPDATE shelter.community_posts SET hidden_at=now() WHERE id=?",UUID.fromString(p));else body(delete("/v1/community/posts/"+p).param("version","1"),author);
            var room=body(get(path(r)),requester).path("data");assertThat(room.path("canSend").asBoolean()).isFalse();assertThat(room.at("/post/available").asBoolean()).isFalse();assertThat(room.at("/post/title").asText()).isEqualTo("현재 볼 수 없는 게시글");
            assertThat(body(get(path(r)+"/messages"),author).at("/data/0/text").asText()).isEqualTo("이전 기록");assertThat(send(r,requester,payload)).isEqualTo(sent);
            mvc.perform(as(post(path(r)+"/messages").contentType("application/json").content(text("새 기록")),requester)).andExpect(status().isConflict());
        }
    }
    @Test void disabledCounterpartIsRedactedAndCannotReceiveNewMessages()throws Exception{
        String r=open(createPost(),requester);send(r,author,text("이전 답변"));jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",authorUser);
        var room=body(get(path(r)),requester).path("data");assertThat(room.at("/counterpart/nickname").asText()).isEqualTo("이웃");assertThat(room.path("canSend").asBoolean()).isFalse();
        mvc.perform(as(post(path(r)+"/messages").contentType("application/json").content(text("새 문의")),requester)).andExpect(status().isConflict());mvc.perform(as(get(path(r)),author)).andExpect(status().isForbidden());
    }
    @Test void sharedPhotoIsVisibleOnlyToParticipantsAndCannotBeRebound()throws Exception{
        jdbc.execute("SET LOCAL ROLE shelter_runtime");String r=open(createPost(),requester),r2=open(createPost(),requester),media=upload(requester);
        mvc.perform(as(get("/v1/community/media/"+media),author)).andExpect(status().isNotFound());
        send(r,requester,image(media));body(get("/v1/community/media/"+media),author);mvc.perform(as(get("/v1/community/media/"+media),third)).andExpect(status().isNotFound());
        mvc.perform(as(post(path(r2)+"/messages").contentType("application/json").content(image(media)),requester)).andExpect(status().isConflict());
        mvc.perform(as(post(path(r)+"/messages").contentType("application/json").content(image(media)),author)).andExpect(status().isNotFound());
        String payload=postPayload().replace("\"mediaIds\":[]","\"mediaIds\":[\""+media+"\"]");mvc.perform(as(post("/v1/community/posts").contentType("application/json").content(payload),requester)).andExpect(status().isConflict());
    }
    @Test void locationAndMessageKindsValidateBeforeWriting()throws Exception{
        String r=open(createPost(),requester);String location="{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"kind\":\"LOCATION\",\"location\":{\"label\":\"가상 공원 입구\",\"latitude\":37.8,\"longitude\":127.7}}";
        assertThat(send(r,requester,location).at("/data/location/latitude").asDouble()).isEqualTo(37.8);
        for(String bad:List.of(location.replace("37.8","137.8"),location.replace("\"longitude\":127.7","\"unknown\":1"),text("").replace("\"TEXT\"","\"IMAGE\""),text("").replace("\"TEXT\"","\"TEXT\",\"senderId\":\""+authorUser+"\"")))mvc.perform(as(post(path(r)+"/messages").contentType("application/json").content(bad),requester)).andExpect(status().isBadRequest());
        assertThat(body(get(path(r)+"/messages"),author).path("data").size()).isEqualTo(1);
    }
    @Test void runtimeCannotRewriteOrDeleteDeliveredMessagesAndClientsCannotReadTables(){
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('shelter_runtime','shelter.inquiry_messages','UPDATE,DELETE')",Boolean.class)).isFalse();
        for(String role:List.of("anon","authenticated"))for(String table:List.of("inquiry_rooms","inquiry_messages"))assertThat(jdbc.queryForObject("SELECT has_table_privilege(?,?,'SELECT,INSERT,UPDATE,DELETE')",Boolean.class,role,"shelter."+table)).isFalse();
    }
    private String postPayload(){return "{\"clientRequestId\":\""+UUID.randomUUID()+"\",\"category\":\"LOST\",\"publication\":\"PUBLISHED\",\"title\":\"친구 찾기\",\"text\":\"가상 검사\",\"regionLabel\":\"춘천시\",\"mediaIds\":[],\"location\":{\"label\":\"가상 공원\",\"occurredAt\":\"2026-09-30T00:00:00Z\"}}";}
    private String createPost()throws Exception{return body(post("/v1/community/posts").contentType("application/json").content(postPayload()),author).at("/data/id").asText();}
    private String open(String p,UUID who)throws Exception{return body(post("/v1/community/posts/"+p+"/inquiries"),who).at("/data/id").asText();}
    private String text(String value){return "{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"kind\":\"TEXT\",\"text\":\""+value+"\"}";}
    private String image(String id){return "{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"kind\":\"IMAGE\",\"mediaId\":\""+id+"\"}";}
    private String path(String room){return "/v1/inquiry-rooms/"+room;}
    private JsonNode send(String room,UUID who,String value)throws Exception{return body(post(path(room)+"/messages").contentType("application/json").content(value),who);}
    private JsonNode read(String room,UUID who,long sequence)throws Exception{return body(put(path(room)+"/read").contentType("application/json").content("{\"upToSequence\":"+sequence+"}"),who);}
    private String upload(UUID who)throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",out);return body(multipart("/v1/community/media").part(new MockPart("file","fixture.png",out.toByteArray()),new MockPart("clientRequestId",UUID.randomUUID().toString().getBytes())),who).at("/data/id").asText();}
    private AbstractMockHttpServletRequestBuilder<?> as(AbstractMockHttpServletRequestBuilder<?> request,UUID who){return request.header("Authorization","Bearer "+tokens.token(who));}
    private JsonNode body(AbstractMockHttpServletRequestBuilder<?> request,UUID who)throws Exception{return json.readTree(mvc.perform(as(request,who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
}
