package org.shelterconnect.api.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class ChatInput {
	private ChatInput() {}
	record Send(String clientMessageId, String text) {}
	record Cursor(Instant at, UUID id) {}
	static void emptyBody(JsonNode body) {
		if (body != null && (!body.isObject() || !body.isEmpty())) throw ChatException.invalid();
	}
	static Send send(JsonNode body) {
		if (body == null || !body.isObject() || body.size()!=2
				|| !body.has("text") || !body.has("clientMessageId")) throw ChatException.invalid();
		String key=text(body.get("clientMessageId"),128), text=text(body.get("text"),4000);
		if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw ChatException.invalid();
		return new Send(key,text);
	}
	private static String text(JsonNode node,int max) {
		if (!node.isString()) throw ChatException.invalid();
		String value=node.asString();
		if(value.isBlank() || value.indexOf('\0')>=0 || value.codePointCount(0,value.length())>max) throw ChatException.invalid();
		return value;
	}
	static UUID id(String value) {
		try {
			UUID id=UUID.fromString(value);
			if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
			return id;
		} catch(IllegalArgumentException|NullPointerException ex) { throw ChatException.invalid(); }
	}
	static int limit(String value) {
		if(value==null) return 20;
		if(!value.matches("[0-9]{1,2}") || Integer.parseInt(value)<1 || Integer.parseInt(value)>50) throw ChatException.invalid();
		return Integer.parseInt(value);
	}
	static String cursor(String scope, Instant at, UUID id) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(("1\n"+scope+"\n"+at+"\n"+id).getBytes(StandardCharsets.UTF_8));
	}
	static Cursor cursor(String value,String scope) {
		if(value==null) return null;
		try {
			if(value.length()>400 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
			String[] parts=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8).split("\n",-1);
			if(parts.length!=4 || !parts[0].equals("1") || !parts[1].equals(scope)) throw new IllegalArgumentException();
			Instant at=Instant.parse(parts[2]);
			if(at.isBefore(Instant.EPOCH) || at.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw new IllegalArgumentException();
			return new Cursor(at,id(parts[3]));
		} catch(RuntimeException ex) { throw new ChatException(400,"INVALID_CURSOR","목록의 첫 페이지부터 다시 조회해 주세요."); }
	}
}
