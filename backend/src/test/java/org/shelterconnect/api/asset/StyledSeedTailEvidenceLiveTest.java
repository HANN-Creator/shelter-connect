package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Explicit photo-authorized replay of negative/positive native assets; never generates or publishes images. */
class StyledSeedTailEvidenceLiveTest {
    @Test void actualTailObservationsRejectTheFalsePassAndKeepCompleteTails()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("TAIL_EVIDENCE_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var out=Path.of(System.getenv("TAIL_EVIDENCE_LIVE_OUTPUT"));Files.createDirectories(out);
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);var client=new OpenAiResponsesClient(ai,json);
        byte[] photo=Files.readAllBytes(Path.of(System.getenv("TAIL_EVIDENCE_LIVE_PHOTO")));
        var cases=new LinkedHashMap<String,Path>();cases.put("deployed",Path.of("scripts/fixtures/tail-evidence-v17"));
        cases.put("selected",Path.of("scripts/fixtures/selected-seed-repair-v16/selected"));var outcomes=new ArrayList<Object>();
        for(var entry:cases.entrySet()){
            var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(Files.readAllBytes(entry.getValue().resolve(d+".png")));
            String binding=StyledSpriteCodec.sha(String.join("|",StyledSpriteCodec.sha(photo),StyledSeedQualityAgent.binding(seeds),StyledSpriteCodec.qualityRulesSha()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path intent=out.resolve(entry.getKey()+"-intent.txt"),saved=out.resolve(entry.getKey()+"-review.json");JsonNode result;
            if(Files.exists(saved)){assertThat(Files.readString(intent)).matches("[a-f0-9]{64}");result=json.readTree(Files.readAllBytes(saved));
                // Completed replies carry each exact binding. Early intent digests used Map.of iteration order;
                // verify explicit fields instead of buying a new call merely to replace that nondeterministic digest.
                assertThat(result.path("inputSha256").asText()).isEqualTo(StyledSeedQualityAgent.binding(seeds));
                assertThat(result.path("photoSha256").asText()).isEqualTo(StyledSpriteCodec.sha(photo));
                assertThat(result.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
                var validated=StyledSeedTailEvidence.grounded(json,result.path("observation"),seeds);
                for(String field:List.of("version","inputSha256","photoSha256","rulesSha256","model","reviewedAt"))validated.set(field,result.path(field));
                Files.write(out.resolve(entry.getKey()+"-validated-review.json"),json.writeValueAsBytes(validated));result=validated;}
            else{assertThat(Files.exists(intent)).as("Do not repeat an unknown paid QA request").isFalse();Files.writeString(intent,binding,StandardOpenOption.CREATE_NEW);
                result=StyledSeedTailEvidence.review(client,ai,json,photo,seeds);Files.write(saved,json.writeValueAsBytes(result));}
            outcomes.add(Map.of("case",entry.getKey(),"passed",result.path("passed").asBoolean(),"expected",entry.getKey().equals("selected"),"failedDirections",result.path("failedDirections")));
        }
        Files.write(out.resolve("results.json"),json.writeValueAsBytes(outcomes));
        for(var item:outcomes){var r=json.valueToTree(item);assertThat(r.path("passed").asBoolean()).as(r.path("case").asText()).isEqualTo(r.path("expected").asBoolean());}
    }
}
