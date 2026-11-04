package com.holdmyspot.cat.claude.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.AddDirectory;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.activation.ReplaceDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plans installation and updates of the CAT directory marketplace that Claude Code loads in place.
 * <p>
 * Existing symbolic links from the policy's home through the plugin directory are refused. This guards against
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
	 * Reads only the live marketplace manifest; install records may report another version.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return empty and consistent when CAT's marketplace is absent; empty and inconsistent when the marketplace
	 * 	exists without a plugin manifest, which a stopped installation or removal leaves; otherwise the manifest's
	 * 	version
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the manifest cannot be read or parsed, or a path component is a symlink
	 */
	@Override
	public InstalledState installed(Path home) throws IOException
	{
		Path live = liveDirectory(home);
		Path manifestPath = Path.of(pluginManifest());
		Locations.checkLinks(home, live.resolve(manifestPath));
		if (Files.notExists(live.resolve(manifestPath), LinkOption.NOFOLLOW_LINKS))
		{
			boolean absent = Files.notExists(Locations.marketplaceRoot(home), LinkOption.NOFOLLOW_LINKS);
			return new InstalledState(Optional.empty(), absent);
		}
		return new InstalledState(PluginManifest.version(live, manifestPath), true);
	}

	/**
	 * Plans replacement of the live plugin without creating or registering a marketplace.
	 *
	 * @param home       the absolute Claude Code configuration directory recorded in policy
	 * @param newVersion the non-blank release version
	 * @return one replacement of the live marketplace plugin, staged beside it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code newVersion} is blank
	 * @throws IOException              if a path component is a symlink or CAT is not installed; run initial setup
	 *                                  when CAT is absent
	 */
	@Override
	public List<ActivationStep> plan(Path home, String newVersion) throws IOException
	{
		requireThat(newVersion, "newVersion").isNotBlank();
		Path live = liveDirectory(home);
		if (!Files.isDirectory(live, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("CAT is not installed at " + live + ". Run the initial setup before updating.");
		return List.of(new ReplaceDirectory(live, live.getParent()));
	}

	/**
	 * Reconstructs the original destination even when a stopped activation moved the live directory away.
	 *
	 * @param home            the absolute normalized configuration directory recorded in protected policy
	 * @param previousVersion the non-blank version active before the recorded activation
	 * @param newVersion      the non-blank version installed by the recorded activation
	 * @return one replacement of the canonical marketplace plugin, staged beside it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized, or either version is blank
	 * @throws IOException              if a path component is a symlink
	 */
	@Override
	public List<ActivationStep> recoveryPlan(Path home, String previousVersion, String newVersion) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(previousVersion, "previousVersion").isNotBlank();
		requireThat(newVersion, "newVersion").isNotBlank();
		Path live = liveDirectory(home);
		return List.of(new ReplaceDirectory(live, live.getParent()));
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
	 * Plans adding the plugin directory inside CAT's marketplace, staged beside it, where Claude Code loads only the
	 * plugin that the marketplace manifest names. Registration later writes the marketplace manifest from the
	 * activated release.
	 *
	 * @param home       the absolute Claude Code configuration directory recorded in policy
	 * @param newVersion the non-blank release version
	 * @return one addition of the marketplace plugin
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code newVersion} is blank
	 * @throws IOException              if a path component is a symlink
	 */
	@Override
	public List<ActivationStep> installPlan(Path home, String newVersion) throws IOException
	{
		requireThat(newVersion, "newVersion").isNotBlank();
		Path live = liveDirectory(home);
		return List.of(new AddDirectory(live, live.getParent()));
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
		List<Path> paths = new ArrayList<>();
		for (Path path : List.of(Locations.marketplaceRoot(home), home.normalize().resolve("plugins/cache/cat")))
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
		requireThat(home, "home").isAbsolute();
		return home.normalize().resolve("plugins/data/cat-cat");
	}

	/**
	 * Locates the live plugin and refuses links that could redirect its replacement.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the normalized live plugin directory
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if a path component is a symlink
	 */
	private static Path liveDirectory(Path home) throws IOException
	{
		Path live = Locations.pluginDirectory(home);
		Locations.checkLinks(home, live);
		return live;
	}
}
