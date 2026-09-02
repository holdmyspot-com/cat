package io.github.cowwoc.cat.common.optimization.history;

import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies and displays one installation-local certificate-history document.
 */
public final class CertificateHistoryCommand
{
	/**
	 * Runs the local certificate-history verification command.
	 *
	 * @param args the key directory, certificate document, and currently available evidence fingerprints
	 * @throws IOException if an input is malformed, belongs to another installation, or cannot be verified
	 */
	public static void main(String[] args) throws IOException
	{
		Objects.requireNonNull(args, "args must not be null");
		if (args.length < 2)
		{
			throw new IllegalArgumentException(
				"Usage: certificate-history <key-directory> <certificate.json> [available-evidence-sha256 ...]");
		}
		Set<String> evidence = new TreeSet<>(Arrays.asList(args).subList(2, args.length));
		try (ToolScope scope = new ToolScope())
		{
			verify(scope.json(), Path.of(args[0]), Path.of(args[1]), evidence, System.out);
		}
		catch (GeneralSecurityException e)
		{
			throw new IOException("Cannot verify the local Ed25519 certificate", e);
		}
	}

	/**
	 * Verifies and renders one local historical result.
	 *
	 * @param json the JSON codec
	 * @param keyDirectory the installation-key directory
	 * @param certificatePath the certificate document
	 * @param availableEvidenceFingerprints currently retained evidence fingerprints
	 * @param report the plain-language report destination
	 * @return the current verification state
	 * @throws IOException if a key, certificate, or report operation fails
	 * @throws GeneralSecurityException if Ed25519 verification is unavailable
	 */
	public static LocalCertificate.Verification verify(JsonMapper json, Path keyDirectory, Path certificatePath,
		Set<String> availableEvidenceFingerprints, Appendable report) throws IOException, GeneralSecurityException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(keyDirectory, "keyDirectory must not be null");
		Objects.requireNonNull(certificatePath, "certificatePath must not be null");
		Objects.requireNonNull(availableEvidenceFingerprints, "availableEvidenceFingerprints must not be null");
		Objects.requireNonNull(report, "report must not be null");
		LocalCertificateStore store = LocalCertificateStore.open(keyDirectory);
		LocalCertificateStore.VerifiedCertificate verified = store.readAndVerify(json, certificatePath,
			availableEvidenceFingerprints);
		report.append(verified.certificate().display(verified.verification())).append('\n');
		for (String missing : verified.verification().missingEvidenceFingerprints())
			report.append("Missing evidence: ").append(missing).append('\n');
		return verified.verification();
	}

	/**
	 * Prevents construction of this static command owner.
	 */
	private CertificateHistoryCommand()
	{
	}
}
