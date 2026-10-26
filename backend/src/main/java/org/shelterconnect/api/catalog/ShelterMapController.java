package org.shelterconnect.api.catalog;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/v1")
public class ShelterMapController {
    private final CatalogRepository catalog;
    private final ShelterMapRepository maps;
    private final MapResourceStore resources;

    public ShelterMapController(CatalogRepository catalog, ShelterMapRepository maps, MapResourceStore resources) {
        this.catalog = catalog;
        this.maps = maps;
        this.resources = resources;
    }

    public record MapList(List<ShelterMapRepository.MapSummary> data) {}
    public record SceneResponse(ShelterMapRepository.MeetingScene data) {}

    @Operation(summary = "독대 화면 에셋 조회", description = "버전 고정 정적 배경·스프라이트 정보. 보호소 맵 목록과 별도로 사용합니다.")
    @GetMapping("/meeting-scenes/{sceneId}/versions/{version}")
    public SceneResponse meetingScene(@PathVariable String sceneId, @PathVariable String version) {
        if (!sceneId.matches("[a-z0-9-]{1,64}") || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || version.length() > 32) {
            throw CatalogException.notFound();
        }
        return new SceneResponse(maps.meetingScene(sceneId, version).orElseThrow(CatalogException::notFound));
    }

    @Operation(summary = "공개 보호소의 맵 3종 조회", description = "각 보호소는 세 공용 맵을 모두 사용합니다. URL은 API 원점을 기준으로 해석합니다.")
    @GetMapping("/shelters/{shelterId}/maps")
    public MapList maps(@PathVariable String shelterId) {
        var id = CatalogQuery.id(shelterId);
        catalog.shelter(id).orElseThrow(CatalogException::notFound);
        return new MapList(maps.maps(id));
    }

    @Operation(summary = "버전 고정 공용 맵 PNG·JSON 파일 조회")
    @GetMapping("/map-assets/{version}/{mapId}/{*file}")
    public ResponseEntity<byte[]> asset(@PathVariable String version, @PathVariable String mapId, @PathVariable String file) {
        var asset = resources.get(version, mapId, file);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable())
                .eTag(asset.sha256()).contentLength(asset.bytes().length).body(asset.bytes());
    }
}
