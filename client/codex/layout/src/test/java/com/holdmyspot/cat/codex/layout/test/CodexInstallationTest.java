package com.holdmyspot.cat.codex.layout.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexInstallation}.
 */
public final class CodexInstallationTest
{
	/**
	 * The plugin version that the tests locate.
	 */
	private static final PluginVersion VERSION = PluginVersion.parse("1.0+b0123456789ab");

	/**
	 * Without {@code CODEX_HOME}, the installation is in {@code .codex} in the user's home directory, and
	 * Codex runs each version from a folder in its plugin cache.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void defaultHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			CodexInstallation installation = CodexInstallation.fromEnvironment(Map.of(), user);

			Path home = user.resolve(".codex");
			requireThat(installation.home(), "home").isEqualTo(home);
			requireThat(installation.marketplaceRoot(), "marketplaceRoot").
				isEqualTo(home.resolve("plugins/cat-marketplace"));
			requireThat(installation.marketplacePlugin(), "marketplacePlugin").
				isEqualTo(home.resolve("plugins/cat-marketplace/plugins/cat"));
			requireThat(installation.cacheRoot(), "cacheRoot").isEqualTo(home.resolve("plugins/cache"));
			requireThat(installation.versionFolder(VERSION), "versionFolder").
				isEqualTo(home.resolve("plugins/cache/cat/cat/1.0+b0123456789ab"));
			requireThat(installation.dataDirectory(), "dataDirectory").isEqualTo(home.resolve("plugins/data/cat-cat"));
			requireThat(installation.policy(), "policy").isEqualTo(home.resolve("plugins/data/cat-cat/policy.json"));
			requireThat(installation.marketplaceManifest(), "marketplaceManifest").
				isEqualTo(home.resolve("plugins/cat-marketplace/.agents/plugins/marketplace.json"));
			requireThat(installation.agents(), "agents").isEqualTo(home.resolve("agents"));
			requireThat(installation.rules(), "rules").isEqualTo(home.resolve("rules"));
			requireThat(installation.configuration(), "configuration").isEqualTo(home.resolve("config.toml"));
		}
	}

	/**
	 * {@code CODEX_HOME} moves the whole installation, and the other harness's variable does not.
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
			CodexInstallation moved = CodexInstallation.fromEnvironment(Map.of("CODEX_HOME", home.toString()), user);
			CodexInstallation unmoved = CodexInstallation.fromEnvironment(Map.of("CLAUDE_CONFIG_DIR", other.toString()),
				user);

			requireThat(moved.versionFolder(VERSION), "moved").
				isEqualTo(home.resolve("plugins/cache/cat/cat/1.0+b0123456789ab"));
			requireThat(moved.marketplaceManifest(), "movedManifest").
				isEqualTo(home.resolve("plugins/cat-marketplace/.agents/plugins/marketplace.json"));
			requireThat(moved.agents(), "movedAgents").isEqualTo(home.resolve("agents"));
			requireThat(moved.rules(), "movedRules").isEqualTo(home.resolve("rules"));
			requireThat(moved.configuration(), "movedConfiguration").isEqualTo(home.resolve("config.toml"));
			requireThat(unmoved.home(), "unmoved").isEqualTo(user.resolve(".codex"));
		}
	}

	/**
	 * {@code CODEX_HOME} naming a relative directory is refused.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "CODEX_HOME must name an absolute directory: \"relative\"")
	public void refusesRelativeHomeVariable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			CodexInstallation.fromEnvironment(Map.of("CODEX_HOME", "relative"), user);
		}
	}
}
