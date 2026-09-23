package org.shelterconnect.api.behavior;

import java.math.BigDecimal;
import java.util.*;
import org.shelterconnect.api.chat.AiFailure;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

final class BehaviorTraitMapping {
    enum Trait { RUNNER, SNIFFER, FRIENDLY, CAUTIOUS, RESTFUL, BALL_CHASER, BALL_RETURNER }
    record Evidence(Trait code,UUID observationId,String quote) {}
    record Result(Settings settings,List<Evidence> traits) {}
    static Result map(JsonNode output,List<BehaviorSuggestionProvider.Observation> observations) {
        if(output==null || !output.isObject() || output.size()!=1 || !output.path("traits").isArray() || output.path("traits").size()>7) throw invalid();
        var seen=EnumSet.noneOf(Trait.class);var evidence=new ArrayList<Evidence>();
        try {
            for(var item:output.path("traits")) {
                if(!item.isObject() || item.size()!=3 || !item.path("code").isString() || !item.path("observationId").isString() || !item.path("quote").isString()) throw invalid();
                var trait=Trait.valueOf(item.path("code").asText());var id=BehaviorInput.id(item.path("observationId").asText());
                String quote=item.path("quote").asText();
                if(!seen.add(trait) || quote.isBlank() || quote.length()>300 || observations.stream().noneMatch(o->o.id().equals(id)&&o.content().contains(quote))) throw invalid();
                evidence.add(new Evidence(trait,id,quote));
            }
        } catch(RuntimeException ex) { throw invalid(); }
        var defaults=BehaviorInput.defaults();var actions=new EnumMap<Action,Motion>(defaults.actions());
        for(var trait:seen) switch(trait) {
            case RUNNER -> weight(actions,Action.RUN,30);
            case SNIFFER -> weight(actions,Action.SNIFF,30);
            case FRIENDLY -> weight(actions,Action.TAIL_WAG,30);
            case CAUTIOUS -> weight(actions,Action.BACK_OFF,30);
            case RESTFUL -> weight(actions,Action.LIE_DOWN,30);
            case BALL_CHASER,BALL_RETURNER -> { weight(actions,Action.RUN,40);weight(actions,Action.SNIFF,30); }
        }
        boolean chase=seen.contains(Trait.BALL_CHASER)||seen.contains(Trait.BALL_RETURNER);
        boolean cautious=seen.contains(Trait.CAUTIOUS),friendly=seen.contains(Trait.FRIENDLY);
        // A cautious record takes precedence over a friendly approach suggestion; the shelter reviews both quotes.
        var settings=new Settings(Collections.unmodifiableMap(actions),BigDecimal.valueOf(cautious?3:friendly?4:0),
            BigDecimal.valueOf(cautious?2:friendly?1:0),cautious?1500:1000,new BallPlay(chase,seen.contains(Trait.BALL_RETURNER),800));
        return new Result(settings,List.copyOf(evidence));
    }
    private static void weight(EnumMap<Action,Motion> actions,Action action,int weight) {
        var old=actions.get(action);actions.put(action,new Motion(Math.max(weight,old.weight()),old.speedTilesPerSecond(),old.minDurationMs(),old.maxDurationMs(),old.cooldownMs()));
    }
    private static AiFailure invalid() { return new AiFailure("AI_INVALID_RESPONSE"); }
}
