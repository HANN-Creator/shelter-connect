package org.shelterconnect.api.behavior;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

@RestController
public class BehaviorController {
	private final BehaviorService service;
	public BehaviorController(BehaviorService service) { this.service=service; }
	@GetMapping("/v1/dogs/{dogId}/behavior")
	public Item<Playback> playback(@PathVariable String dogId) { return new Item<>(service.playback(dogId)); }
	@GetMapping("/v1/shelter-admin/dogs/{dogId}/behavior")
	public Item<Profile> profile(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId) { return new Item<>(service.profile(subject(jwt),dogId)); }
	@PutMapping("/v1/shelter-admin/dogs/{dogId}/behavior")
	public Item<Profile> save(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@RequestBody JsonNode body) { return new Item<>(service.save(subject(jwt),dogId,body)); }
	@PostMapping("/v1/shelter-admin/dogs/{dogId}/behavior/confirmation")
	public Item<Profile> confirm(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@RequestBody JsonNode body) { return new Item<>(service.confirm(subject(jwt),dogId,body)); }
	private UUID subject(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
