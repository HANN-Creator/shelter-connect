package org.shelterconnect.api.auth;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.shelterconnect.api.auth.AccountRepository.ManagementAccess;

@Service
@Transactional(readOnly = true)
public class ShelterAccessService {
	private final AccountRepository repository;
	private final AccountService accounts;

	public ShelterAccessService(AccountRepository repository, AccountService accounts) {
		this.repository = repository;
		this.accounts = accounts;
	}

	// Future mutation services must call these guards with the verified JWT subject inside their transaction.
	// An earlier /access response must never be accepted as permission for a later write.
	public ManagementAccess requireShelter(UUID subject, UUID shelterId) {
		accounts.profile(subject);
		return repository.shelterAccess(subject, shelterId).orElseThrow(ShelterAccessService::forbidden);
	}

	public ManagementAccess requireDog(UUID subject, UUID dogId) {
		accounts.profile(subject);
		return repository.dogAccess(subject, dogId).orElseThrow(ShelterAccessService::forbidden);
	}

	private static AccountAccessException forbidden() {
		return new AccountAccessException(403, "FORBIDDEN", "이 보호소의 동물을 관리할 권한이 없어요.");
	}
}
