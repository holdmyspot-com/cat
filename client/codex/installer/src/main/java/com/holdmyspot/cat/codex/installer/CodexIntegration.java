package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integrates the installer with Codex's command-line program and execution policy.
 * <p>
 * Registration runs {@code codex plugin marketplace add} and {@code codex plugin add}. Codex's {@code plugin add}
 * copies the marketplace source into {@code plugins/cache/cat/cat/<version>}, replacing the cache copy that activation
 * added, so registration then requires that copy to equal the validated marketplace source file for file.
 * <p>
 * Permissions live in the CAT-owned file {@code rules/cat-updater.rules}, which Codex reads beside the user's rules.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexIntegration implements HarnessIntegration
{
	/**
	 * The marketplace root that CAT registers.
	 */
	private static final Path MARKETPLACE_ROOT = Path.of("plugins/cat-marketplace");
	/**
	 * The activated plugin inside the marketplace root.
	 */
	private static final Path MARKETPLACE_PLUGIN = MARKETPLACE_ROOT.resolve("plugins/cat");
	/**
	 * The marketplace metadata that Codex reads from the marketplace root.
	 */
	private static final Path MARKETPLACE_METADATA = MARKETPLACE_ROOT.resolve(".agents/plugins/marketplace.json");
	/**
	 * The release's copy of the marketplace metadata, relative to the plugin directory.
	 */
	private static final String RELEASE_METADATA = "install/codex-marketplace.json";
	/**
	 * The directory whose version directories Codex loads the plugin from.
	 */
	private static final Path CACHE_VERSIONS = Path.of("plugins/cache/cat/cat");
	/**
	 * The plugin manifest inside the plugin directory.
	 */
	private static final String PLUGIN_MANIFEST = ".codex-plugin/plugin.json";
	/**
	 * The CAT-owned execution-policy file.
	 */
	private static final Path RULES_FILE = Path.of("rules/cat-updater.rules");
	/**
	 * The permission key of the CAT-owned execution-policy file: its path relative to the home and an empty setting.
	 */
	private static final String RULES_KEY = "rules/cat-updater.rules#";
	/**
	 * The plugin as Codex identifies it: the plugin name, then the marketplace name.
	 */
	private static final String PLUGIN_ID = "cat@cat";
	/**
	 * The marketplace name in CAT's marketplace metadata.
	 */
	private static final String MARKETPLACE_NAME = "cat";
	/**
	 * Matches Codex's answers that CAT's plugin or marketplace is not registered.
	 */
	private static final Pattern NOT_REGISTERED = Pattern.compile("(?i)\\bnot (installed|configured|found)\\b");
	/**
	 * Bounds a plugin manifest or marketplace metadata file to 1 MiB.
	 */
	private static final int MAX_METADATA_BYTES = 1024 * 1024;
	/**
	 * Reads the plugin manifest's version.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Creates the integration used by service discovery.
	 */
	public CodexIntegration()
	{
	}

	@Override
	public String harness()
	{
		return CodexActivation.HARNESS;
	}

	@Override
	public String programName()
	{
		return "codex";
	}

	/**
	 * Returns {@code HOME} and {@code USERPROFILE} as given, and {@code CODEX_HOME} unless {@code home} is Codex's
	 * default, {@code <user home>/.codex}.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 */
	@Override
	public Map<String, String> programEnvironment(Path home, Map<String, String> environment)
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(environment, "environment").isNotNull();
		Map<String, String> variables = new HashMap<>();
		for (String variable : List.of("HOME", "USERPROFILE"))
		{
			String value = environment.get(variable);
			if (value != null)
				variables.put(variable, value);
		}
		String userHomeName = "HOME";
		if (isWindows())
			userHomeName = "USERPROFILE";
		Path userHome = absolute(environment.get(userHomeName));
		if (userHome == null || !home.equals(userHome.resolve(".codex")))
			variables.put("CODEX_HOME", home.toString());
		return variables;
	}

	/**
	 * Fails unless {@code codex --version} exited with status 0.
	 */
	@Override
	public void requireIdentity(CommandResult version) throws IOException
	{
		requireThat(version, "version").isNotNull();
		if (version.exitCode() != 0)
		{
			throw new IOException("The Codex command-line program failed with status " + version.exitCode() +
				"; reinstall Codex and rerun this command. Output: " + version.output().strip());
		}
	}

	/**
	 * Refuses a {@code rules} directory or {@code rules/cat-updater.rules} file that is a symbolic link or not of its
	 * expected kind, and a rules file whose content is not exactly what CAT writes for the launcher that its first
	 * rule names.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 */
	@Override
	public void validateConfiguration(Path home) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		Path file = home.resolve(RULES_FILE);
		Path directory = file.getParent();
		if (Files.isSymbolicLink(home) || Files.isSymbolicLink(directory))
			throw new IOException("The Codex rules directory must not be a symbolic link: " + directory);
		if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) &&
			!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
		{
			throw new IOException("The Codex rules path is not a directory: " + directory);
		}
		if (Files.isSymbolicLink(file))
			throw new IOException("CAT's Codex rules file must not be a symbolic link: " + file);
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return;
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("CAT's Codex rules path is not a regular file: " + file);
		if (!InstallerRules.isWrittenByCat(read(file), isWindows()))
		{
			throw new IOException("CAT did not write " + file + "; move it out of the rules directory and rerun " +
				"this command.");
		}
	}

	/**
	 * Writes the marketplace metadata from the activated release, then runs {@code codex plugin marketplace add}
	 * and {@code codex plugin add cat@cat}, and requires the cache copy that Codex loads to equal the marketplace
	 * source.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if the activated release lacks its marketplace metadata, Codex fails, or the
	 *                                  cache copy differs from the marketplace source
	 */
	@Override
	public void register(Path home, HarnessCli cli, FileReplacer replacer) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(cli, "cli").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		Path plugin = home.resolve(MARKETPLACE_PLUGIN);
		requireNoLinks(home, MARKETPLACE_METADATA);
		requireNoLinks(home, MARKETPLACE_PLUGIN.resolve(RELEASE_METADATA));
		if (!Files.isDirectory(plugin, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("CAT's marketplace source is not activated: " + plugin);
		writeMetadata(plugin.resolve(RELEASE_METADATA), home.resolve(MARKETPLACE_METADATA), replacer);

		run(cli, List.of("plugin", "marketplace", "add", home.resolve(MARKETPLACE_ROOT).toString()));
		run(cli, List.of("plugin", "add", PLUGIN_ID));
		requireCacheMatches(home, plugin);
	}

	/**
	 * Runs {@code codex plugin remove cat@cat}, then {@code codex plugin marketplace remove cat}. An exit status other
	 * than 0 is success only when Codex's output says that the plugin or marketplace is not installed, configured or
	 * found.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 */
	@Override
	public void unregister(Path home, HarnessCli cli) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(cli, "cli").isNotNull();
		runUnlessAbsent(cli, List.of("plugin", "remove", PLUGIN_ID));
		runUnlessAbsent(cli, List.of("plugin", "marketplace", "remove", MARKETPLACE_NAME));
	}

	/**
	 * Writes {@code rules/cat-updater.rules} unless it already holds exactly the rules for {@code stableLauncher}.
	 *
	 * @return {@code {"rules/cat-updater.rules#": [<file content>]}} when this call wrote the file, otherwise an empty
	 * 	map
	 * @throws IllegalArgumentException if any of the paths are relative or not normalized
	 * @throws IOException              if {@link #validateConfiguration} refuses the configuration or the file
	 *                                  cannot be written
	 */
	@Override
	public Map<String, List<String>> installPermissions(Path home, Path stableLauncher, FileReplacer replacer)
		throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(stableLauncher, "stableLauncher").isAbsolute().isEqualTo(stableLauncher.normalize());
		requireThat(replacer, "replacer").isNotNull();
		validateConfiguration(home);
		String content = InstallerRules.render(stableLauncher, isWindows());
		Path file = home.resolve(RULES_FILE);
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && read(file).equals(content))
			return Map.of();

		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), ".cat-updater-", ".tmp");
		try
		{
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			replacer.replace(temporary, file);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
		return Map.of(RULES_KEY, List.of(content));
	}

	/**
	 * Returns the content of {@code rules/cat-updater.rules} that {@link #installPermissions} writes.
	 *
	 * @return {@code {"rules/cat-updater.rules#": [<file content>]}}
	 * @throws IllegalArgumentException if {@code stableLauncher} is relative or not normalized
	 */
	@Override
	public Map<String, List<String>> permissions(Path stableLauncher)
	{
		return Map.of(RULES_KEY, List.of(InstallerRules.render(stableLauncher, isWindows())));
	}

	/**
	 * Returns no entries: earlier builds asked the user to put their rule in {@code rules/cat-updater.rules}, which
	 * {@link #installPermissions} replaces as a whole.
	 *
	 * @param legacyLauncher the absolute path of the shared installation's stable launcher
	 * @return an empty map
	 * @throws NullPointerException if {@code legacyLauncher} is null
	 */
	@Override
	public Map<String, List<String>> legacyPermissions(Path legacyLauncher)
	{
		requireThat(legacyLauncher, "legacyLauncher").isNotNull();
		return Map.of();
	}

	/**
	 * Deletes {@code rules/cat-updater.rules} when its content equals a recorded content. Leaves a changed file, a
	 * file reached through a symbolic link, and every other entry in place.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 */
	@Override
	public void removePermissions(Path home, Map<String, List<String>> added, FileReplacer replacer)
		throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(added, "added").isNotNull();
		List<String> contents = added.getOrDefault(RULES_KEY, List.of());
		Path file = home.resolve(RULES_FILE);
		if (contents.isEmpty() || Files.isSymbolicLink(home) || Files.isSymbolicLink(file.getParent()) ||
			!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
		{
			return;
		}
		if (contents.contains(read(file)))
			Files.delete(file);
	}

	/**
	 * Reports whether the installer runs on Windows, whose launchers and program lookup differ.
	 *
	 * @return {@code true} on Windows
	 */
	private static boolean isWindows()
	{
		return System.getProperty("os.name").startsWith("Windows");
	}

	/**
	 * Parses an absolute path from an environment value.
	 *
	 * @param value the value, or null
	 * @return the normalized absolute path, or null when the value is absent, empty, relative or invalid
	 */
	private static Path absolute(String value)
	{
		if (value == null || value.isEmpty())
			return null;
		try
		{
			Path path = Path.of(value);
			if (!path.isAbsolute())
				return null;
			return path.normalize();
		}
		catch (InvalidPathException _)
		{
			return null;
		}
	}

	/**
	 * Refuses a symbolic link from the home through a relative path's final component.
	 *
	 * @param home     the harness home
	 * @param relative the path within the home
	 * @throws IOException if an existing component is a symbolic link
	 */
	private static void requireNoLinks(Path home, Path relative) throws IOException
	{
		Path current = home;
		if (Files.isSymbolicLink(current))
			throw new IOException("A Codex path is a symbolic link: " + current);
		for (Path name : relative)
		{
			current = current.resolve(name);
			if (Files.isSymbolicLink(current))
				throw new IOException("A Codex path is a symbolic link: " + current);
		}
	}

	/**
	 * Copies the release's marketplace metadata to where Codex reads it, unless it is already there.
	 *
	 * @param source      the release's metadata file
	 * @param destination the marketplace root's metadata file
	 * @param replacer    replaces the destination, retrying while Windows refuses the replacement
	 * @throws IOException if the source is absent or not a regular file, or the copy fails
	 */
	private static void writeMetadata(Path source, Path destination, FileReplacer replacer) throws IOException
	{
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The activated release lacks its Codex marketplace metadata: " + source);
		if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(source, destination) == -1)
			return;
		Files.createDirectories(destination.getParent());
		Path temporary = Files.createTempFile(destination.getParent(), ".marketplace-", ".tmp");
		try
		{
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			replacer.replace(temporary, destination);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Runs one Codex command and requires it to succeed.
	 *
	 * @param cli       runs the recorded Codex program
	 * @param arguments the arguments
	 * @throws IOException if the command cannot run or exits with a status other than 0
	 */
	private static void run(HarnessCli cli, List<String> arguments) throws IOException
	{
		CommandResult result = cli.run(arguments);
		if (result.exitCode() != 0)
			throw failure(arguments, result);
	}

	/**
	 * Runs one Codex removal command, accepting Codex's answer that there is nothing to remove.
	 *
	 * @param cli       runs the recorded Codex program
	 * @param arguments the arguments
	 * @throws IOException if the command cannot run or fails for another reason
	 */
	private static void runUnlessAbsent(HarnessCli cli, List<String> arguments) throws IOException
	{
		CommandResult result = cli.run(arguments);
		if (result.exitCode() != 0 && !NOT_REGISTERED.matcher(result.output()).find())
			throw failure(arguments, result);
	}

	/**
	 * Describes a failed Codex command.
	 *
	 * @param arguments the arguments after the program
	 * @param result    the command's outcome
	 * @return the exception
	 */
	private static IOException failure(List<String> arguments, CommandResult result)
	{
		return new IOException("\"codex " + String.join(" ", arguments) + "\" failed with status " +
			result.exitCode() + ": " + result.output().strip());
	}

	/**
	 * Requires Codex to load exactly the validated marketplace source.
	 *
	 * @param home   the harness home
	 * @param plugin the activated marketplace source
	 * @throws IOException if Codex selects another version or its cache copy differs from the source
	 */
	private static void requireCacheMatches(Path home, Path plugin) throws IOException
	{
		String version = readVersion(plugin.resolve(PLUGIN_MANIFEST));
		InstalledState state = new CodexActivation().installed(home);
		if (!state.equals(new InstalledState(Optional.of(version), true)))
		{
			throw new IOException("After \"codex plugin add\", Codex does not load CAT " + version + " consistently: " +
				home.resolve(CACHE_VERSIONS));
		}
		Path cache = home.resolve(CACHE_VERSIONS).resolve(version);
		Optional<String> difference = difference(plugin, cache);
		if (difference.isPresent())
		{
			throw new IOException("Codex's plugin cache differs from the validated release at " +
				difference.get() + ": " + cache);
		}
	}

	/**
	 * Reads a plugin manifest's version.
	 *
	 * @param manifest the plugin manifest
	 * @return the version
	 * @throws IOException if the manifest is absent, exceeds 1 MiB, is invalid JSON or lacks a string version
	 */
	private static String readVersion(Path manifest) throws IOException
	{
		byte[] bytes;
		try (InputStream input = Files.newInputStream(manifest, LinkOption.NOFOLLOW_LINKS))
		{
			bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
		}
		if (bytes.length > MAX_METADATA_BYTES)
			throw new IOException("The plugin manifest exceeds 1 MiB: " + manifest);
		try
		{
			JsonNode version = MAPPER.readTree(bytes).path("version");
			if (!version.isString())
				throw new IOException("The plugin manifest must declare a string version: " + manifest);
			return version.stringValue();
		}
		catch (JacksonException e)
		{
			throw new IOException("The plugin manifest is not valid JSON: " + manifest, e);
		}
	}

	/**
	 * Finds the first difference between two directory trees: a path present in only one, a symbolic link, a
	 * different kind of entry, different bytes or a different executable permission.
	 *
	 * @param expected the validated tree
	 * @param actual   the tree to check
	 * @return the relative path of the first difference, or empty when the trees are equal
	 * @throws IOException if a tree cannot be read
	 */
	private static Optional<String> difference(Path expected, Path actual) throws IOException
	{
		Map<String, Path> expectedEntries = entries(expected);
		Map<String, Path> actualEntries = entries(actual);
		for (Map.Entry<String, Path> entry : expectedEntries.entrySet())
		{
			Path other = actualEntries.get(entry.getKey());
			if (other == null || !sameEntry(entry.getValue(), other))
				return Optional.of(entry.getKey());
		}
		for (String name : actualEntries.keySet())
		{
			if (!expectedEntries.containsKey(name))
				return Optional.of(name);
		}
		return Optional.empty();
	}

	/**
	 * Lists a tree's entries by relative path, without following symbolic links.
	 *
	 * @param root the tree's root directory
	 * @return the entries in sorted order, keyed by their path relative to the root written with {@code /}
	 * @throws IOException if the tree cannot be read
	 */
	private static Map<String, Path> entries(Path root) throws IOException
	{
		Map<String, Path> entries = new TreeMap<>();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			return entries;
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				if (!path.equals(root))
					entries.put(root.relativize(path).toString().replace(File.separatorChar, '/'), path);
			}
		}
		return entries;
	}

	/**
	 * Compares two entries that have the same relative path.
	 *
	 * @param expected the validated entry
	 * @param actual   the entry to check
	 * @return {@code true} if both are directories, or both are regular files with equal bytes and executable
	 * 	permission
	 * @throws IOException if a file cannot be read
	 */
	private static boolean sameEntry(Path expected, Path actual) throws IOException
	{
		if (Files.isSymbolicLink(expected) || Files.isSymbolicLink(actual))
			return false;
		if (Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS))
			return Files.isDirectory(actual, LinkOption.NOFOLLOW_LINKS);
		if (!Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS) ||
			!Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS))
		{
			return false;
		}
		return Files.isExecutable(expected) == Files.isExecutable(actual) && Files.mismatch(expected, actual) == -1;
	}

	/**
	 * Reads a small configuration file.
	 *
	 * @param file the file
	 * @return its UTF-8 content
	 * @throws IOException if the file exceeds 1 MiB or cannot be read
	 */
	private static String read(Path file) throws IOException
	{
		if (Files.size(file) > MAX_METADATA_BYTES)
			throw new IOException("The file exceeds 1 MiB: " + file);
		return Files.readString(file, StandardCharsets.UTF_8);
	}
}
