package com.holdmyspot.cat.claude.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.AddDirectory;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.runner.PluginManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plans installation and updates of CAT's version folders in the directory marketplace that Claude Code loads in
 * place. Each version lives in its own folder, named after the version, inside the marketplace plugin.
 * <p>
 * Existing symbolic links from the policy's home through a version folder are refused. This guards against
 * redirection by a project that can write those directories. Planning never changes Claude Code's configuration.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Activation implements PluginActivation
{
	/**
	 * Creates a provider for service loading.
	 */
	public Activation()
	{
	}

	@Override
	public String harness()
	{
		return "claude";
	}

	@Override
	public String pluginManifest()
	{
		return ".claude-plugin/plugin.json";
	}

	/**
	 * Reads the version that Claude Code records for CAT, and checks that its version folder states it.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return empty and consistent when Claude Code records no CAT installation and CAT's marketplace is absent; empty
	 * 	and inconsistent when the marketplace exists without a record, which a stopped installation or removal leaves;
	 * 	otherwise the recorded version, consistent only if its version folder's plugin manifest states that version
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the record or the manifest cannot be read or parsed, or a path component is a
	 *                                  symlink
	 */
	@Override
	public InstalledState installed(Path home) throws IOException
	{
		ClaudeInstallation installation = new ClaudeInstallation(home);
		Optional<String> recorded = Integration.installedVersion(home);
		if (recorded.isEmpty())
		{
			Locations.checkLinks(home, installation.marketplaceRoot());
			boolean absent = Files.notExists(installation.marketplaceRoot(), LinkOption.NOFOLLOW_LINKS);
			return new InstalledState(Optional.empty(), absent);
		}
		String version = recorded.get();
		if (!isVersion(version))
			return new InstalledState(recorded, false);
		Path folder = installation.versionFolders().resolve(version);
		Path manifestPath = Path.of(pluginManifest());
		Locations.checkLinks(home, folder.resolve(manifestPath));
		if (Files.notExists(folder.resolve(manifestPath), LinkOption.NOFOLLOW_LINKS))
			return new InstalledState(recorded, false);
		return new InstalledState(recorded, PluginManifest.version(folder, manifestPath).equals(recorded));
	}

	/**
	 * Plans activation of a version's folder without creating or registering a marketplace. Activating the installed
	 * version again replaces its folder; any other version adds its own folder and leaves the installed one in place.
	 *
	 * @param home       the absolute Claude Code configuration directory recorded in policy
	 * @param newVersion the plugin version or release version to activate
	 * @return one replacement or addition of the version's folder, staged outside the folder that holds the version
	 * 	folders
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code newVersion} is neither a plugin version
	 *                                  nor a release version
	 * @throws IOException              if a path component is a symlink or CAT is not installed; run initial setup
	 *                                  when CAT is absent
	 */
	@Override
	public List<ActivationStep> plan(Path home, String newVersion) throws IOException
	{
		Path folder = versionFolder(home, newVersion);
		Optional<String> active = installed(home).version();
		if (active.isEmpty())
		{
			throw new IOException("CAT is not installed at " + new ClaudeInstallation(home).versionFolders() +
				". Run the initial setup before updating.");
		}
		return steps(folder, active.get().equals(newVersion));
	}

	/**
	 * Reconstructs the original plan even when a stopped activation moved a version folder away.
	 *
	 * @param home            the absolute normalized configuration directory recorded in protected policy
	 * @param previousVersion the non-blank version active before the recorded activation
	 * @param newVersion      the version installed by the recorded activation
	 * @return the plan that {@link #plan(Path, String)} returned while {@code previousVersion} was active
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized, {@code previousVersion} is blank,
	 *                                  or {@code newVersion} is neither a plugin version nor a release version
	 * @throws IOException              if a path component is a symlink
	 */
	@Override
	public List<ActivationStep> recoveryPlan(Path home, String previousVersion, String newVersion) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(previousVersion, "previousVersion").isNotBlank();
		Path folder = versionFolder(home, newVersion);
		return steps(folder, previousVersion.equals(newVersion));
	}

	/**
	 * Returns the migration marker in CAT's existing data directory.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the absolute migration marker path
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	@Override
	public Path migrationVersionFile(Path home)
	{
		return dataDirectory(home).resolve("migration-version");
	}

	/**
	 * Plans adding the version's folder inside CAT's marketplace plugin, staged outside the folder that holds the
	 * version folders. Claude Code loads only the folder that the marketplace manifest names; registration later
	 * writes that manifest.
	 *
	 * @param home       the absolute Claude Code configuration directory recorded in policy
	 * @param newVersion the plugin version or release version to install
	 * @return one addition of the version's folder
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code newVersion} is neither a plugin version
	 *                                  nor a release version
	 * @throws IOException              if a path component is a symlink
	 */
	@Override
	public List<ActivationStep> installPlan(Path home, String newVersion) throws IOException
	{
		return steps(versionFolder(home, newVersion), false);
	}

	/**
	 * Lists CAT's marketplace and Claude Code's cached copies of the plugin that it installed from that marketplace.
	 * Claude Code's uninstall only marks the cached copies as orphaned, so uninstall deletes them to remove CAT
	 * completely.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the locations that exist, in deletion order
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if a path component is a symlink
	 */
	@Override
	public List<Path> removalPaths(Path home) throws IOException
	{
		ClaudeInstallation installation = new ClaudeInstallation(home);
		List<Path> paths = new ArrayList<>();
		for (Path path : List.of(installation.marketplaceRoot(), installation.marketplaceCache()))
		{
			Locations.checkLinks(home, path);
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
				paths.add(path);
		}
		return paths;
	}

	/**
	 * Returns the data directory that Claude Code assigns to the {@code cat} plugin of the {@code cat} marketplace.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the absolute data directory
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	@Override
	public Path dataDirectory(Path home)
	{
		return new ClaudeInstallation(home).dataDirectory();
	}

	/**
	 * Locates a version's folder and refuses links that could redirect its activation.
	 *
	 * @param home    the absolute Claude Code configuration directory recorded in policy
	 * @param version the plugin version or release version
	 * @return the normalized version folder
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code version} is neither a plugin version nor a
	 *                                  release version
	 * @throws IOException              if a path component is a symlink
	 */
	private static Path versionFolder(Path home, String version) throws IOException
	{
		requireThat(version, "version").isNotNull();
		ClaudeInstallation installation = new ClaudeInstallation(home);
		if (!isVersion(version))
		{
			throw new IllegalArgumentException("version must be a plugin version, such as 1.0" +
				PluginVersion.BUILD_SEPARATOR + "0123456789ab, or a release version, such as 1.0: \"" + version + "\"");
		}
		Path folder = installation.versionFolders().resolve(version);
		Locations.checkLinks(home, folder);
		return folder;
	}

	/**
	 * Returns whether a text names a version folder: a plugin version, or a release version that an installation
	 * from before plugin versions recorded.
	 *
	 * @param text the text
	 * @return true if {@code text} is a plugin version or a release version
	 */
	private static boolean isVersion(String text)
	{
		try
		{
			PluginVersion.parse(text);
			return true;
		}
		catch (IllegalArgumentException _)
		{
			try
			{
				ReleaseVersion.parse(text);
				return true;
			}
			catch (TrustException _)
			{
				return false;
			}
		}
	}

	/**
	 * Returns the single step that activates a version folder, staged outside the folder that holds the version
	 * folders.
	 *
	 * @param folder  the version folder
	 * @param replace true to replace the folder, false to add it
	 * @return the step
	 */
	private static List<ActivationStep> steps(Path folder, boolean replace)
	{
		Path staging = folder.getParent().getParent();
		if (replace)
			return List.of(new ReplaceDirectory(folder, staging));
		return List.of(new AddDirectory(folder, staging));
	}
}
