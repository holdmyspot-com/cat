package com.holdmyspot.cat.common.installation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessInstallation}.
 */
public final class HarnessInstallationTest
{
	/**
	 * The home variable of the generic harness that the tests use.
	 */
	private static final String HOME_VARIABLE = "HARNESS_A_HOME";

	/**
	 * Without its home variable, or with an empty one, a harness's configuration directory is its default directory in
	 * the user's home directory.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void unsetOrEmptyVariableSelectsDefault() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path expected = user.resolve(".harness-a");

			requireThat(HarnessInstallation.resolveHome(Map.of(), HOME_VARIABLE, user, Path.of(".harness-a")),
				"unset").isEqualTo(expected);
			requireThat(HarnessInstallation.resolveHome(Map.of(HOME_VARIABLE, ""), HOME_VARIABLE, user,
				Path.of(".harness-a")), "empty").isEqualTo(expected);
		}
	}

	/**
	 * A home variable that names an absolute directory selects it, and another harness's variable does not.
	 *
	 * @throws IOException if the temporary homes cannot be created
	 */
	@Test
	public void variableSelectsItsDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			Path home = cleanup.add(Files.createTempDirectory("cat-harness-a-"));
			Map<String, String> environment = Map.of(HOME_VARIABLE, home.toString(), "HARNESS_B_HOME",
				user.resolve("elsewhere").toString());

			requireThat(HarnessInstallation.resolveHome(environment, HOME_VARIABLE, user, Path.of(".harness-a")),
				"home").isEqualTo(home);
		}
	}

	/**
	 * A home variable that names a relative directory is refused.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "HARNESS_A_HOME must name an absolute directory: \"relative\"")
	public void refusesRelativeVariable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path user = cleanup.add(Files.createTempDirectory("cat-user-"));
			HarnessInstallation.resolveHome(Map.of(HOME_VARIABLE, "relative"), HOME_VARIABLE, user,
				Path.of(".harness-a"));
		}
	}

	/**
	 * A version folder is named after the full plugin version, and the policy, state and release cache are in the data
	 * directory.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void derivedFoldersFollowTheLayout() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-harness-a-"));
			HarnessInstallation installation = new HarnessA(home);

			requireThat(installation.versionFolder(PluginVersion.parse("1.0+b0123456789ab")), "versionFolder").
				isEqualTo(home.resolve("versions/1.0+b0123456789ab"));
			requireThat(installation.policy(), "policy").isEqualTo(home.resolve("data/policy.json"));
			requireThat(installation.stateDirectory(), "stateDirectory").isEqualTo(home.resolve("data/state"));
			requireThat(installation.releases(), "releases").isEqualTo(home.resolve("data/releases"));
		}
	}

	/**
	 * A generic harness's layout.
	 *
	 * @param home the configuration directory
	 */
	private record HarnessA(Path home) implements HarnessInstallation
	{
		@Override
		public Path marketplaceRoot()
		{
			return home.resolve("marketplace");
		}

		@Override
		public Path marketplacePlugin()
		{
			return marketplaceRoot().resolve("plugin");
		}

		@Override
		public Path cacheRoot()
		{
			return home.resolve("cache");
		}

		@Override
		public Path versionFolders()
		{
			return home.resolve("versions");
		}

		@Override
		public Path dataDirectory()
		{
			return home.resolve("data");
		}
	}
}
