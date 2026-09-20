package org.shelterconnect.api.photo;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.Page;
import org.shelterconnect.api.photo.PhotoTypes.Photo;

@RestController
public class PhotoController {
	private final PhotoService service;
	public PhotoController(PhotoService service) { this.service = service; }
	@GetMapping("/v1/dogs/{dogId}/photos")
	public Page<Photo> photos(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
		return service.list(UUID.fromString(jwt.getSubject()), dogId, cursor, limit);
	}
}
