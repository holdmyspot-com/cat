package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Describes the CAT-owned files that Codex loads, without changing its configuration.
 * <p>
 * Codex loads the cache folder that {@link CodexVersions} chooses: a developer's {@code local} folder, otherwise the
 * greatest version folder name. Its session-start refresh compares that directory name with the marketplace source's
 * manifest version.
 * <p>
 * This provider's observations do not prevent another process from changing those paths afterward.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexActivation implements PluginActivation
{
	/**
	 * Identifies the policy entry and both installer services for Codex.
	 */
	static final String HARNESS = "codex";
	/**
	 * The plugin manifest inside both the marketplace source and a cache version.
	 */
	private static final String PLUGIN_MANIFEST = ".codex-plugin/plugin.json";
	/**
	 * The installer-written ownership manifest in CAT's data directory, which authorizes replacing CAT's custom
	 * agents.
	 */
	private static final String AGENT_OWNERSHIP = "codex-agent-files";
	/**
	 * The text that starts the name of every custom agent that CAT writes.
	 */
	private static final String AGENT_PREFIX = "cat-";
	/**
	 * The release directory that holds CAT's custom agents, written with {@code /} between names.
	 */
	private static final String AGENT_SOURCES = "agents/codex";
	/**
	 * The file in CAT's data directory that holds the last data migration applied to this installation.
	 */
	private static final String MIGRATION_VERSION = "migration-version";
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
	 * Reads the cache version that Codex loads. A real {@code local} directory takes precedence and is always
	 * inconsistent; otherwise both plugin manifests must equal the loaded directory's name for the state to be
	 * consistent.
	 * Without a cache version, the state is consistent only when neither the marketplace root nor any entry of the
	 * plugin's cache directory remains. Leftover custom agents and their ownership manifest do not make it
	 * inconsistent, because installation replaces the agents that the manifest lists.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or a path used by this read is a symbolic link
	 */
	@Override
	public InstalledState installed(Path home) throws IOException
	{
		CodexInstallation layout = installation(home);
		Path cache = target(home, layout.versionFolders());
		Path source = target(home, layout.marketplacePlugin());
		String active = CodexVersions.active(cache).orElse(null);
		if (active == null)
			return new InstalledState(Optional.empty(), !hasLeftovers(home));
		if (active.equals(CodexVersions.LOCAL))
			return new InstalledState(Optional.of(active), false);

		String sourceVersion = readVersion(target(home, source.resolve(PLUGIN_MANIFEST)));
		String cacheVersion = readVersion(target(home, cache.resolve(active).resolve(PLUGIN_MANIFEST)));
		boolean copiesAgree = active.equals(sourceVersion) && active.equals(cacheVersion) &&
			Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
		return new InstalledState(Optional.of(active), copiesAgree);
	}

	/**
	 * Installs the version's custom agents, each {@code agents/codex/<name>.toml} of the version folder, as
	 * {@code cat-<name>.toml} in Codex's agents directory, and records them as CAT's. Agents that CAT installed earlier
	 * are replaced, and those that the version no longer ships are deleted. The record lists every agent that this call
	 * may write before it writes any, so that an install that stops part way leaves no agent of CAT's unrecorded.
	 *
	 * @throws IOException if the record of CAT's agents is invalid, the version's agent is not a regular file, an agent
	 *                     with CAT's name is not recorded as CAT's, a path that this call writes is a symbolic link, or a
	 *                     file cannot be read or written
	 */
	@Override
	public void installCompanions(Path home, Path versionFolder) throws IOException
	{
		requireThat(versionFolder, "versionFolder").isNotNull();
		CodexInstallation layout = installation(home);
		Path agents = linkFree(home, layout.agents());
		Path ownership = linkFree(home, layout.dataDirectory().resolve(AGENT_OWNERSHIP));
		Set<String> owned = new LinkedHashSet<>();
		if (Files.exists(ownership, LinkOption.NOFOLLOW_LINKS))
			owned.addAll(readOwnedNames(ownership));
		Map<String, Path> shipped = shippedAgents(versionFolder);
		for (String name : shipped.keySet())
		{
			Path agent = linkFree(home, agents.resolve(name));
			if (Files.exists(agent, LinkOption.NOFOLLOW_LINKS) &&
				(!owned.contains(name) || !Files.isRegularFile(agent, LinkOption.NOFOLLOW_LINKS)))
			{
				throw new IOException("Codex's custom agent " + agent + " is not CAT's, so install does not replace " +
					"it; rename or remove it, then run install again.");
			}
		}
		for (String name : owned)
			linkFree(home, agents.resolve(name));

		Set<String> recorded = new LinkedHashSet<>(owned);
		recorded.addAll(shipped.keySet());
		Files.createDirectories(ownership.getParent());
		replace(ownership, lines(recorded));
		Files.createDirectories(agents);
		for (Map.Entry<String, Path> agent : shipped.entrySet())
			replace(agents.resolve(agent.getKey()), Files.readAllBytes(agent.getValue()));
		for (String name : owned)
		{
			if (!shipped.containsKey(name))
				Files.deleteIfExists(agents.resolve(name));
		}
		replace(ownership, lines(shipped.keySet()));
	}

	/**
	 * Returns the custom agents that a version folder ships.
	 *
	 * @param versionFolder the version folder
	 * @return each agent's installed name, in name order, with its file in the version folder
	 * @throws IOException if an agent is not a regular file or the folder cannot be listed
	 */
	private static Map<String, Path> shippedAgents(Path versionFolder) throws IOException
	{
		Map<String, Path> shipped = new TreeMap<>();
		Path sources = versionFolder.resolve(AGENT_SOURCES);
		if (!Files.isDirectory(sources, LinkOption.NOFOLLOW_LINKS))
			return shipped;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(sources, "*.toml"))
		{
			for (Path source : files)
			{
				if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("The release's custom agent is not a regular file: " + source);
				shipped.put(AGENT_PREFIX + source.getFileName(), source);
			}
		}
		return shipped;
	}

	/**
	 * Returns names as the lines of the record of CAT's agents.
	 *
	 * @param names the names
	 * @return the record's bytes
	 */
	private static byte[] lines(Set<String> names)
	{
		StringBuilder text = new StringBuilder();
		for (String name : names)
			text.append(name).append('\n');
		return text.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Replaces a file's content in one step, through a file written beside it.
	 *
	 * @param file    the file
	 * @param content its new content
	 * @throws IOException if the file cannot be written
	 */
	private static void replace(Path file, byte[] content) throws IOException
	{
		Path staged = file.resolveSibling("." + file.getFileName() + ".new");
		Files.deleteIfExists(staged);
		Files.write(staged, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Refuses links from the recorded home through a path that this provider writes.
	 *
	 * @param home   the absolute harness home
	 * @param target the absolute path within that home
	 * @return the normalized path
	 * @throws IOException if a component of the path is a symbolic link
	 */
	private static Path linkFree(Path home, Path target) throws IOException
	{
		try
		{
			return target(home, target);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Lists the marketplace root, the cache directory of CAT's marketplace (every version and CAT's staging and
	 * previous-installation slots), each listed custom agent whose bytes still equal the release's agent, and then the
	 * ownership manifest. A listed agent that differs from the release, or that no installed copy of the release can be
	 * compared with, is left in place; once the manifest is deleted it belongs to the user.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or a listed path is a symbolic link
	 * @throws IOException              if the ownership manifest is invalid or a file cannot be read
	 */
	@Override
	public List<Path> removalPaths(Path home) throws IOException
	{
		CodexInstallation layout = installation(home);
		Path marketplace = target(home, layout.marketplaceRoot());
		Path cache = target(home, layout.versionFolders().getParent());
		Path ownership = target(home, layout.dataDirectory().resolve(AGENT_OWNERSHIP));
		List<Path> paths = new ArrayList<>();
		if (Files.exists(marketplace, LinkOption.NOFOLLOW_LINKS))
			paths.add(marketplace);
		if (Files.exists(cache, LinkOption.NOFOLLOW_LINKS))
			paths.add(cache);
		if (Files.notExists(ownership, LinkOption.NOFOLLOW_LINKS))
			return paths;

		List<Path> releases = releaseCopies(home);
		for (String name : readOwnedNames(ownership))
		{
			Path agent = target(home, layout.agents().resolve(name));
			if (Files.isRegularFile(agent, LinkOption.NOFOLLOW_LINKS) && matchesRelease(agent, name, releases))
				paths.add(agent);
		}
		paths.add(ownership);
		return paths;
	}

	/**
	 * Resolves CAT's data directory without following symbolic links in its installation path.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or a component of the path is a symbolic link
	 */
	@Override
	public Path dataDirectory(Path home)
	{
		return target(home, installation(home).dataDirectory());
	}

	/**
	 * Reports whether a marketplace root or a cache entry remains without an active cache version.
	 *
	 * @param home the absolute harness home
	 * @return {@code true} if a partial installation remains
	 * @throws IOException if the cache directory cannot be listed
	 */
	private static boolean hasLeftovers(Path home) throws IOException
	{
		CodexInstallation layout = installation(home);
		if (Files.exists(target(home, layout.marketplaceRoot()), LinkOption.NOFOLLOW_LINKS))
			return true;
		Path cache = target(home, layout.versionFolders());
		if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS))
			return Files.exists(cache, LinkOption.NOFOLLOW_LINKS);
		try (Stream<Path> entries = Files.list(cache))
		{
			return entries.findAny().isPresent();
		}
	}

	/**
	 * Returns the installed copies of the release that hold its custom agents: the marketplace source, then every
	 * real cache version directory.
	 *
	 * @param home the absolute harness home
	 * @return the existing plugin directories
	 * @throws IOException if the cache directory cannot be listed
	 */
	private static List<Path> releaseCopies(Path home) throws IOException
	{
		CodexInstallation layout = installation(home);
		List<Path> copies = new ArrayList<>();
		Path source = target(home, layout.marketplacePlugin());
		if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
			copies.add(source);
		Path cache = target(home, layout.versionFolders());
		if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS))
			return copies;
		try (Stream<Path> entries = Files.list(cache))
		{
			for (Path entry : (Iterable<Path>) entries.sorted()::iterator)
			{
				if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
					copies.add(entry);
			}
		}
		return copies;
	}

	/**
	 * Reports whether an owned agent still has the bytes of the release agent that CAT installed it from.
	 *
	 * @param agent    the installed agent file
	 * @param name     its name in the ownership manifest, which is {@code cat-} followed by the release file's name
	 * @param releases the installed plugin directories to compare with
	 * @return {@code true} if a release copy holds an agent with identical bytes
	 * @throws IOException if a file cannot be read
	 */
	private static boolean matchesRelease(Path agent, String name, List<Path> releases) throws IOException
	{
		String sourceName = name.substring(AGENT_PREFIX.length());
		for (Path release : releases)
		{
			Path source = release.resolve(AGENT_SOURCES).resolve(sourceName);
			if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(source, agent) == -1)
				return true;
		}
		return false;
	}

	/**
	 * Reads the agent names that the ownership manifest lists, one per line.
	 *
	 * @param ownership the ownership manifest
	 * @return the listed names
	 * @throws IOException if the manifest is not a regular file, exceeds 1 MiB or lists a name that is not a CAT
	 *                     custom-agent file in the agents directory
	 */
	private static List<String> readOwnedNames(Path ownership) throws IOException
	{
		if (!Files.isRegularFile(ownership, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The agent ownership manifest is not a regular file: " + ownership);
		if (Files.size(ownership) > MAX_MANIFEST_BYTES)
			throw new IOException("The agent ownership manifest exceeds 1 MiB: " + ownership);
		List<String> names = Files.readAllLines(ownership, StandardCharsets.UTF_8);
		for (String name : names)
		{
			if (!name.startsWith(AGENT_PREFIX) || !name.endsWith(".toml") || name.contains("/") ||
				name.contains("\\") || name.contains("..") || name.indexOf('\u0000') >= 0)
			{
				throw new IOException("The agent ownership manifest lists an invalid file name: " + name);
			}
		}
		return names;
	}

	/**
	 * Resolves the migration marker without following symbolic links in its installation path.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or the migration file's path is a symbolic link
	 */
	@Override
	public Path migrationVersionFile(Path home)
	{
		CodexInstallation layout = installation(home);
		return target(home, layout.dataDirectory().resolve(MIGRATION_VERSION));
	}

	/**
	 * Returns the Codex installation in a home.
	 *
	 * @param home the absolute harness home
	 * @return the installation
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	private static CodexInstallation installation(Path home)
	{
		return new CodexInstallation(home);
	}

	/**
	 * Refuses links from the recorded home through one installation target's final component.
	 *
	 * @param home   the absolute harness home
	 * @param target the absolute target within that home
	 * @return the normalized target
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative, {@code target} is not within it, or an existing
	 *                                  target component is a symbolic link
	 */
	private static Path target(Path home, Path target)
	{
		requireThat(home, "home").isAbsolute();
		home = home.normalize();
		Path normalized = target.normalize();
		requireThat(normalized.startsWith(home), "target.startsWith(home)").isTrue();
		Path current = home;
		if (Files.isSymbolicLink(current))
			throw new IllegalArgumentException("An activation path is a symbolic link: " + current);
		for (Path name : home.relativize(normalized))
		{
			current = current.resolve(name);
			if (Files.isSymbolicLink(current))
				throw new IllegalArgumentException("An activation path is a symbolic link: " + current);
		}
		return current;
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
