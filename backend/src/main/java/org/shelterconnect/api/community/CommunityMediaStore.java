package org.shelterconnect.api.community;

import java.util.*;
import org.shelterconnect.api.auth.AccountService;
import org.shelterconnect.api.web.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static org.shelterconnect.api.community.CommunityTypes.*;

@Service @Transactional(readOnly=true)
public class CommunityMediaStore {
    public record Stored(UUID id,UUID owner,String key,String hash,String state,int bytes,UUID postId) {public Media response(){return new Media(id,state,bytes);}}
    private final JdbcClient jdbc;private final AccountService accounts;
    public CommunityMediaStore(JdbcClient jdbc,AccountService accounts){this.jdbc=jdbc;this.accounts=accounts;}
    public void authorize(UUID subject){accounts.profile(subject);}
    @Transactional public Stored reserve(UUID subject,UUID request,String hash,int bytes) {
        UUID user=accounts.lockProfile(subject,true).id();
        var old=jdbc.sql("SELECT * FROM shelter.community_media WHERE owner_id=:user AND client_request_id=:request").param("user",user).param("request",request).query(this::stored).optional();
        if(old.isPresent()) {if(!old.get().hash().equals(hash))throw new FeatureException(409,"REQUEST_ID_CONFLICT","같은 업로드 ID에 다른 사진을 보낼 수 없어요.");return old.get();}
        long daily=jdbc.sql("SELECT count(*) FROM shelter.community_media WHERE owner_id=:user AND created_at>clock_timestamp()-interval '24 hours'").param("user",user).query(Long.class).single();
        long total=jdbc.sql("SELECT coalesce(sum(byte_size),0) FROM shelter.community_media WHERE owner_id=:user").param("user",user).query(Long.class).single();
        if(daily>=20 || total+bytes>100L*1024*1024)throw new FeatureException(429,"MEDIA_QUOTA_EXCEEDED","사진 저장 한도에 도달했어요.");
        UUID id=UUID.randomUUID();String key=user+"/"+id+"/"+hash+".png";
        return jdbc.sql("INSERT INTO shelter.community_media(id,owner_id,client_request_id,content_hash,object_key,byte_size) VALUES(:id,:user,:request,:hash,:key,:bytes) RETURNING *")
            .param("id",id).param("user",user).param("request",request).param("hash",hash).param("key",key).param("bytes",bytes).query(this::stored).single();
    }
    @Transactional public Stored complete(UUID subject,UUID id) {
        UUID user=accounts.lockProfile(subject,false).id();
        return jdbc.sql("UPDATE shelter.community_media SET state='READY' WHERE id=:id AND owner_id=:user RETURNING *").param("id",id).param("user",user).query(this::stored).optional().orElseThrow(FeatureException::missing);
    }
    @Transactional(propagation=Propagation.MANDATORY) public void bindPost(UUID user,UUID post,List<UUID> ids) {
        // A consistent media lock order prevents two drafts with overlapping images deadlocking.
        for(UUID id:ids.stream().sorted().toList()) {
            var found=jdbc.sql("SELECT * FROM shelter.community_media WHERE id=:id AND owner_id=:user FOR UPDATE").param("id",id).param("user",user).query(this::stored).optional().orElseThrow(FeatureException::missing);
            if(!found.state().equals("READY") || found.postId()!=null&&!found.postId().equals(post))throw new FeatureException(409,"MEDIA_NOT_AVAILABLE","사용할 수 없는 사진이에요.");
            jdbc.sql("UPDATE shelter.community_media SET post_id=:post WHERE id=:id").param("post",post).param("id",id).update();
        }
    }
    public Stored accessible(UUID subject,UUID id) {
        var user=accounts.profile(subject);
        String sql="""
            SELECT m.* FROM shelter.community_media m WHERE m.id=:id AND m.state='READY' AND (
                m.owner_id=:user OR EXISTS (
                    SELECT 1 FROM shelter.community_posts p JOIN shelter.app_users a ON a.id=p.author_id
                    WHERE p.id=m.post_id AND ((p.publication='PUBLISHED' AND p.hidden_at IS NULL AND p.deleted_at IS NULL AND a.disabled_at IS NULL) OR :operator)
                    AND (jsonb_exists(p.content->'mediaIds',CAST(m.id AS text)) OR EXISTS (
                        SELECT 1 FROM shelter.community_comments c JOIN shelter.app_users ca ON ca.id=c.author_id
                        WHERE c.post_id=p.id AND c.deleted_at IS NULL AND ca.disabled_at IS NULL
                        AND jsonb_exists(c.content->'mediaIds',CAST(m.id AS text))
                    ))
                )
            )
            """;
        return jdbc.sql(sql).param("id",id).param("user",user.id()).param("operator",user.role().equals("OPERATOR")).query(this::stored).optional().orElseThrow(FeatureException::missing);
    }
    private Stored stored(java.sql.ResultSet rs,int row)throws java.sql.SQLException {return new Stored(rs.getObject("id",UUID.class),rs.getObject("owner_id",UUID.class),rs.getString("object_key"),rs.getString("content_hash"),rs.getString("state"),rs.getInt("byte_size"),rs.getObject("post_id",UUID.class));}
}
