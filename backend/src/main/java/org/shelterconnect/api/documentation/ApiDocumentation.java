package org.shelterconnect.api.documentation;

import java.io.IOException;
import java.util.*;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Supplement runtime-discovered routes/DTOs where strict JSON validation has no Java request DTO. */
@Configuration(proxyBeanMethods = false)
public class ApiDocumentation {
    @Bean OpenAPI shelterOpenApi() {
        return new OpenAPI().info(new Info().title("보호소 커넥트 API 명세서").version("v1")
            .description("""
                개발 서버: https://shelter-connect-dev.onrender.com

                Supabase 로그인 후 받은 **access token**을 Authorize에 입력하세요(Bearer 접두어 제외).
                로그인 자체는 Supabase Auth에서 처리합니다. 계정 최초 연결은 POST /v1/me입니다.
                보호소 API는 승인된 보호소의 활성 소속, 운영 API는 OPERATOR 권한이 필요합니다.

                Try it out은 현재 서버에 실제 요청을 보냅니다. 저장·AI·생성 요청은 데이터를 바꾸거나 사용량을 소모합니다.
                AI와 에셋 생성은 서버 설정이 활성화된 경우에만 가능합니다. Free 서버 첫 응답은 늦을 수 있습니다.
                목록은 nextCursor를 그대로 보내 이어 읽습니다. 오류는 code/message/requestId를 확인하세요.

                [사진·특징 연결 순서](https://github.com/HANN-Creator/shelter-connect/blob/main/docs/asset-input-workflow.md)
                · [행동별 재생 명세서](https://github.com/HANN-Creator/shelter-connect/blob/main/docs/dog-action-playback-spec.md)
                """))
            // Never infer a token destination from forwarded Host headers or a request parameter.
            .servers(List.of(new Server().url("/").description("현재 접속한 서버")))
            .components(new Components().addSecuritySchemes("supabaseBearer",new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                .description("Supabase 사용자 access token. API secret/service-role 키를 넣지 마세요.")));
    }

    @Bean OpenApiCustomizer apiContracts(JsonMapper json) throws IOException {
        final JsonNode contracts;
        try(var input=new ClassPathResource("api-documentation.json").getInputStream()) { contracts=json.readTree(input); }
        // Swagger's mapper includes Schema deserializers (additionalProperties / composed schemas).
        var schemas=new LinkedHashMap<String,Schema>();
        for(var entry:contracts.path("schemas").properties())
            schemas.put(entry.getKey(),io.swagger.v3.core.util.Json.mapper().readValue(entry.getValue().toString(),Schema.class));
        return api -> {
            schemas.forEach((name,schema) -> api.getComponents().addSchemas(name,schema));
            api.getPaths().forEach((path,item) -> item.readOperationsMap().forEach((method,op) -> {
                var spec=contracts.path("operations").path(method.name()+" "+path);
                if(spec.isMissingNode()) throw new IllegalStateException("API contract missing: "+method+" "+path);
                op.setSummary(spec.path("summary").asText());
                op.setDescription(spec.path("description").asText());
                op.setTags(List.of(spec.path("tag").asText()));
                op.setOperationId(method.name().toLowerCase(Locale.ROOT)+path.replaceAll("[^A-Za-z0-9]+","_"));
                op.setSecurity(spec.path("public").asBoolean(false)?List.of():List.of(new SecurityRequirement().addList("supabaseBearer")));
                if(op.getParameters()!=null) op.getParameters().forEach(p -> {
                    if("path".equals(p.getIn()) || p.getName().endsWith("Id")) p.setSchema(new StringSchema().format("uuid"));
                    if("cursor".equals(p.getName())) p.setDescription("이전 응답의 nextCursor. 첫 페이지에서는 생략.");
                    if("limit".equals(p.getName())) p.setSchema(new IntegerSchema().minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(50))._default(20));
                });
                if(spec.has("request")) {
                    var request=spec.get("request");
                    var media=new MediaType().schema(ref(request.path("schema").asText()));
                    if(request.has("example")) media.example(json.convertValue(request.get("example"),Object.class));
                    String contentType=request.path("contentType").asText("application/json");
                    if(contentType.equals("multipart/form-data")) media.addEncoding("metadata",new Encoding().contentType("application/json"));
                    op.setRequestBody(new RequestBody().required(request.path("required").asBoolean(true))
                        .content(new Content().addMediaType(contentType,media)));
                }
                var discovered=op.getResponses().values().stream().filter(r -> r.getContent()!=null).findFirst().orElse(null);
                var responses=new ApiResponses();
                for(var code:spec.path("success")) {
                    var response=new ApiResponse().description(code.asText().equals("202")?"접수됨 · 작업 상태를 조회하세요":"성공");
                    if(!code.asText().equals("204")) {
                        if(spec.has("responseSchema")) response.content(new Content().addMediaType("application/json",new MediaType().schema(ref(spec.path("responseSchema").asText()))));
                        else if(discovered!=null) response.content(discovered.getContent());
                    }
                    responses.addApiResponse(code.asText(),response);
                }
                for(var code:spec.path("errors")) responses.addApiResponse(code.asText(),error(code.asText()));
                if(!spec.path("public").asBoolean(false)) {
                    responses.addApiResponse("401",error("401"));responses.addApiResponse("403",error("403"));
                }
                responses.addApiResponse("500",error("500"));
                op.setResponses(responses);
            }));
        };
    }
    private static Schema<?> ref(String name) { return new Schema<>().$ref("#/components/schemas/"+name); }
    private static ApiResponse error(String status) {
        String description=switch(status) {
            case "400" -> "요청 형식·필드·커서 오류";
            case "401" -> "로그인 필요 / 토큰 만료·검증 실패";
            case "403" -> "계정·소속·운영 권한 또는 사진 열람 조건 불충족";
            case "404" -> "조회할 공개 리소스 또는 본인 리소스 없음";
            case "409" -> "버전·근거·허가·진행 상태 충돌. code를 확인하고 다시 조회";
            case "413" -> "사진 또는 요청 크기 초과";
            case "415" -> "지원하지 않는 Content-Type";
            case "422" -> "체형·이미지 검증 실패";
            case "429" -> "요청 한도 초과";
            case "502" -> "외부 사진 저장소 응답 오류";
            case "504" -> "외부 사진 저장소 응답 시간 초과";
            case "503" -> "AI/Storage/생성 기능 연결 비활성화 또는 일시적 사용 불가";
            default -> "서버 오류. requestId와 함께 확인 요청";
        };
        return new ApiResponse().description(description).content(new Content().addMediaType("application/json",new MediaType().schema(ref("ApiFailure"))));
    }
}
