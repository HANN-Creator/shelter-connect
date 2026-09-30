package org.shelterconnect.api.community;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.shelterconnect.api.catalog.CatalogResponses.*;
import org.shelterconnect.api.web.*;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.community.CommunityTypes.*;
import static org.shelterconnect.api.web.FeatureInput.id;

@RestController @RequestMapping("/v1")
public class CommunityController {
    private final CommunityService service;private final CommunityMediaService media;
    public CommunityController(CommunityService service,CommunityMediaService media){this.service=service;this.media=media;}
    @GetMapping("/me/community-region") public Item<Region> region(@AuthenticationPrincipal Jwt jwt){return new Item<>(service.region(subject(jwt)));}
    @PutMapping("/me/community-region") public Item<Region> region(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body){return new Item<>(service.region(subject(jwt),body));}
    @GetMapping("/community/posts") public Page<Post> posts(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String q,@RequestParam(required=false) String region,@RequestParam(required=false) String category,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit){return service.posts(subject(jwt),q,region,category,cursor,limit,false,null);}
    @GetMapping("/me/community-posts") public Page<Post> mine(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String q,@RequestParam(required=false) String publication,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit){return service.posts(subject(jwt),q,null,null,cursor,limit,true,publication);}
    @GetMapping("/community/posts/{postId}") public Item<Post> post(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId){return new Item<>(service.post(subject(jwt),id(postId)));}
    @PostMapping("/community/posts") public Item<Post> create(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body){return new Item<>(service.create(subject(jwt),body));}
    @PatchMapping("/community/posts/{postId}") public Item<Post> edit(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId,@RequestBody JsonNode body){return new Item<>(service.edit(subject(jwt),id(postId),body));}
    @PostMapping("/community/posts/{postId}/publish") public Item<Post> publish(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId,@RequestBody JsonNode body){return new Item<>(service.publish(subject(jwt),id(postId),body));}
    @PutMapping("/community/posts/{postId}/status") public Item<Post> status(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId,@RequestBody JsonNode body){return new Item<>(service.status(subject(jwt),id(postId),body));}
    @DeleteMapping("/community/posts/{postId}") public Item<Mutation> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId,@RequestParam long version){return new Item<>(service.delete(subject(jwt),id(postId),version));}
    @GetMapping("/community/posts/{postId}/comments") public Page<Comment> comments(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId,@RequestParam(required=false) String kind,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit){return service.comments(subject(jwt),id(postId),kind,cursor,limit);}
    @PostMapping("/community/posts/{postId}/comments") public Item<Comment> comment(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId,@RequestBody JsonNode body){return new Item<>(service.comment(subject(jwt),id(postId),body));}
    @DeleteMapping("/community/posts/{postId}/comments/{commentId}") public Item<Mutation> deleteComment(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId,@PathVariable String commentId){return new Item<>(service.deleteComment(subject(jwt),id(postId),id(commentId)));}
    @PostMapping("/community/posts/{postId}/reports") public Item<ReportReceipt> report(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId,@RequestBody JsonNode body){return new Item<>(service.report(subject(jwt),id(postId),body));}
    @GetMapping("/operations/community-reports") public Page<Report> reports(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String status,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit){return service.reports(subject(jwt),status,cursor,limit);}
    @PutMapping("/operations/community-reports/{reportId}") public Item<Report> review(@AuthenticationPrincipal Jwt jwt,@PathVariable String reportId,@RequestBody JsonNode body){return new Item<>(service.review(subject(jwt),id(reportId),body));}
    @PostMapping(value="/community/media",consumes="multipart/form-data") public Item<Media> upload(@AuthenticationPrincipal Jwt jwt,@RequestParam String clientRequestId,@RequestPart MultipartFile file,HttpServletRequest request)throws Exception {
        var parts=request.getParts();if(parts.size()!=2 || !parts.stream().map(jakarta.servlet.http.Part::getName).collect(java.util.stream.Collectors.toSet()).equals(Set.of("file","clientRequestId")))throw FeatureException.invalid();
        return new Item<>(media.upload(subject(jwt),id(clientRequestId),file.getBytes()));
    }
    @GetMapping("/community/media/{mediaId}") public Item<MediaLink> media(@AuthenticationPrincipal Jwt jwt,@PathVariable String mediaId){return new Item<>(media.link(subject(jwt),id(mediaId)));}
    private static UUID subject(Jwt jwt){return UUID.fromString(jwt.getSubject());}
}
