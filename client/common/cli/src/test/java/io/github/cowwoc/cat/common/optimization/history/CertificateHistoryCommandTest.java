package io.github.cowwoc.cat.common.optimization.history;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the plain-language local certificate-history projection.
 */
public final class CertificateHistoryCommandTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public CertificateHistoryCommandTest()
	{
	}

	/**
	 * Verifies the command reports the agreed scalar terminology and local trust boundary.
	 *
	 * @throws Exception if fixture persistence or verification fails
	 */
	@Test
	public void displaysVerifiedHistory() throws Exception
	{
		Path root = Files.createTempDirectory("cat-certificate-command-");
		JsonMapper json = new JsonMapper();
		LocalCertificateStore store = LocalCertificateStore.open(root.resolve("keys"));
		LocalCertificate.Payload payload = new LocalCertificate.Payload("certificate-v1",
			LocalCertificate.TerminalResult.CERTIFIED, "candidate-a", SHA_A, OptionalDouble.of(0.96), 0.95, SHA_A,
			SHA_B, SHA_A, List.of(SHA_B), "family-sequential-v1", List.of(SHA_A));
		Path certificate = root.resolve("history/candidate-a.json");
		store.write(json, certificate, store.sign(payload));
		StringBuilder report = new StringBuilder();

		LocalCertificate.Verification verification = CertificateHistoryCommand.verify(json, root.resolve("keys"),
			certificate, Set.of(SHA_A), report);

		requireThat(verification.integrity(), "integrity").isEqualTo(LocalCertificate.Integrity.VALID);
		requireThat(report.toString(), "report").contains("Current lower bound: 96%").
			contains("Target: above 95%").contains("Status: certified").
			contains("not independent CAT attestation");
	}
}
