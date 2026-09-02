package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests SHA-256 fingerprints for optimization artifacts.
 */
public final class FingerprintsTest
{
	/**
	 * Creates the test fixture.
	 */
	public FingerprintsTest()
	{
	}

	/**
	 * Verifies equivalent UTF-8 text and file content produce the standard SHA-256 fingerprint.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void fingerprintsUtf8TextAndFiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("fingerprints-"));
			Path source = Files.writeString(root.resolve("content.txt"), "abc");

			requireThat(Fingerprints.ofUtf8("abc"), "textFingerprint").isEqualTo(
				"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
			requireThat(Fingerprints.ofFile(source), "fileFingerprint").isEqualTo(
				"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		}
		}

	/**
	 * Verifies a directory fingerprint changes when a retained relative path changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void fingerprintsDirectoryFilesAndPaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("fingerprints-"));
			Path first = Files.createDirectories(root.resolve("first"));
			Path second = Files.createDirectories(root.resolve("second"));
			Files.writeString(first.resolve("rule.md"), "same content");
			Files.writeString(second.resolve("different.md"), "same content");

			requireThat(Fingerprints.ofArtifact(first), "firstFingerprint").isNotEqualTo(
				Fingerprints.ofArtifact(second));
		}
	}
}
