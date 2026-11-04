package com.holdmyspot.cat.claude.layout.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeInstallation}.
 */
public final class ClaudeInstallationTest
{
	/**
	 * The plugin version that the tests locate.
	 */
	private static final PluginVersion VERSION = PluginVersion.parse("1.0+b0123456789ab");

	/**
	 * Without {@code CLAUDE_CONFIG_DIR}, the installation is in {@code .claude} in the user's home directory, and
	 * Claude Code runs each version from a folder inside the marketplace plugin.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void defaultHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			ClaudeInstallation installation = ClaudeInstallation.fromEnvironment(Map.of(), user);

			Path home = user.resolve(".claude");
			requireThat(installation.home(), "home").isEqualTo(home);
			requireThat(installation.marketplaceRoot(), "marketplaceRoot").
				isEqualTo(home.resolve("plugins/cat-marketplace"));
			requireThat(installation.marketplacePlugin(), "marketplacePlugin").
				isEqualTo(home.resolve("plugins/cat-marketplace/plugins/cat"));
			requireThat(installation.cacheRoot(), "cacheRoot").isEqualTo(home.resolve("plugins/cache"));
			requireThat(installation.versionFolder(VERSION), "versionFolder").
				isEqualTo(home.resolve("plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab"));
			requireThat(installation.dataDirectory(), "dataDirectory").isEqualTo(home.resolve("plugins/data/cat-cat"));
			requireThat(installation.policy(), "policy").isEqualTo(home.resolve("plugins/data/cat-cat/policy.json"));
		}
	}

	/**
	 * {@code CLAUDE_CONFIG_DIR} moves the whole installation, and the other harness's variable does not.
	 *
	 * @throws IOException if the temporary homes cannot be created
	 */
	@Test
	public void homeVariableMovesInstallation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			Path other = cleanup.add(Files.createTempDirectory("cat-other-"));
			ClaudeInstallation moved = ClaudeInstallation.fromEnvironment(Map.of("CLAUDE_CONFIG_DIR", home.toString()), user);
			ClaudeInstallation unmoved = ClaudeInstallation.fromEnvironment(Map.of("CODEX_HOME", other.toString()),
				user);

			requireThat(moved.versionFolder(VERSION), "moved").
				isEqualTo(home.resolve("plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab"));
			requireThat(unmoved.home(), "unmoved").isEqualTo(user.resolve(".claude"));
		}
	}

	/**
	 * {@code CLAUDE_CONFIG_DIR} naming a relative directory is refused.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "CLAUDE_CONFIG_DIR must name an absolute directory: \"relative\"")
	public void refusesRelativeHomeVariable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			ClaudeInstallation.fromEnvironment(Map.of("CLAUDE_CONFIG_DIR", "relative"), user);
		}
	}
}
