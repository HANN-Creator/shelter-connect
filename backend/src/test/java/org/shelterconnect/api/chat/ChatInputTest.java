package org.shelterconnect.api.chat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ChatInputTest {
	private final JsonMapper json=JsonMapper.builder().build();
	@Test void textIsPreservedExactlyAndUnicodeLimitsCountCharacters() {
		String text="  안녕!\n🐶  ";
		assertThat(ChatInput.send(json.createObjectNode().put("clientMessageId","request-1").put("text",text)).text()).isEqualTo(text);
		assertThat(ChatInput.send(json.createObjectNode().put("clientMessageId","r1").put("text","🐶".repeat(4000))).text()).hasSize(8000);
		assertThatThrownBy(()->ChatInput.send(json.createObjectNode().put("clientMessageId","r1").put("text","가".repeat(4001)))).isInstanceOf(ChatException.class);
	}
	@ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"clientMessageId\":\"r1\"}","{\"clientMessageId\":\"r1\",\"text\":null}",
		"{\"clientMessageId\":1,\"text\":\"hi\"}","{\"clientMessageId\":\" has space\",\"text\":\"hi\"}",
		"{\"clientMessageId\":\"r1\",\"text\":\"  \"}","{\"clientMessageId\":\"r1\",\"text\":4}",
		"{\"clientMessageId\":\"r1\",\"text\":\"hi\",\"role\":\"ASSISTANT\"}"})
	void rejectsMalformedOrForgedMessages(String body) {
		assertThatThrownBy(()->ChatInput.send(json.readTree(body))).isInstanceOf(ChatException.class);
	}
	@Test void rejectsNulAndOversizedKeys() {
		assertThatThrownBy(()->ChatInput.send(json.createObjectNode().put("clientMessageId","r1").put("text","\0"))).isInstanceOf(ChatException.class);
		assertThatThrownBy(()->ChatInput.send(json.createObjectNode().put("clientMessageId","x".repeat(129)).put("text","hi"))).isInstanceOf(ChatException.class);
	}
	@Test void openingAcceptsOnlyNoBodyOrEmptyObject() {
		ChatInput.emptyBody(null); ChatInput.emptyBody(json.createObjectNode());
		for(String value:new String[]{"null","[]","{\"userId\":\"someone\"}"})
			assertThatThrownBy(()->ChatInput.emptyBody(json.readTree(value))).isInstanceOf(ChatException.class);
	}
	@Test void cursorsRetainMicrosecondsAndAreScopedToResourceOwnerAndFilter() {
		UUID id=UUID.randomUUID(); Instant at=Instant.parse("2026-09-01T01:02:03.123456Z");
		String scope="messages:"+UUID.randomUUID()+":"+UUID.randomUUID();
		String cursor=ChatInput.cursor(scope,at,id);
		assertThat(ChatInput.cursor(cursor,scope)).isEqualTo(new ChatInput.Cursor(at,id));
		for(String other:new String[]{scope+"other","sessions:all"}) assertThatThrownBy(()->ChatInput.cursor(cursor,other)).isInstanceOf(ChatException.class);
		assertThatThrownBy(()->ChatInput.cursor(ChatInput.cursor(scope,Instant.MAX,id),scope)).isInstanceOf(ChatException.class);
	}
	@ParameterizedTest @ValueSource(strings={"","=","x"})
	void badCursorsAreClientErrors(String value) { assertThatThrownBy(()->ChatInput.cursor(value,"scope")).isInstanceOf(ChatException.class); }
	@ParameterizedTest @ValueSource(strings={"0","51","-1","1.5","100",""})
	void invalidPageSizesAreRejected(String value) { assertThatThrownBy(()->ChatInput.limit(value)).isInstanceOf(ChatException.class); }
	@Test void validPageSizesAndCanonicalIds() {
		assertThat(ChatInput.limit(null)).isEqualTo(20); assertThat(ChatInput.limit("50")).isEqualTo(50);
		UUID id=UUID.randomUUID(); assertThat(ChatInput.id(id.toString().toUpperCase())).isEqualTo(id);
		assertThatThrownBy(()->ChatInput.id("1-1-1-1-1")).isInstanceOf(ChatException.class);
	}
}
