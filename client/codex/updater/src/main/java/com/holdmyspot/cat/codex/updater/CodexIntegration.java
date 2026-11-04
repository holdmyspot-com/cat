package com.holdmyspot.cat.codex.updater;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integrates the updater with Codex's command-line program and execution policy.
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

	/**
	 * Finds {@code codex} on the setup PATH and confirms that {@code codex --version} succeeds.
	 * <p>
	 * The recorded program is the path of the first match as PATH names it, without resolving symbolic links, so a
	 * program that updates itself by re-pointing a link keeps working. Discovery refuses the match when it or its
	 * real path lies in an excluded location. On Windows, a match is {@code codex} followed by an
	 * extension from {@code PATHEXT}, and a {@code .cmd} or {@code .bat} match runs through
	 * {@code %SystemRoot%\System32\cmd.exe /d /s /c}. The recorded PATH keeps only absolute entries outside the setup
	 * working directory, the system temporary directory, the harness home, {@code <HOME>/.cat} and the updater's data
	 * directory. The recorded environment holds {@code HOME} and {@code USERPROFILE} as given, that PATH,
	 * {@code CODEX_HOME} only when {@code home} is not {@code <HOME>/.codex}, and on Windows {@code SystemRoot} and
	 * {@code NoDefaultCurrentDirectoryInExePath=1}.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 */
	@Override
	public HarnessCommand discover(Path home, Map<String, String> environment) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(environment, "environment").isNotNull();
		boolean windows = isWindows();
		Map<String, String> lookup = environment;
		if (windows)
		{
			lookup = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			lookup.putAll(environment);
		}

		String userHomeName = "HOME";
		if (windows)
			userHomeName = "USERPROFILE";
		Path userHome = absolute(lookup.get(userHomeName));
		List<Path> excluded = excludedLocations(lookup, home, userHome, windows);
		List<Path> searchPath = searchPath(lookup, excluded);
		Path program = find(searchPath, lookup, windows);
		if (isUnder(program, excluded))
		{
			throw new IOException("The Codex command-line program " + program + " links into the working " +
				"directory, the temporary directory, the Codex home, ~/.cat or CAT's updater data; put a \"codex\" " +
				"outside them first on PATH and rerun this command.");
		}

		List<String> command = new ArrayList<>();
		String name = program.getFileName().toString().toLowerCase(Locale.ROOT);
		if (windows && (name.endsWith(".cmd") || name.endsWith(".bat")))
		{
			Path systemRoot = absolute(lookup.get("SystemRoot"));
			if (systemRoot == null)
				throw new IOException("SystemRoot must name an absolute directory to run " + program);
			command.addAll(List.of(systemRoot.resolve("System32").resolve("cmd.exe").toString(), "/d", "/s", "/c"));
		}
		command.add(program.toString());

		Map<String, String> recorded = new HashMap<>();
		for (String variable : List.of("HOME", "USERPROFILE"))
		{
			String value = lookup.get(variable);
			if (value != null)
				recorded.put(variable, value);
		}
		List<String> entries = new ArrayList<>();
		for (Path entry : searchPath)
			entries.add(entry.toString());
		recorded.put("PATH", String.join(File.pathSeparator, entries));
		if (userHome == null || !home.equals(userHome.resolve(".codex")))
			recorded.put("CODEX_HOME", home.toString());
		if (windows)
		{
			recorded.put("SystemRoot", lookup.get("SystemRoot"));
			recorded.put("NoDefaultCurrentDirectoryInExePath", "1");
		}
		CodexProgram.requireVersion(command, recorded, home);
		return new HarnessCommand(command, recorded);
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
		if (!UpdaterRules.isWrittenByCat(read(file), isWindows()))
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
		String content = UpdaterRules.render(stableLauncher, isWindows());
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
	 * Reports whether the updater runs on Windows, whose launchers and program lookup differ.
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
	 * Returns the locations that discovery neither searches nor accepts a program from: the setup working directory,
	 * the system temporary directory, the harness home, {@code <HOME>/.cat} and the updater's data directory.
	 *
	 * @param environment the setup environment
	 * @param home        the harness home
	 * @param userHome    the user's home directory, or null
	 * @param windows     {@code true} on Windows
	 * @return the absolute, normalized locations
	 */
	private static List<Path> excludedLocations(Map<String, String> environment, Path home, Path userHome,
		boolean windows)
	{
		List<Path> excluded = new ArrayList<>();
		excluded.add(Path.of("").toAbsolutePath().normalize());
		excluded.add(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize());
		excluded.add(home);
		if (userHome != null)
			excluded.add(userHome.resolve(".cat"));
		Path data = updaterData(environment, userHome, windows);
		if (data != null)
			excluded.add(data);
		return excluded;
	}

	/**
	 * Returns the PATH entries that discovery may search and record.
	 *
	 * @param environment the setup environment
	 * @param excluded    the locations that discovery excludes
	 * @return the absolute, normalized entries in PATH order, without excluded locations or repeats
	 */
	private static List<Path> searchPath(Map<String, String> environment, List<Path> excluded)
	{
		List<Path> entries = new ArrayList<>();
		String value = environment.getOrDefault("PATH", "");
		for (String text : value.split(Pattern.quote(File.pathSeparator), -1))
		{
			Path entry = absolute(text);
			if (entry == null || entries.contains(entry) || isUnder(entry, excluded))
				continue;
			entries.add(entry);
		}
		return entries;
	}

	/**
	 * Returns the updater's data directory as setup selects it from the same environment.
	 *
	 * @param environment the setup environment
	 * @param userHome    the user's home directory, or null
	 * @param windows     {@code true} on Windows
	 * @return the directory, or null when the environment does not determine one
	 */
	private static Path updaterData(Map<String, String> environment, Path userHome, boolean windows)
	{
		Path base;
		if (windows)
			base = absolute(environment.get("LOCALAPPDATA"));
		else
		{
			base = absolute(environment.get("XDG_DATA_HOME"));
			if (base == null && userHome != null)
				base = userHome.resolve(".local/share");
		}
		if (base == null)
			return null;
		return base.resolve("cat-updater");
	}

	/**
	 * Reports whether a PATH entry or program lies in an excluded location, comparing both the written and the real
	 * paths.
	 *
	 * @param entry    the absolute, normalized entry or program
	 * @param excluded the excluded locations
	 * @return {@code true} if the entry is excluded
	 */
	private static boolean isUnder(Path entry, List<Path> excluded)
	{
		List<Path> forms = new ArrayList<>(List.of(entry));
		realPath(entry).ifPresent(forms::add);
		for (Path root : excluded)
		{
			List<Path> roots = new ArrayList<>(List.of(root));
			realPath(root).ifPresent(roots::add);
			for (Path form : forms)
			{
				for (Path candidate : roots)
				{
					if (form.startsWith(candidate))
						return true;
				}
			}
		}
		return false;
	}

	/**
	 * Resolves a path's symbolic links.
	 *
	 * @param path the path
	 * @return the real path, or empty when the path does not exist or cannot be resolved
	 */
	private static Optional<Path> realPath(Path path)
	{
		try
		{
			return Optional.of(path.toRealPath());
		}
		catch (IOException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Finds the first Codex program in the search path.
	 *
	 * @param searchPath  the PATH entries to search
	 * @param environment the setup environment
	 * @param windows     {@code true} on Windows
	 * @return the program's path within its PATH entry, without resolving symbolic links
	 * @throws IOException if no entry holds an executable Codex program
	 */
	private static Path find(List<Path> searchPath, Map<String, String> environment, boolean windows)
		throws IOException
	{
		List<String> names = new ArrayList<>();
		if (windows)
		{
			String extensions = environment.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
			for (String extension : extensions.split(";"))
			{
				if (!extension.isEmpty())
					names.add("codex" + extension.toLowerCase(Locale.ROOT));
			}
		}
		else
			names.add("codex");
		for (Path entry : searchPath)
		{
			for (String name : names)
			{
				Path candidate = entry.resolve(name);
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
					return candidate;
			}
		}
		throw new IOException("The Codex command-line program \"codex\" is not on PATH; install Codex, make sure " +
			"\"codex\" runs in this shell, and rerun this command.");
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
