package io.github.cowwoc.cat.common.commercial;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the strict signed commercial-lease document boundary.
 */
public final class EntitlementLeaseDocumentTest
{
	private static final Instant CONFIRMED = Instant.parse("2026-09-01T00:00:00Z");

	/**
	 * Creates the test fixture.
	 */
	public EntitlementLeaseDocumentTest()
	{
	}

	/**
	 * Verifies a signed lease survives strict JSON round-trip and validates against the installed backend key.
	 *
	 * @throws Exception if cryptographic or fixture I/O fails
	 */
	@Test
	public void roundTripsVerifiedLease() throws Exception
	{
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		EntitlementLease lease = EntitlementLease.sign("lease-v1", "license-1", "device-1", CONFIRMED,
			CONFIRMED.plus(Duration.ofHours(24)), CONFIRMED.plus(Duration.ofDays(30)), keys.getPrivate());
		Path document = Files.createTempFile("cat-lease-", ".json");
		JsonMapper json = new JsonMapper();

		EntitlementLeaseDocument.write(json, document, lease);
		EntitlementLease restored = EntitlementLeaseDocument.read(json, document);

		requireThat(restored.verify(keys.getPublic()), "signatureValid").isTrue();
		requireThat(restored.authorize(CONFIRMED.plus(Duration.ofHours(12)),
			EntitlementLease.RenewalState.NOT_ATTEMPTED, value -> value.verify(keys.getPublic())).status(), "status").
			isEqualTo(EntitlementLease.Status.ALLOWED);
	}

	/**
	 * Verifies a noncanonical timestamp spelling is rejected even when it identifies the same instant.
	 *
	 * @throws Exception if fixture I/O fails
	 */
	@Test(expectedExceptions = java.io.IOException.class,
		expectedExceptionsMessageRegExp = ".*noncanonical confirmed_at.*")
	public void rejectsNoncanonicalTimestamp() throws Exception
	{
		Path document = Files.createTempFile("cat-lease-", ".json");
		Files.writeString(document, """
			{
			  "schema_version":"lease-v1",
			  "license_identifier":"license-1",
			  "device_identifier":"device-1",
			  "confirmed_at":"2026-09-01T00:00:00+00:00",
			  "expires_at":"2026-09-02T00:00:00Z",
			  "entitlement_ends_at":"2026-10-01T00:00:00Z",
			  "signature_base64":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
			}
			""");

		EntitlementLeaseDocument.read(new JsonMapper(), document);
	}
}
