package org.shelterconnect.api.chat;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/chat-sessions/{sessionId}/messages/{messageId}/reply")
public class AiReplyController {
	private final AiReplyService service;
	public AiReplyController(AiReplyService service) { this.service=service; }
	@PostMapping
	public ResponseEntity<Item<AiTypes.Reply>> reply(@AuthenticationPrincipal Jwt jwt,@PathVariable String sessionId,
			@PathVariable String messageId,@RequestBody(required=false) JsonNode body) {
		var outcome=service.reply(UUID.fromString(jwt.getSubject()),sessionId,messageId,body);
		return ResponseEntity.status(outcome.httpStatus()).body(new Item<>(outcome.data()));
	}
}
