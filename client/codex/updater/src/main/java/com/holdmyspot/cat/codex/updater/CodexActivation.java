package com.holdmyspot.cat.codex.updater;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceOwnedFiles;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Describes the CAT-owned files that Codex loads, without changing its configuration.
 * <p>
 * Codex loads the highest numeric cache directory, with a developer's {@code local} directory taking precedence.
 * Its session-start refresh compares that directory name with the marketplace source's manifest version. Updating
 * the source before the cache makes a refresh after an interrupted activation converge on the new release.
 * <p>
 * The returned steps must be executed by the updater core, which journals and reverses them on failure. This provider
 * only reads files; its observations do not prevent another process from changing those paths afterward.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexActivation implements PluginActivation
{
	/**
	 * Identifies the policy entry and both updater services for Codex.
	 */
	static final String HARNESS = "codex";
	/**
	 * The plugin manifest inside both the marketplace source and a cache version.
	 */
	private static final String PLUGIN_MANIFEST = ".codex-plugin/plugin.json";
	/**
	 * The installed marketplace source that Codex copies during a version refresh.
	 */
	private static final Path MARKETPLACE_PLUGIN = Path.of("plugins/cat-marketplace/plugins/cat");
	/**
	 * The directory whose real children Codex scans for active versions.
	 */
	private static final Path CACHE_VERSIONS = Path.of("plugins/cache/cat/cat");
	/**
	 * The agents directory, shared with user-owned agents.
	 */
	private static final Path AGENTS = Path.of("agents");
	/**
	 * The installer-written ownership manifest that authorizes replacing CAT's custom agents.
	 */
	private static final Path AGENT_OWNERSHIP = Path.of("plugins/data/cat-cat/codex-agent-files");
	/**
	 * The last data migration applied to this installation.
	 */
	private static final Path MIGRATION_VERSION = Path.of("plugins/data/cat-cat/migration-version");
	/**
	 * Bounds one installed plugin manifest to 1 MiB before building its JSON tree.
	 */
	private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
	/**
	 * Reads Codex's external plugin manifest; fields other than the version belong to Codex.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Creates the activation provider used by service discovery.
	 */
	public CodexActivation()
	{
	}

	@Override
	public String harness()
	{
		return HARNESS;
	}

	@Override
	public String pluginManifest()
	{
		return PLUGIN_MANIFEST;
	}

	/**
	 * Reads the selected cache version. A real {@code local} directory takes precedence and is always inconsistent;
	 * otherwise both plugin manifests must equal the highest numeric directory name for the state to be consistent.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or a path used by this read is a symbolic link
	 */
	@Override
	public InstalledState installed(Path home) throws IOException
	{
		Path cache = target(home, CACHE_VERSIONS);
		Path source = target(home, MARKETPLACE_PLUGIN);
		String active = selectVersion(cache);
		if (active == null)
			return new InstalledState(Optional.empty(), false);
		if (active.equals("local"))
			return new InstalledState(Optional.of(active), false);

		String sourceVersion = readVersion(target(home, MARKETPLACE_PLUGIN.resolve(PLUGIN_MANIFEST)));
		String cacheVersion = readVersion(target(home, CACHE_VERSIONS.resolve(active).resolve(PLUGIN_MANIFEST)));
		boolean copiesAgree = active.equals(sourceVersion) && active.equals(cacheVersion) &&
			Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
		return new InstalledState(Optional.of(active), copiesAgree);
	}

	/**
	 * Plans owned-agent replacement, marketplace replacement, then cache activation. A same-version activation replaces
	 * the existing cache directory; a new version adds a real directory and keeps the previous cache version in place.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative, {@code newVersion} is not a CAT release version,
	 *                                  or an activation path is a symbolic link
	 * @throws IOException              if CAT's marketplace or active cache is absent, a developer's {@code local}
	 *                                  directory is active, or the installation cannot be read
	 */
	@Override
	public List<ActivationStep> plan(Path home, String newVersion) throws IOException
	{
		validateVersion(newVersion, "newVersion");
		List<ActivationStep> addition = activationSteps(home, newVersion, false);
		InstalledState state = installed(home);
		String active = state.version().orElse(null);
		if (Objects.equals(active, "local"))
			throw new IOException("A local developer installation is active; use explicit setup to replace it: " +
				target(home, CACHE_VERSIONS));
		if (active == null || !Files.isDirectory(target(home, MARKETPLACE_PLUGIN), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("CAT is not installed in both the cache and marketplace; run explicit setup: " + home);

		if (newVersion.equals(active))
			return activationSteps(home, newVersion, true);
		return addition;
	}

	/**
	 * Reconstructs the original steps from the recorded versions, including while live directories are absent.
	 *
	 * @throws IllegalArgumentException if the home is relative or unnormalized, either version is not a CAT release,
	 *                                  or an activation path is a symbolic link
	 */
	@Override
	public List<ActivationStep> recoveryPlan(Path home, String previousVersion, String newVersion)
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		validateVersion(previousVersion, "previousVersion");
		validateVersion(newVersion, "newVersion");
		target(home, MARKETPLACE_PLUGIN.resolve(PLUGIN_MANIFEST));
		target(home, CACHE_VERSIONS.resolve(previousVersion).resolve(PLUGIN_MANIFEST));
		return activationSteps(home, newVersion, previousVersion.equals(newVersion));
	}

	/**
	 * Validates a canonical release version before resolving paths derived from it.
	 *
	 * @param version the release version
	 * @param name    the parameter name used in validation diagnostics
	 * @throws IllegalArgumentException if the version is blank or is not a CAT release
	 */
	private static void validateVersion(String version, String name)
	{
		requireThat(version, name).isNotBlank();
		try
		{
			ReleaseVersion.parse(version);
		}
		catch (TrustException e)
		{
			throw new IllegalArgumentException("Invalid CAT release version: " + version, e);
		}
	}

	/**
	 * Resolves the fixed activation destinations without requiring their live contents to exist.
	 *
	 * @param home         the absolute harness home
	 * @param newVersion   the validated release version to activate
	 * @param replaceCache whether the original activation replaces an existing version
	 * @return the immutable ordered activation steps
	 * @throws IllegalArgumentException if the home is relative or an activation path is a symbolic link
	 */
	private static List<ActivationStep> activationSteps(Path home, String newVersion, boolean replaceCache)
	{
		Path agents = target(home, AGENTS);
		Path ownership = target(home, AGENT_OWNERSHIP);
		Path source = target(home, MARKETPLACE_PLUGIN);
		Path cache = target(home, CACHE_VERSIONS);
		Path newCache = target(home, CACHE_VERSIONS.resolve(newVersion));
		target(home, MIGRATION_VERSION);
		ActivationStep cacheStep;
		if (replaceCache)
			cacheStep = new ReplaceDirectory(newCache, cache.getParent());
		else
			cacheStep = new AddDirectory(newCache, cache.getParent());
		return List.of(new ReplaceOwnedFiles(agents, ownership, "cat-", "agents/codex", "*.toml"),
			new ReplaceDirectory(source, source.getParent()), cacheStep);
	}

	/**
	 * Resolves the migration marker without following symbolic links in its installation path.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or the migration file's path is a symbolic link
	 */
	@Override
	public Path migrationVersionFile(Path home)
	{
		return target(home, MIGRATION_VERSION);
	}

	/**
	 * Resolves one installation target, refusing links from the recorded home through its final component.
	 *
	 * @param home     the absolute harness home
	 * @param relative the target within that home
	 * @return the absolute normalized target
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative or an existing target component is a symbolic link
	 */
	private static Path target(Path home, Path relative)
	{
		requireThat(home, "home").isAbsolute();
		home = home.normalize();
		Path current = home;
		if (Files.isSymbolicLink(current))
			throw new IllegalArgumentException("An activation path is a symbolic link: " + current);
		for (Path name : relative)
		{
			current = current.resolve(name);
			if (Files.isSymbolicLink(current))
				throw new IllegalArgumentException("An activation path is a symbolic link: " + current);
		}
		return current;
	}

	/**
	 * Selects a real local directory first, otherwise the highest CAT numeric release directory.
	 *
	 * @param cache the directory Codex scans for versions
	 * @return the selected name, or null when the cache has no real version directories
	 * @throws IOException if an existing cache cannot be listed
	 */
	private static String selectVersion(Path cache) throws IOException
	{
		if (Files.notExists(cache, LinkOption.NOFOLLOW_LINKS))
			return null;
		ReleaseVersion highest = null;
		try (Stream<Path> entries = Files.list(cache))
		{
			for (Path entry : (Iterable<Path>) entries::iterator)
			{
				if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
					continue;
				String name = entry.getFileName().toString();
				if (name.equals("local"))
					return name;
				ReleaseVersion version;
				try
				{
					version = ReleaseVersion.parse(name);
				}
				catch (TrustException _)
				{
					continue;
				}
				if (highest == null || version.compareTo(highest) > 0)
					highest = version;
			}
		}
		if (highest == null)
			return null;
		return highest.toString();
	}

	/**
	 * Reads a plugin manifest's version without executing anything from the plugin.
	 *
	 * @param manifest the installed plugin manifest
	 * @return the declared version, or null when the manifest is absent
	 * @throws IOException if the manifest cannot be read, exceeds 1 MiB, is invalid JSON, or lacks a string version
	 */
	private static String readVersion(Path manifest) throws IOException
	{
		if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS))
			return null;
		byte[] bytes;
		try (InputStream input = Files.newInputStream(manifest))
		{
			bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
		}
		if (bytes.length > MAX_MANIFEST_BYTES)
			throw new IOException("The installed plugin manifest exceeds 1 MiB: " + manifest);
		try
		{
			JsonNode root = MAPPER.readTree(bytes);
			if (root == null || !root.path("version").isString())
				throw new IOException("The installed plugin manifest must declare a string version: " + manifest);
			return root.path("version").stringValue();
		}
		catch (JacksonException e)
		{
			throw new IOException("The installed plugin manifest is not valid JSON: " + manifest, e);
		}
	}
}
