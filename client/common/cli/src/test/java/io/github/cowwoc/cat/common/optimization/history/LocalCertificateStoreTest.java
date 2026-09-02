package io.github.cowwoc.cat.common.optimization.history;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimization.ReproductionState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests persistent installation keys and local certificate-history verification.
 */
public final class LocalCertificateStoreTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public LocalCertificateStoreTest()
	{
	}

	/**
	 * Verifies reopening a store preserves its installation identity and verifies retained certificate evidence.
	 *
	 * @throws Exception if fixture setup or cryptographic verification fails
	 */
	@Test
	public void persistsInstallationIdentity() throws Exception
	{
		Path root = Files.createTempDirectory("cat-certificate-store-");
		JsonMapper json = new JsonMapper();
		LocalCertificateStore first = LocalCertificateStore.open(root);
		Path certificatePath = root.resolve("history/candidate-a.json");
		LocalCertificate certificate = first.sign(payload());
		first.write(json, certificatePath, certificate);

		LocalCertificateStore reopened = LocalCertificateStore.open(root);
		LocalCertificateStore.VerifiedCertificate verified = reopened.readAndVerify(json, certificatePath,
			Set.of(SHA_A));

		requireThat(reopened.keyIdentifier(), "keyIdentifier").isEqualTo(first.keyIdentifier());
		requireThat(verified.verification().integrity(), "integrity").isEqualTo(LocalCertificate.Integrity.VALID);
		requireThat(verified.verification().reproduction(), "reproduction").isEqualTo(
			ReproductionState.AVAILABLE);
	}

	/**
	 * Verifies missing local evidence preserves signature integrity while making reproduction unavailable.
	 *
	 * @throws Exception if fixture setup or cryptographic verification fails
	 */
	@Test
	public void reportsMissingEvidence() throws Exception
	{
		Path root = Files.createTempDirectory("cat-certificate-store-");
		JsonMapper json = new JsonMapper();
		LocalCertificateStore store = LocalCertificateStore.open(root);
		Path certificatePath = root.resolve("history/candidate-a.json");
		store.write(json, certificatePath, store.sign(payload()));

		LocalCertificateStore.VerifiedCertificate verified = store.readAndVerify(json, certificatePath, Set.of());

		requireThat(verified.verification().integrity(), "integrity").isEqualTo(LocalCertificate.Integrity.VALID);
		requireThat(verified.verification().reproduction(), "reproduction").isEqualTo(
			ReproductionState.UNAVAILABLE);
		requireThat(verified.verification().missingEvidenceFingerprints(), "missingEvidenceFingerprints").
			containsExactly(List.of(SHA_A));
	}

	/**
	 * Verifies a certificate signed by another installation fails local integrity verification.
	 *
	 * @throws Exception if fixture setup or cryptographic verification fails
	 */
	@Test
	public void rejectsAnotherInstallationSignature() throws Exception
	{
		Path root = Files.createTempDirectory("cat-certificate-store-");
		JsonMapper json = new JsonMapper();
		LocalCertificateStore store = LocalCertificateStore.open(root);
		LocalCertificate foreign = LocalCertificate.sign(payload(),
			KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate(), store.keyIdentifier());
		Path certificatePath = root.resolve("history/foreign.json");
		store.write(json, certificatePath, foreign);

		LocalCertificateStore.VerifiedCertificate verified = store.readAndVerify(json, certificatePath,
			Set.of(SHA_A));

		requireThat(verified.verification().integrity(), "integrity").isEqualTo(LocalCertificate.Integrity.INVALID);
	}

	/**
	 * Creates one certificate payload fixture.
	 *
	 * @return the payload
	 */
	private static LocalCertificate.Payload payload()
	{
		return new LocalCertificate.Payload("certificate-v1", LocalCertificate.TerminalResult.CERTIFIED,
			"candidate-a", SHA_A, OptionalDouble.of(0.96), 0.95, SHA_A, SHA_B, SHA_A, List.of(SHA_B), "family-sequential-v1",
			List.of(SHA_A));
	}
}
