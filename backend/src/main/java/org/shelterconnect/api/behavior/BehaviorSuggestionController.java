package org.shelterconnect.api.behavior;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.shelterconnect.api.chat.AiFailure;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/shelter-admin/dogs/{dogId}/behavior/suggestions")
public class BehaviorSuggestionController {
    private final BehaviorSuggestionStore store;private final BehaviorSuggestionProvider provider;
    public BehaviorSuggestionController(BehaviorSuggestionStore store,BehaviorSuggestionProvider provider) { this.store=store;this.provider=provider; }
    @PostMapping
    public Item<BehaviorSuggestionStore.Job> suggest(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@RequestBody JsonNode body) {
        UUID subject=UUID.fromString(jwt.getSubject()),dog=BehaviorInput.id(dogId);
        var work=store.prepare(subject,dog,body);if(!work.execute()) return new Item<>(work.job());
        try { return new Item<>(store.complete(subject,dog,work.job().id(),BehaviorTraitMapping.map(provider.suggest(work.observations()),work.observations()),work.observations())); }
        catch(AiFailure ex) { store.fail(work.job().id(),ex.code());return new Item<>(store.read(subject,dog,work.job().id())); }
        catch(BehaviorException ex) { store.fail(work.job().id(),ex.code);throw ex; }
        catch(RuntimeException ex) { store.fail(work.job().id(),"SUGGESTION_INTERRUPTED");throw ex; }
    }
    @GetMapping("/{suggestionId}")
    public Item<BehaviorSuggestionStore.Job> read(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String suggestionId) {
        return new Item<>(store.read(UUID.fromString(jwt.getSubject()),BehaviorInput.id(dogId),BehaviorInput.id(suggestionId)));
    }
}
