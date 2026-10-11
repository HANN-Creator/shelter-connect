package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class StyledReferenceContourTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void retainedRealCurrentPolicyEvaluationSeparatesFivePositiveAndFiveNegativeCases()throws Exception {
        var root=StyledTailObservationConsistencyTest.ROOT.resolve("live-v43");
        var hashes=json.readTree(Files.readAllBytes(root.resolve("evidence.json"))).path("sha256");
        for(String file:hashes.propertyNames())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve(file)))).as(file).isEqualTo(hashes.path(file).asText());
        var results=json.readTree(Files.readAllBytes(root.resolve("results.json")));assertThat(results.size()).isEqualTo(10);
        for(var row:results)assertThat(row.path("actualPass")).as(row.path("case").asText()).isEqualTo(row.path("expectedPass"));
        var black=json.readTree(Files.readAllBytes(root.resolve("black-production.json")));
        assertThat(black.path("passed").asBoolean()).isTrue();assertThat(StyledTailAnatomy.boundPass(black)).isTrue();
        var cream=json.readTree(Files.readAllBytes(root.resolve("cream-motion.json")));
        assertThat(cream.path("passed").asBoolean()).isFalse();assertThat(cream.path("referencePoseUsable").asBoolean()).isFalse();
        assertThat(cream.at("/referenceContourEvidence/contrastChanges").size()).isEqualTo(12);
        assertThat(cream.path("reviewImageHashes").size()).isEqualTo(4);
    }
    @Test void actualTailChangeGetsAnUnmodifiedAlignedDetailWithoutInventingAVerdict()throws Exception {
        var root=StyledTailObservationConsistencyTest.ROOT.resolve("cream");
        var seed=StyledSpriteCodec.paddedSeed(Files.readAllBytes(root.resolve("north.png")));
        var entry=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-north.png"))).getFirst();
        var before=List.of(StyledSpriteCodec.sha(seed),StyledSpriteCodec.sha(entry));
        var e=StyledReferenceContour.measure(json,seed,entry,StyledSpriteCodec.qualityRules(json));
        assertThat(e.metadata().path("sameAlpha").asBoolean()).isTrue();assertThat(e.metadata().path("focusShown").asBoolean()).isTrue();
        assertThat(e.metadata().path("contrastChanges").size()).isEqualTo(12);
        assertThat(e.metadata().toString()).doesNotContain("CONFIRMED_DEFECT","FAIL","PASS");
        var board=ImageIO.read(new ByteArrayInputStream(e.board()));var b=e.metadata().path("boundsXYExclusive");
        int l=b.get(0).asInt(),t=b.get(1).asInt(),w=b.get(2).asInt()-l,h=b.get(3).asInt()-t,scale=Math.min(24,400/Math.max(w,h));
        for(int index=0;index<2;index++) {
            var source=StyledSpriteCodec.motionFrame(index==0?seed:entry);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)if((source.getRGB(l+x,t+y)>>>24)==255)
                assertThat(board.getRGB(10+440*index+x*scale,40+y*scale)).isEqualTo(source.getRGB(l+x,t+y));
        }
        assertThat(List.of(StyledSpriteCodec.sha(seed),StyledSpriteCodec.sha(entry))).isEqualTo(before);
    }
    @Test void unchangedPoseSubtleShadingAndUnalignedSilhouettesDoNotClaimAnEntryContourChange()throws Exception {
        var raw=Files.readAllBytes(StyledTailObservationConsistencyTest.ROOT.resolve("cream/north.png"));
        var seed=StyledSpriteCodec.paddedSeed(raw);var rules=StyledSpriteCodec.qualityRules(json);
        assertThat(StyledReferenceContour.measure(json,seed,seed,rules).board()).isNull();
        var image=StyledSpriteCodec.motionFrame(seed);image.setRGB(20,21,0xff3f3430);
        assertThat(StyledReferenceContour.measure(json,seed,StyledSpriteCodec.png(image),rules).board()).isNull();
        image.setRGB(20,21,0);assertThat(StyledReferenceContour.measure(json,seed,StyledSpriteCodec.png(image),rules).metadata().path("sameAlpha").asBoolean()).isFalse();
    }
}
