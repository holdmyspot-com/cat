package com.holdmyspot.cat.common.optimizer.port.contract.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.testng.annotations.Test;

/**
 * Tests {@link VersionRegistry}.
 */
public final class VersionRegistryTest
{
	private static final String FIRST = VersionRegistry.hash("first");
	private static final String SECOND = VersionRegistry.hash("second");

	/**
	 * Creates the test.
	 */
	public VersionRegistryTest()
	{
	}

	/**
	 * Verifies that a version registered with the current hash passes, among comments and other versions.
	 *
	 * @throws IOException if the registry cannot be written
	 */
	@Test
	public void registeredHashPasses() throws IOException
	{
		check("# Versions\n1 " + FIRST + "\n\n2 " + SECOND + "\n", 2, SECOND);
	}

	/**
	 * Verifies that a hash that the declared version does not register fails, even if another version registers it.
	 *
	 * @throws IOException if the registry cannot be written
	 */
	@Test(expectedExceptions = AssertionError.class,
		expectedExceptionsMessageRegExp = "(?s).*does not register under the declared test version 2\\..*")
	public void hashOfAnotherVersionFails() throws IOException
	{
		check("1 " + FIRST + "\n2 " + SECOND + "\n", 2, FIRST);
	}

	/**
	 * Verifies that a declared version that the registry lacks fails.
	 *
	 * @throws IOException if the registry cannot be written
	 */
	@Test(expectedExceptions = AssertionError.class,
		expectedExceptionsMessageRegExp = "(?s).*does not register under the declared test version 3\\..*")
	public void unregisteredVersionFails() throws IOException
	{
		check("1 " + FIRST + "\n", 3, FIRST);
	}

	/**
	 * Verifies that a version registered with two hashes fails, even when the declared version and hash match.
	 *
	 * @throws IOException if the registry cannot be written
	 */
	@Test(expectedExceptions = AssertionError.class,
		expectedExceptionsMessageRegExp = "(?s).*registers test version 1 with two hashes.*")
	public void versionWithTwoHashesFails() throws IOException
	{
		check("1 " + FIRST + "\n1 " + SECOND + "\n", 1, FIRST);
	}

	/**
	 * Verifies that a malformed line fails.
	 *
	 * @throws IOException if the registry cannot be written
	 */
	@Test(expectedExceptions = AssertionError.class, expectedExceptionsMessageRegExp = "(?s).*line 1: expected.*")
	public void malformedLineFails() throws IOException
	{
		check("1 " + FIRST.toUpperCase(Locale.ROOT) + "\n", 1, FIRST);
	}

	/**
	 * Writes a registry and checks a declared version and hash against it.
	 *
	 * @param registry the registry's content
	 * @param version the declared version
	 * @param hash the current hash
	 * @throws IOException if the registry cannot be written
	 */
	private static void check(String registry, int version, String hash) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("version-registry"));
			Path file = Files.writeString(directory.resolve("versions.txt"), registry, UTF_8);
			VersionRegistry.requireRegistered(file, "test", version, hash);
		}
	}
}
