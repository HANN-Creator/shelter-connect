package org.shelterconnect.api.behavior;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

class BehaviorInputTest {
	private final JsonMapper json=new JsonMapper();
	private ObjectNode settings() { return (ObjectNode)json.valueToTree(BehaviorInput.defaults()); }
	private ObjectNode request() {
		var body=json.createObjectNode().put("expectedRevision",0).put("schemaVersion",1).put("source","SHELTER");
		body.set("settings",settings());body.putArray("evidenceObservationIds");return body;
	}
	@Test void defaultHasEightActionsAndThreeCommonActions() {
		var value=BehaviorInput.settings(settings());
		assertThat(value.actions()).hasSize(8);
		assertThat(value.actions().entrySet().stream().filter(e->e.getValue().weight()>0).map(e->e.getKey()).toList()).containsExactly(Action.IDLE,Action.WALK,Action.SIT);
		assertThat(value.ballPlay().chaseEnabled()).isFalse();
		assertThat(BehaviorInput.save(request()).evidence()).isEmpty();
	}
	@ParameterizedTest @ValueSource(strings={"IDLE","WALK","RUN","SNIFF","TAIL_WAG","BACK_OFF","SIT","LIE_DOWN"})
	void everyActionIsRequired(String key) {
		var body=settings();((ObjectNode)body.get("actions")).remove(key);
		assertThatThrownBy(()->BehaviorInput.settings(body)).isInstanceOf(BehaviorException.class);
	}
	@ParameterizedTest @ValueSource(strings={"weight:-1","weight:101","weight:1.5","weight:\"20\"","speedTilesPerSecond:-1","speedTilesPerSecond:7",
		"speedTilesPerSecond:0.001","speedTilesPerSecond:null","minDurationMs:499","minDurationMs:30001","maxDurationMs:60001","maxDurationMs:500",
		"cooldownMs:-1","cooldownMs:120001","weight:9999999999999999999999999999999"})
	void invalidMotionValuesAreNotCoerced(String change) {
		var parts=change.split(":",2);var body=settings();((ObjectNode)body.at("/actions/WALK")).set(parts[0],json.readTree(parts[1]));
		assertThatThrownBy(()->BehaviorInput.settings(body)).isInstanceOf(BehaviorException.class);
	}
	@Test void rejectsContradictionsUnknownFieldsAndForgedAuditFields() {
		var body=settings();((ObjectNode)body.at("/actions/IDLE")).put("speedTilesPerSecond",1);reject(body);
		body=settings();((ObjectNode)body.at("/actions/WALK")).put("speedTilesPerSecond",0);reject(body);
		body=settings();((ObjectNode)body.at("/actions/RUN")).put("speedTilesPerSecond",0.1);reject(body);
		body=settings();((ObjectNode)body.at("/actions/IDLE")).put("weight",0);reject(body);
		body=settings();body.put("personalSpaceTiles",4).put("approachDistanceTiles",2);reject(body);
		body=settings();((ObjectNode)body.at("/ballPlay")).put("returnEnabled",true);reject(body);
		body=settings();body.put("unknown",true);reject(body);
		var request=request().put("confirmedBy",UUID.randomUUID().toString());
		assertThatThrownBy(()->BehaviorInput.save(request)).isInstanceOf(BehaviorException.class);
	}
	@ParameterizedTest @ValueSource(strings={"expectedRevision:-1","expectedRevision:1.5","schemaVersion:2","source:\"USER\"","evidenceObservationIds:null","evidenceObservationIds:[\"bad\"]"})
	void saveRequiresSupportedVersionSourceAndEvidenceShape(String change) {
		var parts=change.split(":",2);var body=request();body.set(parts[0],json.readTree(parts[1]));
		assertThatThrownBy(()->BehaviorInput.save(body)).isInstanceOf(BehaviorException.class);
	}
	@Test void evidenceCannotBeDuplicatedOrUnbounded() {
		var body=request();var refs=body.putArray("evidenceObservationIds");String id=UUID.randomUUID().toString();refs.add(id).add(id.toUpperCase());
		assertThatThrownBy(()->BehaviorInput.save(body)).isInstanceOf(BehaviorException.class);
		refs.removeAll();for(int i=0;i<21;i++)refs.add(UUID.randomUUID().toString());
		assertThatThrownBy(()->BehaviorInput.save(body)).isInstanceOf(BehaviorException.class);
	}
	@Test void boundaryValuesAndAiDraftSourceAreAccepted() {
		var body=request().put("source","AI_SUGGESTED");var config=(ObjectNode)body.get("settings");
		config.put("approachDistanceTiles",12).put("personalSpaceTiles",12).put("reactionDelayMs",10000);
		((ObjectNode)config.at("/actions/RUN")).put("weight",100).put("speedTilesPerSecond",6).put("minDurationMs",30000).put("maxDurationMs",60000).put("cooldownMs",120000);
		((ObjectNode)config.at("/ballPlay")).put("chaseEnabled",true).put("returnEnabled",true).put("reactionDelayMs",0);
		assertThat(BehaviorInput.save(body).settings().actions().get(Action.RUN).weight()).isEqualTo(100);
	}
	private void reject(ObjectNode body) { assertThatThrownBy(()->BehaviorInput.settings(body)).isInstanceOf(BehaviorException.class); }
}
