package org.shelterconnect.api.community;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.shelterconnect.api.auth.AccountService;
import org.shelterconnect.api.catalog.CatalogResponses.Page;
import org.shelterconnect.api.web.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.shelterconnect.api.web.FeatureInput.*;
import static org.shelterconnect.api.community.CommunityInput.*;
import static org.shelterconnect.api.community.CommunityTypes.*;

@Service @Transactional(readOnly=true)
public class CommunityService {
    public static final String VISIBLE="p.publication='PUBLISHED' AND p.hidden_at IS NULL AND p.deleted_at IS NULL AND a.disabled_at IS NULL";
    private static final String SELECT_POST="SELECT p.*,a.display_name,a.disabled_at,(SELECT count(*) FROM shelter.community_comments c WHERE c.post_id=p.id AND c.deleted_at IS NULL AND c.kind='COMMENT') comment_count,(SELECT count(*) FROM shelter.community_comments c WHERE c.post_id=p.id AND c.deleted_at IS NULL AND c.kind='SIGHTING') sighting_count FROM shelter.community_posts p JOIN shelter.app_users a ON a.id=p.author_id";
    private final JdbcClient jdbc; private final AccountService accounts; private final JsonMapper json; private final CommunityMediaStore media;
    public CommunityService(JdbcClient jdbc,AccountService accounts,JsonMapper json,CommunityMediaStore media) {this.jdbc=jdbc;this.accounts=accounts;this.json=json;this.media=media;}

    public Region region(UUID subject) {
        UUID user=accounts.profile(subject).id();
        return new Region(jdbc.sql("SELECT community_region FROM shelter.user_preferences WHERE user_id=:user").param("user",user).query(String.class).optional().orElse(null));
    }
    @Transactional public Region region(UUID subject,JsonNode body) {
        fields(body,"regionLabel");if(!body.has("regionLabel"))throw FeatureException.invalid();
        String region=body.path("regionLabel").isNull()?null:text(body,"regionLabel",100);
        UUID user=accounts.lockProfile(subject,true).id();
        jdbc.sql("INSERT INTO shelter.user_preferences(user_id,community_region) VALUES(:user,:region) ON CONFLICT(user_id) DO UPDATE SET community_region=excluded.community_region,updated_at=clock_timestamp()")
            .param("user",user).param("region",region,Types.VARCHAR).update();return new Region(region);
    }
    public Page<Post> posts(UUID subject,String search,String region,String category,String cursor,String size,boolean own,String publication) {
        UUID user=accounts.profile(subject).id();String q=query(search),r=query(region);int count=limit(size);
        if(category!=null)choice(category,"LOST","FOUND","NEIGHBOR_NEWS");
        if(publication!=null)choice(publication,"DRAFT","PUBLISHED");
        String scope=scope("community-posts",user,q,r,category,own,publication);var after=pagePosition(cursor,scope);
        String order=own?"p.created_at":"p.published_at";
        String sql=SELECT_POST+" WHERE "+(own?"p.author_id=:user AND p.deleted_at IS NULL":VISIBLE)+" AND (strpos(lower(p.content->>'title'),lower(:q))>0 OR strpos(lower(p.content->>'text'),lower(:q))>0) AND starts_with(p.content->>'regionLabel',:region)";
        if(category!=null)sql+=" AND p.category=:category";
        if(own && publication!=null)sql+=" AND p.publication=:publication";
        if(after!=null)sql+=" AND ("+order+",p.id)<(:at,:id)";
        var stmt=jdbc.sql(sql+" ORDER BY "+order+" DESC,p.id DESC LIMIT :limit").param("q",q).param("region",r).param("limit",count+1);
        if(own)stmt=stmt.param("user",user);if(category!=null)stmt=stmt.param("category",category);if(own&&publication!=null)stmt=stmt.param("publication",publication);
        if(after!=null)stmt=stmt.param("at",after.at().atOffset(ZoneOffset.UTC)).param("id",after.id());
        var rows=stmt.query((rs,n)->post(rs,user)).list();var data=rows.stream().limit(count).toList();var last=data.isEmpty()?null:data.getLast();
        return new Page<>(data,rows.size()>count?cursor(scope,(own?last.createdAt():last.publishedAt())+"|"+last.id()):null);
    }
    public Post post(UUID subject,UUID id) { return read(accounts.profile(subject).id(),id); }
    public Post read(UUID user,UUID id) {
        return jdbc.sql(SELECT_POST+" WHERE p.id=:id AND p.deleted_at IS NULL AND (p.author_id=:user OR ("+VISIBLE+"))")
            .param("id",id).param("user",user).query((rs,n)->post(rs,user)).optional().orElseThrow(FeatureException::missing);
    }
    @Transactional public Post create(UUID subject,JsonNode body) {
        fields(body,"clientRequestId","category","publication","title","text","regionLabel","location","features","mediaIds");
        UUID request=id(text(body,"clientRequestId",36));String category=choice(text(body,"category",20),"LOST","FOUND","NEIGHBOR_NEWS");
        String publication=choice(text(body,"publication",12),"DRAFT","PUBLISHED");var content=content(body,category,publication.equals("PUBLISHED"));
        UUID user=accounts.lockProfile(subject,true).id();String hash=scope(category,publication,json.writeValueAsString(content));
        var old=jdbc.sql("SELECT id,request_hash,deleted_at FROM shelter.community_posts WHERE author_id=:user AND client_request_id=:request")
            .param("user",user).param("request",request).query((rs,n)->new Existing(rs.getObject(1,UUID.class),rs.getString(2),rs.getTimestamp(3)!=null)).optional();
        if(old.isPresent()) { same(old.get(),hash);return read(user,old.get().id()); }
        UUID post=UUID.randomUUID();
        jdbc.sql("INSERT INTO shelter.community_posts(id,author_id,client_request_id,request_hash,category,publication,content,published_at) VALUES(:id,:user,:request,:hash,:category,:publication,CAST(:content AS jsonb),CASE WHEN :publication='PUBLISHED' THEN clock_timestamp() END)")
            .param("id",post).param("user",user).param("request",request).param("hash",hash).param("category",category).param("publication",publication).param("content",json.writeValueAsString(content)).update();
        media.bindPost(user,post,content.mediaIds());return read(user,post);
    }
    @Transactional public Post edit(UUID subject,UUID id,JsonNode body) {
        fields(body,"version","category","title","text","regionLabel","location","features","mediaIds");long expected=version(body);
        UUID user=accounts.lockProfile(subject,false).id();var old=ownLock(user,id);checkVersion(old.version(),expected);
        String category=choice(text(body,"category",20),"LOST","FOUND","NEIGHBOR_NEWS");
        if(old.publication().equals("PUBLISHED")&&!category.equals(old.category()))throw new FeatureException(409,"CATEGORY_LOCKED","등록한 글의 분류는 바꿀 수 없어요.");
        var content=content(body,category,old.publication().equals("PUBLISHED"));media.bindPost(user,id,content.mediaIds());
        jdbc.sql("UPDATE shelter.community_posts SET category=:category,content=CAST(:content AS jsonb),version=version+1,updated_at=clock_timestamp() WHERE id=:id")
            .param("category",category).param("content",json.writeValueAsString(content)).param("id",id).update();return read(user,id);
    }
    @Transactional public Post publish(UUID subject,UUID id,JsonNode body) {
        fields(body,"version");UUID user=accounts.lockProfile(subject,false).id();var old=ownLock(user,id);checkVersion(old.version(),version(body));
        if(old.publication().equals("PUBLISHED"))return old;publishable(old.content(),old.category());media.bindPost(user,id,old.content().mediaIds());
        jdbc.sql("UPDATE shelter.community_posts SET publication='PUBLISHED',published_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1 WHERE id=:id").param("id",id).update();return read(user,id);
    }
    @Transactional public Post status(UUID subject,UUID id,JsonNode body) {
        fields(body,"version","status");String next=choice(text(body,"status",16),"ACTIVE","REUNITED","CLOSED","TRANSFERRED");
        UUID user=accounts.lockProfile(subject,false).id();var old=ownLock(user,id);checkVersion(old.version(),version(body));
        if(!old.publication().equals("PUBLISHED") || next.equals("TRANSFERRED")&&!old.category().equals("FOUND") || old.category().equals("NEIGHBOR_NEWS")&&!Set.of("ACTIVE","CLOSED").contains(next))throw FeatureException.invalid();
        if(next.equals(old.status()))return old;
        jdbc.sql("UPDATE shelter.community_posts SET status=:status,version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",id).param("status",next).update();return read(user,id);
    }
    @Transactional public Mutation delete(UUID subject,UUID id,long expected) {
        UUID user=accounts.lockProfile(subject,false).id();
        var found=jdbc.sql("SELECT version,deleted_at FROM shelter.community_posts WHERE id=:id AND author_id=:user FOR UPDATE").param("id",id).param("user",user)
            .query((rs,n)->new Object[]{rs.getLong(1),rs.getTimestamp(2)}).optional().orElseThrow(FeatureException::missing);
        if(found[1]!=null)return new Mutation(id,(Long)found[0]);checkVersion((Long)found[0],expected);
        jdbc.sql("UPDATE shelter.community_posts SET deleted_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1 WHERE id=:id").param("id",id).update();return new Mutation(id,expected+1);
    }
    private Post ownLock(UUID user,UUID id) {
        jdbc.sql("SELECT id FROM shelter.community_posts WHERE id=:id AND author_id=:user AND deleted_at IS NULL FOR UPDATE").param("id",id).param("user",user).query(UUID.class).optional().orElseThrow(FeatureException::missing);
        var post=read(user,id);if(post.hidden())throw new FeatureException(403,"POST_MODERATED","운영 검토로 숨겨진 글이에요.");return post;
    }
    /** Serialize against publication/status/moderation changes before adding related records. */
    public Post publicLock(UUID user,UUID id) {
        jdbc.sql("SELECT id FROM shelter.community_posts WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().orElseThrow(FeatureException::missing);
        var post=read(user,id);if(!post.publication().equals("PUBLISHED")||post.hidden())throw FeatureException.missing();return post;
    }
    public Page<Comment> comments(UUID subject,UUID post,String kind,String cursor,String size) {
        UUID user=accounts.profile(subject).id();var parent=read(user,post);if(!parent.publication().equals("PUBLISHED")||parent.hidden())throw FeatureException.missing();
        if(kind!=null)choice(kind,"COMMENT","SIGHTING");int count=limit(size);String scope=scope("comments",user,post,kind);var after=pagePosition(cursor,scope);
        String sql="SELECT c.*,a.display_name,a.disabled_at FROM shelter.community_comments c JOIN shelter.app_users a ON a.id=c.author_id WHERE c.post_id=:post";
        if(kind!=null)sql+=" AND c.kind=:kind";if(after!=null)sql+=" AND (c.created_at,c.id)>(:at,:id)";
        var stmt=jdbc.sql(sql+" ORDER BY c.created_at,c.id LIMIT :limit").param("post",post).param("limit",count+1);
        if(kind!=null)stmt=stmt.param("kind",kind);if(after!=null)stmt=stmt.param("at",after.at().atOffset(ZoneOffset.UTC)).param("id",after.id());
        var rows=stmt.query((rs,n)->comment(rs,user)).list();var data=rows.stream().limit(count).toList();var last=data.isEmpty()?null:data.getLast();
        return new Page<>(data,rows.size()>count?cursor(scope,last.createdAt()+"|"+last.id()):null);
    }
    @Transactional public Comment comment(UUID subject,UUID post,JsonNode body) {
        fields(body,"clientRequestId","kind","parentId","text","location","mediaIds");UUID request=id(text(body,"clientRequestId",36));
        String kind=choice(text(body,"kind",10),"COMMENT","SIGHTING");UUID parent=body.path("parentId").isNull()||!body.has("parentId")?null:id(text(body,"parentId",36));
        var content=new CommentContent(text(body,"text",1000),location(body.path("location"),kind.equals("SIGHTING")),media(body,1));
        if(parent!=null&&!kind.equals("COMMENT"))throw FeatureException.invalid();
        UUID user=accounts.lockProfile(subject,false).id();var target=publicLock(user,post);String hash=scope(kind,parent,json.writeValueAsString(content));
        var old=jdbc.sql("SELECT id,request_hash,deleted_at FROM shelter.community_comments WHERE post_id=:post AND author_id=:user AND client_request_id=:request")
            .param("post",post).param("user",user).param("request",request).query((rs,n)->new Existing(rs.getObject(1,UUID.class),rs.getString(2),rs.getTimestamp(3)!=null)).optional();
        if(old.isPresent()){same(old.get(),hash);return comment(user,old.get().id());}
        if(kind.equals("SIGHTING")&&(target.category().equals("NEIGHBOR_NEWS")||!target.status().equals("ACTIVE")))throw new FeatureException(409,"SIGHTINGS_CLOSED","새 목격 제보가 마감된 글이에요.");
        if(parent!=null && jdbc.sql("SELECT id FROM shelter.community_comments WHERE id=:id AND post_id=:post AND parent_id IS NULL AND deleted_at IS NULL").param("id",parent).param("post",post).query(UUID.class).optional().isEmpty())throw FeatureException.missing();
        media.bindPost(user,post,content.mediaIds());UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO shelter.community_comments(id,post_id,author_id,client_request_id,request_hash,kind,parent_id,content) VALUES(:id,:post,:user,:request,:hash,:kind,:parent,CAST(:content AS jsonb))")
            .param("id",id).param("post",post).param("user",user).param("request",request).param("hash",hash).param("kind",kind).param("parent",parent,Types.OTHER).param("content",json.writeValueAsString(content)).update();return comment(user,id);
    }
    private Comment comment(UUID user,UUID id) {return jdbc.sql("SELECT c.*,a.display_name,a.disabled_at FROM shelter.community_comments c JOIN shelter.app_users a ON a.id=c.author_id WHERE c.id=:id").param("id",id).query((rs,n)->comment(rs,user)).single();}
    @Transactional public Mutation deleteComment(UUID subject,UUID post,UUID id) {
        UUID user=accounts.lockProfile(subject,false).id();
        int updated=jdbc.sql("UPDATE shelter.community_comments SET deleted_at=coalesce(deleted_at,clock_timestamp()) WHERE id=:id AND post_id=:post AND author_id=:user").param("id",id).param("post",post).param("user",user).update();
        if(updated==0)throw FeatureException.missing();return new Mutation(id,1);
    }
    @Transactional public ReportReceipt report(UUID subject,UUID post,JsonNode body) {
        fields(body,"reason","details");String reason=choice(text(body,"reason",24),"SPAM","FALSE_INFORMATION","ABUSE","PRIVACY","OTHER"),details=optionalText(body,"details",1000);
        if(reason.equals("OTHER")&&details.isEmpty())throw FeatureException.invalid();
        UUID user=accounts.lockProfile(subject,false).id();var target=publicLock(user,post);if(target.authorId().equals(user))throw FeatureException.invalid();
        var old=jdbc.sql("SELECT id,status FROM shelter.community_reports WHERE post_id=:post AND reporter_id=:user").param("post",post).param("user",user).query((rs,n)->new ReportReceipt(rs.getObject(1,UUID.class),rs.getString(2))).optional();
        if(old.isPresent())return old.get();
        return jdbc.sql("INSERT INTO shelter.community_reports(post_id,reporter_id,reason,details) VALUES(:post,:user,:reason,:details) RETURNING id,status").param("post",post).param("user",user).param("reason",reason).param("details",details).query((rs,n)->new ReportReceipt(rs.getObject(1,UUID.class),rs.getString(2))).single();
    }
    public Page<Report> reports(UUID subject,String state,String cursor,String size) {
        UUID user=operator(subject,false);String status=state==null?"PENDING":choice(state,"PENDING","HIDDEN","DISMISSED");int count=limit(size);String scope=scope("reports",user,status);var after=pagePosition(cursor,scope);
        String sql="SELECT r.*,p.content post_content FROM shelter.community_reports r JOIN shelter.community_posts p ON p.id=r.post_id WHERE r.status=:status";
        if(after!=null)sql+=" AND (r.created_at,r.id)>(:at,:id)";
        var stmt=jdbc.sql(sql+" ORDER BY r.created_at,r.id LIMIT :limit").param("status",status).param("limit",count+1);
        if(after!=null)stmt=stmt.param("at",after.at().atOffset(ZoneOffset.UTC)).param("id",after.id());
        var rows=stmt.query((rs,n)->report(rs)).list();var data=rows.stream().limit(count).toList();var last=data.isEmpty()?null:data.getLast();return new Page<>(data,rows.size()>count?cursor(scope,last.createdAt()+"|"+last.id()):null);
    }
    @Transactional public Report review(UUID subject,UUID id,JsonNode body) {
        fields(body,"version","action","note");long expected=version(body);String action=choice(text(body,"action",12),"HIDE","DISMISS"),note=text(body,"note",1000);UUID user=operator(subject,true);
        // Lock post before report, matching report creation's order.
        UUID post=jdbc.sql("SELECT post_id FROM shelter.community_reports WHERE id=:id").param("id",id).query(UUID.class).optional().orElseThrow(FeatureException::missing);
        jdbc.sql("SELECT id FROM shelter.community_posts WHERE id=:id FOR UPDATE").param("id",post).query(UUID.class).single();
        var old=jdbc.sql("SELECT version,status FROM shelter.community_reports WHERE id=:id FOR UPDATE").param("id",id).query((rs,n)->new Object[]{rs.getLong(1),rs.getString(2)}).single();checkVersion((Long)old[0],expected);
        if(!old[1].equals("PENDING"))throw FeatureException.conflict();
        if(action.equals("HIDE"))jdbc.sql("UPDATE shelter.community_posts SET hidden_at=coalesce(hidden_at,clock_timestamp()),version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",post).update();
        jdbc.sql("UPDATE shelter.community_reports SET status=:status,review_note=:note,reviewed_by=:user,reviewed_at=clock_timestamp(),version=version+1 WHERE id=:id").param("status",action.equals("HIDE")?"HIDDEN":"DISMISSED").param("note",note).param("user",user).param("id",id).update();
        return jdbc.sql("SELECT r.*,p.content post_content FROM shelter.community_reports r JOIN shelter.community_posts p ON p.id=r.post_id WHERE r.id=:id").param("id",id).query((rs,n)->report(rs)).single();
    }
    private UUID operator(UUID subject,boolean lock) {
        var user=lock?accounts.lockProfile(subject,false):accounts.profile(subject);if(!user.role().equals("OPERATOR"))throw new FeatureException(403,"FORBIDDEN","운영 권한이 필요해요.");return user.id();
    }
    private record Existing(UUID id,String hash,boolean deleted) {}
    private static void same(Existing old,String hash) {
        if(!old.hash().equals(hash))throw new FeatureException(409,"REQUEST_ID_CONFLICT","같은 요청 ID에 다른 내용을 보낼 수 없어요.");
        if(old.deleted())throw new FeatureException(409,"RESOURCE_DELETED","삭제된 요청은 새 ID로 작성해 주세요.");
    }
    public static void checkVersion(long actual,long expected){if(expected<1)throw FeatureException.invalid();if(actual!=expected)throw FeatureException.conflict();}
    public record PagePosition(Instant at,UUID id) {}
    public static PagePosition pagePosition(String cursor,String scope) {String value=position(cursor,scope);if(value==null)return null;var bits=value.split("\\|",-1);if(bits.length!=2)throw FeatureException.invalid();return new PagePosition(instant(bits[0]),id(bits[1]));}
    private Post post(ResultSet rs,UUID user)throws SQLException {
        UUID author=rs.getObject("author_id",UUID.class);return new Post(rs.getObject("id",UUID.class),author,rs.getTimestamp("disabled_at")==null?rs.getString("display_name"):"이웃",author.equals(user),rs.getString("category"),rs.getString("publication"),rs.getString("status"),rs.getLong("version"),json.readValue(rs.getString("content"),Content.class),rs.getTimestamp("hidden_at")!=null,at(rs,"created_at"),at(rs,"published_at"),at(rs,"updated_at"),rs.getLong("comment_count"),rs.getLong("sighting_count"));
    }
    private Comment comment(ResultSet rs,UUID user)throws SQLException {
        UUID author=rs.getObject("author_id",UUID.class);boolean deleted=rs.getTimestamp("deleted_at")!=null||rs.getTimestamp("disabled_at")!=null;
        return new Comment(rs.getObject("id",UUID.class),rs.getObject("post_id",UUID.class),deleted?null:author,deleted?"이웃":rs.getString("display_name"),author.equals(user),rs.getString("kind"),rs.getObject("parent_id",UUID.class),deleted?null:json.readValue(rs.getString("content"),CommentContent.class),deleted,at(rs,"created_at"));
    }
    private Report report(ResultSet rs)throws SQLException {return new Report(rs.getObject("id",UUID.class),rs.getObject("post_id",UUID.class),rs.getObject("reporter_id",UUID.class),rs.getString("reason"),rs.getString("details"),rs.getString("status"),rs.getLong("version"),rs.getString("review_note"),at(rs,"created_at"),at(rs,"reviewed_at"),json.readValue(rs.getString("post_content"),Content.class));}
    private static Instant at(ResultSet rs,String field)throws SQLException {var value=rs.getTimestamp(field);return value==null?null:value.toInstant();}
}
