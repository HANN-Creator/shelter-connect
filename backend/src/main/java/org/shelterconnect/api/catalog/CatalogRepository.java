package org.shelterconnect.api.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static org.shelterconnect.api.catalog.CatalogResponses.*;

@Repository
public class CatalogRepository {
	private static final String PUBLIC_SHELTER = "s.approval_status = 'APPROVED' AND s.is_public = true";
	private static final String PUBLIC_DOG = "d.is_public = true AND d.archived_at IS NULL"
			+ " AND d.adoption_status IN ('AVAILABLE', 'IN_PROGRESS')";
	private static final String SHELTER_COLUMNS = """
			s.id, s.name, s.region, s.latitude, s.longitude, s.map_key,
			(SELECT count(*) FROM shelter.dogs d WHERE d.shelter_id = s.id AND
			""" + PUBLIC_DOG + ") AS dog_count";
	private static final String DOG_COLUMNS = "d.id, d.shelter_id, d.name, d.adoption_status, d.avatar_key, d.trait_labels";
	private static final RowMapper<ShelterSummary> SHELTER_ROW = (rs, row) -> new ShelterSummary(
			uuid(rs, "id"), rs.getString("name"), rs.getString("region"), rs.getBigDecimal("latitude"),
			rs.getBigDecimal("longitude"), rs.getString("map_key"), rs.getLong("dog_count"));
	private static final RowMapper<DogSummary> DOG_ROW = (rs, row) -> new DogSummary(
			uuid(rs, "id"), uuid(rs, "shelter_id"), rs.getString("name"), "DOG",
			rs.getString("adoption_status"), rs.getString("avatar_key"), labels(rs));

	private final JdbcClient jdbc;

	public CatalogRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<ShelterSummary> shelters(String region, UUID after, int count) {
		String sql = "SELECT " + SHELTER_COLUMNS + " FROM shelter.shelters s WHERE " + PUBLIC_SHELTER;
		if (!region.isEmpty()) sql += " AND starts_with(s.region, :region)";
		if (after != null) sql += " AND s.id > :after";
		var query = jdbc.sql(sql + " ORDER BY s.id LIMIT :count").param("count", count);
		if (!region.isEmpty()) query = query.param("region", region);
		if (after != null) query = query.param("after", after);
		return query.query(SHELTER_ROW).list();
	}

	public Optional<ShelterDetail> shelter(UUID id) {
		return jdbc.sql("SELECT " + SHELTER_COLUMNS + ", s.address, s.contact_phone, s.website_url"
				+ " FROM shelter.shelters s WHERE " + PUBLIC_SHELTER + " AND s.id = :id")
				.param("id", id).query((rs, row) -> new ShelterDetail(
						uuid(rs, "id"), rs.getString("name"), rs.getString("region"), rs.getString("address"),
						rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("contact_phone"),
						rs.getString("website_url"), rs.getString("map_key"), rs.getLong("dog_count"))).optional();
	}

	public List<DogSummary> dogs(UUID shelterId, UUID after, int count) {
		String sql = "SELECT " + DOG_COLUMNS + " FROM shelter.dogs d JOIN shelter.shelters s ON s.id = d.shelter_id"
				+ " WHERE " + PUBLIC_SHELTER + " AND " + PUBLIC_DOG + " AND s.id = :shelterId";
		if (after != null) sql += " AND d.id > :after";
		var query = jdbc.sql(sql + " ORDER BY d.id LIMIT :count").param("shelterId", shelterId).param("count", count);
		if (after != null) query = query.param("after", after);
		return query.query(DOG_ROW).list();
	}

	public Optional<DogDetail> dog(UUID id) {
		return jdbc.sql("SELECT " + DOG_COLUMNS + """
				, d.sex, d.breed, d.birth_date, d.birth_date_precision, d.birth_date_estimated,
				d.weight_kg, d.neutered, d.introduction
				FROM shelter.dogs d JOIN shelter.shelters s ON s.id = d.shelter_id WHERE
				""" + PUBLIC_SHELTER + " AND " + PUBLIC_DOG + " AND d.id = :id")
				.param("id", id).query((rs, row) -> new DogDetail(
						uuid(rs, "id"), uuid(rs, "shelter_id"), rs.getString("name"), "DOG", rs.getString("sex"),
						rs.getString("breed"), rs.getObject("birth_date", LocalDate.class), rs.getString("birth_date_precision"),
						rs.getObject("birth_date_estimated", Boolean.class), rs.getBigDecimal("weight_kg"),
						rs.getObject("neutered", Boolean.class), rs.getString("adoption_status"), rs.getString("avatar_key"),
						labels(rs), rs.getString("introduction"))).optional();
	}

	private static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	private static List<String> labels(ResultSet rs) throws SQLException {
		var array = rs.getArray("trait_labels");
		try {
			return List.copyOf(Arrays.asList((String[]) array.getArray()));
		} finally {
			array.free();
		}
	}
}
