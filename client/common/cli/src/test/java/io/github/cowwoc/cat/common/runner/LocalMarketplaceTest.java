package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link LocalMarketplace}.
 */
public final class LocalMarketplaceTest
{
	/**
	 * Creates the test fixture.
	 */
	public LocalMarketplaceTest()
	{
	}

	/**
	 * Verifies jlink's relative license links are retained in the isolated artifact copy.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsInternalRelativeFileLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
		Path licenses = Files.createDirectories(artifact.resolve("legal/base"));
		Files.writeString(licenses.resolve("LICENSE"), "license");
		Path xml = Files.createDirectories(artifact.resolve("legal/xml"));
		Files.createSymbolicLink(xml.resolve("LICENSE"), Path.of("../base/LICENSE"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));

			LocalMarketplace.copy(artifact, plugin);

			Path copiedLink = plugin.resolve("legal/xml/LICENSE");
			requireThat(Files.isSymbolicLink(copiedLink), "copiedLink").isTrue();
			requireThat(Files.readString(copiedLink), "license").isEqualTo("license");
		}
	}

	/**
	 * Verifies artifact links cannot resolve outside the supplied complete artifact.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsExternalFileLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
			Files.createSymbolicLink(artifact.resolve("external"), Path.of("/etc/passwd"));

			LocalMarketplace.copy(artifact, cleanup.add(Files.createTempDirectory("cat-plugin-")));
		}
	}
}
