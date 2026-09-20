package org.shelterconnect.api.management;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.*;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.management.ManagementResponses.*;

@RestController
@RequestMapping("/v1/shelter-admin")
public class ManagementController {
	private final ManagementService service;
	public ManagementController(ManagementService service) { this.service = service; }
	@GetMapping("/shelters/{shelterId}/dogs")
	public Page<Dog> dogs(@AuthenticationPrincipal Jwt jwt, @PathVariable String shelterId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
		return service.dogs(subject(jwt), shelterId, cursor, limit);
	}
	@GetMapping("/dogs/{dogId}")
	public Item<Dog> dog(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId) {
		return new Item<>(service.dog(subject(jwt), dogId));
	}
	@PostMapping("/dogs")
	public ResponseEntity<Item<Dog>> create(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
		Dog dog = service.createDog(subject(jwt), body);
		return ResponseEntity.created(URI.create("/v1/shelter-admin/dogs/" + dog.id())).body(new Item<>(dog));
	}
	@PatchMapping("/dogs/{dogId}")
	public Item<Dog> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId, @RequestBody JsonNode body) {
		return new Item<>(service.updateDog(subject(jwt), dogId, body));
	}
	@GetMapping("/dogs/{dogId}/observations")
	public Page<Observation> observations(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
		return service.observations(subject(jwt), dogId, cursor, limit);
	}
	@PostMapping("/dogs/{dogId}/observations")
	public ResponseEntity<Item<Observation>> createObservation(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId, @RequestBody JsonNode body) {
		return ResponseEntity.status(201).body(new Item<>(service.createObservation(subject(jwt), dogId, body)));
	}
	@PatchMapping("/dogs/{dogId}/observations/{observationId}")
	public Item<Observation> updateObservation(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId,
			@PathVariable String observationId, @RequestBody JsonNode body) {
		return new Item<>(service.updateObservation(subject(jwt), dogId, observationId, body));
	}
	private UUID subject(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
