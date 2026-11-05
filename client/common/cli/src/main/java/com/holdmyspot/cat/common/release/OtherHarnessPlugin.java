package com.holdmyspot.cat.common.release;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds the CAT plugin that another harness has installed, for a runner launcher in this harness's plugin that runs
 * that harness. The other harness's plugin is used when it is of this plugin's CAT release, whatever its build.
 * Otherwise the lookup stops and names the command that brings CAT in the other harness to this plugin's release: the
 * installer of this plugin, which installs CAT into a harness that has none, or the installer of the other harness's
 * installation, which updates it. The user approves that command before it runs; the lookup itself downloads nothing.
 */
public final class OtherHarnessPlugin
{
	/**
	 * A program path that a POSIX shell runs as written, without quotes.
	 */
	private static final Pattern POSIX_WORD = Pattern.compile("[A-Za-z0-9_./+:=@%,-]+");
	/**
	 * A program path that PowerShell runs as written, without quotes or its call operator.
	 */
	private static final Pattern POWERSHELL_WORD = Pattern.compile("[A-Za-z0-9_.\\\\/:-]+");

	/**
	 * Finds the other harness's CAT plugin and its runner launcher.
	 *
	 * @param ownPlugin   the version folder of the plugin that holds the running code
	 * @param other       the other harness's installation
	 * @param harness     the other harness's identifier in the installer's {@code --harness} option
	 * @param harnessName the other harness's name in messages
	 * @param installed   the plugin versions that the other harness has installed, as it records them, in the order
	 *                    that it records them
	 * @param manifest    the other harness's plugin manifest path, relative to the plugin
	 * @param runner      the runner launcher's name in the other plugin's {@code bin} directory, without the
	 *                    {@code .cmd} extension that it has on Windows
	 * @param platform    the platform that the plugins were built for and run on
	 * @return the other harness's plugin and its runner launcher
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness}, {@code harnessName} or {@code runner} is blank
	 * @throws IOException              if this plugin does not state its version, the other harness has no CAT plugin
	 *                                  of this plugin's release, or that plugin is incomplete
	 */
	public static RunnerArtifact select(Path ownPlugin, HarnessInstallation other, String harness,
		String harnessName, List<String> installed, Path manifest, String runner, Platform platform)
		throws IOException
	{
		requireThat(ownPlugin, "ownPlugin").isNotNull();
		requireThat(other, "other").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(harnessName, "harnessName").isNotBlank();
		requireThat(installed, "installed").isNotNull();
		requireThat(manifest, "manifest").isNotNull();
		requireThat(runner, "runner").isNotBlank();
		requireThat(platform, "platform").isNotNull();
		PluginVersion own = HarnessArtifact.version(ownPlugin);
		if (installed.isEmpty())
		{
			throw new IOException("CAT is not installed in " + harnessName + ". CAT will be installed into " +
				harnessName + " once the user approves this command; ask the user to approve it, run it, then retry: " +
				installCommand(ownPlugin, harness, platform));
		}
		Optional<PluginVersion> chosen = choose(own, installed);
		if (chosen.isEmpty())
		{
			String update = updateCommand(other, installed, harness, own, platform);
			throw new IOException(harnessName + " has CAT " + String.join(", ", installed) + " installed, not CAT " +
				"release " + own.release() + ". CAT in " + harnessName + " will be updated to " + own.release() +
				" once the user approves this command; ask the user to approve it, run it, then retry: " + update);
		}
		Path folder = other.versionFolder(chosen.get());
		Path launcher = HarnessArtifact.runnerLauncher(folder, manifest, runner, chosen.get(), platform);
		return new RunnerArtifact(folder.toAbsolutePath().normalize(), launcher, RunnerArtifact.Source.INSTALLED);
	}

	/**
	 * Chooses the installed version to use: this plugin's own version when it is installed, otherwise the first
	 * installed build of this plugin's release.
	 *
	 * @param own       this plugin's version
	 * @param installed the installed versions, of which those that are not plugin versions count as other releases
	 * @return the version, or empty when none is of this plugin's release
	 */
	private static Optional<PluginVersion> choose(PluginVersion own, List<String> installed)
	{
		List<PluginVersion> sameRelease = new ArrayList<>();
		for (String text : installed)
		{
			PluginVersion version;
			try
			{
				version = PluginVersion.parse(text);
			}
			catch (IllegalArgumentException _)
			{
				continue;
			}
			if (version.equals(own))
				return Optional.of(version);
			if (version.release().equals(own.release()))
				sameRelease.add(version);
		}
		return sameRelease.stream().findFirst();
	}

	/**
	 * Returns the command that updates the other harness's CAT installation to this plugin's release: the installer of
	 * the last installed version, among those that are plugin versions, whose version folder exists. When there is no
	 * such folder, the command is the CAT entry command of the other harness.
	 *
	 * @param other     the other harness's installation
	 * @param installed the plugin versions that the other harness has installed, in the order that it records them
	 * @param harness   the other harness's identifier in the installer's {@code --harness} option
	 * @param own       this plugin's version
	 * @param platform  the platform that the plugins run on
	 * @return the command
	 */
	private static String updateCommand(HarnessInstallation other, List<String> installed, String harness,
		PluginVersion own, Platform platform)
	{
		for (String text : installed.reversed())
		{
			Path folder;
			try
			{
				folder = other.versionFolder(PluginVersion.parse(text));
			}
			catch (IllegalArgumentException _)
			{
				continue;
			}
			if (Files.isDirectory(folder))
				return installCommand(folder, harness, platform) + " v" + own.release();
		}
		return "cat.sh --harness " + harness + " (in PowerShell: cat.ps1 -Harness " + harness + ")";
	}

	/**
	 * Returns the command that installs or updates CAT in a harness from a plugin's installer, quoted so that it runs as
	 * written: in a POSIX shell, or in PowerShell on Windows. The installer of a plugin installs CAT into a harness that
	 * has none, of the plugin's release, and updates the harness that the plugin belongs to.
	 *
	 * @param plugin   the version folder of the plugin whose installer runs the command
	 * @param harness  the harness's identifier in the installer's {@code --harness} option
	 * @param platform the platform that the plugin runs on
	 * @return the command
	 */
	private static String installCommand(Path plugin, String harness, Platform platform)
	{
		Path installer = plugin.toAbsolutePath().normalize().resolve(PluginDirectory.INSTALLER);
		String program;
		if (platform.isWindows())
		{
			program = installer.resolveSibling(installer.getFileName() + ".ps1").toString();
			if (!POWERSHELL_WORD.matcher(program).matches())
				program = "& '" + program.replace("'", "''") + "'";
		}
		else
		{
			program = installer.toString();
			if (!POSIX_WORD.matcher(program).matches())
				program = "'" + program.replace("'", "'\\''") + "'";
		}
		return program + " install --harness " + harness;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private OtherHarnessPlugin()
	{
	}
}
