package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
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
import java.nio.file.attribute.PosixFileAttributeView;
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
 * copies the marketplace source into the version folder that the plugin's version names, and deletes the folder of
 * the version it replaces, so registration then requires the new folder to equal the validated marketplace source file
 * for file. Codex's {@code plugin remove} keeps the plugin data directory.
 * <p>
 * Permissions live in the CAT-owned file {@code rules/cat-installer.rules}, which Codex reads beside the user's rules.
 * The only sandbox entry is one writable root for CAT's capacity locks in {@code config.toml}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexIntegration implements HarnessIntegration
{
	/**
	 * The marketplace metadata that Codex reads, relative to the marketplace root.
	 */
	private static final Path MARKETPLACE_METADATA = Path.of(".agents/plugins/marketplace.json");
	/**
	 * The release's copy of the marketplace metadata, relative to the plugin directory.
	 */
	private static final String RELEASE_METADATA = "install/codex-marketplace.json";
	/**
	 * The plugin manifest inside the plugin directory.
	 */
	private static final String PLUGIN_MANIFEST = ".codex-plugin/plugin.json";
	/**
	 * The CAT-owned execution-policy file.
	 */
	private static final Path RULES_FILE = Path.of("rules/cat-installer.rules");
	/**
	 * The permission key of the CAT-owned execution-policy file: its path relative to the home and an empty setting.
	 */
	private static final String RULES_KEY = "rules/cat-installer.rules#";
	/**
	 * The execution-policy file that CAT wrote before its installer was called the installer.
	 */
	private static final Path UPDATER_RULES_FILE = Path.of("rules/cat-updater.rules");
	/**
	 * Codex's configuration file.
	 */
	private static final Path CONFIGURATION = Path.of("config.toml");
	/**
	 * The sandbox key of the writable root that CAT adds to Codex's configuration.
	 */
	private static final String WRITABLE_ROOTS_KEY = "config.toml#sandbox_workspace_write.writable_roots";
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
	public HarnessInstallation installation(Path home)
	{
		return new CodexInstallation(home);
	}

	@Override
	public HarnessInstallation installation(Map<String, String> environment, Path userHome)
	{
		return CodexInstallation.fromEnvironment(environment, userHome);
	}

	/**
	 * Returns the plugin folder of CAT's marketplace, which {@code codex plugin add} copies into the version folder.
	 */
	@Override
	public Path marketplaceFolder(Path home, PluginVersion version)
	{
		requireThat(version, "version").isNotNull();
		return new CodexInstallation(home).marketplacePlugin();
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
	 * Refuses a {@code rules} directory or {@code rules/cat-installer.rules} file that is a symbolic link or not of its
	 * expected kind, a rules file whose content is not exactly what CAT writes for the launcher that its first rule
	 * names, and a {@code config.toml} that is a symbolic link, not a regular file, not valid TOML, or in a form whose
	 * writable roots CAT does not edit.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 */
	@Override
	public void validateConfiguration(Path home) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		validateRules(home);
		validateWritableRoots(home);
	}

	/**
	 * Refuses a {@code rules} directory or {@code rules/cat-installer.rules} file that is a symbolic link or not of its
	 * expected kind, and a rules file whose content is not exactly what CAT writes.
	 *
	 * @param home the Codex home
	 * @throws IOException if the rules cannot be replaced safely
	 */
	private static void validateRules(Path home) throws IOException
	{
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
	 * Refuses a {@code config.toml} whose writable roots CAT cannot edit.
	 *
	 * @param home the Codex home
	 * @throws IOException if the file is a symbolic link, not a regular file, not valid TOML, or in a form whose
	 *                     writable roots CAT does not edit
	 */
	private static void validateWritableRoots(Path home) throws IOException
	{
		Path file = home.resolve(CONFIGURATION);
		if (Files.isSymbolicLink(file))
			throw new IOException("Codex's configuration must not be a symbolic link: " + file);
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return;
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Codex's configuration is not a regular file: " + file);
		WritableRoots.requireEditable(read(file), file);
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
		CodexInstallation installation = new CodexInstallation(home);
		Path plugin = installation.marketplacePlugin();
		Path metadata = installation.marketplaceRoot().resolve(MARKETPLACE_METADATA);
		requireNoLinks(home, home.relativize(metadata));
		requireNoLinks(home, home.relativize(plugin.resolve(RELEASE_METADATA)));
		if (!Files.isDirectory(plugin, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("CAT's marketplace source is not activated: " + plugin);
		writeMetadata(plugin.resolve(RELEASE_METADATA), metadata, replacer);

		run(cli, List.of("plugin", "marketplace", "add", installation.marketplaceRoot().toString()));
		run(cli, List.of("plugin", "add", PLUGIN_ID));
		requireCacheMatches(installation);
	}

	/**
	 * Runs {@code codex plugin remove cat@cat}, then {@code codex plugin marketplace remove cat}. An exit status other
	 * than 0 is success only when Codex's output says that the plugin or marketplace is not installed, configured or
	 * found. Codex's {@code plugin remove} keeps the plugin data directory and has no option to delete it, so this
	 * call runs the same commands whatever {@code keepData} says, and the installer deletes the data itself.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 */
	@Override
	public void unregister(Path home, HarnessCli cli, boolean keepData) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(cli, "cli").isNotNull();
		runUnlessAbsent(cli, List.of("plugin", "remove", PLUGIN_ID));
		runUnlessAbsent(cli, List.of("plugin", "marketplace", "remove", MARKETPLACE_NAME));
	}

	/**
	 * Writes {@code rules/cat-installer.rules} unless it already holds exactly the rules for {@code installer}, and
	 * deletes a {@code rules/cat-updater.rules} file whose content is exactly what CAT wrote there before its installer
	 * was called the installer.
	 *
	 * @return {@code {"rules/cat-installer.rules#": [<file content>]}} when this call wrote the file, otherwise an
	 * 	empty map
	 * @throws IllegalArgumentException if any of the paths are relative or not normalized
	 * @throws IOException              if {@link #validateConfiguration} refuses the configuration or the file
	 *                                  cannot be written
	 */
	@Override
	public Map<String, List<String>> installPermissions(Path home, Path installer, FileReplacer replacer)
		throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(installer, "installer").isAbsolute().isEqualTo(installer.normalize());
		requireThat(replacer, "replacer").isNotNull();
		validateConfiguration(home);
		deleteUpdaterLeftover(home);
		String content = InstallerRules.render(installer, isWindows());
		Path file = home.resolve(RULES_FILE);
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && read(file).equals(content))
			return Map.of();

		write(file, content, replacer);
		return Map.of(RULES_KEY, List.of(content));
	}

	/**
	 * Deletes the {@code rules/cat-updater.rules} file that CAT wrote before its installer was called the installer,
	 * whose launcher no longer exists. Leaves a file that CAT did not write, or that is reached through a symbolic
	 * link, in place.
	 *
	 * @param home the Codex home
	 * @throws IOException if the file cannot be read or deleted
	 */
	private static void deleteUpdaterLeftover(Path home) throws IOException
	{
		Path file = home.resolve(UPDATER_RULES_FILE);
		if (Files.isSymbolicLink(file.getParent()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return;
		if (InstallerRules.isUpdaterLeftover(read(file), isWindows()))
			Files.delete(file);
	}

	/**
	 * Returns the content of {@code rules/cat-installer.rules} that {@link #installPermissions} writes.
	 *
	 * @return {@code {"rules/cat-installer.rules#": [<file content>]}}
	 * @throws IllegalArgumentException if {@code installer} is relative or not normalized
	 */
	@Override
	public Map<String, List<String>> permissions(Path installer)
	{
		return Map.of(RULES_KEY, List.of(InstallerRules.render(installer, isWindows())));
	}

	/**
	 * Adds {@code catLocks} to {@code sandbox_workspace_write.writable_roots} in {@code config.toml}, creating the file
	 * or the table when it is absent, unless the writable roots already hold it. Codex's own sandbox already keeps
	 * sandboxed commands from writing the other harness's CAT installation, so this call adds nothing for it.
	 *
	 * @param home     the absolute Codex home
	 * @param other    the other harness's CAT installation
	 * @param catLocks the capacity lock directory
	 * @param replacer replaces Codex's configuration
	 * @return {@code {"config.toml#sandbox_workspace_write.writable_roots": [<catLocks>]}} when this call added the
	 * 	writable root, otherwise an empty map
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative or not normalized
	 * @throws IOException              if {@code config.toml} is a symbolic link, not a regular file, not valid TOML or
	 *                                  in a form whose writable roots CAT does not edit, or cannot be written
	 */
	@Override
	public Map<String, List<String>> installSandboxEntries(Path home, HarnessInstallation other, Path catLocks,
		FileReplacer replacer) throws IOException
	{
		Map<String, List<String>> entries = sandboxEntries(home, other, catLocks);
		requireThat(replacer, "replacer").isNotNull();
		validateWritableRoots(home);

		Path file = home.resolve(CONFIGURATION);
		boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
		String content = "";
		if (exists)
			content = read(file);
		String edited = WritableRoots.add(content, catLocks.toString(), file);
		if (exists && edited.equals(content))
			return Map.of();
		write(file, edited, replacer);
		return entries;
	}

	/**
	 * Returns the writable root for {@code catLocks} that {@link #installSandboxEntries} adds.
	 *
	 * @param home     the absolute Codex home
	 * @param other    the other harness's CAT installation
	 * @param catLocks the capacity lock directory
	 * @return {@code {"config.toml#sandbox_workspace_write.writable_roots": [<catLocks>]}}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative or not normalized
	 */
	@Override
	public Map<String, List<String>> sandboxEntries(Path home, HarnessInstallation other, Path catLocks)
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(other, "other").isNotNull();
		requireThat(catLocks, "catLocks").isAbsolute().isEqualTo(catLocks.normalize());
		return Map.of(WRITABLE_ROOTS_KEY, List.of(catLocks.toString()));
	}

	/**
	 * Deletes {@code rules/cat-installer.rules} when its content equals a recorded content, and removes each recorded
	 * writable root from {@code config.toml}. Leaves a changed rules file, a file reached through a symbolic link, the
	 * writable roots' array and table, and every other entry in place.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if a file cannot be read or written, or {@code config.toml} holds a recorded
	 *                                  writable root in a form that CAT does not edit
	 */
	@Override
	public void removePermissions(Path home, Map<String, List<String>> added, FileReplacer replacer)
		throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(added, "added").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		removeRules(home, added.getOrDefault(RULES_KEY, List.of()));
		removeWritableRoots(home, added.getOrDefault(WRITABLE_ROOTS_KEY, List.of()), replacer);
	}

	/**
	 * Deletes {@code rules/cat-installer.rules} when its content equals a recorded content.
	 *
	 * @param home     the Codex home
	 * @param contents the recorded contents
	 * @throws IOException if the file cannot be read or deleted
	 */
	private static void removeRules(Path home, List<String> contents) throws IOException
	{
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
	 * Removes recorded writable roots from {@code config.toml}.
	 *
	 * @param home     the Codex home
	 * @param roots    the recorded writable roots
	 * @param replacer replaces Codex's configuration
	 * @throws IOException if the file cannot be read or written, or holds a recorded writable root in a form that CAT
	 *                     does not edit
	 */
	private static void removeWritableRoots(Path home, List<String> roots, FileReplacer replacer) throws IOException
	{
		Path file = home.resolve(CONFIGURATION);
		if (roots.isEmpty() || Files.isSymbolicLink(home) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return;
		String content = read(file);
		String edited = content;
		for (String root : roots)
			edited = WritableRoots.remove(edited, root, file);
		if (!edited.equals(content))
			write(file, edited, replacer);
	}

	/**
	 * Replaces a configuration file with new content, keeping the existing file's POSIX permissions.
	 *
	 * @param file     the file
	 * @param content  the new content
	 * @param replacer replaces the file, retrying while Windows refuses the replacement
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content, FileReplacer replacer) throws IOException
	{
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), ".cat-installer-", ".tmp");
		try
		{
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
				Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class))
			{
				Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
			}
			replacer.replace(temporary, file);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
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
	 * Requires Codex to load exactly the validated marketplace source from the version folder that its version names.
	 *
	 * @param installation where Codex keeps CAT's installation
	 * @throws IOException if Codex selects another version or its cache copy differs from the source
	 */
	private static void requireCacheMatches(CodexInstallation installation) throws IOException
	{
		Path plugin = installation.marketplacePlugin();
		String version = readVersion(plugin.resolve(PLUGIN_MANIFEST));
		InstalledState state = new CodexActivation().installed(installation.home());
		if (!state.equals(new InstalledState(Optional.of(version), true)))
		{
			throw new IOException("After \"codex plugin add\", Codex does not load CAT " + version + " consistently: " +
				installation.versionFolders());
		}
		Path cache = installation.versionFolders().resolve(version);
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
