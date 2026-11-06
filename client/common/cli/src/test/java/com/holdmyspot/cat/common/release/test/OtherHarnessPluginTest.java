package com.holdmyspot.cat.common.release.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.release.OtherHarnessPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests the command that {@link OtherHarnessPlugin} names when the other harness has no CAT plugin of this plugin's
 * release: this plugin's installer when the other harness has no CAT plugin, and the other installation's own
 * installer when it has another release. The command must run as written, in a POSIX shell or in PowerShell, wherever
 * the plugin is.
 */
public final class OtherHarnessPluginTest
{
	/**
	 * The full plugin version of the plugin that holds the running code.
	 */
	private static final String VERSION = "1.0+b0123456789ab";

	/**
	 * Creates a test instance.
	 */
	public OtherHarnessPluginTest()
	{
	}

	/**
	 * Verifies that a command whose installer path holds no character that a POSIX shell treats specially is named
	 * without quotes.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesPlainPosixCommandUnquoted() throws IOException
	{
		// Requirement: R-I6
		if (Platform.current().isWindows())
			throw new SkipException("A POSIX shell treats the backslashes of a Windows path specially");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = ownPlugin(cleanup.add(Files.createTempDirectory("other-harness-plain-")), "plugin");

			String message = refusal(plugin, Platform.LINUX_X64);

			requireThat(message, "message").endsWith(": " + plugin.resolve("bin/installer") +
				" install --harness harness-b");
		}
	}

	/**
	 * Verifies that a command whose installer path holds spaces and an apostrophe is quoted for a POSIX shell.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void quotesPosixCommandWithSpaces() throws IOException
	{
		// Requirement: R-I6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = ownPlugin(cleanup.add(Files.createTempDirectory("other-harness-posix-")), "Gili's plugins");

			String message = refusal(plugin, Platform.LINUX_X64);

			String quoted = "'" + plugin.resolve("bin/installer").toString().replace("'", "'\\''") + "'";
			requireThat(message, "message").endsWith(": " + quoted + " install --harness harness-b");
		}
	}

	/**
	 * Verifies that on Windows a command whose installer path holds spaces and an apostrophe is quoted for PowerShell
	 * and run through its call operator.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void quotesPowerShellCommandWithSpaces() throws IOException
	{
		// Requirement: R-I6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = ownPlugin(cleanup.add(Files.createTempDirectory("other-harness-windows-")), "Gili's plugins");

			String message = refusal(plugin, Platform.WINDOWS_X64);

			String quoted = "& '" + plugin.resolve("bin/installer.ps1").toString().replace("'", "''") + "'";
			requireThat(message, "message").endsWith(": " + quoted + " install --harness harness-b");
		}
	}

	/**
	 * Verifies that when the other harness has a CAT plugin of another release, the command is that installation's own
	 * installer for the platform that runs the test, which updates it to this plugin's release.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void namesOtherInstallerForOtherRelease() throws IOException
	{
		// Requirement: R-I6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = ownPlugin(cleanup.add(Files.createTempDirectory("other-harness-release-")), "plugin");
			HarnessB other = new HarnessB(plugin.getParent().resolve("harness-b"));
			String installed = "0.9+b0123456789ab";
			Path folder = Files.createDirectories(other.versionFolders().resolve(installed));

			Platform platform = Platform.current();

			String message = refusal(plugin, other, List.of(installed), platform);

			// The "+" of the installed version's folder is not a PowerShell word character
			String installer = folder.resolve("bin/installer").toString();
			if (platform.isWindows())
				installer = "& '" + folder.resolve("bin/installer.ps1").toString().replace("'", "''") + "'";
			requireThat(message, "message").endsWith(": " + installer + " install --harness harness-b v1.0");
		}
	}

	/**
	 * Creates the version folder of the plugin that holds the running code, holding its version file.
	 *
	 * @param root   the test's directory
	 * @param parent the name of the folder that holds the version folder
	 * @return the version folder
	 * @throws IOException if the folder cannot be written
	 */
	private static Path ownPlugin(Path root, String parent) throws IOException
	{
		Path plugin = root.resolve(parent).resolve(VERSION).toAbsolutePath().normalize();
		Files.createDirectories(plugin.resolve("bin"));
		Files.writeString(plugin.resolve("bin/VERSION"), VERSION + "\n");
		return plugin;
	}

	/**
	 * Looks up the other harness's plugin where that harness has none, and returns the refusal's message.
	 *
	 * @param plugin   the plugin that holds the running code
	 * @param platform the platform that the plugin runs on
	 * @return the message
	 * @throws AssertionError if the lookup does not refuse
	 */
	private static String refusal(Path plugin, Platform platform)
	{
		return refusal(plugin, new HarnessB(plugin.getParent().resolve("harness-b")), List.of(), platform);
	}

	/**
	 * Looks up the other harness's plugin where that harness has no plugin of this plugin's release, and returns the
	 * refusal's message.
	 *
	 * @param plugin    the plugin that holds the running code
	 * @param other     the other harness's installation
	 * @param installed the plugin versions that the other harness has installed
	 * @param platform  the platform that the plugin runs on
	 * @return the message
	 * @throws AssertionError if the lookup does not refuse
	 */
	private static String refusal(Path plugin, HarnessB other, List<String> installed, Platform platform)
	{
		try
		{
			OtherHarnessPlugin.select(plugin, other, "harness-b", "Harness B", installed,
				Path.of(".harness-b/plugin.json"), "harness-b-runner", platform);
			throw new AssertionError("The lookup found a plugin in a harness that has none");
		}
		catch (IOException e)
		{
			return e.getMessage();
		}
	}

	/**
	 * A generic harness's layout.
	 *
	 * @param home the configuration directory
	 */
	private record HarnessB(Path home) implements HarnessInstallation
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
