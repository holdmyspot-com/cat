package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.runner.LocalMarketplace;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


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
	 * Verifies that the isolated copy of a plugin that a trial installs keeps the plugin's runtime binding, so the
	 * copy's launchers run the same Java runtime as the plugin's.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void copyKeepsRuntimeBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
			Path runtime = FakeRelease.runtime(cleanup.add(Files.createTempDirectory("cat-runtime-")),
				Platform.current());
			RuntimeBinding.write(artifact, runtime);
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));

			LocalMarketplace.copy(artifact, plugin);

			requireThat(RuntimeBinding.read(plugin), "copiedBinding").isEqualTo(runtime);
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
			Files.createSymbolicLink(artifact.resolve("external"), Path.of("/etc/passwd").toAbsolutePath());

			LocalMarketplace.copy(artifact, cleanup.add(Files.createTempDirectory("cat-plugin-")));
		}
	}
}
