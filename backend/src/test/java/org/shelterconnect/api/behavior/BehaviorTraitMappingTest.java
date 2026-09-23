package org.shelterconnect.api.behavior;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.shelterconnect.api.behavior.BehaviorTypes.Action;

class BehaviorTraitMappingTest {
    final JsonMapper json=new JsonMapper();
    final BehaviorSuggestionProvider.Observation observation=new BehaviorSuggestionProvider.Observation(UUID.randomUUID(),"PLAY","던진 공을 따라 달리고 냄새를 맡았다.",Instant.now(),Instant.now());
    @Test void commonActionsAndBallPhasesShareTheExistingSpriteCodes() {
        var result=BehaviorTraitMapping.map(json.valueToTree(Map.of("traits",List.of(Map.of("code","BALL_CHASER","observationId",observation.id(),"quote",observation.content())))),List.of(observation));
        assertThat(result.settings().actions().get(Action.SIT).weight()).isPositive();
        assertThat(result.settings().ballPlay().chaseEnabled()).isTrue();assertThat(result.settings().ballPlay().returnEnabled()).isFalse();
        var rule=BehaviorInteractions.ballChase(result.settings());
        assertThat(rule.phases().stream().map(BehaviorInteractions.Phase::phase)).containsExactly("CHASE","APPROACH","INSPECT","RETURN","FINISH");
        assertThat(rule.arrivalDistanceTiles()).isLessThan(rule.slowDistanceTiles());
        assertThat(rule.phases().get(2).preferredActions()).containsExactly(Action.SNIFF,Action.IDLE);
    }
    @Test void noEvidenceDoesNotInventTraitsAndInvalidOrForeignEvidenceIsRejected() {
        var result=BehaviorTraitMapping.map(json.readTree("{\"traits\":[]}"),List.of(observation));
        assertThat(result.settings().ballPlay().chaseEnabled()).isFalse();
        for(Object item:List.of(Map.of("code","BALL_CHASER","observationId",UUID.randomUUID(),"quote",observation.content()),
            Map.of("code","RUNNER","observationId",observation.id(),"quote","없는 문장"),Map.of("code","UNSUPPORTED","observationId",observation.id(),"quote",observation.content())))
            assertThatThrownBy(()->BehaviorTraitMapping.map(json.valueToTree(Map.of("traits",List.of(item))),List.of(observation))).hasMessage("AI_INVALID_RESPONSE");
    }
}
