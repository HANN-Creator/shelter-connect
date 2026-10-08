package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Explicit approved-image check, never enabled by CI/build. Keeps every paid acknowledgement and original. */
class StyledMotionLearningLiveTest {
    final JsonMapper json=JsonMapper.builder().build();
    void save(Path p,JsonNode value)throws Exception {Files.write(p,json.writeValueAsBytes(value));}
    List<byte[]> seeds(Path p)throws Exception {var r=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)r.add(Files.readAllBytes(p.resolve(d+".png")));return r;}
    @Test void approvedPhotoGeneratesSafeNewSeeds()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("OSHU_NEW_SEED_LIVE")));
        var root=Path.of(System.getenv("OSHU_EDIT_REPORT")).resolve("new-seed");
        var props=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var quality=new StyledSeedQualityAgent(new OpenAiResponsesClient(props,json),props,json);
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_local_test","dog-photos","dog-assets"),json);
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        byte[] photo=Files.readAllBytes(root.resolve("photo.png"));var traits=json.readTree(Files.readAllBytes(root.resolve("traits.json")));
        var policy=json.createObjectNode().put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("seedMotionMargin",2);
        JsonNode previous=null;
        for(int attempt=0;attempt<=2;attempt++) {
            Path run=root.resolve("attempt-"+attempt);Files.createDirectories(run);
            Path reviewed=run.resolve("review.json");
            if(Files.exists(reviewed)){previous=json.readTree(Files.readAllBytes(reviewed));if(previous.path("passed").asBoolean())break;continue;}
            policy.put("attempt",attempt);policy.set("issues",previous==null?json.createArrayNode():previous.path("issues"));
            var payload=codec.character(UUID.fromString("a27997b3-2aab-473c-99c5-a7cab6703cf5"),traits,photo,policy);
            Path ack=run.resolve("ack.json"),intent=run.resolve("submitted.json"),completed=run.resolve("provider-result.json");UUID id;
            if(Files.exists(ack))id=UUID.fromString(json.readTree(Files.readAllBytes(ack)).path("id").asText());
            else {assertThat(Files.exists(intent)).as("Do not resubmit an unknown paid outcome").isFalse();save(intent,json.valueToTree(Map.of("requestSha256",StyledSpriteCodec.sha(json.writeValueAsBytes(payload)))));
                id=provider.submit(true,payload);save(ack,json.valueToTree(Map.of("id",id.toString())));}
            JsonNode result=Files.exists(completed)?json.readTree(Files.readAllBytes(completed)):null;
            for(int polls=0;result==null && polls<120;polls++) {var r=provider.poll(id,true);if(r.path("status").asText().equals("COMPLETED")){result=r;save(completed,result);}
                else {assertThat(r.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}}
            assertThat(result).isNotNull();var seeds=new ArrayList<byte[]>();
            for(String direction:StyledSpriteCodec.DIRECTIONS){byte[] bytes=Base64.getDecoder().decode(result.at("/directions/"+direction).asText());seeds.add(bytes);Files.write(run.resolve(direction+".png"),bytes);}
            previous=StyledSeedQualityAgent.motionMargin(quality.review(photo,seeds),seeds,policy,json);save(reviewed,previous);
            if(previous.path("passed").asBoolean())break;
        }
        save(root.resolve("outcome.json"),json.valueToTree(Map.of("passed",previous!=null && previous.path("passed").asBoolean(),"qualityReport",previous)));
    }
    @Test void approvedHistoricalMotionLearnsAndEditsFullNativeStrips()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("OSHU_EDIT_LIVE")));
        var root=Path.of(System.getenv("OSHU_EDIT_REPORT"));var fixtures=Path.of("scripts/fixtures/oshu-motion-learning-v13");
        var props=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var client=new OpenAiResponsesClient(props,json);var quality=new StyledQualityAgent(client,props,json);var learner=new StyledLessonAgent(client,json);
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_local_test","dog-photos","dog-assets"),json);
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var sourceSeeds=seeds(fixtures.resolve("directions"));var contract=json.readTree("{\"tailCarriage\":\"UNKNOWN\"}");
        for(String action:List.of("WALK","SIT"))for(String direction:List.of("west","east")) {
            boolean regenerate="true".equals(System.getenv("OSHU_REGENERATE_LIVE"));
            String label=action.toLowerCase()+"-"+direction;Path run=root.resolve((regenerate?"regenerated/":"edits/")+label);Files.createDirectories(run);
            if(Files.exists(run.resolve("outcome.json")))continue;
            var audit=json.createObjectNode().put("label",label).put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("productionPublished",false);
            try {
                Path positive=root.resolve("positive-candidates/"+label);var goodSeeds=seeds(positive);
                byte[] goodBytes=Files.readAllBytes(positive.resolve("sheet.png")),badBytes=Files.readAllBytes(fixtures.resolve("sheets/"+label+".png"));
                var bad=StyledSpriteCodec.frames(badBytes);var good=StyledSpriteCodec.frames(goodBytes);
                var badReport=json.readTree(Files.readAllBytes(root.resolve("live-review/"+label+".json")));
                assertThat(badReport.path("inputSha256").asText()).isEqualTo(StyledSpriteCodec.sha(badBytes));
                assertThat(badReport.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
                Path checked=run.resolve("positive-review.json");JsonNode goodReport;
                if(Files.exists(checked))goodReport=json.readTree(Files.readAllBytes(checked));
                else {goodReport=quality.review(contract,goodSeeds,good,action,direction);save(checked,goodReport);}
                if(!goodReport.path("passed").asBoolean()){audit.put("state","NO_APPROVED_MOTION_REFERENCE");save(run.resolve("outcome.json"),audit);continue;}
                var scope=json.createObjectNode().put("action",action).put("direction",direction).put("tail","UNKNOWN").put("issue","CANVAS_CLIPPING");
                JsonNode candidate=null;boolean valid=false;
                var cases=List.of(new StyledLessonAgent.Case("CASE_A",goodReport,goodSeeds,good),new StyledLessonAgent.Case("CASE_B",badReport,sourceSeeds,bad));
                for(int revision=0;revision<=2;revision++) {
                    Path proposed=run.resolve("candidate-"+revision+".json"),replayed=run.resolve("replay-"+revision+".json");
                    candidate=Files.exists(proposed)?json.readTree(Files.readAllBytes(proposed)):learner.propose(scope,cases.get(1));save(proposed,candidate);
                    JsonNode verdict=Files.exists(replayed)?json.readTree(Files.readAllBytes(replayed)):learner.replay(scope,candidate,cases);save(replayed,verdict);
                    var byKey=new HashMap<String,JsonNode>();verdict.path("cases").forEach(c->byKey.put(c.path("key").asText(),c));
                    valid=verdict.path("safeAndGeneral").asBoolean() && !byKey.get("CASE_A").path("violates").asBoolean() && byKey.get("CASE_B").path("violates").asBoolean();
                    for(var frame:badReport.path("edgeFrames"))if(byKey.get("CASE_B").path("frames").valueStream().noneMatch(n->n.asInt()==frame.asInt()))valid=false;
                    if(valid)break;
                    scope.set("revisionFeedback",json.valueToTree(Map.of("previousCandidate",candidate,"reviewReason",verdict.path("reason").asText(),
                        "failedChecks",List.of("Replay must accept normal motion and identify every measured clipped frame without weakening checks."))));
                }
                if(!valid){audit.put("state","RULE_VALIDATION_FAILED");save(run.resolve("outcome.json"),audit);continue;}
                var lesson=json.createObjectNode().put("id",UUID.randomUUID().toString()).put("sha256",StyledSpriteCodec.sha(json.writeValueAsBytes(candidate)))
                    .put("action",action).put("direction",direction).put("tail","UNKNOWN").put("issue","CANVAS_CLIPPING").put("rulesSha256",StyledSpriteCodec.qualityRulesSha())
                    .put("prevention",candidate.path("prevention").asText()).put("criterion",candidate.path("criterion").asText());
                var lessons=json.createArrayNode().add(lesson);save(run.resolve("lesson.json"),lessons);
                var generationTraits=json.createObjectNode().put("seed",2026100865).put("motionDescription","Approved black and tan puppy, rounded head, small floppy ears, complete short tail and compact paws.").put("rearDescription","Rear view of the same approved dog, face hidden.");
                var generationPolicy=json.valueToTree(Map.of("attempt",0,"contract",contract,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"issues",badReport.path("issues")));
                var payload=(tools.jackson.databind.node.ObjectNode)(regenerate?codec.motion(generationTraits,action,direction,sourceSeeds.get(StyledSpriteCodec.DIRECTIONS.indexOf(direction)),generationPolicy):codec.marginEdit(action,direction,badBytes,2026100864));
                payload.put("description",payload.path("description").asText()+" Lessons: "+candidate.path("prevention").asText());assertThat(payload.path("description").asText().length()).isLessThanOrEqualTo(regenerate?1000:2000);
                Path ack=run.resolve("ack.json"),intent=run.resolve("submitted.json"),completed=run.resolve("provider-result.json");UUID id;
                if(Files.exists(ack))id=UUID.fromString(json.readTree(Files.readAllBytes(ack)).path("id").asText());
                else {
                    assertThat(Files.exists(intent)).as("Unknown paid outcome must not be submitted twice").isFalse();
                    save(intent,json.valueToTree(Map.of("requestSha256",StyledSpriteCodec.sha(json.writeValueAsBytes(payload)),"sourceSha256",StyledSpriteCodec.sha(badBytes))));
                    id=regenerate?provider.submit(false,payload):provider.editAnimation(payload);save(ack,json.valueToTree(Map.of("id",id.toString())));
                }
                JsonNode response=Files.exists(completed)?json.readTree(Files.readAllBytes(completed)):null;
                for(int polls=0;response==null && polls<120;polls++) {var polled=provider.poll(id,false);
                    if(polled.path("status").asText().equals("COMPLETED")){response=polled;save(completed,response);}
                    else {assertThat(polled.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}
                }
                assertThat(response).isNotNull();var raw=response.path("frames").valueStream().map(n->Base64.getDecoder().decode(n.asText())).toList();
                assertThat(raw).hasSize(9);Files.write(run.resolve("raw.png"),StyledSpriteCodec.rawSheet(raw));
                var restored=regenerate?raw:StyledSpriteCodec.restoreEditPalette(raw,sourceSeeds.get(StyledSpriteCodec.DIRECTIONS.indexOf(direction)));
                Files.write(run.resolve("restored.png"),StyledSpriteCodec.rawSheet(restored));
                var rawReport=quality.review(contract,sourceSeeds,raw,action,direction,lessons);save(run.resolve("raw-review.json"),rawReport);
                var restoredReport=regenerate?rawReport:quality.review(contract,sourceSeeds,restored,action,direction,lessons);save(run.resolve("restored-review.json"),restoredReport);
                audit.put("strategy",regenerate?"validated-pixminimax-regeneration":"validated-pro-edit").put("qualityReviewCount",regenerate?1:2);
                audit.put("state",rawReport.path("passed").asBoolean() && restoredReport.path("passed").asBoolean()?"PASS":"EXHAUSTED");
                audit.put("providerJobId",id.toString());audit.set("rawIssues",rawReport.path("issues"));audit.set("restoredIssues",restoredReport.path("issues"));
            } catch(RuntimeException e) {audit.put("state","FAILED").put("errorType",e.getClass().getSimpleName());}
            save(run.resolve("outcome.json"),audit);System.out.println(label+": "+audit.path("state"));
        }
    }
}
