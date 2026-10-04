package org.shelterconnect.api.behavior;

import java.util.*;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

/** Immutable selection snapshot; photo identity is deliberately independent of personality. */
public record BehaviorGenerationPlan(String policyVersion,String basis,Integer behaviorRevision,
        List<String> baseActions,List<String> optionalActions,List<String> selectedActions,
        int directionCount,int expectedProviderRequests,Settings settings,Map<String,Object> interactions) {
    public static final String POLICY="traits-v2";
    public static final List<String> BASE=List.of("IDLE","WALK","SIT");
    public static BehaviorGenerationPlan from(Settings settings,String basis,Integer revision) {
        var optional=Arrays.stream(Action.values()).filter(a->!BASE.contains(a.name()) && settings.actions().get(a).weight()>0)
            .map(Enum::name).toList();
        var selected=new ArrayList<>(BASE);selected.addAll(optional);
        return new BehaviorGenerationPlan(POLICY,basis,revision,BASE,optional,List.copyOf(selected),4,
            1+selected.size()*4,settings,BehaviorInteractions.all(settings));
    }
    /** Current behavior must never ask a cached, smaller pack for a missing clip. */
    public static Settings compatible(Settings settings,Collection<String> available) {
        var motions=new EnumMap<Action,Motion>(settings.actions());
        motions.replaceAll((a,m)->available.contains(a.name())?m:new Motion(0,m.speedTilesPerSecond(),m.minDurationMs(),m.maxDurationMs(),m.cooldownMs()));
        boolean chase=settings.ballPlay().chaseEnabled() && available.containsAll(List.of("RUN","SNIFF","WALK","IDLE"));
        return new Settings(Collections.unmodifiableMap(motions),settings.approachDistanceTiles(),settings.personalSpaceTiles(),settings.reactionDelayMs(),
            new BallPlay(chase,chase&&settings.ballPlay().returnEnabled(),settings.ballPlay().reactionDelayMs()));
    }
    public static BehaviorGenerationPlan defaults() { return from(BehaviorInput.defaults(),"DEFAULT",null); }
}
