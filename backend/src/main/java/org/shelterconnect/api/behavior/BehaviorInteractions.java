package org.shelterconnect.api.behavior;

import java.util.*;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

/** Reusable instructions for the frontend state machine; no new sprite action codes. */
public final class BehaviorInteractions {
    private BehaviorInteractions() {}
    public record Phase(String phase,List<Action> preferredActions,String until) {}
    public record BallChase(int schemaVersion,boolean enabled,boolean returnEnabled,int reactionDelayMs,
                            double slowDistanceTiles,double arrivalDistanceTiles,int sniffDurationMs,
                            int maxChaseDurationMs,Action fallbackAction,List<Phase> phases) {}
    public record PersonGreeting(int schemaVersion,boolean enabled,double triggerDistanceTiles,
        double arrivalDistanceTiles,int reactionDelayMs,int wagDurationMs,int sniffDurationMs,
        int maxDurationMs,int cooldownMs,String cancelWhen,Action fallbackAction,List<Phase> phases) {}
    public static Map<String,Object> all(Settings settings) {
        return Map.of("BALL_CHASE",ballChase(settings),"PERSON_GREETING",personGreeting(settings));
    }
    public static Map<String,Object> forAvailable(Settings settings,Collection<String> available) {
        // Evaluate caution before masking missing clips: no BACK_OFF sprite does not mean the dog is friendly.
        return Map.of("BALL_CHASE",ballChase(settings,available.containsAll(List.of("RUN","WALK","SNIFF","IDLE"))),
            "PERSON_GREETING",personGreeting(settings,available.containsAll(List.of("WALK","TAIL_WAG","SNIFF","IDLE"))));
    }
    public static PersonGreeting personGreeting(Settings settings) { return personGreeting(settings,true); }
    private static PersonGreeting personGreeting(Settings settings,boolean available) {
        boolean enabled=available && settings.approachDistanceTiles().signum()>0 && settings.actions().get(Action.BACK_OFF).weight()==0
            && settings.actions().get(Action.TAIL_WAG).weight()>0 && settings.actions().get(Action.SNIFF).weight()>0;
        return new PersonGreeting(1,enabled,settings.approachDistanceTiles().doubleValue(),
            Math.max(.5,settings.personalSpaceTiles().doubleValue()),settings.reactionDelayMs(),1800,1200,12000,15000,
            "TARGET_GONE_OR_OUTSIDE_TRIGGER_DISTANCE",Action.IDLE,enabled?List.of(
                new Phase("APPROACH",List.of(Action.WALK),"WITHIN_ARRIVAL_DISTANCE"),
                new Phase("GREET",List.of(Action.TAIL_WAG),"WAG_DURATION_ELAPSED"),
                new Phase("INSPECT",List.of(Action.SNIFF),"SNIFF_DURATION_ELAPSED"),
                new Phase("FINISH",List.of(Action.IDLE),"IMMEDIATE")):List.of());
    }
    public static BallChase ballChase(Settings settings) { return ballChase(settings,true); }
    private static BallChase ballChase(Settings settings,boolean available) {
        return new BallChase(1,available&&settings.ballPlay().chaseEnabled(),available&&settings.ballPlay().returnEnabled(),
            settings.ballPlay().reactionDelayMs(),1.5,0.5,1200,15000,Action.IDLE,List.of(
                new Phase("CHASE",List.of(Action.RUN,Action.WALK),"WITHIN_SLOW_DISTANCE"),
                new Phase("APPROACH",List.of(Action.WALK),"WITHIN_ARRIVAL_DISTANCE"),
                new Phase("INSPECT",List.of(Action.SNIFF,Action.IDLE),"SNIFF_DURATION_ELAPSED"),
                new Phase("RETURN",List.of(Action.WALK),"AT_THROW_ORIGIN_IF_RETURN_ENABLED"),
                new Phase("FINISH",List.of(Action.IDLE),"IMMEDIATE")));
    }
}
