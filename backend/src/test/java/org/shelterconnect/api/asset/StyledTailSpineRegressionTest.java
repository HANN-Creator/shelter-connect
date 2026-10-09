package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Replay of the unchanged deployed dog and actual observations; not a fresh model review. */
class StyledTailSpineRegressionTest {
    private static final Path ROOT=Path.of("scripts/fixtures/tail-spine-v31");
    private final JsonMapper json=JsonMapper.builder().build();

    private List<byte[]> seeds() throws Exception {
        var result=new ArrayList<byte[]>();
        for(String d:StyledSpriteCodec.DIRECTIONS)result.add(Files.readAllBytes(ROOT.resolve(d+".png")));
        return result;
    }

    @Test void deployedTailConnectsToSpineBeforeTraceContinuesThroughBodyToHead() throws Exception {
        var evidence=json.readTree(Files.readAllBytes(ROOT.resolve("evidence.json")));
        var original=json.readTree(Files.readAllBytes(ROOT.resolve("deployed-review.json")));
        var pixels=seeds();var before=pixels.stream().map(StyledSpriteCodec::sha).toList();
        for(int i=0;i<4;i++)assertThat(before.get(i)).isEqualTo(evidence.at("/sha256/"+StyledSpriteCodec.DIRECTIONS.get(i)+".png").asText());
        assertThat(original.at("/tailEvidence/geometry/west/rearBranchSupport").asBoolean()).isFalse();
        assertThat(original.at("/tailEvidence/observationHistory").size()).isEqualTo(2);
        var geometry=StyledTailGeometry.measure(json,pixels);
        for(String d:List.of("west","east"))assertThat(geometry.at("/"+d+"/rearBranchSupport").asBoolean()).as(d).isTrue();
        for(var observation:original.at("/tailEvidence/observationHistory")) {
            for(var view:observation.at("/observation/views"))assertThat(view.path("tail").asText()).isEqualTo("COMPLETE_CONNECTED");
            assertThat(StyledTailAnatomy.assess(json,observation.path("observation"),geometry,List.of("west","east")).path("passed").asBoolean()).isTrue();
        }
        assertThat(pixels.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(before);
        // Reinterpreting historical observations is regression evidence, never a new approval receipt.
        assertThat(StyledTailAnatomy.boundPass(original)).isFalse();
    }

    @Test void connectionMeasurementIsDeterministicAndMirrorSymmetric() throws Exception {
        var pixels=seeds();var measured=StyledTailGeometry.measure(json,pixels);
        var mirrored=new ArrayList<byte[]>();
        for(byte[] bytes:pixels) {
            var source=StyledSpriteCodec.nativeFrame(bytes);var flip=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)flip.setRGB(31-x,y,source.getRGB(x,y));
            mirrored.add(StyledSpriteCodec.png(flip));
        }
        Collections.swap(mirrored,2,3);var reflected=StyledTailGeometry.measure(json,mirrored);
        assertThat(measured).isEqualTo(StyledTailGeometry.measure(json,pixels));
        for(String d:List.of("west","east")) {
            String opposite=d.equals("west")?"east":"west";
            assertThat(measured.at("/"+d+"/rearBranchSupport")).isEqualTo(reflected.at("/"+opposite+"/rearBranchSupport"));
        }
    }

    @Test void silhouetteSupportStillCannotWaiveClippingSeparationOrAnUncertainObservation() throws Exception {
        var report=json.readTree(Files.readAllBytes(ROOT.resolve("deployed-review.json")));
        var observation=report.at("/tailEvidence/observation");
        var pixels=seeds();var geometry=StyledTailGeometry.measure(json,pixels);
        // Synthetic observations exercise the gate, not visual recognition accuracy.
        var uncertain=new StyledTailAnatomyTest().raw("UNCERTAIN","COMPLETE_CONNECTED");
        assertThat(StyledTailAnatomy.assess(json,uncertain,geometry,List.of("west","east")).path("passed").asBoolean()).isFalse();
        for(boolean detached:List.of(false,true)) {
            var modified=new ArrayList<>(pixels);var source=StyledSpriteCodec.nativeFrame(pixels.get(2));
            var target=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)target.setRGB(x,y,source.getRGB(x,y));
            if(detached)target.setRGB(2,2,0xff333333); // Separate foreground fragment.
            else for(int x=26;x<32;x++)target.setRGB(x,14,0xff333333); // Connected tail reaches the edge.
            modified.set(2,StyledSpriteCodec.png(target));
            JsonNode result=StyledTailAnatomy.assess(json,observation,StyledTailGeometry.measure(json,modified),List.of("west","east"));
            assertThat(result.path("passed").asBoolean()).as(detached?"separate fragment":"edge contact").isFalse();
        }
    }
}
