package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Replays exact deployed pixels and recorded observations, not a new paid review or approval. */
class StyledTailMirrorRegressionTest {
    private static final Path ROOT=Path.of("scripts/fixtures/tail-mirror-v41");
    private final JsonMapper json=JsonMapper.builder().build();

    private List<byte[]> seeds() throws Exception {
        var result=new ArrayList<byte[]>();
        for(String d:StyledSpriteCodec.DIRECTIONS)result.add(Files.readAllBytes(ROOT.resolve(d+".png")));
        return result;
    }

    @Test void exactMirroredSilhouettesMustSupportBothNormalTails() throws Exception {
        var evidence=json.readTree(Files.readAllBytes(ROOT.resolve("evidence.json")));
        var original=json.readTree(Files.readAllBytes(ROOT.resolve("deployed-review.json")));
        for(var entry:evidence.path("sha256").properties())
            assertThat(StyledSpriteCodec.sha(Files.readAllBytes(ROOT.resolve(entry.getKey())))).isEqualTo(entry.getValue().asText());
        var pixels=seeds();var hashes=pixels.stream().map(StyledSpriteCodec::sha).toList();
        var west=StyledSpriteCodec.nativeFrame(pixels.get(2));var east=StyledSpriteCodec.nativeFrame(pixels.get(3));
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)assertThat(west.getRGB(x,y)>>>24).isEqualTo(east.getRGB(31-x,y)>>>24);
        assertThat(original.at("/tailEvidence/geometry/west/rearBranchSupport").asBoolean()).isFalse();
        assertThat(original.at("/tailEvidence/geometry/east/rearBranchSupport").asBoolean()).isTrue();
        var geometry=StyledTailGeometry.measure(json,pixels);
        for(String d:List.of("west","east"))assertThat(geometry.at("/"+d+"/rearBranchSupport").asBoolean()).as(d).isTrue();
        assertThat(geometry.at("/west/branchVersion").asText()).isEqualTo(
            StyledSpriteCodec.qualityRules(json).at("/recovery/tailSilhouetteSupport/version").asText());
        var westPaths=geometry.at("/west/rearBranchCandidates");var eastPaths=geometry.at("/east/rearBranchCandidates");
        assertThat(westPaths.size()).isEqualTo(eastPaths.size());
        for(int i=0;i<westPaths.size();i++) {
            assertThat(westPaths.get(i).size()).isEqualTo(eastPaths.get(i).size());
            for(int j=0;j<westPaths.get(i).size();j++) {
                var w=westPaths.get(i).get(j);var e=eastPaths.get(i).get(j);
                int x=w.path("x").asInt(),y=w.path("y").asInt();
                assertThat(x).isEqualTo(31-e.path("x").asInt());assertThat(y).isEqualTo(e.path("y").asInt());
                assertThat(west.getRGB(x,y)>>>24).isNotZero();
            }
        }
        assertThat(original.at("/tailEvidence/observationHistory").size()).isEqualTo(2);
        for(var observation:original.at("/tailEvidence/observationHistory"))
            assertThat(StyledTailAnatomy.assess(json,observation.path("observation"),geometry,List.of("west","east")).path("passed").asBoolean()).isTrue();
        assertThat(pixels.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
        assertThat(StyledTailAnatomy.boundPass(original)).isFalse();
        var fresh=evidence.path("freshReview");var receipt=Files.readAllBytes(ROOT.resolve(fresh.path("file").asText()));
        assertThat(StyledSpriteCodec.sha(receipt)).isEqualTo(fresh.path("sha256").asText());
        var reviewed=json.readTree(receipt);
        assertThat(reviewed.path("inputSha256")).isEqualTo(original.path("inputSha256"));
        assertThat(reviewed.path("photoSha256")).isEqualTo(original.path("photoSha256"));
        assertThat(reviewed.path("passed").asBoolean()).isTrue();
        assertThat(reviewed.at("/tailEvidence/passed").asBoolean()).isTrue();
        assertThat(fresh.path("remoteApprovalChanged").asBoolean()).isFalse();
    }

    @Test void eachViewMustSupplyItsOwnGeometryAndAnatomy() throws Exception {
        var pixels=seeds();var original=json.readTree(Files.readAllBytes(ROOT.resolve("deployed-review.json")));
        var good=StyledTailGeometry.measure(json,pixels).path("east");
        var missing=StyledTailAnatomyTest.fixture("case-b");
        pixels.set(2,missing.get(2)); // Only west loses its tail; east cannot lend it support.
        var measured=StyledTailGeometry.measure(json,pixels);
        assertThat(measured.path("east")).isEqualTo(good);
        assertThat(measured.at("/west/rearBranchSupport").asBoolean()).isFalse();
        // A stale or hallucinated complete-tail observation cannot pass the changed west pixels.
        assertThat(StyledTailAnatomy.assess(json,original.at("/tailEvidence/observation"),measured,List.of("west","east"))
            .path("decision").asText()).isEqualTo("UNCERTAIN");
        var observed=new StyledTailAnatomyTest().raw("NOT_DISCERNIBLE","COMPLETE_CONNECTED");
        assertThat(StyledTailAnatomy.assess(json,observed,measured,List.of("west","east"))
            .path("decision").asText()).isEqualTo("CONFIRMED_DEFECT");
    }
}
