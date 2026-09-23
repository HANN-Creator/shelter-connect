package org.shelterconnect.api.behavior;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.ShelterAccessService;
import org.shelterconnect.api.chat.AiProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;
import static org.shelterconnect.api.behavior.BehaviorSuggestionProvider.Observation;

@Service
public class BehaviorSuggestionStore {
    @io.swagger.v3.oas.annotations.media.Schema(name="BehaviorSuggestionJob")
    public record Job(UUID id,UUID dogId,String status,String failureCode,JsonNode result,Instant createdAt) {}
    record Prepared(Job job,List<Observation> observations,boolean execute) {}
    private final JdbcClient jdbc;private final JsonMapper json;private final ShelterAccessService access;
    private final BehaviorRepository repository;private final AiProperties ai;private final int dailyLimit;
    public BehaviorSuggestionStore(JdbcClient jdbc,JsonMapper json,ShelterAccessService access,BehaviorRepository repository,AiProperties ai,
                                  @Value("${app.ai.behavior-daily-limit:20}") int dailyLimit) {
        if(dailyLimit<1 || dailyLimit>100) throw new IllegalArgumentException("BEHAVIOR_AI_DAILY_LIMIT must be 1..100");
        this.jdbc=jdbc;this.json=json;this.access=access;this.repository=repository;this.ai=ai;this.dailyLimit=dailyLimit;
    }
    @Transactional public Prepared prepare(UUID subject,UUID dog,JsonNode body) {
        var actor=access.requireDogForWrite(subject,dog);active(dog);
        if(body==null || !body.isObject() || body.size()!=3 || !body.path("clientRequestId").isString()
            || !body.path("expectedRevision").isIntegralNumber() || !body.path("expectedRevision").canConvertToInt()
            || body.path("expectedRevision").intValue()<0 || body.path("expectedRevision").intValue()==Integer.MAX_VALUE || !body.path("evidenceObservationIds").isArray()) throw BehaviorException.invalid();
        UUID request=BehaviorInput.id(body.path("clientRequestId").asText());int revision=body.path("expectedRevision").intValue();
        var ids=new TreeSet<UUID>();for(var id:body.path("evidenceObservationIds")) if(!id.isString() || !ids.add(BehaviorInput.id(id.asText()))) throw BehaviorException.invalid();
        if(ids.isEmpty() || ids.size()>20) throw BehaviorException.evidence();
        String evidence=json.writeValueAsString(ids);
        var old=jdbc.sql("SELECT id,expected_revision,evidence_ids::text FROM shelter.behavior_suggestions WHERE dog_id=:d AND client_request_id=:r")
            .param("d",dog).param("r",request).query((rs,n)->Map.of("id",rs.getObject("id",UUID.class),"revision",rs.getInt("expected_revision"),"evidence",rs.getString("evidence_ids"))).optional();
        if(old.isPresent()) {
            var value=old.get();if(!value.get("revision").equals(revision) || !json.readTree(value.get("evidence").toString()).equals(json.readTree(evidence))) throw conflict("REQUEST_ID_CONFLICT");
            return new Prepared(readJob((UUID)value.get("id"),dog),List.of(),false);
        }
        if(!ai.enabled()) throw new BehaviorException(503,"AI_NOT_CONFIGURED","AI 연결을 준비 중이에요.");
        var profile=repository.profile(dog).orElse(null);
        if(revision!=(profile==null?0:profile.revision())) throw BehaviorException.stale();
        if(!repository.evidenceValid(dog,List.copyOf(ids),true)) throw BehaviorException.evidence();
        var observations=observations(dog,ids);
        if(observations.stream().mapToInt(o->o.content().length()).sum()>20000) throw BehaviorException.invalid();
        jdbc.sql("SELECT pg_advisory_xact_lock(62019)").query(Object.class).single();
        int count=jdbc.sql("SELECT count(*) FROM shelter.behavior_suggestions WHERE created_at >= date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'").query(Integer.class).single();
        if(count>=dailyLimit) throw new BehaviorException(429,"BEHAVIOR_AI_DAILY_LIMIT","오늘의 행동 초안 생성 한도에 도달했어요.");
        UUID id=jdbc.sql("""
            INSERT INTO shelter.behavior_suggestions(dog_id,client_request_id,requested_by,expected_revision,evidence_ids,observations,status)
            VALUES (:d,:r,:u,:v,CAST(:e AS jsonb),CAST(:o AS jsonb),'PENDING') RETURNING id
            """).param("d",dog).param("r",request).param("u",actor.userId()).param("v",revision).param("e",evidence).param("o",json.writeValueAsString(observations)).query(UUID.class).single();
        return new Prepared(readJob(id,dog),observations,true);
    }
    @Transactional public Job read(UUID subject,UUID dog,UUID id) { access.requireDog(subject,dog);return readJob(id,dog); }
    @Transactional public Job complete(UUID subject,UUID dog,UUID id,BehaviorTraitMapping.Result generated,List<Observation> snapshot) {
        access.requireDogForWrite(subject,dog);active(dog);
        var job=readJob(id,dog);if(!job.status().equals("PENDING")) return job;
        var ids=snapshot.stream().map(Observation::id).toList();
        if(!repository.evidenceValid(dog,ids,true) || !observations(dog,new TreeSet<>(ids)).equals(snapshot)) throw BehaviorException.evidence();
        int revision=jdbc.sql("SELECT expected_revision FROM shelter.behavior_suggestions WHERE id=:id FOR UPDATE").param("id",id).query(Integer.class).single();
        var current=repository.profile(dog).orElse(null);if(revision!=(current==null?0:current.revision())) throw BehaviorException.stale();
        var settings=BehaviorInput.settings(json.valueToTree(generated.settings()));
        repository.save(dog,new Save(revision,settings,"AI_SUGGESTED",ids));
        var result=Map.of("profile",repository.profile(dog).orElseThrow(),"traits",generated.traits(),"requiresConfirmation",true);
        jdbc.sql("UPDATE shelter.behavior_suggestions SET status='COMPLETED',result=CAST(:r AS jsonb) WHERE id=:id AND status='PENDING'")
            .param("r",json.writeValueAsString(result)).param("id",id).update();
        return readJob(id,dog);
    }
    @Transactional public void fail(UUID id,String code) {
        jdbc.sql("UPDATE shelter.behavior_suggestions SET status='FAILED',failure_code=:c WHERE id=:id AND status='PENDING'").param("c",code).param("id",id).update();
    }
    private List<Observation> observations(UUID dog,Set<UUID> ids) {
        return jdbc.sql("SELECT id,category,content,observed_at,updated_at FROM shelter.dog_observations WHERE dog_id=:d AND id IN (:ids) ORDER BY id")
            .param("d",dog).param("ids",ids).query((rs,n)->new Observation(rs.getObject("id",UUID.class),rs.getString("category"),rs.getString("content"),rs.getTimestamp("observed_at").toInstant(),rs.getTimestamp("updated_at").toInstant())).list();
    }
    private Job readJob(UUID id,UUID dog) {
        jdbc.sql("UPDATE shelter.behavior_suggestions SET status='FAILED',failure_code='AI_OUTCOME_UNKNOWN' WHERE id=:id AND dog_id=:d AND status='PENDING' AND created_at < now()-make_interval(secs=>:seconds)")
            .param("id",id).param("d",dog).param("seconds",ai.leaseSeconds()).update();
        return jdbc.sql("SELECT id,dog_id,status,failure_code,result::text,created_at FROM shelter.behavior_suggestions WHERE id=:id AND dog_id=:d")
            .param("id",id).param("d",dog).query((rs,n)->new Job(id,dog,rs.getString("status"),rs.getString("failure_code"),rs.getString("result")==null?null:json.readTree(rs.getString("result")),rs.getTimestamp("created_at").toInstant()))
            .optional().orElseThrow(()->new BehaviorException(404,"SUGGESTION_NOT_FOUND","행동 초안을 찾을 수 없어요."));
    }
    private void active(UUID dog) { if(jdbc.sql("SELECT archived_at IS NOT NULL FROM shelter.dogs WHERE id=:d").param("d",dog).query(Boolean.class).single()) throw conflict("DOG_ARCHIVED"); }
    private static BehaviorException conflict(String code) { return new BehaviorException(409,code,"기존 요청과 현재 상태를 확인해 주세요."); }
}
