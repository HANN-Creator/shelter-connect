package org.shelterconnect.api.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.shelterconnect.api.catalog.CatalogResponses.*;

@RestController
@RequestMapping("/v1")
public class PublicCatalogController {
	private final CatalogService service;

	public PublicCatalogController(CatalogService service) {
		this.service = service;
	}

	@GetMapping("/shelters")
	public Page<ShelterSummary> shelters(@RequestParam(required = false) String region,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
		return service.shelters(region, cursor, limit);
	}

	@GetMapping("/shelters/{shelterId}")
	public Item<ShelterDetail> shelter(@PathVariable String shelterId) {
		return new Item<>(service.shelter(shelterId));
	}

	@GetMapping("/shelters/{shelterId}/dogs")
	public Page<DogSummary> dogs(@PathVariable String shelterId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
		return service.dogs(shelterId, cursor, limit);
	}

	@GetMapping("/dogs/{dogId}")
	public Item<DogDetail> dog(@PathVariable String dogId) {
		return new Item<>(service.dog(dogId));
	}
}
