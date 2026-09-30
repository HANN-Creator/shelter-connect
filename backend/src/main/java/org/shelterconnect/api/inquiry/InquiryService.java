package org.shelterconnect.api.inquiry;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.shelterconnect.api.auth.AccountService;
import org.shelterconnect.api.community.*;
import org.shelterconnect.api.web.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.shelterconnect.api.inquiry.InquiryTypes.*;
import static org.shelterconnect.api.web.FeatureInput.*;
import static org.shelterconnect.api.community.CommunityInput.*;

@Service @Transactional(readOnly=true)
public class InquiryService {
    private static final String MEMBER="(r.author_id=:user OR r.requester_id=:user)";
    private static final String READ="CASE WHEN r.author_id=:user THEN r.author_read_sequence ELSE r.requester_read_sequence END";
    private static final String UNREAD="(SELECT count(*) FROM shelter.inquiry_messages im WHERE im.room_id=r.id AND im.sender_id<>:user AND im.sequence>("+READ+"))";
    private static final String AVAILABLE="p.publication='PUBLISHED' AND p.hidden_at IS NULL AND p.deleted_at IS NULL AND a.disabled_at IS NULL";
    private static final String SELECT="SELECT r.*,other.id other_id,other.display_name other_name,other.disabled_at other_disabled,("+AVAILABLE+") post_available,p.content post_content,p.category,p.status,m.id m_id,m.room_id m_room_id,m.sequence m_sequence,m.sender_id m_sender_id,m.kind m_kind,m.text m_text,m.media_id m_media_id,m.location m_location,m.created_at m_created_at,"+UNREAD+" unread_count FROM shelter.inquiry_rooms r JOIN shelter.community_posts p ON p.id=r.post_id JOIN shelter.app_users a ON a.id=p.author_id JOIN shelter.app_users other ON other.id=CASE WHEN r.author_id=:user THEN r.requester_id ELSE r.author_id END LEFT JOIN LATERAL (SELECT * FROM shelter.inquiry_messages WHERE room_id=r.id ORDER BY sequence DESC LIMIT 1) m ON true";
    private final JdbcClient jdbc;private final AccountService accounts;private final CommunityService community;private final CommunityMediaStore media;private final JsonMapper json;
    public InquiryService(JdbcClient jdbc,AccountService accounts,CommunityService community,CommunityMediaStore media,JsonMapper json){this.jdbc=jdbc;this.accounts=accounts;this.community=community;this.media=media;this.json=json;}
    @Transactional public Room open(UUID subject,UUID post) {
        UUID user=accounts.lockProfile(subject,false).id();var target=community.publicLock(user,post);
        if(target.authorId().equals(user))throw new FeatureException(400,"SELF_INQUIRY","본인 글에는 문의방을 만들 수 없어요.");
        var existing=jdbc.sql("SELECT id FROM shelter.inquiry_rooms WHERE post_id=:post AND requester_id=:user").param("post",post).param("user",user).query(UUID.class).optional();
        if(existing.isPresent())return read(user,existing.get());
        if(!target.status().equals("ACTIVE"))throw new FeatureException(409,"INQUIRIES_CLOSED","새 문의가 마감된 글이에요.");
        UUID id=jdbc.sql("INSERT INTO shelter.inquiry_rooms(post_id,author_id,requester_id) VALUES(:post,:author,:user) RETURNING id").param("post",post).param("author",target.authorId()).param("user",user).query(UUID.class).single();return read(user,id);
    }
    public Room room(UUID subject,UUID id){return read(accounts.profile(subject).id(),id);}
    private Room read(UUID user,UUID id){return jdbc.sql(SELECT+" WHERE r.id=:id AND "+MEMBER).param("id",id).param("user",user).query((rs,n)->room(rs,user)).optional().orElseThrow(FeatureException::missing);}
    public Rooms rooms(UUID subject,String search,boolean unreadOnly,String cursor,String size) {
        UUID user=accounts.profile(subject).id();String q=query(search);int count=limit(size);String scope=scope("inquiry-rooms",user,q,unreadOnly);var after=CommunityService.pagePosition(cursor,scope);
        String sql=SELECT+" WHERE "+MEMBER+" AND (:q='' OR (other.disabled_at IS NULL AND strpos(lower(other.display_name),lower(:q))>0) OR (("+AVAILABLE+") AND strpos(lower(p.content->>'title'),lower(:q))>0))";
        if(unreadOnly)sql+=" AND "+UNREAD+">0";if(after!=null)sql+=" AND (r.updated_at,r.id)<(:at,:id)";
        var stmt=jdbc.sql(sql+" ORDER BY r.updated_at DESC,r.id DESC LIMIT :limit").param("user",user).param("q",q).param("limit",count+1);
        if(after!=null)stmt=stmt.param("at",after.at().atOffset(ZoneOffset.UTC)).param("id",after.id());
        var rows=stmt.query((rs,n)->room(rs,user)).list();var data=rows.stream().limit(count).toList();var last=data.isEmpty()?null:data.getLast();
        long unread=jdbc.sql("SELECT count(*) FROM shelter.inquiry_rooms r WHERE "+MEMBER+" AND EXISTS(SELECT 1 FROM shelter.inquiry_messages im WHERE im.room_id=r.id AND im.sender_id<>:user AND im.sequence>("+READ+"))").param("user",user).query(Long.class).single();
        return new Rooms(data,rows.size()>count?cursor(scope,last.updatedAt()+"|"+last.id()):null,unread);
    }
    public Messages messages(UUID subject,UUID room,String before,String after,String size) {
        UUID user=accounts.profile(subject).id();read(user,room);int count=limit(size);
        if(before!=null&&after!=null)throw FeatureException.invalid();Long beforeSeq=sequence(before,false),afterSeq=sequence(after,true);
        String sql="SELECT * FROM shelter.inquiry_messages WHERE room_id=:room";
        if(beforeSeq!=null)sql+=" AND sequence<:before";if(afterSeq!=null)sql+=" AND sequence>:after";
        var stmt=jdbc.sql(sql+" ORDER BY sequence "+(afterSeq==null?"DESC":"ASC")+" LIMIT :limit").param("room",room).param("limit",count+1);
        if(beforeSeq!=null)stmt=stmt.param("before",beforeSeq);if(afterSeq!=null)stmt=stmt.param("after",afterSeq);
        var rows=stmt.query((rs,n)->message(rs,user)).list();boolean more=rows.size()>count;var data=new ArrayList<>(rows.stream().limit(count).toList());if(afterSeq==null)Collections.reverse(data);
        Long older=afterSeq==null&&more?data.getFirst().sequence():null;
        Long next=afterSeq!=null?(data.isEmpty()?afterSeq:data.getLast().sequence()):null;
        return new Messages(List.copyOf(data),older,next,more);
    }
    @Transactional public Message send(UUID subject,UUID room,JsonNode body) {
        fields(body,"clientMessageId","kind","text","mediaId","location");UUID client=id(text(body,"clientMessageId",36));String kind=choice(text(body,"kind",10),"TEXT","IMAGE","LOCATION");
        String text=optionalText(body,"text",1000);UUID image=body.path("mediaId").isNull()||!body.has("mediaId")?null:id(text(body,"mediaId",36));SharedLocation location=null;
        if(body.has("location")&&!body.path("location").isNull()) {
            fields(body.get("location"),"label","latitude","longitude");var loc=CommunityInput.location(body.get("location"),false);
            if(loc.latitude()==null||loc.longitude()==null)throw FeatureException.invalid();location=new SharedLocation(loc.label(),loc.latitude(),loc.longitude());
        }
        if(kind.equals("TEXT")&&(text.isEmpty()||image!=null||location!=null) || kind.equals("IMAGE")&&(image==null||location!=null) || kind.equals("LOCATION")&&(location==null||image!=null))throw FeatureException.invalid();
        UUID user=accounts.lockProfile(subject,false).id();lock(user,room);var target=read(user,room);
        String hash=scope(kind,text,image,location);var existing=jdbc.sql("SELECT * FROM shelter.inquiry_messages WHERE room_id=:room AND sender_id=:user AND client_message_id=:client")
            .param("room",room).param("user",user).param("client",client).query((rs,n)->new Existing(rs.getString("request_hash"),message(rs,user))).optional();
        if(existing.isPresent()){if(!existing.get().hash().equals(hash))throw new FeatureException(409,"MESSAGE_ID_CONFLICT","같은 메시지 ID에 다른 내용을 보낼 수 없어요.");return existing.get().message();}
        jdbc.sql("SELECT id FROM shelter.community_posts WHERE id=:post FOR SHARE").param("post",target.postId()).query(UUID.class).single();
        if(jdbc.sql("SELECT id FROM shelter.app_users WHERE id=:user AND disabled_at IS NULL FOR SHARE").param("user",target.counterpart().id()).query(UUID.class).optional().isEmpty())throw cannotSend();
        target=read(user,room);if(!target.canSend())throw cannotSend();
        if(image!=null)media.bindRoom(user,room,image);
        long sequence=target.lastSequence()+1;UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO shelter.inquiry_messages(id,room_id,sequence,sender_id,client_message_id,request_hash,kind,text,media_id,location) VALUES(:id,:room,:sequence,:user,:client,:hash,:kind,:text,:media,CAST(:location AS jsonb))")
            .param("id",id).param("room",room).param("sequence",sequence).param("user",user).param("client",client).param("hash",hash).param("kind",kind).param("text",text).param("media",image,Types.OTHER).param("location",location==null?null:json.writeValueAsString(location),Types.VARCHAR).update();
        jdbc.sql("UPDATE shelter.inquiry_rooms SET last_sequence=:sequence,updated_at=clock_timestamp() WHERE id=:room").param("sequence",sequence).param("room",room).update();
        return jdbc.sql("SELECT * FROM shelter.inquiry_messages WHERE id=:id").param("id",id).query((rs,n)->message(rs,user)).single();
    }
    @Transactional public ReadState markRead(UUID subject,UUID room,JsonNode body) {
        fields(body,"upToSequence");var value=body.path("upToSequence");if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0)throw FeatureException.invalid();long sequence=value.asLong();
        UUID user=accounts.lockProfile(subject,false).id();lock(user,room);var target=read(user,room);if(sequence>target.lastSequence())throw FeatureException.invalid();
        jdbc.sql("UPDATE shelter.inquiry_rooms SET author_read_sequence=CASE WHEN author_id=:user THEN greatest(author_read_sequence,:sequence) ELSE author_read_sequence END, requester_read_sequence=CASE WHEN requester_id=:user THEN greatest(requester_read_sequence,:sequence) ELSE requester_read_sequence END WHERE id=:room")
            .param("user",user).param("sequence",sequence).param("room",room).update();var updated=read(user,room);return new ReadState(room,updated.readSequence(),updated.unreadCount());
    }
    private void lock(UUID user,UUID room){jdbc.sql("SELECT r.id FROM shelter.inquiry_rooms r WHERE r.id=:room AND "+MEMBER+" FOR UPDATE").param("room",room).param("user",user).query(UUID.class).optional().orElseThrow(FeatureException::missing);}
    private record Existing(String hash,Message message) {}
    private static FeatureException cannotSend(){return new FeatureException(409,"INQUIRY_READ_ONLY","기존 문의 기록만 볼 수 있어요.");}
    private static Long sequence(String value,boolean zero){if(value==null)return null;try{if(!value.matches("[0-9]{1,18}"))throw new IllegalArgumentException();long seq=Long.parseLong(value);if(seq<(zero?0:1))throw new IllegalArgumentException();return seq;}catch(Exception ex){throw FeatureException.invalid();}}
    private Room room(ResultSet rs,UUID user)throws SQLException {
        UUID id=rs.getObject("id",UUID.class),postId=rs.getObject("post_id",UUID.class);boolean author=rs.getObject("author_id",UUID.class).equals(user),available=rs.getBoolean("post_available"),otherAvailable=rs.getTimestamp("other_disabled")==null;
        var content=available?json.readValue(rs.getString("post_content"),CommunityTypes.Content.class):null;
        var context=new PostContext(postId,available,available?content.title():"현재 볼 수 없는 게시글",available?rs.getString("category"):null,available?rs.getString("status"):null,available&&!content.mediaIds().isEmpty()?content.mediaIds().getFirst():null);
        var counterpart=new Counterpart(rs.getObject("other_id",UUID.class),otherAvailable?rs.getString("other_name"):"이웃",null,otherAvailable);
        var last=rs.getObject("m_id")==null?null:message(rs,user,"m_");
        return new Room(id,postId,counterpart,context,last,rs.getLong("unread_count"),rs.getLong("last_sequence"),rs.getLong(author?"author_read_sequence":"requester_read_sequence"),rs.getLong(author?"requester_read_sequence":"author_read_sequence"),available&&otherAvailable,rs.getTimestamp("updated_at").toInstant());
    }
    private Message message(ResultSet rs,UUID user)throws SQLException {return message(rs,user,"");}
    private Message message(ResultSet rs,UUID user,String prefix)throws SQLException {
        UUID sender=rs.getObject(prefix+"sender_id",UUID.class);String location=rs.getString(prefix+"location");
        return new Message(rs.getObject(prefix+"id",UUID.class),rs.getObject(prefix+"room_id",UUID.class),rs.getLong(prefix+"sequence"),sender,sender.equals(user),rs.getString(prefix+"kind"),rs.getString(prefix+"text"),rs.getObject(prefix+"media_id",UUID.class),location==null?null:json.readValue(location,SharedLocation.class),rs.getTimestamp(prefix+"created_at").toInstant());
    }
}
