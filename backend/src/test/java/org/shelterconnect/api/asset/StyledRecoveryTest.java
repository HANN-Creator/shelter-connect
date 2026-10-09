package org.shelterconnect.api.asset;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class StyledRecoveryTest {
    final JsonMapper json=JsonMapper.builder().build();
    byte[] seed()throws Exception{return new StyledSpriteCodecTest().image(32);}
    @Test void paddingAndSheetRoundTripPreserveEverySourcePixelIncludingTransparentRgb()throws Exception {
        byte[] original=seed(),padded=StyledSpriteCodec.paddedSeed(original);var a=StyledSpriteCodec.nativeFrame(original);var b=StyledSpriteCodec.motionFrame(padded);
        assertThat(b.getWidth()).isEqualTo(40);
        for(int y=0;y<40;y++)for(int x=0;x<40;x++)assertThat(b.getRGB(x,y)).isEqualTo(x>=4 && x<36 && y>=4 && y<36?a.getRGB(x-4,y-4):0);
        var frames=Collections.nCopies(9,padded);var round=StyledSpriteCodec.frames(StyledSpriteCodec.sheet(frames,padded));
        assertThat(round).hasSize(9);for(byte[] frame:round)assertThat(frame).isEqualTo(padded);
        assertThatThrownBy(()->StyledSpriteCodec.sheet(frames,original)).hasMessage("STYLED_FRAME_INVALID");
    }
    @Test void anchoringNeverErasesChangedGeometryOrRecolorsFramesOneToEight()throws Exception {
        byte[] seed=StyledSpriteCodec.paddedSeed(seed());var raw=StyledSpriteCodec.motionFrame(seed);raw.setRGB(13,10,0xff993355);
        byte[] changed=StyledSpriteCodec.png(raw);var frames=new ArrayList<>(Collections.nCopies(9,changed));
        var anchored=StyledSpriteCodec.anchorEdit(frames,seed);assertThat(anchored.getFirst()).isEqualTo(seed);
        for(int i=1;i<9;i++)assertThat(anchored.get(i)).isSameAs(changed);
        raw.setRGB(39,10,0xff993355);frames.set(0,StyledSpriteCodec.png(raw));
        assertThat(StyledSpriteCodec.anchorEdit(frames,seed)).isSameAs(frames);
        assertThat(StyledQualityAgent.touchesEdge(raw)).isTrue();
        assertThat(StyledQualityAgent.touchesEdge(StyledSpriteCodec.motionFrame(seed))).isFalse();
    }
    @Test void wholeClipEditsKeepLastFrameAndDoNotDependOnLowTailOrSecondAttempt()throws Exception {
        byte[] padded=StyledSpriteCodec.paddedSeed(seed());var frames=new ArrayList<>(Collections.nCopies(9,padded));
        var last=StyledSpriteCodec.motionFrame(padded);last.setRGB(39,20,0xffaa8877);frames.set(8,StyledSpriteCodec.png(last));
        var report=json.readTree("{\"issues\":[\"CANVAS_CLIPPING\",\"TAIL_CARRIAGE\"],\"frames\":[8],\"edgeFrames\":[8],\"note\":\"Tail clips in final frame\"}");
        var payload=StyledRecovery.motionPayload(json,frames,"WALK","south",report,42);
        assertThat(payload.path("frames").size()).isEqualTo(9);
        assertThat(payload.path("description").asText()).contains("crown","complete","WALK").hasSizeLessThanOrEqualTo(2000);
        var decoded=StyledPixelLabClient.decode(payload.at("/frames/8/image/base64").asText());
        assertThat(StyledSpriteCodec.motionFrame(decoded).getRGB(39,20)).isEqualTo(0xffaa8877);
    }
    @Test void nativeFirstGenerationUsesSamePreventionAndAllDirectionsFitProviderLimits()throws Exception {
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var traits=json.valueToTree(Map.of("seed",42,"sourcePhotoSha256",StyledSpriteCodec.sha(seed()),"faceBox",List.of(0,0,1,1),"identityDescription","a".repeat(850),
            "motionDescription","tan and white puppy","rearDescription","same puppy rear","reviewNote","actual photo visually reviewed"));
        var policy=json.valueToTree(Map.of("recoveryVersion",StyledRecovery.VERSION,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"attempt",0,"issues",List.of(),"contract",Map.of("tailCarriage","HIGH")));
        var base=codec.character(UUID.randomUUID(),traits,seed(),policy);
        assertThat(base.path("description").asText()).contains("white/colored","complete side tails").hasSizeLessThanOrEqualTo(2000);
        for(String d:StyledSpriteCodec.DIRECTIONS)for(String action:StyledSpriteCodec.ACTIONS){
            var motion=codec.motion(traits,action,d,StyledSpriteCodec.paddedSeed(seed()),policy);
            assertThat(motion.path("description").asText()).contains("40x40","crown").hasSizeLessThanOrEqualTo(1000);
        }
    }
    @Test void baseEditingRejectsStaleSourceAndNeverUsesPhotoAsStyleTransfer()throws Exception {
        var seeds=Collections.nCopies(4,seed());var report=json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION)
            .put("inputSha256",StyledSeedQualityAgent.binding(seeds)).put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("repairDescription","Restore the actual white blaze and chest patch.");
        var p=StyledRecovery.basePayload(json,seeds,report,1);
        assertThat(p.path("method").asText()).isEqualTo("edit_with_text");assertThat(p.has("reference_image")).isFalse();assertThat(p.path("edit_images").size()).isEqualTo(4);
        report.put("inputSha256","0".repeat(64));assertThatThrownBy(()->StyledRecovery.basePayload(json,seeds,report,2)).hasMessage("RECOVERY_INPUT_CHANGED");
    }
    @Test void requiredTailPropertyCannotBeBypassedByAnEmptyIssueList() {
        var result=json.createObjectNode().put("passed",true).put("confidence",.95);result.putArray("issues");
        StyledRecoveryReview.MOTION.keySet().forEach(k->result.put(k,true));
        assertThat(StyledRecoveryReview.motionIssues(result)).isEmpty();
        result.put("tailConsistent",false);assertThat(StyledRecoveryReview.motionIssues(result)).containsExactly("TAIL_CARRIAGE");
        result.remove("tailConsistent");assertThatThrownBy(()->StyledRecoveryReview.motionIssues(result)).hasMessage("QUALITY_RECOVERY_RESPONSE_INVALID");
    }
    @Test void separatePhotoAndNativeViewInputsPreserveTheirDifferentPurposes()throws Exception {
        byte[] nativeSeed=seed();var inputs=StyledRecoveryReview.seedImages(nativeSeed,Collections.nCopies(4,nativeSeed),json.readTree("{\"faceBox\":[0,0,0.5,1]}"));
        assertThat(inputs.keySet()).containsExactly("Actual body photo","Actual face photo","Approved art style only; not this dog","Unapproved view: south","Unapproved view: north","Unapproved view: west","Unapproved view: east");
        var photo=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(inputs.get("Actual face photo")));assertThat(photo.getWidth()).isEqualTo(16);
        var board=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(inputs.get("Unapproved view: south")));assertThat(board.getHeight()).isEqualTo(256);
        assertThat(inputs.get("Actual body photo")).isEqualTo(nativeSeed);
    }
    @Test void crownPixelGuardRejectsActualMissWhilePreservingAllTenNormalWalks()throws Exception {
        var root=java.nio.file.Path.of("scripts/fixtures/pilot10-recovery-v15");var rules=StyledSpriteCodec.qualityRules(json).at("/recovery/frontalHeadGrowth");
        var seed=java.nio.file.Files.readAllBytes(root.resolve("seeds/south.png"));var bad=new ArrayList<byte[]>();
        for(int i=0;i<9;i++)bad.add(java.nio.file.Files.readAllBytes(root.resolve("initial-walk-south/"+String.format("%02d",i)+".png")));
        assertThat(StyledQualityAgent.frontalHeadGrowth(seed,bad,rules)).containsExactly(4,5,6);
        try(var dirs=java.nio.file.Files.list(root.resolve("front-walk-regressions"))) {
            var dogs=dirs.filter(java.nio.file.Files::isDirectory).toList();assertThat(dogs).hasSize(10);
            for(var d:dogs)assertThat(StyledQualityAgent.frontalHeadGrowth(java.nio.file.Files.readAllBytes(d.resolve("seed.png")),
                StyledSpriteCodec.frames(java.nio.file.Files.readAllBytes(d.resolve("walk.png"))),rules)).as(d.getFileName().toString()).isEmpty();
        }
    }
    @Test void recoveryBudgetsArePinnedAndFailClosed() {
        var policy=json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION).put("maxSeedRepairs",3).put("maxRepairsPerClip",3);
        assertThat(StyledRecovery.limit(policy,true)).isEqualTo(3);assertThat(StyledRecovery.limit(null,false)).isEqualTo(2);
        policy.put("maxSeedRepairs",100);assertThatThrownBy(()->StyledRecovery.limit(policy,true)).hasMessage("RECOVERY_POLICY_INVALID");
    }
}
