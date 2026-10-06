package org.shelterconnect.api.asset;

import java.io.IOException;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Private, hash-bound review material. Importing it never submits a PixelLab generation. */
@RestController
@RequestMapping("/v1/shelter-admin/dogs/{dogId}/styled-seed-examples")
public class StyledSeedExampleController {
    private final StyledAssetStore store;private final AssetStorage storage;private final JsonMapper json;
    public StyledSeedExampleController(StyledAssetStore store,AssetStorage storage,JsonMapper json){this.store=store;this.storage=storage;this.json=json;}
    @io.swagger.v3.oas.annotations.Operation(summary="기본 도트 학습 자료 등록",description="소속 STAFF/MANAGER 전용. 허가된 원본 사진과 32×32 투명 PNG 4방향을 해시로 연결해 Luna 검수만 실행합니다. POSITIVE는 AI 통과 후 기존 seed-review 승인도 필요합니다. NEGATIVE의 담당자 결함 평가와 AI 판정은 따로 보존합니다. 학습 자료는 PixelLab 생성·행동 생성·앱 공개에 사용되지 않습니다.")
    @PostMapping(consumes="multipart/form-data") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Item<StyledAssetStore.Job> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,
        @RequestPart("metadata") JsonNode body,@RequestPart("south") MultipartFile south,@RequestPart("north") MultipartFile north,
        @RequestPart("west") MultipartFile west,@RequestPart("east") MultipartFile east)throws IOException {
        UUID subject=UUID.fromString(jwt.getSubject()),dog=AssetInput.id(dogId),photo=AssetInput.id(body,"photoId");
        var source=store.referencePhoto(subject,dog,photo);
        AssetInput.fields(body,"photoId","sourcePhotoSha256","expectedSeedHashes","assessment","issues","note");
        String sourceSha=AssetInput.text(body,"sourcePhotoSha256",64),assessment=AssetInput.text(body,"assessment",8),note=AssetInput.text(body,"note",1000);
        if(!sourceSha.matches("[a-f0-9]{64}") || !Set.of("POSITIVE","NEGATIVE").contains(assessment) || note.length()<20)throw AssetException.invalid();
        var issues=body.path("issues");
        if(!issues.isArray() || issues.size()>5 || issues.valueStream().anyMatch(n->!n.isTextual() || !StyledLessonAgent.SEED_ISSUES.contains(n.asText()))
            || issues.valueStream().map(JsonNode::asText).distinct().count()!=issues.size()
            || (assessment.equals("POSITIVE")?!issues.isEmpty():issues.isEmpty()))throw AssetException.invalid();
        AssetInput.fields(body.path("expectedSeedHashes"),"south","north","west","east");
        var files=List.of(south,north,west,east);var images=new ArrayList<byte[]>();var hashes=new TreeMap<String,String>();
        for(int i=0;i<4;i++) {
            var file=files.get(i);if(file.getSize()>65536)throw new AssetException(413,"SEED_EXAMPLE_TOO_LARGE");
            byte[] bytes=file.getBytes();StyledSpriteCodec.nativeFrame(bytes);images.add(bytes);
            String direction=StyledSpriteCodec.DIRECTIONS.get(i),sha=StyledSpriteCodec.sha(bytes);
            if(!sha.equals(body.path("expectedSeedHashes").path(direction).asText()))throw new AssetException(409,"STYLED_SEED_CHANGED");
            hashes.put(direction,sha);
        }
        if(!sourceSha.equals(StyledSpriteCodec.sha(storage.photo(dog,source.bucket(),source.key()))))throw new AssetException(409,"SOURCE_PHOTO_CHANGED");
        String selection="reference-"+StyledSpriteCodec.sha(json.writeValueAsBytes(new TreeMap<>(Map.of("photo",photo,"photoSha",sourceSha,"hashes",hashes,"rules",StyledSpriteCodec.qualityRulesSha()))));
        var existing=store.referenceExisting(subject,dog,photo,selection,body);
        if(existing.isPresent())return new Item<>(existing.get());
        UUID id=UUID.randomUUID();var keys=new TreeMap<String,String>();
        for(int i=0;i<4;i++) {
            String direction=StyledSpriteCodec.DIRECTIONS.get(i),key=dog+"/"+id+"/native-32/directions/"+direction+".png";
            storage.put(key,images.get(i));keys.put(direction,key);
        }
        return new Item<>(store.referenceInsert(subject,dog,photo,id,selection,body,json.valueToTree(Map.of("keys",keys,"hashes",hashes))));
    }
}
