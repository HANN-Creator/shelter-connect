package org.shelterconnect.api.adoption;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.*;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.adoption.AdoptionNoteResponses.*;

@RestController
@RequestMapping("/v1/me/adoption-notes")
public class AdoptionNoteController {
	private final AdoptionNoteService service;
	public AdoptionNoteController(AdoptionNoteService service) { this.service = service; }
	@GetMapping
	public Page<Note> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) String limit) {
		return service.list(UUID.fromString(jwt.getSubject()), cursor, limit);
	}
	@GetMapping("/{dogId}")
	public Item<Note> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId) {
		return new Item<>(service.get(UUID.fromString(jwt.getSubject()), dogId));
	}
	@PutMapping("/{dogId}")
	public ResponseEntity<Item<Note>> save(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId, @RequestBody JsonNode body) {
		var stored = service.save(UUID.fromString(jwt.getSubject()), dogId, body);
		return ResponseEntity.status(stored.created() ? 201 : 200)
				.location(URI.create("/v1/me/adoption-notes/" + stored.value().dogId())).body(new Item<>(stored.value()));
	}
}
