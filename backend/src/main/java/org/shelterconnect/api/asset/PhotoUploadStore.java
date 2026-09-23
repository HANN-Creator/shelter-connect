package org.shelterconnect.api.asset;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.ShelterAccessService;
import tools.jackson.databind.JsonNode;

@Service
public class PhotoUploadStore {
    record Reservation(UUID id,UUID dogId,UUID photoId,UUID permissionId,String key,UUID token,boolean completed) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="ManagedPhoto")
    public record Photo(UUID id,String rightsStatus,int sortOrder,String uploadStatus) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="ManagedPhotoPage")
    public record Page(List<Photo> data,String nextCursor) {}
    private final JdbcClient jdbc;private final ShelterAccessService access;private final AssetProperties properties;private final AssetStore assets;
    public PhotoUploadStore(JdbcClient jdbc,ShelterAccessService access,AssetProperties properties,AssetStore assets) { this.jdbc=jdbc;this.access=access;this.properties=properties;this.assets=assets; }
    @Transactional public Reservation begin(UUID subject,UUID dog,JsonNode body,String hash) {
        var actor=access.requireDogForWrite(subject,dog);properties.requireEnabled();active(dog);
        AssetInput.fields(body,"clientUploadId","permissionId","rightsConfirmed","rightsNote");
        var client=AssetInput.id(body,"clientUploadId");var permission=AssetInput.id(body,"permissionId");
        var note=AssetInput.text(body,"rightsNote",2000);if(!AssetInput.bool(body,"rightsConfirmed")) throw new AssetException(409,"PHOTO_RIGHTS_REQUIRED");
        permission(permission,actor.shelterId());
        var old=jdbc.sql("SELECT id FROM shelter.photo_upload_requests WHERE dog_id=:d AND client_upload_id=:c FOR UPDATE").param("d",dog).param("c",client).query(UUID.class).optional();
        UUID id,photo;
        if(old.isPresent()) {
            id=old.get();
            boolean match=jdbc.sql("SELECT content_hash=:hash AND permission_id=:p AND rights_note=:note FROM shelter.photo_upload_requests WHERE id=:id")
                .param("hash",hash).param("p",permission).param("note",note).param("id",id).query(Boolean.class).single();
            if(!match) throw new AssetException(409,"UPLOAD_ID_CONFLICT");
            var existing=reservation(id);
            if(existing.completed()) return existing;
            if(jdbc.sql("SELECT coalesce(lease_until>now(),false) FROM shelter.photo_upload_requests WHERE id=:id").param("id",id).query(Boolean.class).single()) throw new AssetException(409,"UPLOAD_IN_PROGRESS");
        } else {
            photo=UUID.randomUUID();String key=dog+"/uploads/"+photo+"/"+hash+".png";
            jdbc.sql("""
                INSERT INTO shelter.dog_photos(id,dog_id,storage_bucket,storage_key,sort_order,rights_status)
                SELECT :id,:dog,:bucket,:key,coalesce(max(sort_order)+1,0),'UNKNOWN' FROM shelter.dog_photos WHERE dog_id=:dog
                """).param("id",photo).param("dog",dog).param("bucket",properties.photoBucket).param("key",key).update();
            id=jdbc.sql("""
                INSERT INTO shelter.photo_upload_requests(dog_id,client_upload_id,photo_id,permission_id,content_hash,rights_note,status)
                VALUES (:d,:c,:photo,:p,:hash,:note,'PENDING') RETURNING id
                """).param("d",dog).param("c",client).param("photo",photo).param("p",permission).param("hash",hash).param("note",note).query(UUID.class).single();
        }
        jdbc.sql("UPDATE shelter.photo_upload_requests SET lease_token=:t,lease_until=now()+interval '90 seconds' WHERE id=:id").param("t",UUID.randomUUID()).param("id",id).update();
        return reservation(id);
    }
    @Transactional public Map<String,Object> complete(UUID subject,Reservation upload) {
        var actor=access.requireDogForWrite(subject,upload.dogId());active(upload.dogId());permission(upload.permissionId(),actor.shelterId());
        var current=reservation(upload.id());
        if(!current.completed()) {
            if(!Objects.equals(current.token(),upload.token())) throw new AssetException(409,"UPLOAD_IN_PROGRESS");
            jdbc.sql("""
                UPDATE shelter.dog_photos p SET rights_status='GRANTED',rights_note=u.rights_note,rights_confirmed_by=:actor,rights_confirmed_at=now()
                FROM shelter.photo_upload_requests u WHERE u.id=:id AND p.id=u.photo_id AND p.rights_status='UNKNOWN'
                """).param("actor",actor.userId()).param("id",upload.id()).update();
            jdbc.sql("UPDATE shelter.photo_upload_requests SET status='COMPLETED',lease_token=NULL,lease_until=NULL WHERE id=:id").param("id",upload.id()).update();
        }
        return assets.photoStored(upload.photoId(),upload.permissionId());
    }
    @Transactional public void release(Reservation upload) {
        jdbc.sql("UPDATE shelter.photo_upload_requests SET lease_token=NULL,lease_until=NULL WHERE id=:id AND lease_token=:token")
            .param("id",upload.id()).param("token",upload.token()).update();
    }
    @Transactional(readOnly=true) public Page list(UUID subject,UUID dog,int limit,String cursor) {
        access.requireDog(subject,dog);if(limit<1 || limit>50) throw AssetException.invalid();
        UUID after=cursor==null?null:AssetInput.id(cursor);
        var query=jdbc.sql("SELECT p.id,p.rights_status,p.sort_order,coalesce(u.status,'EXTERNAL') AS upload_status FROM shelter.dog_photos p LEFT JOIN shelter.photo_upload_requests u ON u.photo_id=p.id WHERE p.dog_id=:d"+(after==null?"":" AND p.id>:cursor")+" ORDER BY p.id LIMIT :lim").param("d",dog).param("lim",limit+1);
        if(after!=null) query=query.param("cursor",after);
        var rows=query.query((rs,n)->new Photo(rs.getObject("id",UUID.class),rs.getString("rights_status"),rs.getInt("sort_order"),rs.getString("upload_status"))).list();
        boolean more=rows.size()>limit;var page=more?rows.subList(0,limit):rows;
        return new Page(List.copyOf(page),more?page.getLast().id().toString():null);
    }
    private Reservation reservation(UUID id) {
        return jdbc.sql("SELECT u.*,p.storage_key FROM shelter.photo_upload_requests u JOIN shelter.dog_photos p ON p.id=u.photo_id WHERE u.id=:id")
            .param("id",id).query((rs,n)->new Reservation(id,rs.getObject("dog_id",UUID.class),rs.getObject("photo_id",UUID.class),rs.getObject("permission_id",UUID.class),rs.getString("storage_key"),rs.getObject("lease_token",UUID.class),rs.getString("status").equals("COMPLETED"))).single();
    }
    private void permission(UUID id,UUID shelter) {
        if(jdbc.sql("SELECT id FROM shelter.asset_source_permissions WHERE id=:id AND shelter_id=:s AND source_kind='SHELTER' AND revoked_at IS NULL AND derivatives_allowed AND pixellab_allowed FOR SHARE")
            .param("id",id).param("s",shelter).query(UUID.class).optional().isEmpty()) throw new AssetException(409,"ASSET_PERMISSION_REQUIRED");
    }
    private void active(UUID dog) { if(jdbc.sql("SELECT archived_at IS NOT NULL FROM shelter.dogs WHERE id=:d").param("d",dog).query(Boolean.class).single()) throw new AssetException(409,"DOG_ARCHIVED"); }
}
