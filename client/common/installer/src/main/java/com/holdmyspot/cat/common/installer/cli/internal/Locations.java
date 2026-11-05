package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.engine.LinkSafeDeletion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The installation that the running installer belongs to: the version folder that the installer runs from, the
 * harness, and where that harness keeps CAT's installation, which holds the plugin data folder and its policy.
 *
 * @param image        the real version folder of the running installer
 * @param harness      the harness whose version folders contain the image
 * @param installation where the harness keeps CAT's installation
 */
public record Locations(Path image, String harness, HarnessInstallation installation)
{
	/**
	 * Creates the locations.
	 *
	 * @param image        the real version folder of the running installer
	 * @param harness      the harness whose version folders contain the image
	 * @param installation where the harness keeps CAT's installation
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Locations
	{
		requireThat(image, "image").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(installation, "installation").isNotNull();
	}

	/**
	 * Returns the instruction to install a harness's plugin again with the CAT entry command.
	 *
	 * @param harness the harness
	 * @return the instruction, a sentence fragment that starts with a verb
	 */
	public static String rerunEntryCommand(String harness)
	{
		return "run the CAT entry command again: " + entryCommand(harness);
	}

	/**
	 * Returns the CAT entry command that installs a harness's plugin.
	 *
	 * @param harness the harness
	 * @return the command, followed by its PowerShell form in parentheses
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public static String entryCommand(String harness)
	{
		requireThat(harness, "harness").isNotBlank();
		return "cat.sh --harness " + harness + " (in PowerShell: cat.ps1 -Harness " + harness + ")";
	}

	/**
	 * Finds the installation that the running installer belongs to from the folder that it runs from alone. The folder
	 * must be a version folder of exactly one harness: a folder named by a plugin version in the folder where that
	 * harness keeps CAT's plugin versions.
	 *
	 * @param image        the folder that the running installer runs from
	 * @param integrations the installed harness integrations
	 * @return the installation
	 * @throws IOException    if the folder cannot be resolved
	 * @throws TrustException if the folder is not a version folder of exactly one harness
	 */
	public static Locations installed(Path image, List<HarnessIntegration> integrations)
		throws IOException, TrustException
	{
		requireThat(integrations, "integrations").isNotNull();
		Path real = image.toRealPath();
		Path parent = real.getParent();
		List<Locations> matches = new ArrayList<>();
		if (parent != null && isPluginVersion(real.getFileName().toString()))
		{
			for (HarnessIntegration integration : integrations)
			{
				for (Path home = parent.getParent(); home != null; home = home.getParent())
				{
					HarnessInstallation installation = integration.installation(home);
					if (installation.versionFolders().equals(parent))
						matches.add(new Locations(real, integration.harness(), installation));
				}
			}
		}
		if (matches.size() != 1)
		{
			throw new TrustException(Reason.MALFORMED_POLICY, "The installer at " + real + " does not run from the " +
				"version folder of CAT's plugin in a harness, so it manages no installation; run the installer in " +
				"bin/ of the plugin version that the harness loads, or install CAT with the CAT entry command.");
		}
		return matches.getFirst();
	}

	/**
	 * Returns whether a folder name is a plugin version.
	 *
	 * @param name the name
	 * @return {@code true} if the name is a plugin version
	 */
	private static boolean isPluginVersion(String name)
	{
		try
		{
			PluginVersion.parse(name);
			return true;
		}
		catch (IllegalArgumentException _)
		{
			return false;
		}
	}

	/**
	 * Resolves existing ancestors so a symlink cannot disguise a protected path inside an excluded directory.
	 *
	 * @param path the absolute path, which may not exist yet
	 * @return the path below its nearest real ancestor
	 * @throws IOException if an existing ancestor cannot be resolved
	 */
	public static Path resolveExistingAncestor(Path path) throws IOException
	{
		return LinkSafeDeletion.resolveRoot(path);
	}

	/**
	 * Returns the plugin data folder, which holds the policy and the installer's state.
	 *
	 * @return the folder
	 */
	public Path data()
	{
		return installation.dataDirectory();
	}

	/**
	 * Returns the policy.
	 *
	 * @return the policy file
	 */
	public Path policy()
	{
		return installation.policy();
	}

	/**
	 * Returns the installer launcher in the version folder for the platform.
	 *
	 * @param platform the current platform
	 * @return the absolute launcher path
	 */
	public Path launcher(Platform platform)
	{
		Path launcher = image.resolve(PluginDirectory.INSTALLER);
		if (platform.isWindows())
			return launcher.resolveSibling(launcher.getFileName() + ".ps1");
		return launcher;
	}
}
