package com.holdmyspot.cat.codex.layout.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
			home = home.toRealPath();
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
	 * An unset or empty {@code CODEX_HOME} leaves Codex's home in {@code .codex} in the user's home directory, whether
	 * or not that folder exists.
	 *
	 * @throws IOException if the temporary directory cannot be created
	 */
	@Test
	public void unsetOrEmptyVariableUsesDefaultHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));

			requireThat(CodexInstallation.resolveHome(Map.of(), user, working), "unset").
				isEqualTo(user.resolve(".codex"));
			requireThat(CodexInstallation.resolveHome(Map.of("CODEX_HOME", ""), user, working), "empty").
				isEqualTo(user.resolve(".codex"));
		}
	}

	/**
	 * A user home that the environment names wins over the account's home directory, which Codex reads only when the
	 * environment names none or names an empty one.
	 *
	 * @throws IOException if the temporary directories cannot be created
	 */
	@Test
	public void environmentNamesUserHomeBeforeAccount() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path named = cleanup.add(Files.createTempDirectory("cat-named-"));
			Path account = cleanup.add(Files.createTempDirectory("cat-account-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			String variable = Platform.current().homeVariable();

			requireThat(CodexInstallation.resolveHome(Map.of(variable, named.toString()), account, working),
				"named").isEqualTo(named.resolve(".codex"));
			requireThat(CodexInstallation.resolveHome(Map.of(variable, ""), account, working), "empty").
				isEqualTo(account.resolve(".codex"));
			requireThat(CodexInstallation.resolveHome(Map.of(), account, working), "unset").
				isEqualTo(account.resolve(".codex"));
		}
	}

	/**
	 * A relative user home is made absolute against the working directory, and its {@code ..} elements are removed,
	 * as Codex does.
	 *
	 * @throws IOException if the temporary directory cannot be created
	 */
	@Test
	public void relativeUserHomeUsesWorkingDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			String variable = Platform.current().homeVariable();

			requireThat(CodexInstallation.resolveHome(Map.of(variable, "a/../b"), null, working), "home").
				isEqualTo(working.resolve("b/.codex"));
		}
	}

	/**
	 * Without a user home in the environment or an account home directory, Codex finds no home, and the refusal names
	 * both places that it looked.
	 *
	 * @throws IOException if the temporary directory cannot be created
	 */
	@Test
	public void refusesWhenNoUserHomeExists() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			String variable = Platform.current().homeVariable();

			try
			{
				CodexInstallation.resolveHome(Map.of(variable, ""), null, working);
				throw new AssertionError("A missing user home was accepted");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(variable).contains("CODEX_HOME");
			}
		}
	}

	/**
	 * A {@code CODEX_HOME} that is blank but not empty names a folder, as Codex reads it, so a blank value neither
	 * falls back to the default home nor names an existing folder until a folder of that name exists. On Windows, which
	 * refuses a file name that ends in a space, a blank value is refused as Codex refuses it there: as a value that
	 * cannot be read, not as a folder that does not exist, so that the installer does not try to create it.
	 *
	 * @throws IOException if the temporary directories cannot be created
	 */
	@Test
	public void blankVariableIsHandledAsThePlatformDoes() throws IOException
	{
		if (Platform.current().isWindows())
			windowsRefusesBlankVariable();
		else
			blankVariableNamesAFolder();
	}

	/**
	 * Verifies that a blank {@code CODEX_HOME} names a folder on a platform that allows a file name that ends in a
	 * space.
	 *
	 * @throws IOException if the temporary directories cannot be created
	 */
	private static void blankVariableNamesAFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));

			try
			{
				CodexInstallation.resolveHome(Map.of("CODEX_HOME", "  "), user, working);
				throw new AssertionError("A blank CODEX_HOME was accepted");
			}
			catch (NoSuchFileException e)
			{
				requireThat(e.getMessage(), "message").contains("CODEX_HOME");
			}
			Path blank = Files.createDirectory(working.resolve("  "));
			Map<String, String> environment = Map.of("CODEX_HOME", "  ");
			requireThat(CodexInstallation.resolveHome(environment, user, working), "home").
				isEqualTo(blank.toRealPath());
		}
	}

	/**
	 * Verifies that a blank {@code CODEX_HOME} is refused on Windows as a value that cannot be read.
	 *
	 * @throws IOException if the temporary directories cannot be created
	 */
	private static void windowsRefusesBlankVariable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			Map<String, String> blank = Map.of("CODEX_HOME", "  ");

			try
			{
				CodexInstallation.resolveHome(blank, user, working);
				throw new AssertionError("A blank CODEX_HOME was accepted");
			}
			catch (NoSuchFileException e)
			{
				throw new AssertionError("A blank CODEX_HOME was read as a missing folder", e);
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("CODEX_HOME");
			}
			try
			{
				CodexInstallation.fromEnvironment(blank, user);
				throw new AssertionError("A blank CODEX_HOME located an installation");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("CODEX_HOME");
			}
		}
	}

	/**
	 * A relative {@code CODEX_HOME} names a folder of the working directory, and the home is that folder's absolute
	 * path.
	 *
	 * @throws IOException if the temporary directories cannot be created
	 */
	@Test
	public void relativeVariableUsesWorkingDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			Path home = Files.createDirectories(working.resolve("codex"));

			requireThat(CodexInstallation.resolveHome(Map.of("CODEX_HOME", "codex"), user, working), "relative").
				isEqualTo(home.toRealPath());
			requireThat(CodexInstallation.resolveHome(Map.of("CODEX_HOME", "codex/../codex"), user, working),
				"withParent").isEqualTo(home.toRealPath());
		}
	}

	/**
	 * A {@code CODEX_HOME} that names no folder is refused, and the refusal names the variable and the folder.
	 *
	 * @throws IOException if the temporary directories cannot be created
	 */
	@Test
	public void refusesMissingVariableFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			Path missing = working.resolve("missing");

			try
			{
				CodexInstallation.resolveHome(Map.of("CODEX_HOME", missing.toString()), user, working);
				throw new AssertionError("A missing CODEX_HOME was accepted");
			}
			catch (NoSuchFileException e)
			{
				requireThat(e.getFile(), "file").isEqualTo(missing.toString());
				requireThat(e.getMessage(), "message").contains("CODEX_HOME").contains(missing.toString());
			}
		}
	}

	/**
	 * A {@code CODEX_HOME} that names a file is refused.
	 *
	 * @throws IOException if the temporary directories cannot be created
	 */
	@Test
	public void refusesVariableNamingAFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			Path file = Files.createFile(working.resolve("file"));

			try
			{
				CodexInstallation.resolveHome(Map.of("CODEX_HOME", file.toString()), user, working);
				throw new AssertionError("A CODEX_HOME naming a file was accepted");
			}
			catch (IOException e)
			{
				requireThat(e, "exception").isNotInstanceOf(NoSuchFileException.class);
				requireThat(e.getMessage(), "message").contains("CODEX_HOME").contains("is not a directory");
			}
		}
	}

	/**
	 * A {@code CODEX_HOME} that is a symbolic link names the folder that it links to, as Codex canonicalizes it.
	 *
	 * @throws IOException if the temporary directories or the link cannot be created
	 */
	@Test
	public void variableLinkIsFollowed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			Path target = Files.createDirectory(working.resolve("target"));
			Path link = Files.createSymbolicLink(working.resolve("link"), target);

			requireThat(CodexInstallation.resolveHome(Map.of("CODEX_HOME", link.toString()), user, working), "home").
				isEqualTo(target.toRealPath());
		}
	}

	/**
	 * The installer may use a {@code CODEX_HOME} whose folder does not exist yet, because it creates the folder, but
	 * not one that names a file.
	 *
	 * @throws IOException if the temporary directories cannot be created
	 */
	@Test
	public void installationMayUseMissingVariableFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path working = cleanup.add(Files.createTempDirectory("cat-working-"));
			Path missing = working.resolve("missing");
			Path file = Files.createFile(working.resolve("file"));

			requireThat(CodexInstallation.fromEnvironment(Map.of("CODEX_HOME", missing.toString()), user).home(),
				"missing").isEqualTo(missing);
			try
			{
				CodexInstallation.fromEnvironment(Map.of("CODEX_HOME", file.toString()), user);
				throw new AssertionError("A CODEX_HOME naming a file was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("CODEX_HOME").contains("is not a directory");
			}
		}
	}

	/**
	 * The default home and the configuration file in it are named without a user home, and agree with an
	 * installation located in a user home.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void defaultNamesAgreeWithInstallation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			CodexInstallation installation = CodexInstallation.fromEnvironment(Map.of(), user);

			requireThat(CodexInstallation.defaultHome(), "defaultHome").isEqualTo(Path.of(".codex"));
			requireThat(CodexInstallation.defaultConfiguration(), "defaultConfiguration").
				isEqualTo(Path.of(".codex/config.toml"));
			requireThat(user.resolve(CodexInstallation.defaultHome()), "home").isEqualTo(installation.home());
			requireThat(user.resolve(CodexInstallation.defaultConfiguration()), "configuration").
				isEqualTo(installation.configuration());
		}
	}
}
