package org.shelterconnect.api.asset;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StyledPhotoInputTest {
    final PhotoAppearanceTest fixture=new PhotoAppearanceTest();
    @Test void mapsAllVisibleFeaturesPaddedHeadAndStablePhotoHashWithoutInventingRear() {
        var photo=fixture.photo();var traits=StyledPhotoInput.traits(photo,fixture.fixture(Map.of()),fixture.json);
        assertThat(traits.path("identityDescription").asText()).contains("pink upper-center patch","not clearly visible");
        assertThat(traits.at("/faceBox/0").asDouble()).isCloseTo(.19,within(.00001));
        assertThat(traits.at("/faceBox/2").asDouble()).isCloseTo(.81,within(.00001));
        assertThat(traits.path("rearDescription").asText()).contains("inferred, not observed");
        assertThat(traits.path("sourcePhotoSha256").asText()).isEqualTo(StyledSpriteCodec.sha(photo));
        assertThat(StyledPhotoInput.traits(photo,fixture.fixture(Map.of()),fixture.json)).isEqualTo(traits);
    }
    @Test void maximalStructuredDescriptionsFitNativeContractWithoutTruncation() {
        var analysis=fixture.fixture(Map.of());
        for(String key:PhotoAppearance.FEATURES)((tools.jackson.databind.node.ObjectNode)analysis.path("features")).put(key,"x".repeat(80));
        var t=StyledPhotoInput.traits(fixture.photo(),analysis,fixture.json);
        assertThat(t.path("identityDescription").asText().length()).isLessThanOrEqualTo(850);
        assertThat(t.path("motionDescription").asText().length()).isLessThanOrEqualTo(300);
        assertThat(t.path("rearDescription").asText().length()).isLessThanOrEqualTo(300);
        ((tools.jackson.databind.node.ObjectNode)analysis.path("features")).put("tail","x".repeat(81));
        assertThatThrownBy(()->StyledPhotoInput.traits(fixture.photo(),analysis,fixture.json)).isInstanceOf(AssetException.class);
    }
    @Test void ambiguousPhotoCannotBecomeReviewedTraits() {
        for(var change:List.of(Map.<String,Object>of("dogCount",2),Map.<String,Object>of("headVisible",false)))
            assertThatThrownBy(()->StyledPhotoInput.traits(fixture.photo(),fixture.fixture(change),fixture.json)).isInstanceOf(AssetException.class);
    }
}
