package org.shelterconnect.api.management;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.shelterconnect.api.auth.ShelterAccessService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.shelterconnect.api.management.ManagementResponses.*;

/** Fetch before the write transaction; a public dog and its real shelter commit together. */
@Service
public class PublicDogRegistration {
    private static final Pattern MARKER=Pattern.compile("public animal ([0-9]{15})\\.");
    private final ShelterAccessService access;
    private final ManagementRepository dogs;
    private final PublicSourceService sources;
    private final TransactionTemplate transaction;

    public PublicDogRegistration(ShelterAccessService access,ManagementRepository dogs,
            PublicSourceService sources,PlatformTransactionManager manager) {
        this.access=access;this.dogs=dogs;this.sources=sources;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(10);
    }

    static String animalId(JsonNode body) {
        String explicit=null;
        if(body.has("publicAnimalId")) {
            if(!body.path("publicAnimalId").isString() || !body.path("publicAnimalId").asText().matches("[0-9]{15}"))
                throw ManagementException.invalid("publicAnimalId는 공공 동물번호 15자리여야 해요.");
            explicit=body.path("publicAnimalId").asText();
        }
        String text=body.path("introduction").asText("");
        var matcher=MARKER.matcher(text);String legacy=null;int count=0;
        while(matcher.find()) { legacy=matcher.group(1);count++; }
        if(count>1 || (text.contains("public animal ") && count!=1)
                || (explicit!=null && legacy!=null && !explicit.equals(legacy)))
            throw ManagementException.invalid("공공 동물번호와 기존 출처 표기가 일치해야 해요.");
        return explicit==null?legacy:explicit;
    }

    public Dog create(UUID subject,JsonNode body) {
        if(body==null || !body.isObject())throw ManagementException.invalid("JSON 객체를 보내 주세요.");
        String animal=animalId(body);
        ObjectNode normalized=((ObjectNode)body).deepCopy();normalized.remove("publicAnimalId");
        ManagementInput.dogBody(normalized,true);
        if(animal!=null && !MARKER.matcher(normalized.path("introduction").asText("")).find()) {
            if(normalized.hasNonNull("introduction") && !normalized.path("introduction").isString())
                throw ManagementException.invalid("introduction 값을 확인해 주세요.");
            normalized.put("introduction",("public animal "+animal+". "+normalized.path("introduction").asText("")).strip());
        }
        UUID scope=ManagementInput.shelterId(normalized);
        DogFields fields=ManagementInput.dogFields(normalized,DogFields.defaults());
        access.requireShelter(subject,scope);
        if(animal!=null && fields.isPublic())
            throw ManagementException.conflict("PUBLIC_SOURCE_PRIVATE_REQUIRED","공공 동물은 비공개로 먼저 등록해 주세요.");
        // No row or paid generation is created when government lookup fails.
        JsonNode record=animal==null?null:sources.fetch(animal);
        try {
            return transaction.execute(status->{
                access.requireShelterForWrite(subject,scope);
                Dog dog=dogs.createDog(scope,fields);
                if(record!=null)sources.applyVerified(dog.id(),dog.updatedAt(),record);
                return dogs.dog(dog.id(),scope).orElseThrow(ManagementException::missing);
            });
        } catch(DataAccessException failure) { throw sources.translate(failure); }
    }
}
