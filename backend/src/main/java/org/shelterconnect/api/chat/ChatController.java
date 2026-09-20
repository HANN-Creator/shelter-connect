package org.shelterconnect.api.chat;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.*;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.chat.ChatResponses.*;

@RestController
@RequestMapping("/v1")
public class ChatController {
	private final ChatService service;
	public ChatController(ChatService service) { this.service=service; }
	@PostMapping("/dogs/{dogId}/chat-sessions")
	public ResponseEntity<Item<Session>> open(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,
			@RequestBody(required=false) JsonNode body) {
		var stored=service.open(subject(jwt),dogId,body);
		return ResponseEntity.status(stored.created()?201:200).location(URI.create("/v1/chat-sessions/"+stored.value().id())).body(new Item<>(stored.value()));
	}
	@GetMapping("/me/chat-sessions")
	public Page<Session> sessions(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String dogId,
			@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit) {
		return service.sessions(subject(jwt),dogId,cursor,limit);
	}
	@GetMapping("/chat-sessions/{sessionId}")
	public Item<Session> session(@AuthenticationPrincipal Jwt jwt,@PathVariable String sessionId) {
		return new Item<>(service.session(subject(jwt),sessionId));
	}
	@GetMapping("/chat-sessions/{sessionId}/messages")
	public Page<Message> messages(@AuthenticationPrincipal Jwt jwt,@PathVariable String sessionId,
			@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit) {
		return service.messages(subject(jwt),sessionId,cursor,limit);
	}
	@PostMapping("/chat-sessions/{sessionId}/messages")
	public ResponseEntity<Item<Message>> send(@AuthenticationPrincipal Jwt jwt,@PathVariable String sessionId,@RequestBody JsonNode body) {
		var stored=service.send(subject(jwt),sessionId,body);
		return ResponseEntity.status(stored.created()?201:200).body(new Item<>(stored.value()));
	}
	private UUID subject(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
