package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.CodexPluginInfo;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexPluginInfo}.
 */
public final class CodexPluginInfoTest
{
	/**
	 * Creates a test instance.
	 */
	public CodexPluginInfoTest()
	{
	}

	/**
	 * Verifies that Codex's {@code plugin-info} reports the full plugin version that the plugin's {@code bin/VERSION}
	 * states, not the bare release.
	 *
	 * @throws IOException if the fixture plugin cannot be created
	 */
	@Test
	public void reportsFullPluginVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = plugin(cleanup, "1.0+b0123456789ab\n");

			requireThat(CodexPluginInfo.info(plugin).toDisplayString(), "info").isEqualTo(
				"harness=codex\nversion=1.0+b0123456789ab\nplatform=" + Platform.current().id());
		}
	}

	/**
	 * Verifies that a plugin whose {@code bin/VERSION} states only the release is refused rather than reported.
	 *
	 * @throws IOException if the fixture plugin cannot be created
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesVersionWithoutBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexPluginInfo.info(plugin(cleanup, "1.0\n"));
		}
	}

	/**
	 * Creates a plugin directory whose {@code bin/VERSION} holds a text.
	 *
	 * @param cleanup removes the plugin directory
	 * @param version the content of {@code bin/VERSION}
	 * @return the plugin directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path plugin(TemporaryDirectories cleanup, String version) throws IOException
	{
		Path plugin = cleanup.add(Files.createTempDirectory("cat-codex-plugin-info-"));
		Files.writeString(Files.createDirectories(plugin.resolve("bin")).resolve("VERSION"), version);
		return plugin;
	}
}
