package org.shelterconnect.api.behavior;

import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

final class BehaviorInput {
	private BehaviorInput() {}
	static UUID id(String value) {
		try { UUID id=UUID.fromString(value); if(!id.toString().equalsIgnoreCase(value)) throw BehaviorException.invalid(); return id; }
		catch(IllegalArgumentException | NullPointerException ex) { throw BehaviorException.invalid(); }
	}
	static Save save(JsonNode body) {
		exact(body,"expectedRevision","schemaVersion","settings","source","evidenceObservationIds");
		int revision=integer(body,"expectedRevision",0,Integer.MAX_VALUE-1);
		if(integer(body,"schemaVersion",1,1)!=1) throw BehaviorException.invalid();
		JsonNode source=body.get("source");
		if(!source.isString() || !Set.of("SHELTER","AI_SUGGESTED").contains(source.asString())) throw BehaviorException.invalid();
		JsonNode refs=body.get("evidenceObservationIds");
		if(!refs.isArray() || refs.size()>20) throw BehaviorException.invalid();
		Set<UUID> seen=new HashSet<>();
		for(var ref:refs) { if(!ref.isString() || !seen.add(id(ref.asString()))) throw BehaviorException.invalid(); }
		return new Save(revision,settings(body.get("settings")),source.asString(),seen.stream().sorted().toList());
	}
	static int confirmation(JsonNode body) { exact(body,"expectedRevision"); return integer(body,"expectedRevision",1,Integer.MAX_VALUE-1); }
	static Settings settings(JsonNode body) {
		exact(body,"actions","approachDistanceTiles","personalSpaceTiles","reactionDelayMs","ballPlay");
		JsonNode actions=body.get("actions");
		exact(actions,Arrays.stream(Action.values()).map(Enum::name).toArray(String[]::new));
		var motions=new EnumMap<Action,Motion>(Action.class);
		for(Action action:Action.values()) {
			var node=actions.get(action.name());
			exact(node,"weight","speedTilesPerSecond","minDurationMs","maxDurationMs","cooldownMs");
			int weight=integer(node,"weight",0,100);
			BigDecimal speed=decimal(node,"speedTilesPerSecond",0,6);
			boolean moving=Set.of(Action.WALK,Action.RUN,Action.BACK_OFF).contains(action);
			if(moving ? speed.signum()<=0 : speed.signum()!=0) throw BehaviorException.invalid();
			int min=integer(node,"minDurationMs",500,30000), max=integer(node,"maxDurationMs",500,60000);
			if(min>max) throw BehaviorException.invalid();
			motions.put(action,new Motion(weight,speed,min,max,integer(node,"cooldownMs",0,120000)));
		}
		if(motions.get(Action.IDLE).weight()==0 || motions.get(Action.WALK).weight()==0
				|| motions.get(Action.RUN).speedTilesPerSecond().compareTo(motions.get(Action.WALK).speedTilesPerSecond())<0) throw BehaviorException.invalid();
		BigDecimal approach=decimal(body,"approachDistanceTiles",0,12), space=decimal(body,"personalSpaceTiles",0,12);
		if(approach.compareTo(space)<0) throw BehaviorException.invalid();
		var ball=body.get("ballPlay"); exact(ball,"chaseEnabled","returnEnabled","reactionDelayMs");
		boolean chase=bool(ball,"chaseEnabled"), returns=bool(ball,"returnEnabled");
		if(returns && !chase) throw BehaviorException.invalid();
		return new Settings(Collections.unmodifiableMap(motions),approach,space,integer(body,"reactionDelayMs",0,10000),
				new BallPlay(chase,returns,integer(ball,"reactionDelayMs",0,10000)));
	}
	static Settings defaults() {
		var motions=new EnumMap<Action,Motion>(Action.class);
		for(var action:Action.values()) {
			String speed=switch(action) {case WALK->"0.8";case RUN->"1.8";case BACK_OFF->"0.6";default->"0";};
			motions.put(action,new Motion(action==Action.IDLE?70:action==Action.WALK?30:0,new BigDecimal(speed),2000,5000,2000));
		}
		return new Settings(Collections.unmodifiableMap(motions),BigDecimal.ZERO,BigDecimal.ZERO,1000,new BallPlay(false,false,1000));
	}
	private static void exact(JsonNode node,String... names) {
		if(node==null || !node.isObject() || node.size()!=names.length) throw BehaviorException.invalid();
		for(String name:names) if(!node.hasNonNull(name)) throw BehaviorException.invalid();
	}
	private static int integer(JsonNode node,String key,int min,int max) {
		var value=node.get(key);
		if(!value.isIntegralNumber() || !value.canConvertToInt()) throw BehaviorException.invalid();
		int number=value.intValue(); if(number<min || number>max) throw BehaviorException.invalid(); return number;
	}
	private static BigDecimal decimal(JsonNode node,String key,int min,int max) {
		var value=node.get(key); if(!value.isNumber()) throw BehaviorException.invalid();
		BigDecimal number=value.decimalValue();
		if(number.compareTo(BigDecimal.valueOf(min))<0 || number.compareTo(BigDecimal.valueOf(max))>0
				|| number.stripTrailingZeros().scale()>2) throw BehaviorException.invalid();
		return number;
	}
	private static boolean bool(JsonNode node,String key) {
		var value=node.get(key); if(!value.isBoolean()) throw BehaviorException.invalid(); return value.booleanValue();
	}
}
