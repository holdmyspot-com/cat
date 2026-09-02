package io.github.cowwoc.cat.common.optimization.history;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests strict export and import of locally signed certificate history.
 */
public final class LocalCertificateDocumentTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public LocalCertificateDocumentTest()
	{
	}

	/**
	 * Closes the JSON scope after tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies an exported certificate imports with the same payload, signature, and local verification result.
	 *
	 * @throws Exception if fixture cryptography or I/O fails
	 */
	@Test
	public void roundTripsSignedCertificate() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("local-certificate-"));
			KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
			LocalCertificate certificate = LocalCertificate.sign(payload(), keyPair.getPrivate(), "local-key-v1");
			Path document = root.resolve("certificate.json");

			LocalCertificateDocument.write(scope.json(), document, certificate);
			LocalCertificate imported = LocalCertificateDocument.read(scope.json(), document);

			requireThat(imported.payload(), "payload").isEqualTo(certificate.payload());
			requireThat(imported.keyIdentifier(), "keyIdentifier").isEqualTo(certificate.keyIdentifier());
			requireThat(imported.signature(), "signature").containsExactly(certificate.signature());
			requireThat(imported.verify(keyPair.getPublic(), Set.of(SHA_A, SHA_B)).integrity(), "integrity").
				isEqualTo(LocalCertificate.Integrity.VALID);
		}
	}

	/**
	 * Verifies an unknown root field is rejected rather than entering signed-history interpretation.
	 *
	 * @throws Exception if fixture cryptography or I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*certificate has unsupported or missing fields.*")
	public void rejectsUnknownField() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("local-certificate-"));
			KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
			Path document = root.resolve("certificate.json");
			LocalCertificateDocument.write(scope.json(), document,
				LocalCertificate.sign(payload(), keyPair.getPrivate(), "local-key-v1"));
			String changed = Files.readString(document).replaceFirst("\\{", "{\n  \"attested_by_cat\": true,");
			Files.writeString(document, changed);

			LocalCertificateDocument.read(scope.json(), document);
		}
	}

	/**
	 * Creates a representative certificate payload.
	 *
	 * @return the payload
	 */
	private static LocalCertificate.Payload payload()
	{
		return new LocalCertificate.Payload("certificate-v1", LocalCertificate.TerminalResult.NOT_CERTIFIED,
			"candidate-a", SHA_A, OptionalDouble.of(0.928), 0.95, SHA_A, SHA_A, SHA_A, List.of(SHA_A), "family-sequential-v1",
			List.of(SHA_A, SHA_B));
	}
}
