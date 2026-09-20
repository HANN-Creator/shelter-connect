package org.shelterconnect.api.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.shelterconnect.api.auth.AccountRepository.*;

@Service
@Transactional(readOnly = true)
public class AccountService {
	public record Profile(UUID id, String displayName, String role) {}
	private final AccountRepository repository;

	public AccountService(AccountRepository repository) { this.repository = repository; }

	@Transactional
	public Profile register(UUID subject) {
		repository.register(subject);
		return profile(subject);
	}

	public Profile profile(UUID subject) {
		var account = repository.account(subject).orElseThrow(() ->
				new AccountAccessException(403, "ACCOUNT_NOT_REGISTERED", "서비스 사용자 등록이 필요해요."));
		if (account.disabled()) throw new AccountAccessException(403, "ACCOUNT_DISABLED", "사용이 중지된 계정이에요.");
		return new Profile(account.id(), account.displayName(), account.role());
	}

	public List<ShelterMembership> shelters(UUID subject) {
		profile(subject);
		return repository.shelters(subject);
	}
}
