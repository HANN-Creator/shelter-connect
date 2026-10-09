package org.shelterconnect.api.asset;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import tools.jackson.databind.JsonNode;

@RestController @RequestMapping("/v1")
public class StyledAssetController {
    private final StyledAssetStore store;private final StyledAssetManifest manifests;private final StyledLearningRecoveryStore learning;
    public StyledAssetController(StyledAssetStore store,StyledAssetManifest manifests,StyledLearningRecoveryStore learning) { this.store=store;this.manifests=manifests;this.learning=learning; }
    @PostMapping("/shelter-admin/dogs/{dogId}/styled-assets") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Item<StyledAssetStore.Job> request(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@RequestBody JsonNode body) { return new Item<>(store.request(subject(jwt),AssetInput.id(dogId),body)); }
    @GetMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}")
    public Item<StyledAssetStore.Job> read(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId) { return new Item<>(store.read(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId))); }
    @GetMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/preview")
    public Item<Map<String,Object>> preview(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId) { return new Item<>(manifests.preview(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId))); }
    @PostMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/seed-review")
    public Item<StyledAssetStore.Job> seedReview(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId,@RequestBody JsonNode body) { return new Item<>(store.review(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId),body,true)); }
    @PostMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/review")
    public Item<StyledAssetStore.Job> review(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId,@RequestBody JsonNode body) { return new Item<>(store.review(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId),body,false)); }
    @PostMapping("/operations/styled-asset-jobs/{jobId}/recover")
    public Item<StyledAssetStore.Job> recover(@AuthenticationPrincipal Jwt jwt,@PathVariable String jobId,@RequestBody JsonNode body) { return new Item<>(store.recover(subject(jwt),AssetInput.id(jobId),body)); }
    @PostMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/repair")
    public Item<StyledAssetStore.Job> repair(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId,@RequestBody JsonNode body) {return new Item<>(store.repair(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId),body));}
    @PostMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/quality-recheck")
    public Item<StyledAssetStore.Job> qualityRecheck(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId,@RequestBody JsonNode body) {return new Item<>(store.recheck(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId),body));}
    @PostMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/seed-repair-resume")
    public Item<StyledAssetStore.Job> resumeSeedRepair(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId,@RequestBody JsonNode body) {return new Item<>(store.resumeSeedRepair(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId),body));}
    @PostMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/repair-continuation")
    public Item<StyledAssetStore.Job> continueRepair(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId,@RequestBody JsonNode body) {return new Item<>(store.continueRepair(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId),body));}
    @PostMapping("/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/learning-repair")
    public Item<StyledAssetStore.Job> learningRepair(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@PathVariable String jobId,@RequestBody JsonNode body) {
        return new Item<>(learning.arm(subject(jwt),AssetInput.id(dogId),AssetInput.id(jobId),body));
    }
    private UUID subject(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
