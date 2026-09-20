package org.shelterconnect.api.auth;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.*;

class SupabaseJwtTest {
	private static final UUID SUBJECT = UUID.randomUUID();
	private final JwtTestSupport tokens = new JwtTestSupport();

	@Test
	void verifiesRealEs256SignatureAndSubject() {
		var jwt = tokens.decoder.decode(tokens.token(SUBJECT));
		assertThat(jwt.getSubject()).isEqualTo(SUBJECT.toString());
		assertThat(jwt.getHeaders().get("alg")).isEqualTo("ES256");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidClaims")
	void rejectsInvalidIdentityAndLifetime(String name, Consumer<JWTClaimsSet.Builder> changes) {
		assertThatThrownBy(() -> tokens.decoder.decode(tokens.token(SUBJECT, changes))).isInstanceOf(JwtException.class);
	}

	static Stream<Arguments> invalidClaims() {
		return Stream.of(
				bad("wrong issuer", b -> b.issuer("https://other.example.invalid/auth/v1")),
				bad("missing issuer", b -> b.issuer(null)),
				bad("wrong audience", b -> b.audience("anon")),
				bad("missing audience", b -> b.claim("aud", null)),
				bad("missing subject", b -> b.subject(null)),
				bad("malformed subject", b -> b.subject("1-1-1-1-1")),
				bad("expired", b -> b.issueTime(Date.from(Instant.now().minusSeconds(600)))
						.expirationTime(Date.from(Instant.now().minusSeconds(120)))),
				bad("missing expiry", b -> b.expirationTime(null)),
				bad("missing issued time", b -> b.issueTime(null)),
				bad("future issued time", b -> b.issueTime(Date.from(Instant.now().plusSeconds(120)))),
				bad("not active yet", b -> b.notBeforeTime(Date.from(Instant.now().plusSeconds(120)))),
				bad("service key role", b -> b.claim("role", "service_role")),
				bad("anon key role", b -> b.claim("role", "anon")),
				bad("missing role", b -> b.claim("role", null)),
				bad("malformed role", b -> b.claim("role", Map.of("role", "authenticated"))),
				bad("anonymous user", b -> b.claim("is_anonymous", true)),
				bad("missing anonymous flag", b -> b.claim("is_anonymous", null)),
				bad("string anonymous flag", b -> b.claim("is_anonymous", "false")));
	}

	private static Arguments bad(String name, Consumer<JWTClaimsSet.Builder> changes) { return Arguments.of(name, changes); }

	@Test
	void rejectsWrongSignatureUnknownKeyAndUnsignedToken() throws Exception {
		var otherKey = new ECKeyGenerator(Curve.P_256).generate();
		var claims = tokens.claims(SUBJECT).build();
		for (String token : new String[] {tokens.sign(claims, otherKey, tokens.key.getKeyID()),
				tokens.sign(claims, tokens.key, "unknown-key"), new PlainJWT(claims).serialize(), "not-a-token"}) {
			assertThatThrownBy(() -> tokens.decoder.decode(token)).isInstanceOf(JwtException.class);
		}
	}

	@Test
	void rejectsOtherAlgorithmsEvenWithOtherwiseValidClaims() throws Exception {
		var claims = tokens.claims(SUBJECT).build();
		var hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		hmac.sign(new MACSigner(new byte[32]));
		var rsa = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		rsa.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
		for (String token : new String[] {hmac.serialize(), rsa.serialize()}) {
			assertThatThrownBy(() -> tokens.decoder.decode(token)).isInstanceOf(JwtException.class);
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "http://localhost", "https://user:pass@example.invalid", "https://example.invalid/auth/v1",
			"https://example.invalid?secret=value", "https://example.invalid#fragment", "not-a-url"})
	void configurationRejectsMissingOrUnsafeOrigins(String origin) {
		assertThatThrownBy(() -> new SupabaseProperties(origin)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void providerMappingIsStableAndSeparatesProjects() {
		assertThat(tokens.properties.providerKey()).hasSize(35)
				.isEqualTo(new SupabaseProperties("https://auth.example.invalid/").providerKey())
				.isNotEqualTo(new SupabaseProperties("https://other.example.invalid").providerKey());
	}
}
