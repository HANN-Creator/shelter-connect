package org.shelterconnect.api.chat;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.shelterconnect.api.chat.AiTypes.*;

class AiGroundingTest {
	private final AiGrounding grounding=new AiGrounding();
	private final Observation fact=new Observation(UUID.randomUUID(),"PLAY","공을 따라가요",Instant.now(),Instant.now());
	private final Context context=new Context("봄이","뭘 좋아해?",List.of(),List.of(fact));
	@Test void onlySuppliedCitationIdsAreAccepted() {
		var answer=new Generated("난 공을 따라가는 게 좋아!",false,List.of(fact.id()),"resp_test");
		assertThat(grounding.check(context,answer)).isEqualTo(answer);
		for(var ids:List.of(List.<UUID>of(),List.of(UUID.randomUUID()),List.of(fact.id(),fact.id()))) {
			assertThat(grounding.check(context,new Generated("근거 없는 단정",false,ids,"resp_test"))).isEqualTo(AiTypes.unknown());
		}
	}
	@Test void uncertainOrMalformedTextIsReplacedRatherThanEchoed() {
		for(var answer:List.of(new Generated("절대로 물지 않아!",true,List.of(fact.id()),"resp_test"),
				new Generated(" ",false,List.of(fact.id()),null),new Generated("x".repeat(1001),false,List.of(fact.id()),null),
				new Generated("\0",false,List.of(fact.id()),null))) assertThat(grounding.check(context,answer)).isEqualTo(AiTypes.unknown());
		assertThat(grounding.check(context,null)).isEqualTo(AiTypes.unknown());
	}
	@Test void localGreetingsAndMissingRecordsNeedNoProvider() {
		assertThat(grounding.local(new Context("봄이","안녕!",List.of(),List.of(fact))).orElseThrow().needsShelterConfirmation()).isFalse();
		assertThat(grounding.local(new Context("봄이","건강해?",List.of(),List.of())).orElseThrow()).isEqualTo(AiTypes.unknown());
		assertThat(grounding.local(context)).isEmpty();
	}
	@Test void propertiesDoNotPrintKeysAndFailClosedWhenEnabledWithoutOne() {
		assertThatThrownBy(()->new AiProperties(true,"","gpt-5.6-luna",30)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new AiProperties(false,"","gpt-5.6-luna",90)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new AiProperties(true,"secret-test-value","gpt-5.6-luna",30).toString()).doesNotContain("secret-test-value");
	}
}
