package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
	 * Verifies that the isolated copy of a plugin that a trial installs leaves out the plugin's startup cache, which
	 * Java accepts only at the location where it was written.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void copyLeavesOutStartupCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
			Files.createDirectories(artifact.resolve("bin/aot"));
			Files.writeString(artifact.resolve("bin/aot/session-start.aot"), "cache");
			Files.writeString(artifact.resolve("bin/VERSION"), "1.0\n");
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));

			LocalMarketplace.copy(artifact, plugin);

			requireThat(Files.exists(plugin.resolve("bin/aot"), LinkOption.NOFOLLOW_LINKS), "copiedCache").isFalse();
			requireThat(Files.readString(plugin.resolve("bin/VERSION")), "copiedVersion").isEqualTo("1.0\n");
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
