package org.shelterconnect.api.personal;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.*;
import org.shelterconnect.api.auth.AccountService;
import org.shelterconnect.api.web.FeatureInput;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.personal.PersonalService.*;

@RestController @RequestMapping("/v1")
public class PersonalController {
    private final PersonalService service;
    public PersonalController(PersonalService service) { this.service=service; }
    @GetMapping("/registration-policy") public Item<ConsentPolicy> policy() { return new Item<>(service.policy()); }
    @PatchMapping("/me/profile") public Item<AccountService.Profile> profile(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body) { return new Item<>(service.profile(subject(jwt),body)); }
    @GetMapping("/me/preferences") public Item<Preferences> preferences(@AuthenticationPrincipal Jwt jwt) { return new Item<>(service.preferences(subject(jwt))); }
    @PutMapping("/me/preferences") public Item<Preferences> preferences(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body) { return new Item<>(service.preferences(subject(jwt),body)); }
    @GetMapping("/me/consents") public Item<Consents> consents(@AuthenticationPrincipal Jwt jwt) { return new Item<>(service.consents(subject(jwt))); }
    @PutMapping("/me/consents") public Item<Consents> consents(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body) { return new Item<>(service.consent(subject(jwt),body)); }
    @GetMapping("/me/saved-dogs/{dogId}") public Item<SavedState> saved(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId) { return new Item<>(service.saved(subject(jwt),FeatureInput.id(dogId))); }
    @PutMapping("/me/saved-dogs/{dogId}") public Item<SavedState> save(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId) { return new Item<>(service.save(subject(jwt),FeatureInput.id(dogId),true)); }
    @DeleteMapping("/me/saved-dogs/{dogId}") public Item<SavedState> unsave(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId) { return new Item<>(service.save(subject(jwt),FeatureInput.id(dogId),false)); }
    @GetMapping("/me/saved-dogs") public Page<SavedDog> savedDogs(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String q,@RequestParam(required=false) String shelterId,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit) { return service.savedDogs(subject(jwt),q,shelterId,cursor,limit); }
    @GetMapping("/me/dog-conversations") public Page<Conversation> conversations(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String q,@RequestParam(defaultValue="false") boolean savedOnly,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit) { return service.conversations(subject(jwt),q,savedOnly,cursor,limit); }
    @GetMapping("/shelter-discovery") public Page<NearbyShelter> discovery(@RequestParam(required=false) String q,@RequestParam(required=false) String region,@RequestParam(required=false) String latitude,@RequestParam(required=false) String longitude,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit) { return service.discovery(q,region,latitude,longitude,cursor,limit); }
    private UUID subject(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
