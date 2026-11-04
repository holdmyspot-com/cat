package com.holdmyspot.cat.common.release;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the CAT plugin that another harness has installed, for a runner launcher in this harness's plugin that runs
 * that harness. The other harness's plugin is used when it is of this plugin's CAT release, whatever its build.
 * Otherwise the lookup stops and names the command, in this plugin, that installs or updates CAT in the other harness;
 * the user approves that command before it runs. Nothing is downloaded.
 */
public final class OtherHarnessPlugin
{
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
		String command = installCommand(ownPlugin, harness, platform);
		if (installed.isEmpty())
		{
			throw new IOException("CAT is not installed in " + harnessName + ". CAT will be installed into " +
				harnessName + " once the user approves this command; ask the user to approve it, run it, then retry: " +
				command);
		}
		Optional<PluginVersion> chosen = choose(own, installed);
		if (chosen.isEmpty())
		{
			throw new IOException(harnessName + " has CAT " + String.join(", ", installed) + " installed, not CAT " +
				"release " + own.release() + ". CAT " + own.release() + " will be installed into " + harnessName +
				" once the user approves this command; ask the user to approve it, run it, then retry: " + command);
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
	 * Returns the command that installs or updates CAT in a harness from this plugin.
	 *
	 * @param ownPlugin the version folder of the plugin that holds the running code
	 * @param harness   the harness's identifier in the installer's {@code --harness} option
	 * @param platform  the platform that the plugin runs on
	 * @return the command
	 */
	private static String installCommand(Path ownPlugin, String harness, Platform platform)
	{
		Path installer = ownPlugin.toAbsolutePath().normalize().resolve(PluginDirectory.INSTALLER);
		if (platform.isWindows())
			installer = installer.resolveSibling(installer.getFileName() + ".ps1");
		return installer + " install --harness " + harness;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private OtherHarnessPlugin()
	{
	}
}
