package org.shelterconnect.api.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import org.shelterconnect.api.auth.AccountRepository.ShelterMembership;
import org.shelterconnect.api.auth.AccountRepository.ManagementAccess;

@RestController
@RequestMapping("/v1")
public class AccountController {
	private final AccountService accounts;
	private final ShelterAccessService access;

	public AccountController(AccountService accounts, ShelterAccessService access) {
		this.accounts = accounts;
		this.access = access;
	}

	@PostMapping("/me")
	public Item<AccountService.Profile> register(@AuthenticationPrincipal Jwt jwt) {
		return new Item<>(accounts.register(subject(jwt)));
	}

	@GetMapping("/me")
	public Item<AccountService.Profile> me(@AuthenticationPrincipal Jwt jwt) {
		return new Item<>(accounts.profile(subject(jwt)));
	}

	@GetMapping("/me/shelters")
	public Item<List<ShelterMembership>> shelters(@AuthenticationPrincipal Jwt jwt) {
		return new Item<>(accounts.shelters(subject(jwt)));
	}

	@GetMapping("/shelter-admin/shelters/{shelterId}/access")
	public Item<ManagementAccess> shelter(@AuthenticationPrincipal Jwt jwt, @PathVariable String shelterId) {
		return new Item<>(access.requireShelter(subject(jwt), id(shelterId)));
	}

	@GetMapping("/shelter-admin/dogs/{dogId}/access")
	public Item<ManagementAccess> dog(@AuthenticationPrincipal Jwt jwt, @PathVariable String dogId) {
		return new Item<>(access.requireDog(subject(jwt), id(dogId)));
	}

	private UUID subject(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

	private UUID id(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
			return id;
		} catch (IllegalArgumentException exception) {
			throw new AccountAccessException(400, "INVALID_REQUEST", "ID는 UUID 형식으로 보내 주세요.");
		}
	}
}
