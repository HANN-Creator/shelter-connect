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

class StyledMotionEquivalenceTest {
    final JsonMapper json=JsonMapper.builder().build();final Path root=Path.of("scripts/fixtures/sit-conflicts-v28");
    JsonNode front()throws Exception{return json.readTree(Files.readAllBytes(root.resolve("sit-south-review.json")));}
    List<byte[]> frames(String name)throws Exception{return StyledSpriteCodec.frames(Files.readAllBytes(root.resolve(name+".png")));}
    @Test void actualFrontConflictUsesExactGeometryAndKeepsOriginalUncertainty()throws Exception {
        var receipt=front();var raw=receipt.path("rawEditReview");var restored=receipt.path("restoredReview");
        var r=StyledMotionEquivalence.reconcile(raw,restored,frames("sit-south-raw"),frames("sit-south-restored"),"SIT","south",json);
        assertThat(r.path("passed").asBoolean()).isTrue();assertThat(r.path("occlusionOriginalReport")).isEqualTo(raw);
        assertThat(raw.path("motionDecision").asText()).isEqualTo("UNCERTAIN");assertThat(StyledMotionEquivalence.bound(r,restored,"SIT","south")).isTrue();
        var altered=(ObjectNode)restored.deepCopy();altered.putArray("reviewedFrameHashes").add("0".repeat(64));
        assertThat(StyledMotionEquivalence.bound(r,altered,"SIT","south")).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"alpha","dark","later-color","clipping","tail-fail","other-uncertainty","restored-fail","rear","walk","missing-frame","stale-hash"})
    void equivalenceCannotWaiveActualShapeChangesDefectsOrOtherUncertainty(String defect)throws Exception {
        var receipt=front();var raw=(ObjectNode)receipt.path("rawEditReview").deepCopy();var restored=(ObjectNode)receipt.path("restoredReview").deepCopy();
        var original=frames("sit-south-raw");var target=new ArrayList<>(frames("sit-south-restored"));String action="SIT",direction="south";
        switch(defect) {
            case "alpha","dark","later-color" -> {
                int index=defect.equals("later-color")?8:0;var im=StyledSpriteCodec.motionFrame(target.get(index));
                if(defect.equals("alpha"))im.setRGB(0,0,0xffffffff);
                else {outer:for(int y=0;y<40;y++)for(int x=0;x<40;x++)if((im.getRGB(x,y)>>>24)!=0 && (im.getRGB(x,y)&0xffffff)>0x777777){im.setRGB(x,y,defect.equals("dark")?0xff000000:0xffbb55ee);break outer;}}
                target.set(index,StyledSpriteCodec.png(im));restored.set("reviewedFrameHashes",json.valueToTree(target.stream().map(StyledSpriteCodec::sha).toList()));
            }
            case "clipping" -> raw.putArray("edgeFrames").add(8);
            case "tail-fail" -> ((ObjectNode)StyledMotionReview.property(raw.path("initialVision"),"tail")).put("state","FAIL");
            case "other-uncertainty" -> raw.putArray("uncertainProperties").add("tail").add("eyes");
            case "restored-fail" -> restored.put("passed",false);
            case "rear" -> direction="north";
            case "walk" -> action="WALK";
            case "missing-frame" -> target.remove(8);
            case "stale-hash" -> restored.putArray("reviewedFrameHashes").add("0".repeat(64));
        }
        assertThat(StyledMotionEquivalence.reconcile(raw,restored,original,target,action,direction,json)).isSameAs(raw);
    }
    @Test void actualPaletteDefectCanBeRepairedButNotApprovedOrLearned()throws Exception {
        var r=json.readTree(Files.readAllBytes(root.resolve("sit-north-review.json")));
        assertThat(StyledMotionReview.confirmedPaletteRepair(r)).isTrue();assertThat(r.path("passed").asBoolean()).isFalse();assertThat(StyledMotionReview.unresolved(r)).isTrue();
        var payload=StyledRecovery.motionPayload(json,frames("sit-north-restored"),"SIT","north",r,1);
        assertThat(payload.path("description").asText()).contains("color flicker","Preserve anatomy").hasSizeLessThanOrEqualTo(2000);
    }
    @ParameterizedTest @ValueSource(strings={"palette-pass","identity-disjoint","eyes","missing-observation","tail"})
    void appearanceRepairRejectsUnconfirmedOrUnrelatedDefects(String defect)throws Exception {
        var r=(ObjectNode)json.readTree(Files.readAllBytes(root.resolve("sit-north-review.json")));
        switch(defect) {
            case "palette-pass" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"palette")).put("state","PASS");
            case "identity-disjoint" -> ((ObjectNode)StyledMotionReview.property(r.path("consistencyReview"),"identity")).putArray("frames").add(0);
            case "eyes" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"eyes")).put("state","UNCERTAIN");
            case "missing-observation" -> r.remove("consistencyReview");
            case "tail" -> r.putArray("uncertainProperties").add("identity").add("tail");
        }
        assertThat(StyledMotionReview.confirmedPaletteRepair(r)).isFalse();
    }
}
