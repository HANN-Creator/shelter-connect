package org.shelterconnect.api.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record SupabaseProperties(String supabaseUrl) {
	public SupabaseProperties {
		if (supabaseUrl == null || supabaseUrl.isBlank()) {
			throw new IllegalArgumentException("SUPABASE_URL is required");
		}
		URI uri = URI.create(supabaseUrl);
		if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
				|| uri.getQuery() != null || uri.getFragment() != null
				|| !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
			throw new IllegalArgumentException("SUPABASE_URL must be an HTTPS origin without credentials, query or path");
		}
		supabaseUrl = supabaseUrl.replaceFirst("/$", "");
	}

	public String issuer() { return supabaseUrl + "/auth/v1"; }
	public String jwksUrl() { return issuer() + "/.well-known/jwks.json"; }

	// Keep subjects from different issuers separate, within the existing varchar(40) provider column.
	public String providerKey() {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(issuer().getBytes(StandardCharsets.UTF_8));
			return "sb:" + HexFormat.of().formatHex(hash, 0, 16);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
