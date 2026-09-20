package org.shelterconnect.api.auth;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Real signatures, ephemeral keys, and a local mock of the public JWKS endpoint. */
public final class JwtTestSupport {
	public final SupabaseProperties properties = new SupabaseProperties("https://auth.example.invalid");
	public final ECKey key;
	public final JwtDecoder decoder;

	public JwtTestSupport() {
		try {
			key = new ECKeyGenerator(Curve.P_256).keyID("ephemeral-test-key").generate();
			var http = new RestTemplate();
			MockRestServiceServer.bindTo(http).build()
					.expect(manyTimes(), requestTo(properties.jwksUrl()))
					.andRespond(withSuccess(new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
			decoder = SupabaseJwtConfiguration.decoder(properties, http);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	public JWTClaimsSet.Builder claims(UUID subject) {
		return new JWTClaimsSet.Builder().issuer(properties.issuer()).subject(subject.toString())
				.audience("authenticated").issueTime(Date.from(Instant.now().minusSeconds(5)))
				.expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.claim("role", "authenticated").claim("is_anonymous", false);
	}

	public String token(UUID subject) { return token(subject, builder -> {}); }

	public String token(UUID subject, Consumer<JWTClaimsSet.Builder> changes) {
		var builder = claims(subject);
		changes.accept(builder);
		return sign(builder.build(), key, key.getKeyID());
	}

	public String sign(JWTClaimsSet claims, ECKey signer, String kid) {
		try {
			var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(), claims);
			jwt.sign(new ECDSASigner(signer));
			return jwt.serialize();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
