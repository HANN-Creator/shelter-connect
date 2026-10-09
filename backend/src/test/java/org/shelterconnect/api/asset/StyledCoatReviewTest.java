package org.shelterconnect.api.asset;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
class StyledCoatReviewTest {
    final JsonMapper json=JsonMapper.builder().build();
    ObjectNode general(){var r=json.createObjectNode();var v=r.putArray("views").addObject().put("direction","south").put("identityMatches",true);
        v.putArray("issues").add("COAT_MISMATCH").add("EYE_READABILITY");return r;}
    ObjectNode observation(String decision){var r=json.createObjectNode();r.putArray("views").addObject().put("direction","south").put("decision",decision)
        .put("photoPattern","white muzzle with brown outer cheeks").put("spritePattern","same patches in a lighter palette").put("evidence","material pattern assessment fixture, not live model evidence");return r;}
    @Test void conflictingPaletteAssessmentHoldsWithoutInventingACoatDefectOrClearingOtherDefects(){var r=general();((ObjectNode)r.path("views").get(0)).put("identityMatches",false);
        var resolved=StyledCoatReview.resolve(json,r,observation("PRESERVED"),List.of("south"));
        assertThat(resolved.at("/views/0/issues").toString()).isEqualTo("[\"EYE_READABILITY\"]");
        assertThat(resolved.at("/views/0/coatObservationUncertain").asBoolean()).isTrue();assertThat(resolved.at("/views/0/coatObservationConflict").asBoolean()).isTrue();
        assertThat(resolved.at("/views/0/identityMatches").asBoolean()).isFalse();assertThat(r.at("/views/0/issues").size()).isEqualTo(2);}
    @Test void realMissingPatchRemainsFailureAndBodyDefectsCannotBecomeFaceEdits(){for(String d:List.of("FACE_PATTERN_MISSING","BODY_PATTERN_MISSING")){
        var r=StyledCoatReview.resolve(json,general(),observation(d),List.of("south"));assertThat(r.at("/views/0/issues").toString()).contains("COAT_MISMATCH");
        assertThat(r.at("/views/0/coatRepairScope").asText()).isEqualTo(d.startsWith("FACE")?"FACE":"BODY");}}
    @Test void unknownObservationBlocksWithoutInventingAnImageDefect(){var r=json.createObjectNode();r.set("propertyReview",StyledCoatReview.resolve(json,general(),observation("UNCERTAIN"),List.of("south")));
        assertThat(StyledCoatReview.unresolved(r)).isTrue();assertThat(r.at("/propertyReview/views/0/issues").toString()).doesNotContain("COAT_MISMATCH");}
    @Test void missingOrUnexpectedDirectionsAndBlankEvidenceCannotClearDefect(){var obs=observation("PRESERVED");
        assertThatThrownBy(()->StyledCoatReview.resolve(json,general(),obs,List.of("west"))).isInstanceOf(AssetException.class);
        ((ObjectNode)obs.path("views").get(0)).put("photoPattern","");assertThatThrownBy(()->StyledCoatReview.resolve(json,general(),obs,List.of("south"))).isInstanceOf(AssetException.class);}
}
