package org.shelterconnect.api.asset;

import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import tools.jackson.databind.JsonNode;

@RestController @RequestMapping("/v1/operations/styled-quality-lessons")
public class StyledLessonController {
    private final StyledLessonStore store;
    public StyledLessonController(StyledLessonStore store){this.store=store;}
    @GetMapping public Item<List<JsonNode>> list(@AuthenticationPrincipal Jwt jwt){return new Item<>(store.list(UUID.fromString(jwt.getSubject())));}
    @GetMapping("/{id}") public Item<JsonNode> read(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return new Item<>(store.read(UUID.fromString(jwt.getSubject()),AssetInput.id(id)));}
    @PostMapping("/{id}/disable") public Item<JsonNode> disable(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody JsonNode body){return new Item<>(store.disable(UUID.fromString(jwt.getSubject()),AssetInput.id(id),body));}
}
