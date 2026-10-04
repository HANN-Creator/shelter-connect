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
    @Test void preferenceMappingsSelectOnlyTheNecessaryAdditionalClips() {
        var expected=Map.of("FRIENDLY",List.of("SNIFF","TAIL_WAG"),"WALK_LOVER",List.of("RUN"),
            "RESTFUL",List.of("LIE_DOWN"),"SNIFFER",List.of("SNIFF"),"CAUTIOUS",List.of("BACK_OFF"),"BALL_CHASER",List.of("RUN","SNIFF"));
        expected.forEach((trait,actions)-> {
            var result=mapped(trait);BehaviorInput.settings(json.valueToTree(result.settings()));var plan=BehaviorGenerationPlan.from(result.settings(),"AI_DRAFT",1);
            assertThat(plan.baseActions()).containsExactly("IDLE","WALK","SIT");
            assertThat(plan.optionalActions()).containsExactlyElementsOf(actions);
            assertThat(plan.expectedProviderRequests()).isEqualTo(13+actions.size()*4);
        });
        var greeting=BehaviorInteractions.personGreeting(mapped("FRIENDLY").settings());
        assertThat(greeting.enabled()).isTrue();
        assertThat(greeting.phases().stream().map(BehaviorInteractions.Phase::phase)).containsExactly("APPROACH","GREET","INSPECT","FINISH");
    }
    @Test void explicitRestrictionsOverridePositivePreferencesWithoutChangingBasicActions() {
        var result=mapped("FRIENDLY","CAUTIOUS","RUNNER","WALK_LOVER","BALL_RETURNER","RUN_RESTRICTED");
        assertThat(result.settings().actions().get(Action.RUN).weight()).isZero();
        assertThat(result.settings().ballPlay().chaseEnabled()).isFalse();
        assertThat(result.settings().ballPlay().returnEnabled()).isFalse();
        assertThat(BehaviorInteractions.personGreeting(result.settings()).enabled()).isFalse();
        assertThat(BehaviorGenerationPlan.from(result.settings(),"AI_DRAFT",1).selectedActions()).contains("IDLE","WALK","SIT","BACK_OFF");
    }
    @Test void allSupportedOptionalActionsAreKeptAndMissingSpritesDisableInteractions() {
        var result=mapped("RUNNER","SNIFFER","FRIENDLY","CAUTIOUS","RESTFUL","BALL_RETURNER");
        var plan=BehaviorGenerationPlan.from(result.settings(),"AI_DRAFT",1);
        assertThat(plan.selectedActions()).hasSize(8);assertThat(plan.expectedProviderRequests()).isEqualTo(33);
        var compatible=BehaviorGenerationPlan.compatible(mapped("FRIENDLY","BALL_RETURNER").settings(),List.of("IDLE","WALK","SIT"));
        assertThat(BehaviorInteractions.personGreeting(compatible).enabled()).isFalse();assertThat(compatible.ballPlay().chaseEnabled()).isFalse();
    }
    @Test void missingRetreatSpriteDoesNotTurnACautiousDogIntoAnApproachingDog() {
        var original=mapped("FRIENDLY","CAUTIOUS").settings();
        var available=List.of("IDLE","WALK","SIT","TAIL_WAG","SNIFF");
        var rules=BehaviorInteractions.forAvailable(original,available);
        assertThat(((BehaviorInteractions.PersonGreeting)rules.get("PERSON_GREETING")).enabled()).isFalse();
    }
    BehaviorTraitMapping.Result mapped(String... traits) {
        return BehaviorTraitMapping.map(json.valueToTree(Map.of("traits",Arrays.stream(traits)
            .map(code->Map.of("code",code,"observationId",observation.id(),"quote",observation.content())).toList())),List.of(observation));
    }

}
