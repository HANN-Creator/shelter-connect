package org.shelterconnect.api;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.sample.SampleDataLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleDatasetTest {
	@Test
	void datasetCoversFiveDistinctDogsAndUncertainInformation() {
		var data = SampleDataLoader.read(Path.of("sample-data/dataset.json"));
		assertThat(data.shelters()).hasSize(2).allMatch(row -> row.name().contains("가상"));
		assertThat(data.dogs()).hasSize(5).extracting(SampleDataLoader.Dog::name)
				.containsExactlyInAnyOrder("봄이", "두부", "콩이", "밤이", "해리");
		assertThat(data.dogs().stream().filter(row -> row.birthDate() == null)).singleElement()
				.satisfies(row -> {
					assertThat(row.birthDatePrecision()).isEqualTo("UNKNOWN");
					assertThat(row.birthDateEstimated()).isNull();
				});
		assertThat(data.dogs().stream().filter(row -> row.neutered() == null)).hasSize(2);
		assertThat(data.dogs().stream().filter(SampleDataLoader.Dog::isPublic)).hasSize(4);
		assertThat(data.observations()).hasSize(25);
		assertThat(data.observations().stream().filter(row -> row.status().equals("CONFIRMED"))).hasSize(20);
		assertThat(data.observations().stream().map(SampleDataLoader.Observation::status).collect(Collectors.toSet()))
				.isEqualTo(Set.of("CONFIRMED", "DRAFT", "RETRACTED"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"jdbc:postgresql://127.0.0.1:15432/shelter_connect",
			"jdbc:postgresql://localhost:5432/shelter_test",
			"jdbc:postgresql://[::1]/shelter_test"
	})
	void localDevelopmentTargetsAreAllowed(String url) {
		SampleDataLoader.validateTarget(url);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"jdbc:postgresql://db.example.supabase.co:5432/postgres",
			"jdbc:postgresql://127.0.0.1:5432/postgres",
			"jdbc:postgresql://localhost.example.com/shelter_test",
			"jdbc:postgresql://localhost/shelter_test?host=example.com",
			"jdbc:postgresql://localhost/shelter_test?PGHOST=example.com",
			"jdbc:postgresql://localhost,example.com/shelter_test",
			"jdbc:h2:mem:shelter_test", ""
	})
	void remoteOrAmbiguousTargetsAreRejectedBeforeConnecting(String url) {
		assertThatThrownBy(() -> SampleDataLoader.validateTarget(url)).isInstanceOf(IllegalArgumentException.class);
	}
}
