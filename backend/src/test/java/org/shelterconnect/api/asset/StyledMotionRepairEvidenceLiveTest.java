package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Explicit authorized local provider validation; no DB, publication or implicit paid retry. */
class StyledMotionRepairEvidenceLiveTest {
    @Test void disputedSeamUsesOneOriginalBudgetLoopCandidate()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("MOTION_LOOP_LIVE_APPROVED")));
        var t=new StyledMotionRepairEvidenceTest();var json=t.json;var seeds=t.seeds();
        var base=Path.of(System.getenv("MOTION_EVIDENCE_LIVE_OUTPUT"));
        var out=base.resolve(System.getenv().getOrDefault("MOTION_LOOP_LIVE_DIRECTORY","original-seam-edit"));Files.createDirectories(out);
        var report=json.readTree(Files.readAllBytes(base.resolve("current-rule-originals/walk-south-review.json")));
        assertThat(StyledMotionCandidate.plan(report,"WALK",json)).isNotNull();
        var traits=(ObjectNode)json.readTree(Files.readAllBytes(Path.of(System.getenv("MOTION_EVIDENCE_LIVE_TRAITS"))));
        traits.put("seed",(int)Math.floorMod(traits.path("seed").asLong()+7919L*3,2147483647L));
        var contract=json.readTree("{\"tailCarriage\":\"UNKNOWN\"}");
        var payload=StyledRecovery.motionPayload(json,t.frames("walk-south"),"WALK","south",report,traits.path("seed").asInt());
        assertThat(payload.path("frames")).hasSize(9);
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_local_test","dog-photos","dog-assets"),json);
        String hash=StyledSpriteCodec.sha(json.writeValueAsBytes(payload));var intent=out.resolve("intent.json");var ack=out.resolve("ack.json");var completed=out.resolve("provider-result.json");UUID ticket;
        if(Files.exists(ack)){assertThat(json.readTree(Files.readAllBytes(intent)).path("requestSha256").asText()).isEqualTo(hash);ticket=UUID.fromString(json.readTree(Files.readAllBytes(ack)).path("id").asText());}
        else {assertThat(Files.exists(intent)).as("Never resubmit unknown paid candidate").isFalse();
            Files.write(intent,json.writeValueAsBytes(Map.of("requestSha256",hash,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"replayedRepairCount",3,"originalRepairLimit",3,"unconfirmedMotionCandidate",true,"productionChanged",false)),StandardOpenOption.CREATE_NEW);
            Files.writeString(out.resolve("prompt.txt"),payload.path("description").asText());ticket=provider.editAnimation(payload);Files.write(ack,json.writeValueAsBytes(Map.of("id",ticket.toString())),StandardOpenOption.CREATE_NEW);}
        JsonNode result=Files.exists(completed)?json.readTree(Files.readAllBytes(completed)):null;
        for(int poll=0;result==null && poll<90;poll++){var r=provider.poll(ticket,false);if(r.path("status").asText().equals("COMPLETED")){result=r;Files.write(completed,json.writeValueAsBytes(r),StandardOpenOption.CREATE_NEW);}else{assertThat(r.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}}
        assertThat(result).isNotNull();byte[] sheet=StyledSpriteCodec.rawSheet(result.path("frames").valueStream().map(n->Base64.getDecoder().decode(n.asText())).toList());
        Files.write(out.resolve("walk-south.png"),sheet);var frames=StyledSpriteCodec.frames(sheet);
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);var qa=new StyledQualityAgent(new OpenAiResponsesClient(ai,json),ai,json);
        var r=reviewOnce(out,"walk-south",json,()->qa.review(contract,seeds,frames,"WALK","south"));
        Files.write(out.resolve("outcome.json"),json.writeValueAsBytes(Map.of("decision",r.path("motionDecision"),"passed",r.path("passed"),"issues",r.path("issues"),"uncertainty",r.path("uncertainProperties"),"productionChanged",false,"pixelLabRequests",1)));
    }
    @Test void originalHeldSheetsReceiveOneFreshCurrentRuleReview()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("MOTION_ORIGINAL_REVIEW_LIVE_APPROVED")));
        var t=new StyledMotionRepairEvidenceTest();var json=t.json;var seeds=t.seeds();
        var out=Path.of(System.getenv("MOTION_EVIDENCE_LIVE_OUTPUT")).resolve("current-rule-originals");Files.createDirectories(out);
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var qa=new StyledQualityAgent(new OpenAiResponsesClient(ai,json),ai,json);
        var summary=json.createObjectNode().put("productionChanged",false).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        for(String label:List.of("idle-west","walk-south","sit-south")) {
            String[] parts=label.split("-");String action=parts[0].toUpperCase(Locale.ROOT),direction=parts[1];
            var report=reviewOnce(out,label,json,()->qa.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),seeds,t.frames(label),action,direction));
            var route=summary.putObject(label).put("passed",report.path("passed").asBoolean()).put("decision",report.path("motionDecision").asText());
            route.put("staticIdleEligible",action.equals("IDLE") && StyledIdleHold.motionOnlyFailure(report));
            route.put("entryRestartEligible",StyledEntryPoseRepair.plan(report,action,json)!=null);
            route.set("issues",report.path("issues"));route.set("uncertainty",report.path("uncertainProperties"));
        }
        Files.write(out.resolve("outcome.json"),json.writeValueAsBytes(summary));
    }
    @Test void realHeldImagesUseNewInputsAndOneBoundedEvidenceRefinement()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("MOTION_EVIDENCE_LIVE_APPROVED")));
        var t=new StyledMotionRepairEvidenceTest();var json=t.json;
        var out=Path.of(System.getenv("MOTION_EVIDENCE_LIVE_OUTPUT"));Files.createDirectories(out);
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var client=new OpenAiResponsesClient(ai,json);var qa=new StyledQualityAgent(client,ai,json);var seeds=t.seeds();
        var contract=json.readTree("{\"tailCarriage\":\"UNKNOWN\"}");
        // New exact approved static image, not another judgment of the failed animation.
        var idle=Collections.nCopies(9,seeds.get(2));Files.write(out.resolve("idle-west-static.png"),StyledSpriteCodec.rawSheet(idle));
        var idleReview=reviewOnce(out,"idle-west-static",json,()->qa.review(contract,seeds,idle,"IDLE","west"));
        // Replay stored observations ONLY to select the new adjacent-pair evidence path.
        var walk=t.replay("walk-south");walk.remove(List.of("rawEditReview","restoredReview","rawEditSha256"));
        var walkReview=reviewOnce(out,"walk-south-focused",json,()->StyledWalkEvidence.refine(client,ai,json,walk,seeds,tFrames(t),"WALK","south"));
        assertThat(walkReview.path("initialVision")).isEqualTo(t.original("walk-south").path("initialVision"));
        // A visible synthetic seam jump must not be approved by the same focused observer.
        var broken=new ArrayList<>(t.frames("walk-south"));var last=StyledSpriteCodec.motionFrame(broken.get(8));
        var shifted=new java.awt.image.BufferedImage(40,40,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<40;y++)for(int x=0;x<40;x++)if((last.getRGB(x,y)>>>24)!=0 && x+5<40)shifted.setRGB(x+5,y,last.getRGB(x,y));
        broken.set(8,StyledSpriteCodec.png(shifted));Files.write(out.resolve("negative-jump.png"),StyledSpriteCodec.rawSheet(broken));
        var negative=t.replay("walk-south");negative.remove(List.of("rawEditReview","restoredReview","rawEditSha256"));
        // Harness input only: same selection conditions, changed pixels. No claim these were prior real observations.
        var negativeReview=reviewOnce(out,"negative-jump-focused",json,()->StyledWalkEvidence.refine(client,ai,json,negative,seeds,broken,"WALK","south"));
        assertThat(negativeReview.path("passed").asBoolean()).as("Known visible jump must remain held; do not reroll").isFalse();
        var traits=json.readTree(Files.readAllBytes(Path.of(System.getenv("MOTION_EVIDENCE_LIVE_TRAITS"))));
        var updated=(ObjectNode)traits.deepCopy();updated.put("seed",(int)Math.floorMod(traits.path("seed").asLong()+7919L*2,2147483647L));
        var policy=json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION).put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("attempt",0);
        policy.set("contract",contract);
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var payload=codec.motion(updated,"SIT","south",seeds.getFirst(),policy);
        assertThat(payload.at("/first_frame/base64").asText()).isEqualTo(Base64.getEncoder().encodeToString(seeds.getFirst()));
        String sha=StyledSpriteCodec.sha(json.writeValueAsBytes(payload));
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_local_test","dog-photos","dog-assets"),json);
        var intent=out.resolve("sit-intent.json");var ack=out.resolve("sit-ack.json");var completed=out.resolve("sit-provider-result.json");UUID ticket;
        if(Files.exists(ack)) {
            assertThat(json.readTree(Files.readAllBytes(intent)).path("requestSha256").asText()).isEqualTo(sha);
            ticket=UUID.fromString(json.readTree(Files.readAllBytes(ack)).path("id").asText());
        }else {
            assertThat(Files.exists(intent)).as("Do not repeat unknown paid submission").isFalse();
            Files.write(intent,json.writeValueAsBytes(Map.of("requestSha256",sha,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"repairCount",2,"originalRepairLimit",3,
                "firstFrameSha256",StyledSpriteCodec.sha(seeds.getFirst()),"strategy",StyledEntryPoseRepair.VERSION)),StandardOpenOption.CREATE_NEW);
            Files.writeString(out.resolve("sit-prompt.txt"),payload.path("description").asText());
            ticket=provider.submit(false,payload);Files.write(ack,json.writeValueAsBytes(Map.of("id",ticket.toString())),StandardOpenOption.CREATE_NEW);
        }
        JsonNode response=Files.exists(completed)?json.readTree(Files.readAllBytes(completed)):null;
        for(int poll=0;response==null && poll<90;poll++) {
            var result=provider.poll(ticket,false);
            if(result.path("status").asText().equals("COMPLETED")){response=result;Files.write(completed,json.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);}
            else {assertThat(result.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}
        }
        assertThat(response).isNotNull();var generated=response.path("frames").valueStream().map(n->Base64.getDecoder().decode(n.asText())).toList();
        byte[] sheet=StyledSpriteCodec.rawSheet(generated);Files.write(out.resolve("sit-south-restart.png"),sheet);var sit=StyledSpriteCodec.frames(sheet);
        var sitReview=reviewOnce(out,"sit-south-restart",json,()->qa.review(contract,seeds,sit,"SIT","south"));
        int paid=1;
        if(!sitReview.path("passed").asBoolean() && sitReview.path("motionDecision").asText().equals("CONFIRMED_DEFECT")) {
            // Same production edit builder, final original attempt 3; no budget reset or uncertain target.
            var edit=StyledRecovery.motionPayload(json,sit,"SIT","south",sitReview,
                (int)Math.floorMod(traits.path("seed").asLong()+7919L*3,2147483647L));
            var ei=out.resolve("sit-edit-3-intent.json");var ea=out.resolve("sit-edit-3-ack.json");var er=out.resolve("sit-edit-3-provider-result.json");
            String hash=StyledSpriteCodec.sha(json.writeValueAsBytes(edit));UUID eid;
            if(Files.exists(ea)){assertThat(json.readTree(Files.readAllBytes(ei)).path("requestSha256").asText()).isEqualTo(hash);eid=UUID.fromString(json.readTree(Files.readAllBytes(ea)).path("id").asText());}
            else {assertThat(Files.exists(ei)).as("Never retry unknown paid edit").isFalse();
                Files.write(ei,json.writeValueAsBytes(Map.of("requestSha256",hash,"repairCount",3,"sourceSha256",StyledSpriteCodec.sha(sheet))),StandardOpenOption.CREATE_NEW);
                Files.writeString(out.resolve("sit-edit-3-prompt.txt"),edit.path("description").asText());eid=provider.editAnimation(edit);Files.write(ea,json.writeValueAsBytes(Map.of("id",eid.toString())),StandardOpenOption.CREATE_NEW);}
            JsonNode rr=Files.exists(er)?json.readTree(Files.readAllBytes(er)):null;
            for(int poll=0;rr==null && poll<90;poll++){var v=provider.poll(eid,false);if(v.path("status").asText().equals("COMPLETED")){rr=v;Files.write(er,json.writeValueAsBytes(v),StandardOpenOption.CREATE_NEW);}else{assertThat(v.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}}
            assertThat(rr).isNotNull();byte[] repaired=StyledSpriteCodec.rawSheet(rr.path("frames").valueStream().map(n->Base64.getDecoder().decode(n.asText())).toList());
            Files.write(out.resolve("sit-south-edit-3.png"),repaired);var repairedFrames=StyledSpriteCodec.frames(repaired);
            sitReview=reviewOnce(out,"sit-south-edit-3",json,()->qa.review(contract,seeds,repairedFrames,"SIT","south"));paid++;
        }
        var results=json.createObjectNode().put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("productionChanged",false).put("paidPixelLabRequests",paid).put("automaticPackApproval",false);
        for(var e:Map.of("idle-west",idleReview,"walk-south",walkReview,"sit-south",sitReview).entrySet()) {
            var r=results.putObject(e.getKey());r.put("passed",e.getValue().path("passed").asBoolean()).put("decision",e.getValue().path("motionDecision").asText());
            r.set("issues",e.getValue().path("issues"));r.set("uncertainty",e.getValue().path("uncertainProperties"));
        }
        Files.write(out.resolve("outcomes.json"),json.writeValueAsBytes(results));
        System.out.println(json.writeValueAsString(results)); // Real outcomes reported separately from test execution success.
    }
    @Test void standingAndSeatedEndpointsConstrainANewTransitionWithoutReplacingGeneratedFrames()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("MOTION_ENDPOINT_LIVE_APPROVED")));
        var t=new StyledMotionRepairEvidenceTest();var json=t.json;var seeds=t.seeds();var previous=t.frames("sit-south");
        var out=Path.of(System.getenv("MOTION_EVIDENCE_LIVE_OUTPUT")).resolve("endpoint-candidate");Files.createDirectories(out);
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);var qa=new StyledQualityAgent(new OpenAiResponsesClient(ai,json),ai,json);
        var contract=json.readTree("{\"tailCarriage\":\"UNKNOWN\"}");
        var traits=(ObjectNode)json.readTree(Files.readAllBytes(Path.of(System.getenv("MOTION_EVIDENCE_LIVE_TRAITS"))));
        traits.put("seed",(int)Math.floorMod(traits.path("seed").asLong()+7919L*2,2147483647L));
        var policy=json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION).put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("attempt",0);policy.set("contract",contract);
        var payload=StyledEntryPoseRepair.endpoints(new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3")).motion(traits,"SIT","south",seeds.getFirst(),policy),previous,json);
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_local_test","dog-photos","dog-assets"),json);
        String hash=StyledSpriteCodec.sha(json.writeValueAsBytes(payload));var intent=out.resolve("intent.json");var ack=out.resolve("ack.json");var completed=out.resolve("provider-result.json");UUID ticket;
        if(Files.exists(ack)){assertThat(json.readTree(Files.readAllBytes(intent)).path("requestSha256").asText()).isEqualTo(hash);ticket=UUID.fromString(json.readTree(Files.readAllBytes(ack)).path("id").asText());}
        else{assertThat(Files.exists(intent)).as("Never resubmit an unknown paid endpoint candidate").isFalse();
            Files.write(intent,json.writeValueAsBytes(Map.of("requestSha256",hash,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"strategy","standing-seated-endpoints-restart-v1",
                "firstFrameSha256",StyledSpriteCodec.sha(seeds.getFirst()),"lastFrameSha256",StyledSpriteCodec.sha(previous.getLast()),"productionChanged",false,"replayedRepairCount",2)),StandardOpenOption.CREATE_NEW);
            Files.writeString(out.resolve("prompt.txt"),payload.path("description").asText());ticket=provider.submit(false,payload);Files.write(ack,json.writeValueAsBytes(Map.of("id",ticket.toString())),StandardOpenOption.CREATE_NEW);}
        JsonNode result=Files.exists(completed)?json.readTree(Files.readAllBytes(completed)):null;
        for(int poll=0;result==null && poll<90;poll++){var r=provider.poll(ticket,false);if(r.path("status").asText().equals("COMPLETED")){result=r;Files.write(completed,json.writeValueAsBytes(r),StandardOpenOption.CREATE_NEW);}else{assertThat(r.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}}
        assertThat(result).isNotNull();byte[] sheet=StyledSpriteCodec.rawSheet(result.path("frames").valueStream().map(n->Base64.getDecoder().decode(n.asText())).toList());
        Files.write(out.resolve("sit-south.png"),sheet);var frames=StyledSpriteCodec.frames(sheet);
        var r=reviewOnce(out,"sit-south",json,()->qa.review(contract,seeds,frames,"SIT","south"));
        Files.write(out.resolve("outcome.json"),json.writeValueAsBytes(Map.of("decision",r.path("motionDecision"),"passed",r.path("passed"),"issues",r.path("issues"),"uncertainty",r.path("uncertainProperties"),"productionChanged",false,"pixelLabRequests",1)));
    }

    @Test void endpointCandidateUsesOnlyTheRemainingConfirmedEdit()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("MOTION_ENDPOINT_EDIT_LIVE_APPROVED")));
        var t=new StyledMotionRepairEvidenceTest();var json=t.json;var seeds=t.seeds();
        var out=Path.of(System.getenv("MOTION_EVIDENCE_LIVE_OUTPUT")).resolve("endpoint-candidate");
        var prior=json.readTree(Files.readAllBytes(out.resolve("sit-south-review.json")));
        assertThat(prior.path("motionDecision").asText()).isEqualTo("CONFIRMED_DEFECT");
        var source=Files.readAllBytes(out.resolve("sit-south.png"));var frames=StyledSpriteCodec.frames(source);
        var traits=json.readTree(Files.readAllBytes(Path.of(System.getenv("MOTION_EVIDENCE_LIVE_TRAITS"))));
        var payload=StyledRecovery.motionPayload(json,frames,"SIT","south",prior,
            (int)Math.floorMod(traits.path("seed").asLong()+7919L*3,2147483647L));
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_local_test","dog-photos","dog-assets"),json);
        var intent=out.resolve("edit-intent.json");var ack=out.resolve("edit-ack.json");var completed=out.resolve("edit-provider-result.json");
        String hash=StyledSpriteCodec.sha(json.writeValueAsBytes(payload));UUID ticket;
        if(Files.exists(ack)){assertThat(json.readTree(Files.readAllBytes(intent)).path("requestSha256").asText()).isEqualTo(hash);ticket=UUID.fromString(json.readTree(Files.readAllBytes(ack)).path("id").asText());}
        else{assertThat(Files.exists(intent)).as("Never resubmit unknown paid edit").isFalse();
            Files.write(intent,json.writeValueAsBytes(Map.of("requestSha256",hash,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"sourceSha256",StyledSpriteCodec.sha(source),"replayedRepairCount",3,"originalRepairLimit",3,"productionChanged",false)),StandardOpenOption.CREATE_NEW);
            Files.writeString(out.resolve("edit-prompt.txt"),payload.path("description").asText());ticket=provider.editAnimation(payload);Files.write(ack,json.writeValueAsBytes(Map.of("id",ticket.toString())),StandardOpenOption.CREATE_NEW);}
        JsonNode result=Files.exists(completed)?json.readTree(Files.readAllBytes(completed)):null;
        for(int poll=0;result==null && poll<90;poll++){var r=provider.poll(ticket,false);if(r.path("status").asText().equals("COMPLETED")){result=r;Files.write(completed,json.writeValueAsBytes(r),StandardOpenOption.CREATE_NEW);}else{assertThat(r.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}}
        assertThat(result).isNotNull();byte[] sheet=StyledSpriteCodec.rawSheet(result.path("frames").valueStream().map(n->Base64.getDecoder().decode(n.asText())).toList());
        Files.write(out.resolve("sit-south-edit.png"),sheet);var repaired=StyledSpriteCodec.frames(sheet);
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);var qa=new StyledQualityAgent(new OpenAiResponsesClient(ai,json),ai,json);
        var r=reviewOnce(out,"sit-south-edit",json,()->qa.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),seeds,repaired,"SIT","south"));
        Files.write(out.resolve("edit-outcome.json"),json.writeValueAsBytes(Map.of("decision",r.path("motionDecision"),"passed",r.path("passed"),"issues",r.path("issues"),"uncertainty",r.path("uncertainProperties"),"productionChanged",false,"pixelLabRequests",1)));
    }

    static List<byte[]> tFrames(StyledMotionRepairEvidenceTest t){try{return t.frames("walk-south");}catch(Exception e){throw new RuntimeException(e);}}
    interface Review {JsonNode run()throws Exception;}
    static JsonNode reviewOnce(Path out,String label,JsonMapper json,Review review)throws Exception {
        var result=out.resolve(label+"-review.json");var intent=out.resolve(label+"-intent.json");
        if(Files.exists(result)) {
            var saved=json.readTree(Files.readAllBytes(result));assertThat(saved.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());return saved;
        }
        assertThat(Files.exists(intent)).as("Interrupted QA requires diagnosis, never blind reroll: "+label).isFalse();
        Files.write(intent,json.writeValueAsBytes(Map.of("rulesSha256",StyledSpriteCodec.qualityRulesSha(),"label",label)),StandardOpenOption.CREATE_NEW);
        var r=review.run();Files.write(result,json.writeValueAsBytes(r),StandardOpenOption.CREATE_NEW);return r;
    }
}
