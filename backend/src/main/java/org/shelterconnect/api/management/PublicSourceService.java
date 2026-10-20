package org.shelterconnect.api.management;

import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.shelterconnect.api.auth.ShelterAccessService;
import tools.jackson.databind.JsonNode;

@Service
public class PublicSourceService {
    public record Source(UUID dogId,String animalId,UUID shelterId,UUID managementShelterId,
        String careRegNo,String shelterName,String region,String address,String contactPhone,
        String processState,Instant fetchedAt) {}
    private final JdbcClient jdbc;private final ShelterAccessService access;private final PublicAnimalClient source;
    private final TransactionTemplate transaction;
    public PublicSourceService(JdbcClient jdbc,ShelterAccessService access,PublicAnimalClient source,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.access=access;this.source=source;this.transaction=new TransactionTemplate(manager);
        this.transaction.setTimeout(10);
    }
    public Source get(UUID subject,UUID dog) {
        access.requireDog(subject,dog);
        return jdbc.sql("""
            SELECT a.*,coalesce(d.management_shelter_id,d.shelter_id) management_shelter_id,
                s.name,s.region,s.address,s.contact_phone,a.source_record->>'processState' process_state
            FROM shelter.public_animal_sources a JOIN shelter.dogs d ON d.id=a.dog_id
            JOIN shelter.shelters s ON s.id=a.shelter_id WHERE a.dog_id=:dog
            """).param("dog",dog).query((rs,n)->new Source(dog,rs.getString("animal_id"),rs.getObject("shelter_id",UUID.class),
                rs.getObject("management_shelter_id",UUID.class),rs.getString("care_reg_no"),rs.getString("name"),
                rs.getString("region"),rs.getString("address"),rs.getString("contact_phone"),rs.getString("process_state"),
                rs.getTimestamp("fetched_at").toInstant())).optional().orElseThrow(ManagementException::missing);
    }
    public Source link(UUID subject,UUID dog,JsonNode body) {
        access.requireDog(subject,dog);
        if(body==null || !body.isObject() || body.size()!=2 || !body.path("animalId").isString()
            || !body.path("animalId").asText().matches("[0-9]{15}") || !body.path("expectedUpdatedAt").isString())
            throw ManagementException.invalid("animalId와 expectedUpdatedAt을 확인해 주세요.");
        Instant version;
        try { version=Instant.parse(body.path("expectedUpdatedAt").asText()); }
        catch(Exception invalid) { throw ManagementException.invalid("expectedUpdatedAt을 확인해 주세요."); }
        // The HTTP request runs outside a DB transaction; scope/version are rechecked under locks afterwards.
        var record=source.fetch(body.path("animalId").asText());
        try {
            transaction.executeWithoutResult(status->{
                access.requireDogForWrite(subject,dog);
                jdbc.sql("SELECT shelter.link_public_animal(:dog,:version,CAST(:record AS jsonb),:fetched)")
                    .param("dog",dog).param("version",version.atOffset(ZoneOffset.UTC)).param("record",record.toString())
                    .param("fetched",OffsetDateTime.now(ZoneOffset.UTC)).query(UUID.class).single();
            });
        } catch(DataAccessException failure) {
            String message=Objects.toString(failure.getMostSpecificCause().getMessage(),"");
            for(String code:List.of("STALE_RESOURCE","PUBLIC_SOURCE_PRIVATE_REQUIRED","PUBLIC_SOURCE_IDENTITY_MISMATCH",
                    "PUBLIC_SOURCE_JOB_ACTIVE","PUBLIC_SOURCE_INVALID"))
                if(message.contains(code)) throw ManagementException.conflict(code,"공공 데이터 연결 조건을 확인해 주세요.");
            if(message.contains("public_animal_sources_animal_id_key"))
                throw ManagementException.conflict("PUBLIC_SOURCE_DUPLICATE","이미 등록된 공공 동물이 있어요.");
            throw failure;
        }
        return get(subject,dog);
    }
}
