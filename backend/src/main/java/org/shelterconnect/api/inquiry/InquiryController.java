package org.shelterconnect.api.inquiry;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.inquiry.InquiryTypes.*;
import static org.shelterconnect.api.web.FeatureInput.id;

@RestController @RequestMapping("/v1")
public class InquiryController {
    private final InquiryService service;
    public InquiryController(InquiryService service){this.service=service;}
    @PostMapping("/community/posts/{postId}/inquiries") public Item<Room> open(@AuthenticationPrincipal Jwt jwt,@PathVariable String postId){return new Item<>(service.open(subject(jwt),id(postId)));}
    @GetMapping("/inquiry-rooms") public Rooms rooms(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String q,@RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit){return service.rooms(subject(jwt),q,unreadOnly,cursor,limit);}
    @GetMapping("/inquiry-rooms/{roomId}") public Item<Room> room(@AuthenticationPrincipal Jwt jwt,@PathVariable String roomId){return new Item<>(service.room(subject(jwt),id(roomId)));}
    @GetMapping("/inquiry-rooms/{roomId}/messages") public Messages messages(@AuthenticationPrincipal Jwt jwt,@PathVariable String roomId,@RequestParam(required=false) String beforeSequence,@RequestParam(required=false) String afterSequence,@RequestParam(required=false) String limit){return service.messages(subject(jwt),id(roomId),beforeSequence,afterSequence,limit);}
    @PostMapping("/inquiry-rooms/{roomId}/messages") public Item<Message> send(@AuthenticationPrincipal Jwt jwt,@PathVariable String roomId,@RequestBody JsonNode body){return new Item<>(service.send(subject(jwt),id(roomId),body));}
    @PutMapping("/inquiry-rooms/{roomId}/read") public Item<ReadState> read(@AuthenticationPrincipal Jwt jwt,@PathVariable String roomId,@RequestBody JsonNode body){return new Item<>(service.markRead(subject(jwt),id(roomId),body));}
    private UUID subject(Jwt jwt){return UUID.fromString(jwt.getSubject());}
}
