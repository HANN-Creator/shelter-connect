package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Exact native pixels and paid observations, replayed offline; this is not a fresh AI accuracy measurement. */
class StyledTailGeometryTest {
    final JsonMapper json=JsonMapper.builder().build();
    JsonNode measured(String c){return StyledTailGeometry.measure(json,StyledTailAnatomyTest.fixture(c));}
    JsonNode assess(String c,JsonNode observation){return StyledTailAnatomy.assess(json,observation,measured(c),List.of("west","east"));}
    JsonNode recorded(String c,String file)throws Exception{return json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/tail-anatomy-v21",c,file)));}
    @Test void knownRaisedCurledAndSmallTailsHaveSupportWithoutEditingASinglePixel()throws Exception{
        var manifest=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/tail-anatomy-v21/geometry-cases.json")));
        for(var c:manifest.path("cases")){
            var pixels=StyledTailAnatomyTest.fixture(c.path("key").asText());
            var hashes=pixels.stream().map(StyledSpriteCodec::sha).toList();
            for(int i=0;i<4;i++)assertThat(hashes.get(i)).isEqualTo(c.path("hashes").path(StyledSpriteCodec.DIRECTIONS.get(i)).asText());
            var geometry=StyledTailGeometry.measure(json,pixels);
            if(c.path("expected").isBoolean() && c.path("expected").asBoolean())for(String d:List.of("west","east"))
                assertThat(geometry.path(d).path("rearBranchSupport").asBoolean()).as(c.path("key").asText()+" "+d).isTrue();
            assertThat(pixels.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
        }
    }
    @Test void actualConsensusFalsePassIsNowUncertainAndCannotAuthorizeAPaidRepairOrLearning()throws Exception{
        var old=recorded("case-f","production-consensus.json");assertThat(old.path("passed").asBoolean()).isTrue();
        var result=assess("case-f",old.at("/tailEvidence/observation"));
        assertThat(result.path("decision").asText()).isEqualTo("UNCERTAIN");assertThat(result.path("failedDirections").isEmpty()).isTrue();
        assertThat(result.path("uncertainDirections").size()).isEqualTo(2);
        var merged=(tools.jackson.databind.node.ObjectNode)old.deepCopy();StyledTailAnatomy.merge(json,merged,result);
        assertThat(StyledTailAnatomy.unresolved(merged)).isTrue();assertThat(merged.path("issues").isEmpty()).isTrue();
        // Historical receipts retain their old rules binding and must never become current authorization.
        assertThatThrownBy(()->StyledSeedRepair.plan(org.mockito.Mockito.mock(org.shelterconnect.api.chat.OpenAiResponsesClient.class),json,StyledTailAnatomyTest.fixture("case-f"),merged)).hasMessage("SEED_REPAIR_SELECTION_INVALID");
        // Separate synthetic current-policy report verifies the planner, not a forged live receipt.
        var current=new StyledTailAnatomyTest().report();current.put("inputSha256",StyledSeedQualityAgent.binding(StyledTailAnatomyTest.fixture("case-f")));StyledTailAnatomy.merge(json,current,result);
        assertThat(StyledSeedRepair.plan(org.mockito.Mockito.mock(org.shelterconnect.api.chat.OpenAiResponsesClient.class),json,StyledTailAnatomyTest.fixture("case-f"),current).path("status").asText()).isEqualTo("UNCERTAIN_VERDICT");
        assertThat(StyledTailAnatomy.boundPass(merged)).isFalse();
    }
    @Test void supportDisagreesWithActualMissingTailHallucinationInsteadOfRedrawingNormalDog()throws Exception{
        var old=recorded("case-d","observed-r4.json");assertThat(old.path("decision").asText()).isEqualTo("CONFIRMED_DEFECT");
        var result=assess("case-d",old.path("observation"));
        assertThat(result.path("decision").asText()).isEqualTo("UNCERTAIN");assertThat(result.path("failedDirections").isEmpty()).isTrue();
        // Same image was actually judged complete in a different paid observation. Support alone never proves anatomy.
        assertThat(assess("case-d",recorded("case-d","production-consensus.json").at("/tailEvidence/observation")).path("passed").asBoolean()).isTrue();
    }
    @Test void realNoTailAndClippedCasesStillFailAndDisconnectedFragmentNeverPasses()throws Exception{
        for(String c:List.of("case-b","case-f","case-g"))
            assertThat(assess(c,recorded(c,"observed-r4.json").path("observation")).path("decision").asText()).as(c).isEqualTo("CONFIRMED_DEFECT");
        assertThat(assess("case-j",recorded("case-j","observed-r4.json").path("observation")).path("passed").asBoolean()).isFalse();
    }
    @Test void unsupportedDescendingTailIsAnExplicitKnownLimitNotAnAutomaticDefect(){
        var observation=new StyledTailAnatomyTest().raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED");
        var result=assess("low-tail",observation);
        assertThat(result.path("decision").asText()).isEqualTo("UNCERTAIN");assertThat(result.path("failedDirections").isEmpty()).isTrue();
    }
    @Test void measurementsAreDeterministicAndMirrorSymmetric(){
        for(String c:List.of("case-a","case-b","case-c","case-d","case-e","case-f","case-h","case-i","low-tail")){
            var pixels=StyledTailAnatomyTest.fixture(c);var before=measured(c);
            var reflected=new ArrayList<byte[]>();for(var bytes:pixels){var im=StyledSpriteCodec.nativeFrame(bytes);var flip=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<32;y++)for(int x=0;x<32;x++)flip.setRGB(31-x,y,im.getRGB(x,y));reflected.add(StyledSpriteCodec.png(flip));}
            Collections.swap(reflected,2,3);var after=StyledTailGeometry.measure(json,reflected);
            assertThat(before).isEqualTo(measured(c));
            assertThat(before.at("/west/rearBranchSupport")).as(c).isEqualTo(after.at("/east/rearBranchSupport"));
            assertThat(before.at("/east/rearBranchSupport")).as(c).isEqualTo(after.at("/west/rearBranchSupport"));
        }
    }
}
