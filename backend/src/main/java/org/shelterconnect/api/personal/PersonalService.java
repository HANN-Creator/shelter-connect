package org.shelterconnect.api.personal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.shelterconnect.api.auth.AccountService;
import org.shelterconnect.api.catalog.CatalogResponses.Page;
import org.shelterconnect.api.web.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.web.FeatureInput.*;

@Service
@Transactional(readOnly=true)
public class PersonalService {
    public static final String PUBLIC_SHELTER="s.approval_status='APPROVED' AND s.is_public";
    public static final String PUBLIC_DOG=PUBLIC_SHELTER+" AND d.is_public AND d.archived_at IS NULL AND d.adoption_status IN ('AVAILABLE','IN_PROGRESS')";
    public record Preferences(UUID currentShelterId) {}
    public record ConsentPolicy(String termsVersion,String privacyVersion,boolean available) {}
    public record Consents(String termsVersion,String privacyVersion,Instant acceptedAt) {}
    public record SavedState(UUID dogId,boolean saved) {}
    public record SavedDog(UUID dogId,String dogName,UUID shelterId,String shelterName,String avatarKey,
                           String adoptionStatus,Instant savedAt,UUID sessionId) {}
    public record Conversation(UUID sessionId,UUID dogId,String dogName,String shelterName,String avatarKey,
                               boolean available,boolean saved,String lastMessage,String lastMessageRole,Instant updatedAt) {}
    public record NearbyShelter(UUID id,String name,String region,String address,Double latitude,Double longitude,
                                Double distanceMeters,String mapKey,long dogCount) {}
    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final Environment env;
    public PersonalService(JdbcClient jdbc,AccountService accounts,Environment env) { this.jdbc=jdbc;this.accounts=accounts;this.env=env; }
    public ConsentPolicy policy() {
        String terms=env.getProperty("app.registration.terms-version","");
        String privacy=env.getProperty("app.registration.privacy-version","");
        return new ConsentPolicy(terms,privacy,terms.matches("[A-Za-z0-9._-]{1,40}") && privacy.matches("[A-Za-z0-9._-]{1,40}"));
    }
    @Transactional
    public AccountService.Profile profile(UUID subject,JsonNode body) {
        fields(body,"displayName"); String name=text(body,"displayName",30);
        if(name.chars().anyMatch(Character::isISOControl)) throw FeatureException.invalid();
        var user=accounts.lockProfile(subject,true);
        jdbc.sql("UPDATE shelter.app_users SET display_name=:name WHERE id=:user").param("name",name).param("user",user.id()).update();
        return accounts.profile(subject);
    }
    public Preferences preferences(UUID subject) {
        var user=accounts.profile(subject);
        UUID shelter=jdbc.sql("SELECT s.id FROM shelter.user_preferences p JOIN shelter.shelters s ON s.id=p.current_shelter_id WHERE p.user_id=:user AND "+PUBLIC_SHELTER)
            .param("user",user.id()).query(UUID.class).optional().orElse(null);
        return new Preferences(shelter);
    }
    @Transactional
    public Preferences preferences(UUID subject,JsonNode body) {
        fields(body,"currentShelterId"); if(!body.has("currentShelterId")) throw FeatureException.invalid();
        UUID shelter=body.path("currentShelterId").isNull()?null:id(text(body,"currentShelterId",36));
        var user=accounts.lockProfile(subject,true);
        if(shelter!=null && jdbc.sql("SELECT s.id FROM shelter.shelters s WHERE s.id=:id AND "+PUBLIC_SHELTER+" FOR SHARE")
            .param("id",shelter).query(UUID.class).optional().isEmpty()) throw FeatureException.missing();
        jdbc.sql("INSERT INTO shelter.user_preferences(user_id,current_shelter_id) VALUES(:user,:shelter) ON CONFLICT(user_id) DO UPDATE SET current_shelter_id=excluded.current_shelter_id, updated_at=clock_timestamp()")
            .param("user",user.id()).param("shelter",shelter,java.sql.Types.OTHER).update();
        return new Preferences(shelter);
    }
    public Consents consents(UUID subject) {
        UUID user=accounts.profile(subject).id(); var policy=policy();
        return jdbc.sql("SELECT terms_version,privacy_version,accepted_at FROM shelter.user_consents WHERE user_id=:user AND terms_version=:terms AND privacy_version=:privacy")
            .param("user",user).param("terms",policy.termsVersion()).param("privacy",policy.privacyVersion())
            .query((rs,n)->new Consents(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant())).optional().orElse(null);
    }
    @Transactional
    public Consents consent(UUID subject,JsonNode body) {
        fields(body,"termsVersion","privacyVersion","termsAccepted","privacyAccepted");
        String terms=text(body,"termsVersion",40), privacy=text(body,"privacyVersion",40);
        if(!body.path("termsAccepted").isBoolean() || !body.path("termsAccepted").asBoolean() || !body.path("privacyAccepted").isBoolean() || !body.path("privacyAccepted").asBoolean()) throw FeatureException.invalid();
        UUID user=accounts.lockProfile(subject,true).id(); var policy=policy();
        if(!policy.available()) throw new FeatureException(503,"CONSENT_POLICY_UNAVAILABLE","약관 준비 중이에요. 잠시 뒤 다시 시도해 주세요.");
        if(!policy.termsVersion().equals(terms) || !policy.privacyVersion().equals(privacy)) throw new FeatureException(409,"CONSENT_VERSION_CHANGED","최신 약관을 확인해 주세요.");
        jdbc.sql("INSERT INTO shelter.user_consents(user_id,terms_version,privacy_version) VALUES(:user,:terms,:privacy) ON CONFLICT DO NOTHING")
            .param("user",user).param("terms",terms).param("privacy",privacy).update();
        return consents(subject);
    }
    @Transactional
    public SavedState save(UUID subject,UUID dog,boolean saved) {
        UUID user=accounts.lockProfile(subject,true).id();
        if(saved) {
            if(jdbc.sql("SELECT d.id FROM shelter.dogs d JOIN shelter.shelters s ON s.id=d.shelter_id WHERE d.id=:id AND "+PUBLIC_DOG+" FOR SHARE OF s,d")
                .param("id",dog).query(UUID.class).optional().isEmpty()) throw FeatureException.missing();
            jdbc.sql("INSERT INTO shelter.saved_dogs(user_id,dog_id) VALUES(:user,:dog) ON CONFLICT(user_id,dog_id) DO UPDATE SET active=true, saved_at=CASE WHEN shelter.saved_dogs.active THEN shelter.saved_dogs.saved_at ELSE clock_timestamp() END")
                .param("user",user).param("dog",dog).update();
        } else jdbc.sql("UPDATE shelter.saved_dogs SET active=false WHERE user_id=:user AND dog_id=:dog").param("user",user).param("dog",dog).update();
        return new SavedState(dog,saved);
    }
    public SavedState saved(UUID subject,UUID dog) {
        UUID user=accounts.profile(subject).id();
        boolean saved=jdbc.sql("SELECT EXISTS(SELECT 1 FROM shelter.saved_dogs WHERE user_id=:user AND dog_id=:dog AND active)")
            .param("user",user).param("dog",dog).query(Boolean.class).single();
        return new SavedState(dog,saved);
    }
    public Page<SavedDog> savedDogs(UUID subject,String search,String shelterId,String cursor,String limitValue) {
        UUID user=accounts.profile(subject).id(); String q=query(search); UUID shelter=shelterId==null?null:id(shelterId);
        int count=limit(limitValue); String scope=scope("saved",user,q,shelter); var after=activity(cursor,scope);
        String sql="SELECT d.id,d.name,d.shelter_id,s.name shelter_name,d.avatar_key,d.adoption_status,f.saved_at, (SELECT c.id FROM shelter.chat_sessions c WHERE c.user_id=f.user_id AND c.dog_id=d.id AND c.status='OPEN' ORDER BY c.created_at DESC,c.id DESC LIMIT 1) session_id FROM shelter.saved_dogs f JOIN shelter.dogs d ON d.id=f.dog_id JOIN shelter.shelters s ON s.id=d.shelter_id WHERE f.user_id=:user AND f.active AND "+PUBLIC_DOG+" AND (strpos(lower(d.name),lower(:q))>0 OR strpos(lower(s.name),lower(:q))>0)";
        if(shelter!=null) sql+=" AND s.id=:shelter";
        if(after!=null) sql+=" AND (f.saved_at,d.id)<(:at,:id)";
        var stmt=jdbc.sql(sql+" ORDER BY f.saved_at DESC,d.id DESC LIMIT :count").param("user",user).param("q",q).param("count",count+1);
        if(shelter!=null) stmt=stmt.param("shelter",shelter);
        if(after!=null) stmt=stmt.param("at",after.at().atOffset(java.time.ZoneOffset.UTC)).param("id",after.id());
        var rows=stmt.query((rs,n)->new SavedDog(uuid(rs,"id"),rs.getString("name"),uuid(rs,"shelter_id"),rs.getString("shelter_name"),rs.getString("avatar_key"),rs.getString("adoption_status"),rs.getTimestamp("saved_at").toInstant(),uuid(rs,"session_id"))).list();
        var data=rows.stream().limit(count).toList(); var last=data.isEmpty()?null:data.getLast();
        return new Page<>(data,rows.size()>count?cursor(scope,last.savedAt()+"|"+last.dogId()):null);
    }
    public Page<Conversation> conversations(UUID subject,String search,boolean savedOnly,String cursor,String limitValue) {
        UUID user=accounts.profile(subject).id();String q=query(search);int count=limit(limitValue);
        String scope=scope("dog-conversations",user,q,savedOnly);var after=activity(cursor,scope);
        String visible="("+PUBLIC_DOG+")";
        String sql="SELECT c.id,c.dog_id,c.updated_at,"+visible+" available,CASE WHEN "+visible+" THEN d.name ELSE '현재 볼 수 없는 친구' END dog_name,CASE WHEN "+visible+" THEN s.name END shelter_name,CASE WHEN "+visible+" THEN d.avatar_key END avatar_key,coalesce(f.active,false) saved,m.content,m.role FROM shelter.chat_sessions c JOIN shelter.dogs d ON d.id=c.dog_id JOIN shelter.shelters s ON s.id=d.shelter_id LEFT JOIN shelter.saved_dogs f ON f.user_id=c.user_id AND f.dog_id=c.dog_id LEFT JOIN LATERAL (SELECT content,role FROM shelter.chat_messages WHERE session_id=c.id ORDER BY created_at DESC,id DESC LIMIT 1) m ON true WHERE c.user_id=:user AND (:q='' OR ("+visible+" AND (strpos(lower(d.name),lower(:q))>0 OR strpos(lower(s.name),lower(:q))>0)))";
        if(savedOnly) sql+=" AND f.active";
        if(after!=null) sql+=" AND (c.updated_at,c.id)<(:at,:id)";
        var stmt=jdbc.sql(sql+" ORDER BY c.updated_at DESC,c.id DESC LIMIT :count").param("user",user).param("q",q).param("count",count+1);
        if(after!=null) stmt=stmt.param("at",after.at().atOffset(java.time.ZoneOffset.UTC)).param("id",after.id());
        var rows=stmt.query((rs,n)->new Conversation(uuid(rs,"id"),uuid(rs,"dog_id"),rs.getString("dog_name"),rs.getString("shelter_name"),rs.getString("avatar_key"),rs.getBoolean("available"),rs.getBoolean("saved"),rs.getString("content"),rs.getString("role"),rs.getTimestamp("updated_at").toInstant())).list();
        var data=rows.stream().limit(count).toList();var last=data.isEmpty()?null:data.getLast();
        return new Page<>(data,rows.size()>count?cursor(scope,last.updatedAt()+"|"+last.sessionId()):null);
    }
    public Page<NearbyShelter> discovery(String search,String regionValue,String latitude,String longitude,String cursor,String limitValue) {
        String q=query(search),region=query(regionValue); Double lat=coordinate(latitude,90),lon=coordinate(longitude,180);
        if((lat==null)!=(lon==null)) throw FeatureException.invalid();
        int count=limit(limitValue);String scope=scope("discovery",q,region,lat,lon);String position=position(cursor,scope);
        Double afterDistance=null;UUID afterId=null;
        if(position!=null) {
            try { var bits=position.split("\\|",-1);if(bits.length!=2) throw FeatureException.invalid();afterDistance=Double.parseDouble(bits[0]);afterId=id(bits[1]);if(!Double.isFinite(afterDistance)||afterDistance<0||afterDistance>30000000) throw FeatureException.invalid(); }
            catch(RuntimeException ex) { throw FeatureException.invalid(); }
        }
        // Clamp the spherical cosine to avoid floating point acos domain errors at identical coordinates.
        String distance=lat==null?"NULL::double precision":"CASE WHEN s.latitude IS NOT NULL AND s.longitude IS NOT NULL THEN 6371000.0*acos(least(1.0,greatest(-1.0,sin(radians(:lat))*sin(radians(s.latitude::float8))+cos(radians(:lat))*cos(radians(s.latitude::float8))*cos(radians(s.longitude::float8-:lon))))) END";
        String sql="WITH nearby AS (SELECT s.id,s.name,s.region,s.address,s.latitude,s.longitude,s.map_key,"+distance+" distance_meters,(SELECT count(*) FROM shelter.dogs d WHERE d.shelter_id=s.id AND d.is_public AND d.archived_at IS NULL AND d.adoption_status IN ('AVAILABLE','IN_PROGRESS')) dog_count FROM shelter.shelters s WHERE "+PUBLIC_SHELTER+" AND starts_with(s.region,:region) AND (strpos(lower(s.name),lower(:q))>0 OR strpos(lower(coalesce(s.address,'')),lower(:q))>0)) SELECT * FROM nearby";
        if(afterId!=null) sql+=" WHERE (coalesce(distance_meters,30000000),id)>(:distance,:id)";
        var stmt=jdbc.sql(sql+" ORDER BY coalesce(distance_meters,30000000),id LIMIT :count").param("region",region).param("q",q).param("count",count+1);
        if(lat!=null) stmt=stmt.param("lat",lat).param("lon",lon);
        if(afterId!=null) stmt=stmt.param("distance",afterDistance).param("id",afterId);
        var rows=stmt.query((rs,n)->new NearbyShelter(uuid(rs,"id"),rs.getString("name"),rs.getString("region"),rs.getString("address"),number(rs,"latitude"),number(rs,"longitude"),rs.getObject("distance_meters",Double.class),rs.getString("map_key"),rs.getLong("dog_count"))).list();
        var data=rows.stream().limit(count).toList();var last=data.isEmpty()?null:data.getLast();
        return new Page<>(data,rows.size()>count?cursor(scope,(last.distanceMeters()==null?30000000:last.distanceMeters())+"|"+last.id()):null);
    }
    private record Activity(Instant at,UUID id) {}
    private Activity activity(String cursor,String scope) {
        String value=position(cursor,scope);if(value==null)return null;
        var bits=value.split("\\|",-1);if(bits.length!=2)throw FeatureException.invalid();
        return new Activity(instant(bits[0]),id(bits[1]));
    }
    private static Double number(ResultSet rs,String field) throws SQLException { var n=rs.getBigDecimal(field); return n==null?null:n.doubleValue(); }
    private static UUID uuid(ResultSet rs,String field) throws SQLException { return rs.getObject(field,UUID.class); }
}
