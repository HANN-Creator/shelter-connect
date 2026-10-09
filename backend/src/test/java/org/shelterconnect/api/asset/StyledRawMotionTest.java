package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class StyledRawMotionTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/native-rgba-v29");
    final UUID id=UUID.randomUUID(),dog=UUID.randomUUID();
    byte[] raw()throws Exception{return Files.readAllBytes(root.resolve("raw.png"));}
    byte[] seed()throws Exception{return StyledSpriteCodec.paddedSeed(Files.readAllBytes(root.resolve("seed.png")));}
    ObjectNode report()throws Exception {
        var r=(ObjectNode)json.readTree(Files.readAllBytes(root.resolve("idle-west-review.json")));
        // Replay the original observations under current code; never modify the saved real-run receipt.
        r.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        ((ObjectNode)r.path("rawEditReview")).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());return r;
    }
    ObjectNode result()throws Exception {
        var r=json.createObjectNode().put("sha256",StyledSpriteCodec.sha(Files.readAllBytes(root.resolve("restored.png"))))
            .put("frameSize",40).put("frameCount",9).put("durationMs",180).put("loop",true);
        r.putObject("rawEdit").put("sha256",StyledSpriteCodec.sha(raw())).put("key",dog+"/"+id+"/native-32/raw-edits/idle-west-2.png");return r;
    }
    StyledAssetStore.Work work(String status,JsonNode result) {
        var p=json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION).put("automaticApproval",StyledAutoApproval.VERSION)
            .put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("maxRepairsPerClip",3);
        return new StyledAssetStore.Work(id,dog,UUID.randomUUID(),"idle-west","IDLE","west",status,null,null,"dog-photos","photo.png",json.createObjectNode(),null,result,null,2,p);
    }
    @Test void actualRawPassBecomesDistinctCandidateWithoutRelabelingTheFailedPostprocess()throws Exception {
        var old=report();var saved=old.deepCopy();var previous=result();var candidate=StyledRawMotion.candidate(work("PERSISTING",previous),old,previous,raw(),seed(),old.path("seedHashes"),json);
        assertThat(candidate.result().path("sha256")).isEqualTo(previous.at("/rawEdit/sha256"));
        assertThat(candidate.result().path("key").asText()).contains("/sheets/raw-provider/");
        assertThat(candidate.result().has("rawEdit")).isFalse();
        assertThat(candidate.result().at("/derivation/rawProviderReview")).isEqualTo(old.path("rawEditReview"));
        assertThat(StyledRawMotion.bound(candidate.result(),candidate.report(),old.path("seedHashes"),"west",json)).isTrue();
        assertThat(old).isEqualTo(saved);assertThat(old.path("passed").asBoolean()).isFalse();
        assertThat(candidate.report().path("reviewedAt")).isEqualTo(old.at("/rawEditReview/reviewedAt"));
        assertThat(StyledRawMotion.eligible(work("PERSISTING",candidate.result()),candidate.report(),candidate.result())).isFalse();
    }
    @Test void laterIndependentCurrentReviewRetainsOriginalProvenanceWithoutRequiringIdenticalModelWording()throws Exception {
        var old=report();var previous=result();var c=StyledRawMotion.candidate(work("PERSISTING",previous),old,previous,raw(),seed(),old.path("seedHashes"),json);
        var d=(ObjectNode)c.result().path("derivation");var source=(ObjectNode)d.path("rawProviderReview");
        source.put("rulesSha256","1".repeat(64));d.put("rawReviewSha256",StyledAutoApproval.digest(source,json));
        var fresh=(ObjectNode)c.report().deepCopy();fresh.put("note","Independent current review with unchanged input pixels");
        assertThat(StyledRawMotion.bound(c.result(),fresh,old.path("seedHashes"),"west",json)).isTrue();
        fresh.putArray("frameHashes");assertThat(StyledRawMotion.bound(c.result(),fresh,old.path("seedHashes"),"west",json)).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"failed","uncertain","resolved","missing-observation","false-pass","stale","wrong-input","wrong-raw","wrong-frames","wrong-reference","wrong-seeds","audit-only","already-passed","legacy"})
    void malformedStaleUncertainOrAuditOnlyCandidatesCannotBeAdopted(String defect)throws Exception {
        var r=report();var previous=result();var raw=(ObjectNode)r.path("rawEditReview");var seeds=r.path("seedHashes");
        String status="PERSISTING";
        switch(defect){
            case "failed" -> raw.put("passed",false);
            case "uncertain" -> raw.put("motionDecision","UNCERTAIN");
            case "resolved" -> raw.putObject("tailGeometryReconciliation");
            case "missing-observation" -> raw.remove("initialVision");
            case "false-pass" -> ((ObjectNode)raw.at("/initialVision/properties/0")).put("state","UNCERTAIN");
            case "stale" -> raw.put("rulesSha256","0".repeat(64));
            case "wrong-input" -> r.put("inputSha256","0".repeat(64));
            case "wrong-raw" -> {r.put("rawEditSha256","0".repeat(64));((ObjectNode)previous.path("rawEdit")).put("sha256","0".repeat(64));}
            case "wrong-frames" -> raw.putArray("reviewedFrameHashes").add("0".repeat(64));
            case "wrong-reference" -> raw.put("referenceFrameSha256","0".repeat(64));
            case "wrong-seeds" -> seeds=json.createObjectNode();
            case "audit-only" -> status="CHECKING";
            case "already-passed" -> r.put("passed",true);
            case "legacy" -> previous.put("frameSize",32);
        }
        var w=work(status,previous);var expected=seeds;
        assertThatThrownBy(()->StyledRawMotion.candidate(w,r,previous,raw(),seed(),expected,json)).hasMessage("RAW_MOTION_SOURCE_CHANGED");
    }
    @ParameterizedTest @ValueSource(strings={"source","frames","review","direction","seeds"})
    void finalBindingRejectsChangedProvenance(String changed)throws Exception {
        var r=report();var previous=result();var c=StyledRawMotion.candidate(work("PERSISTING",previous),r,previous,raw(),seed(),r.path("seedHashes"),json);
        var d=(ObjectNode)c.result().path("derivation");
        switch(changed){
            case "source" -> d.put("sourceRawSha256","0".repeat(64));
            case "frames" -> d.putArray("rawFrameHashes");
            case "review" -> ((ObjectNode)d.path("rawProviderReview")).put("model","changed");
            case "direction" -> d.put("direction","east");
            case "seeds" -> d.putObject("seedHashes");
        }
        assertThat(StyledRawMotion.bound(c.result(),c.report(),r.path("seedHashes"),"west",json)).isFalse();
    }
}
