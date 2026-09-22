package org.shelterconnect.api.asset;

import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import org.shelterconnect.api.catalog.CatalogResponses.Item;

@RestController
@RequestMapping("/v1")
public class AssetController {
    private final AssetStore store;
    private final AssetManifestService manifests;
    public AssetController(AssetStore store,AssetManifestService manifests) { this.store=store;this.manifests=manifests; }
    @PostMapping("/operations/asset-permissions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Item<Map<String,UUID>> permission(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body) {
        return new Item<>(Map.of("id",store.permission(subject(jwt),body)));
    }
    @DeleteMapping("/operations/asset-permissions/{permissionId}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable String permissionId) { store.revoke(subject(jwt),AssetInput.id(permissionId)); }
    @PostMapping("/operations/asset-imports")
    public Item<Map<String,Object>> imported(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body) { return new Item<>(store.imported(subject(jwt),body)); }
    @PostMapping("/operations/asset-jobs/{jobId}/reconcile")
    public Item<AssetStore.Job> reconcile(@AuthenticationPrincipal Jwt jwt,@PathVariable String jobId,@RequestBody JsonNode body) { return new Item<>(store.reconcile(subject(jwt),AssetInput.id(jobId),body)); }
    @PostMapping("/operations/asset-jobs/{jobId}/retry")
    public Item<AssetStore.Job> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable String jobId) { return new Item<>(store.retry(subject(jwt),AssetInput.id(jobId))); }
    @PostMapping("/shelter-admin/dogs/{dogId}/assets") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Item<AssetStore.Job> request(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@RequestBody JsonNode body) { return new Item<>(store.request(subject(jwt),AssetInput.id(dogId),body)); }
    @GetMapping("/shelter-admin/dogs/{dogId}/assets/{jobId}")
    public Item<AssetStore.Job> read(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId) { return new Item<>(store.read(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId))); }
    @PostMapping("/shelter-admin/dogs/{dogId}/assets/{jobId}/review")
    public Item<AssetStore.Job> review(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId,@RequestBody JsonNode body) { return new Item<>(store.review(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId),body)); }
    @GetMapping("/shelter-admin/dogs/{dogId}/assets/{jobId}/preview")
    public Item<Map<String,Object>> preview(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId) { return new Item<>(manifests.preview(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId))); }
    @GetMapping("/dogs/{dogId}/assets")
    public Item<Map<String,Object>> published(@PathVariable String dogId) { return new Item<>(manifests.published(AssetInput.id(dogId))); }
    private static UUID subject(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
