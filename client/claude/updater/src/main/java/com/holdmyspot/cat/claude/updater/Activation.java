package com.holdmyspot.cat.claude.updater;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Plans updates to the CAT directory marketplace that Claude Code loads in place.
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
	 * @return empty and inconsistent when the live manifest is absent; otherwise its version and whether it exists
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the manifest cannot be read or parsed, or a path component is a symlink
	 */
	@Override
	public InstalledState installed(Path home) throws IOException
	{
		Path live = liveDirectory(home);
		Path manifestPath = Path.of(pluginManifest());
		checkLinks(home.normalize(), live.resolve(manifestPath));
		if (Files.notExists(live.resolve(manifestPath), LinkOption.NOFOLLOW_LINKS))
			return new InstalledState(Optional.empty(), false);
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
		requireThat(home, "home").isAbsolute();
		return home.normalize().resolve("plugins/data/cat-cat/migration-version");
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
		requireThat(home, "home").isAbsolute();
		home = home.normalize();
		Path live = home.resolve("plugins/cat-marketplace/plugins/cat");
		checkLinks(home, live);
		return live;
	}

	/**
	 * Refuses every symlink from the home directory through a target, including dangling links.
	 *
	 * @param home   the absolute normalized configuration directory
	 * @param target the target below the configuration directory
	 * @throws IOException if a path component is a symlink
	 */
	private static void checkLinks(Path home, Path target) throws IOException
	{
		Path component = home;
		if (Files.isSymbolicLink(component))
			throw new IOException("CAT's activation path is a symbolic link: " + component);
		for (Path name : home.relativize(target))
		{
			component = component.resolve(name);
			if (Files.isSymbolicLink(component))
				throw new IOException("CAT's activation path is a symbolic link: " + component);
		}
	}
}
