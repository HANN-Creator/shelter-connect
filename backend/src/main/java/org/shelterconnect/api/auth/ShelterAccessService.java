package org.shelterconnect.api.auth;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import static org.shelterconnect.api.auth.AccountRepository.ManagementAccess;
import static org.shelterconnect.api.auth.AccountRepository.ManagementWriter;

@Service
@Transactional(readOnly = true)
public class ShelterAccessService {
	private final AccountRepository repository;
	private final AccountService accounts;

	public ShelterAccessService(AccountRepository repository, AccountService accounts) {
		this.repository = repository;
		this.accounts = accounts;
	}

	// Read checks do not grant permission to a later write; mutation services use the locked guards below.
	public ManagementAccess requireShelter(UUID subject, UUID shelterId) {
		accounts.profile(subject);
		return repository.shelterAccess(subject, shelterId).orElseThrow(ShelterAccessService::forbidden);
	}

	public ManagementAccess requireDog(UUID subject, UUID dogId) {
		accounts.profile(subject);
		return repository.dogAccess(subject, dogId).orElseThrow(ShelterAccessService::forbidden);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public ManagementWriter requireShelterForWrite(UUID subject, UUID shelterId) {
		accounts.profile(subject);
		return repository.lockShelterAccess(subject, shelterId).orElseThrow(ShelterAccessService::forbidden);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public ManagementWriter requireDogForWrite(UUID subject, UUID dogId) {
		var current = requireDog(subject, dogId);
		var writer = requireShelterForWrite(subject, current.shelterId());
		// Ownership may have changed before we obtained the dog lock. Recheck it after waiting.
		if (!repository.lockDogInShelter(dogId, writer.shelterId())) throw forbidden();
		return writer;
	}

	private static AccountAccessException forbidden() {
		return new AccountAccessException(403, "FORBIDDEN", "이 보호소의 동물을 관리할 권한이 없어요.");
	}
}
