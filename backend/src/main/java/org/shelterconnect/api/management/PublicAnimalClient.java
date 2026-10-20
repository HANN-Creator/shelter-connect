package org.shelterconnect.api.management;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Only this fixed government endpoint supplies public shelter identities. */
@Component
public class PublicAnimalClient {
    private static final String ENDPOINT="https://apis.data.go.kr/1543061/abandonmentPublicService_v2/abandonmentPublic_v2";
    private static final Set<String> FIELDS=Set.of("desertionNo","happenDt","happenPlace","kindCd","kindNm",
        "upKindCd","colorCd","age","weight","noticeNo","noticeSdt","noticeEdt","popfile1","popfile2",
        "processState","sexCd","neuterYn","specialMark","careRegNo","careNm","careTel","careAddr","orgNm","updTm");
    private final String key; private final JsonMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public PublicAnimalClient(@Value("${DATA_GO_KR_SERVICE_KEY:}") String key,JsonMapper json) { this.key=key;this.json=json; }
    public JsonNode fetch(String animalId) {
        if(key.isBlank()) throw new ManagementException(503,"PUBLIC_SOURCE_NOT_CONFIGURED","공공 데이터 연결 설정이 필요해요.");
        try {
            String query="?serviceKey="+URLEncoder.encode(URLDecoder.decode(key,StandardCharsets.UTF_8),StandardCharsets.UTF_8)
                +"&desertion_no="+animalId+"&pageNo=1&numOfRows=5&_type=json";
            var request=HttpRequest.newBuilder(URI.create(ENDPOINT+query)).timeout(Duration.ofSeconds(30)).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()!=200) throw new IllegalStateException();
                byte[] bytes=stream.readNBytes(262145);
                if(bytes.length>262144) throw new IllegalStateException();
                return parse(animalId,json.readTree(bytes));
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw unavailable();
        } catch(Exception failure) {
            // Never retain the credential-bearing URL in an exception or log.
            throw unavailable();
        }
    }
    JsonNode parse(String animalId,JsonNode payload) {
        if(!"00".equals(payload.at("/response/header/resultCode").asText())) throw unavailable();
        var items=payload.at("/response/body/items/item");
        if(items.isObject()) items=json.createArrayNode().add(items);
        JsonNode match=null;
        for(var item:items) if(animalId.equals(item.path("desertionNo").asText())) {
            if(match!=null) throw unavailable();match=item;
        }
        if(match==null || !"417000".equals(match.path("upKindCd").asText()) || !match.path("careRegNo").asText().matches("[0-9]{15}")) throw unavailable();
        var result=json.createObjectNode();
        for(String field:FIELDS) if(match.has(field)) result.set(field,match.get(field));
        return result;
    }
    private ManagementException unavailable() { return new ManagementException(502,"PUBLIC_SOURCE_UNAVAILABLE","공공 동물 정보를 확인하지 못했어요."); }
}
