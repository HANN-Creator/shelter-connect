package org.shelterconnect.api.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
	public record Account(UUID id, String displayName, String role, boolean disabled) {}
	public record ShelterMembership(UUID shelterId, String name, String memberRole) {}
	public record ManagementAccess(UUID shelterId, UUID dogId, String memberRole) {}
	public record ManagementWriter(UUID userId, UUID shelterId, String memberRole) {}
	private final JdbcClient jdbc;
	private final String provider;

	public AccountRepository(JdbcClient jdbc, SupabaseProperties properties) {
		this.jdbc = jdbc;
		this.provider = properties.providerKey();
	}

	public void register(UUID subject) {
		jdbc.sql("""
				INSERT INTO shelter.app_users(display_name, role, auth_provider, auth_subject)
				VALUES ('방문자', 'USER', :provider, :subject)
				ON CONFLICT (auth_provider, auth_subject) DO NOTHING
				""").param("provider", provider).param("subject", subject.toString()).update();
	}

	public Optional<Account> account(UUID subject) {
		return jdbc.sql("""
				SELECT id, display_name, role, disabled_at IS NOT NULL AS disabled
				FROM shelter.app_users WHERE auth_provider = :provider AND auth_subject = :subject
				""").param("provider", provider).param("subject", subject.toString())
				.query((rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("display_name"),
						rs.getString("role"), rs.getBoolean("disabled"))).optional();
	}

	public List<ShelterMembership> shelters(UUID subject) {
		return jdbc.sql("SELECT s.id, s.name, m.role FROM shelter.shelters s " + membershipJoin()
				+ " WHERE " + allowed() + " ORDER BY s.id")
				.param("provider", provider).param("subject", subject.toString())
				.query((rs, row) -> new ShelterMembership(rs.getObject("id", UUID.class),
						rs.getString("name"), rs.getString("role"))).list();
	}

	public Optional<ManagementAccess> shelterAccess(UUID subject, UUID shelterId) {
		return jdbc.sql("SELECT s.id, m.role FROM shelter.shelters s " + membershipJoin()
				+ " WHERE " + allowed() + " AND s.id = :id")
				.param("provider", provider).param("subject", subject.toString()).param("id", shelterId)
				.query((rs, row) -> new ManagementAccess(rs.getObject("id", UUID.class), null,
						rs.getString("role"))).optional();
	}

	public Optional<ManagementAccess> dogAccess(UUID subject, UUID dogId) {
		return jdbc.sql("SELECT s.id, d.id AS dog_id, m.role FROM shelter.dogs d "
				+ "JOIN shelter.shelters s ON s.id = d.shelter_id " + membershipJoin()
				+ " WHERE " + allowed() + " AND d.id = :id")
				.param("provider", provider).param("subject", subject.toString()).param("id", dogId)
				.query((rs, row) -> new ManagementAccess(rs.getObject("id", UUID.class),
						rs.getObject("dog_id", UUID.class), rs.getString("role"))).optional();
	}

	public Optional<ManagementWriter> lockShelterAccess(UUID subject, UUID shelterId) {
		// Shared row locks keep the account, membership and approval valid until the write commits.
		return jdbc.sql("SELECT u.id AS user_id, s.id, m.role FROM shelter.shelters s " + membershipJoin()
				+ " WHERE " + allowed() + " AND s.id = :id FOR SHARE OF u, m, s")
				.param("provider", provider).param("subject", subject.toString()).param("id", shelterId)
				.query((rs, row) -> new ManagementWriter(rs.getObject("user_id", UUID.class),
						rs.getObject("id", UUID.class), rs.getString("role"))).optional();
	}

	public boolean lockDogInShelter(UUID dogId, UUID shelterId) {
		return jdbc.sql("SELECT id FROM shelter.dogs WHERE id = :dog AND shelter_id = :shelter FOR UPDATE")
				.param("dog", dogId).param("shelter", shelterId).query(UUID.class).optional().isPresent();
	}

	private String membershipJoin() {
		return "JOIN shelter.shelter_memberships m ON m.shelter_id = s.id JOIN shelter.app_users u ON u.id = m.user_id";
	}

	private String allowed() {
		return "u.auth_provider = :provider AND u.auth_subject = :subject AND u.disabled_at IS NULL"
				+ " AND m.status = 'ACTIVE' AND m.role IN ('MANAGER', 'STAFF') AND s.approval_status = 'APPROVED'";
	}
}
