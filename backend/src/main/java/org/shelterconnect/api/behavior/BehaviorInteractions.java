package org.shelterconnect.api.behavior;

import java.util.List;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

/** Reusable instructions for the frontend state machine; no new sprite action codes. */
public final class BehaviorInteractions {
    private BehaviorInteractions() {}
    public record Phase(String phase,List<Action> preferredActions,String until) {}
    public record BallChase(int schemaVersion,boolean enabled,boolean returnEnabled,int reactionDelayMs,
                            double slowDistanceTiles,double arrivalDistanceTiles,int sniffDurationMs,
                            int maxChaseDurationMs,Action fallbackAction,List<Phase> phases) {}
    public static BallChase ballChase(Settings settings) {
        return new BallChase(1,settings.ballPlay().chaseEnabled(),settings.ballPlay().returnEnabled(),
            settings.ballPlay().reactionDelayMs(),1.5,0.5,1200,15000,Action.IDLE,List.of(
                new Phase("CHASE",List.of(Action.RUN,Action.WALK),"WITHIN_SLOW_DISTANCE"),
                new Phase("APPROACH",List.of(Action.WALK),"WITHIN_ARRIVAL_DISTANCE"),
                new Phase("INSPECT",List.of(Action.SNIFF,Action.IDLE),"SNIFF_DURATION_ELAPSED"),
                new Phase("RETURN",List.of(Action.WALK),"AT_THROW_ORIGIN_IF_RETURN_ENABLED"),
                new Phase("FINISH",List.of(Action.IDLE),"IMMEDIATE")));
    }
}
