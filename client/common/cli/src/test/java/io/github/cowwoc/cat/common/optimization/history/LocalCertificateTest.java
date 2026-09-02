package io.github.cowwoc.cat.common.optimization.history;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimization.ReproductionState;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests locally signed certificate integrity, reproduction status, and user-facing projection.
 */
public final class LocalCertificateTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public LocalCertificateTest()
	{
	}

	/**
	 * Verifies a signed result remains locally verifiable and reports the documented plain-language fields.
	 *
	 * @throws Exception if the JDK cannot create the test key
	 */
	@Test
	public void verifiesAndProjectsCertificate() throws Exception
	{
		KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		LocalCertificate certificate = LocalCertificate.sign(payload(), keyPair.getPrivate(), "local-key-v1");

		LocalCertificate.Verification verification = certificate.verify(keyPair.getPublic(), Set.of(SHA_A, SHA_B));
		String display = certificate.display(verification);

		requireThat(verification.integrity(), "integrity").isEqualTo(LocalCertificate.Integrity.VALID);
		requireThat(verification.reproduction(), "reproduction").isEqualTo(ReproductionState.AVAILABLE);
		requireThat(display, "display").contains("Current lower bound: 92.8%").
			contains("Target: above 95%").contains("Status: not certified").
			contains("not independent CAT attestation");
	}

	/**
	 * Verifies deleted local evidence preserves signed history but marks reproduction unavailable.
	 *
	 * @throws Exception if the JDK cannot create the test key
	 */
	@Test
	public void preservesHistoryWithoutEvidence() throws Exception
	{
		KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		LocalCertificate certificate = LocalCertificate.sign(payload(), keyPair.getPrivate(), "local-key-v1");

		LocalCertificate.Verification verification = certificate.verify(keyPair.getPublic(), Set.of(SHA_A));

		requireThat(verification.integrity(), "integrity").isEqualTo(LocalCertificate.Integrity.VALID);
		requireThat(verification.reproduction(), "reproduction").isEqualTo(
			ReproductionState.UNAVAILABLE);
		requireThat(verification.missingEvidenceFingerprints(), "missingEvidence").containsExactly(List.of(SHA_B));
	}

	/**
	 * Verifies a changed payload cannot pass signature verification.
	 *
	 * @throws Exception if the JDK cannot create the test key
	 */
	@Test
	public void rejectsTamperedPayload() throws Exception
	{
		KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		LocalCertificate certificate = LocalCertificate.sign(payload(), keyPair.getPrivate(), "local-key-v1");
		LocalCertificate tampered = certificate.withPayload(payloadWithBound(0.99));

		LocalCertificate.Verification verification = tampered.verify(keyPair.getPublic(), Set.of(SHA_A, SHA_B));

		requireThat(verification.integrity(), "integrity").isEqualTo(LocalCertificate.Integrity.INVALID);
	}

	/**
	 * Creates a representative certificate payload.
	 *
	 * @return the payload
	 */
	private static LocalCertificate.Payload payload()
	{
		return payloadWithBound(0.928);
	}

	/**
	 * Creates a representative certificate payload with the selected bound.
	 *
	 * @param bound the current lower bound
	 * @return the payload
	 */
	private static LocalCertificate.Payload payloadWithBound(double bound)
	{
		return new LocalCertificate.Payload("certificate-v1", LocalCertificate.TerminalResult.NOT_CERTIFIED,
			"candidate-a", SHA_A, OptionalDouble.of(bound), 0.95, SHA_A, SHA_A, SHA_A, List.of(SHA_A), "family-sequential-v1",
			List.of(SHA_A, SHA_B));
	}
}
