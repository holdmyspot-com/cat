package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Integrates the installer with Codex's command-line program and execution policy.
 * <p>
 * Registration runs {@code codex plugin marketplace add} and {@code codex plugin add}. Codex's {@code plugin add}
 * copies the marketplace source into the version folder that the plugin's version names, and deletes the folder of
 * the version it replaces, so registration then requires the new folder to equal the validated marketplace source file
 * for file. Codex's {@code plugin remove} keeps the plugin data directory.
 * <p>
 * Permissions live in the CAT-owned file {@code rules/cat-installer.rules}, which Codex reads beside the user's rules.
 * The only sandbox entry lets sandboxed commands write CAT's capacity locks. In {@code config.toml} it is a writable
 * root, or, when the file selects a permission profile that it defines, a write entry in that profile, because Codex
 * then ignores the writable roots. Other projects that use the profile get the entry too. When the file selects a
 * built-in profile that limits which files commands may write, to which CAT cannot add an entry, install asks the user
 * for permission to select a profile of CAT's that extends the built-in one and holds the entry.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexIntegration implements HarnessIntegration
{
	/**
	 * The release's copy of the marketplace metadata, relative to the plugin directory.
	 */
	private static final String RELEASE_METADATA = "install/codex-marketplace.json";
	/**
	 * The plugin manifest inside the plugin directory.
	 */
	private static final String PLUGIN_MANIFEST = ".codex-plugin/plugin.json";
	/**
	 * The CAT-owned execution-policy file, in Codex's rules directory.
	 */
	private static final String RULES_FILE = "cat-installer.rules";
	/**
	 * The permission key of the CAT-owned execution-policy file: its path relative to the home and an empty setting.
	 */
	private static final String RULES_KEY = "rules/cat-installer.rules#";
	/**
	 * The execution-policy file, in Codex's rules directory, in which CAT's installation guide told users to write the
	 * rules that let CAT's runners run outside the sandbox.
	 */
	private static final String RUNNER_RULES_FILE = "cat.rules";
	/**
	 * The execution-policy file, in Codex's rules directory, that CAT wrote before its installer was called the
	 * installer.
	 */
	private static final String UPDATER_RULES_FILE = "cat-updater.rules";
	/**
	 * The sandbox key of the writable root that CAT adds to Codex's configuration.
	 */
	private static final String WRITABLE_ROOTS_KEY = "config.toml#sandbox_workspace_write.writable_roots";
	/**
	 * The start of a sandbox key in Codex's configuration, which the setting's dotted TOML key follows.
	 */
	private static final String CONFIGURATION_KEY = "config.toml#";
	/**
	 * The record key of the built-in permission profile that {@code default_permissions} selected before CAT selected
	 * its own profile in its place.
	 */
	private static final String SELECTION_KEY = CONFIGURATION_KEY + BuiltInProfile.SELECTION;
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

	/**
	 * Codex runs the version folder with the greatest name, so a folder whose name is greater than the registered
	 * version's runs instead of it.
	 */
	@Override
	public boolean runsInstead(PluginVersion candidate, PluginVersion active)
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(active, "active").isNotNull();
		return CodexVersions.ORDER.compare(candidate.toString(), active.toString()) > 0;
	}

	@Override
	public String programName()
	{
		return "codex";
	}

	/**
	 * Returns {@code HOME} and {@code USERPROFILE} as given, and {@code CODEX_HOME} unless Codex finds {@code home}
	 * from them alone. Codex cannot find it from a relative user home, which depends on the working directory that
	 * Codex later runs in.
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
		if (!home.equals(defaultHome(variables)))
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
	 * writable roots, or whose selected permission profile, CAT does not edit.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 */
	@Override
	public void validateConfiguration(Path home) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		validateRules(home);
		validateSandboxEntry(home);
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
		Path file = new CodexInstallation(home).rules().resolve(RULES_FILE);
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
	 * Refuses a {@code config.toml} to which CAT cannot add its sandbox entry: the selected permission profile when
	 * the file defines it, otherwise the writable roots.
	 *
	 * @param home the Codex home
	 * @throws IOException if the file is a symbolic link, not a regular file, not valid TOML, or in a form whose
	 *                     writable roots, whose selected permission profile, or whose selection of a built-in profile
	 *                     CAT does not edit
	 */
	private static void validateSandboxEntry(Path home) throws IOException
	{
		Path file = new CodexInstallation(home).configuration();
		if (Files.isSymbolicLink(file))
			throw new IOException("Codex's configuration must not be a symbolic link: " + file);
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return;
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Codex's configuration is not a regular file: " + file);
		String content = read(file);
		ObjectNode document = TomlText.parse(content, file);
		Optional<String> builtIn = BuiltInProfile.selected(document);
		if (builtIn.isPresent())
		{
			String created = BuiltInProfile.create(content, builtIn.get(), file);
			PermissionProfile.requireEditable(created, BuiltInProfile.catProfile(builtIn.get()), file);
			return;
		}
		Optional<String> profile = PermissionProfile.selected(document);
		if (profile.isPresent())
			PermissionProfile.requireEditable(content, profile.get(), file);
		else
			WritableRoots.requireEditable(content, file);
	}

	/**
	 * Requires the marketplace source to hold {@code version}, writes the marketplace metadata from it, then runs
	 * {@code codex plugin marketplace add} and {@code codex plugin add cat@cat}, and requires Codex to load
	 * {@code version} from the cache folder that it names, with the same content as the marketplace source.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if the marketplace source holds another version or lacks its marketplace
	 *                                  metadata, Codex fails, Codex loads another cache folder, or the cache copy
	 *                                  differs from the marketplace source
	 */
	@Override
	public void register(Path home, PluginVersion version, HarnessCli cli, FileReplacer replacer) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(cli, "cli").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		CodexInstallation installation = new CodexInstallation(home);
		Path plugin = installation.marketplacePlugin();
		Path metadata = installation.marketplaceManifest();
		requireNoLinks(home, home.relativize(metadata));
		requireNoLinks(home, home.relativize(plugin.resolve(RELEASE_METADATA)));
		if (!Files.isDirectory(plugin, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("CAT's marketplace source is not activated: " + plugin);
		requireNoLinks(home, home.relativize(plugin.resolve(PLUGIN_MANIFEST)));
		String sourceVersion = readVersion(plugin.resolve(PLUGIN_MANIFEST));
		if (!sourceVersion.equals(version.toString()))
		{
			throw new IOException("Registration of CAT " + version + " found CAT " + sourceVersion +
				" in the marketplace source: " + plugin);
		}
		writeMetadata(plugin.resolve(RELEASE_METADATA), metadata, replacer);

		run(cli, List.of("plugin", "marketplace", "add", installation.marketplaceRoot().toString()));
		run(cli, List.of("plugin", "add", PLUGIN_ID));
		requireCacheMatches(installation, version);
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
		Path file = new CodexInstallation(home).rules().resolve(RULES_FILE);
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
		Path file = new CodexInstallation(home).rules().resolve(UPDATER_RULES_FILE);
		if (Files.isSymbolicLink(file.getParent()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return;
		if (InstallerRules.isUpdaterLeftover(read(file), isWindows()))
			Files.delete(file);
	}

	/**
	 * Removes the runner rules that CAT's installation guide told users to write into {@code rules/cat.rules}: each
	 * {@code prefix_rule} whose pattern is only the {@code claude-runner} or {@code codex-runner} launcher, with or
	 * without {@code .cmd}, in {@code bin} of one of CAT's version folders in this home, and whose decision is
	 * {@code allow}. Deletes the file when it holds nothing but such rules. Otherwise leaves it as it is, because CAT
	 * does not rewrite the user's rules, and names the rules to remove.
	 *
	 * @return a sentence saying that the file was deleted, or which rules to remove from it; empty when the file is
	 * 	absent, not a regular file, reached through a symbolic link, larger than 1 MiB or holds no such rule
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if the file cannot be read or deleted
	 */
	@Override
	public List<String> removeRunnerExceptions(Path home, FileReplacer replacer) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(replacer, "replacer").isNotNull();
		CodexInstallation installation = new CodexInstallation(home);
		Path file = installation.rules().resolve(RUNNER_RULES_FILE);
		if (Files.isSymbolicLink(home) || Files.isSymbolicLink(file.getParent()) ||
			!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_METADATA_BYTES)
		{
			return List.of();
		}
		InstallerRules.RunnerRules found = InstallerRules.findRunnerRules(read(file), installation.versionFolders());
		if (found.rules().isEmpty())
			return List.of();
		if (!found.other())
		{
			Files.delete(file);
			return List.of("CAT deleted " + file + ", which held only the rules that CAT's installation guide told " +
				"you to write for its runners; CAT's runners no longer run outside the sandbox.");
		}
		return List.of("CAT left " + file + " as it is, because it also holds other content. CAT's runners no " +
			"longer run outside the sandbox, so remove these rules, which CAT's installation guide told you to write " +
			"for them, then restart Codex:\n" + String.join("\n", found.rules()));
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
	 * Asks for permission to select a profile of CAT's when {@code config.toml} selects {@code :workspace} or
	 * {@code :read-only}, which are built into Codex.
	 *
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative or not normalized
	 * @throws IOException              if {@code config.toml} cannot be read or is not valid TOML
	 */
	@Override
	public Optional<String> consentRequest(Path home, Path catLocks) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(catLocks, "catLocks").isAbsolute().isEqualTo(catLocks.normalize());
		Path file = new CodexInstallation(home).configuration();
		Optional<String> builtIn = selectedBuiltIn(file);
		if (builtIn.isEmpty())
			return Optional.empty();
		String profile = TomlText.quote(BuiltInProfile.catProfile(builtIn.get()));
		String quoted = TomlText.quote(builtIn.get());
		return Optional.of("Codex's configuration " + file + " sets default_permissions to the permission profile " +
			quoted + ", which is built into Codex, so CAT cannot add its capacity lock folder " + catLocks + " to it. " +
			"Without write access to that folder, CAT's runners cannot start Codex or Claude Code sessions inside " +
			"Codex's sandbox, because each session takes a capacity lock there. With your permission, CAT creates " +
			"the permission profile " + profile + ", which extends " + quoted + " and adds only write access to " +
			catLocks + ", and sets default_permissions to it. Uninstalling CAT sets it back to " + quoted + ".");
	}

	/**
	 * Sets {@code default_permissions} to CAT's profile of the selected built-in profile, and creates that profile with
	 * {@code extends} set to the built-in profile unless the file already defines it.
	 *
	 * @return {@code {"config.toml#default_permissions": [<built-in>], "config.toml#permissions.cat-<name>":
	 * 	[<built-in>]}}, or an empty map when {@code config.toml} selects no built-in profile that limits which files
	 * 	commands may write
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if {@code config.toml} is in a form whose selection CAT does not edit, or
	 *                                  cannot be read or written
	 */
	@Override
	public Map<String, List<String>> installConsentedEntries(Path home, FileReplacer replacer) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(replacer, "replacer").isNotNull();
		validateSandboxEntry(home);
		Path file = new CodexInstallation(home).configuration();
		Optional<String> builtIn = selectedBuiltIn(file);
		if (builtIn.isEmpty())
			return Map.of();
		write(file, BuiltInProfile.create(read(file), builtIn.get(), file), replacer);
		return Map.of(SELECTION_KEY, List.of(builtIn.get()),
			catProfileKey(builtIn.get()), List.of(builtIn.get()));
	}

	/**
	 * Returns the built-in profile that limits which files commands may write and that a {@code config.toml} selects.
	 *
	 * @param file the {@code config.toml}
	 * @return the profile's name, or empty when the file is absent, not a regular file or selects no such profile
	 * @throws IOException if the file cannot be read or is not valid TOML
	 */
	private static Optional<String> selectedBuiltIn(Path file) throws IOException
	{
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		return BuiltInProfile.selected(TomlText.parse(read(file), file));
	}

	/**
	 * Returns the record key of CAT's profile of a built-in profile.
	 *
	 * @param builtIn the built-in profile
	 * @return {@code config.toml#permissions.cat-<name>}
	 */
	private static String catProfileKey(String builtIn)
	{
		return CONFIGURATION_KEY + "permissions." + BuiltInProfile.catProfile(builtIn);
	}

	/**
	 * Lets sandboxed commands write {@code catLocks}. When {@code config.toml} selects with {@code default_permissions}
	 * a permission profile that it defines, adds {@code "<catLocks>" = "write"} to that profile's {@code filesystem}
	 * table, creating the table when it is absent, unless the profile already has an entry for {@code catLocks}.
	 * Otherwise adds {@code catLocks} to {@code sandbox_workspace_write.writable_roots}, creating the file or the table
	 * when it is absent, unless the writable roots already hold it. Codex's own sandbox already keeps sandboxed commands
	 * from writing the other harness's CAT installation, so this call adds nothing for it.
	 *
	 * @param home     the absolute Codex home
	 * @param other    the other harness's CAT installation
	 * @param catLocks the capacity lock directory
	 * @param replacer replaces Codex's configuration
	 * @return the entry that {@link #sandboxEntries} returns when this call added it, otherwise an empty map
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative or not normalized
	 * @throws IOException              if {@code config.toml} is a symbolic link, not a regular file, not valid TOML or
	 *                                  in a form whose writable roots, or whose selected permission profile, CAT does
	 *                                  not edit, or cannot be written
	 */
	@Override
	public Map<String, List<String>> installSandboxEntries(Path home, HarnessInstallation other, Path catLocks,
		FileReplacer replacer) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(other, "other").isNotNull();
		requireThat(catLocks, "catLocks").isAbsolute().isEqualTo(catLocks.normalize());
		requireThat(replacer, "replacer").isNotNull();
		validateSandboxEntry(home);

		Path file = new CodexInstallation(home).configuration();
		boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
		String content = "";
		if (exists)
			content = read(file);
		Optional<String> profile = PermissionProfile.selected(TomlText.parse(content, file));
		String edited;
		Map<String, List<String>> entries;
		if (profile.isPresent())
		{
			edited = PermissionProfile.add(content, profile.get(), catLocks.toString(), file);
			entries = Map.of(profileKey(profile.get()), List.of(catLocks.toString()));
		}
		else
		{
			edited = WritableRoots.add(content, catLocks.toString(), file);
			entries = Map.of(WRITABLE_ROOTS_KEY, List.of(catLocks.toString()));
		}
		if (exists && edited.equals(content))
			return Map.of();
		write(file, edited, replacer);
		return entries;
	}

	/**
	 * Returns the sandbox entry for {@code catLocks} that {@link #installSandboxEntries} adds to the current
	 * {@code config.toml}.
	 *
	 * @param home     the absolute Codex home
	 * @param other    the other harness's CAT installation
	 * @param catLocks the capacity lock directory
	 * @return {@code {"config.toml#permissions.<profile>.filesystem": [<catLocks>]}} when {@code config.toml} selects
	 * 	a permission profile that it defines, with the profile's name quoted when TOML requires it, otherwise
	 * 	{@code {"config.toml#sandbox_workspace_write.writable_roots": [<catLocks>]}}; when the selected profile is
	 * 	CAT's profile of a built-in profile, also the entries that {@link #installConsentedEntries} returns for it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative or not normalized
	 * @throws UncheckedIOException     if {@code config.toml} cannot be read or is not valid TOML
	 */
	@Override
	public Map<String, List<String>> sandboxEntries(Path home, HarnessInstallation other, Path catLocks)
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(other, "other").isNotNull();
		requireThat(catLocks, "catLocks").isAbsolute().isEqualTo(catLocks.normalize());
		Path file = new CodexInstallation(home).configuration();
		ObjectNode document;
		try
		{
			document = readDocument(file);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		Optional<String> profile = PermissionProfile.selected(document);
		if (profile.isEmpty())
			return Map.of(WRITABLE_ROOTS_KEY, List.of(catLocks.toString()));
		Map<String, List<String>> entries = new HashMap<>();
		entries.put(profileKey(profile.get()), List.of(catLocks.toString()));
		Optional<String> builtIn = BuiltInProfile.extended(document, profile.get());
		if (builtIn.isPresent())
		{
			entries.put(SELECTION_KEY, List.of(builtIn.get()));
			entries.put(catProfileKey(builtIn.get()), List.of(builtIn.get()));
		}
		return Map.copyOf(entries);
	}

	/**
	 * Reads a {@code config.toml}.
	 *
	 * @param file the {@code config.toml}
	 * @return the document, which is empty when the file is absent or not a regular file
	 * @throws IOException if the file cannot be read or is not valid TOML
	 */
	private static ObjectNode readDocument(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return TomlText.table();
		return TomlText.parse(read(file), file);
	}

	/**
	 * Returns the sandbox key of the write entry that CAT adds to a permission profile.
	 *
	 * @param profile the profile's name
	 * @return {@code config.toml#permissions.<profile>.filesystem}
	 */
	private static String profileKey(String profile)
	{
		return CONFIGURATION_KEY + PermissionProfile.tableKey(profile);
	}

	/**
	 * Tells the user that CAT selected its profile of a built-in profile, and that a write entry that CAT added to a
	 * profile of the user's applies to every project that uses the profile.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if {@code config.toml} cannot be read or is not valid TOML
	 */
	@Override
	public List<String> describeAdded(Path home, Map<String, List<String>> added) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(added, "added").isNotNull();
		Path file = new CodexInstallation(home).configuration();
		List<String> sentences = new ArrayList<>();
		List<String> selected = added.getOrDefault(SELECTION_KEY, List.of());
		for (String builtIn : selected)
		{
			String quoted = TomlText.quote(builtIn);
			sentences.add("CAT set default_permissions in " + file + " to its permission profile " +
				TomlText.quote(BuiltInProfile.catProfile(builtIn)) + ", which extends " + quoted + " and lets " +
				"sandboxed commands take CAT's capacity locks; uninstalling CAT sets it back to " + quoted + ".");
		}
		if (added.keySet().stream().noneMatch(key -> key.startsWith(CONFIGURATION_KEY + "permissions.")))
			return List.copyOf(sentences);
		ObjectNode document = readDocument(file);
		for (String profile : document.path("permissions").propertyNames())
		{
			if (BuiltInProfile.extended(document, profile).filter(selected::contains).isPresent())
				continue;
			for (String path : added.getOrDefault(profileKey(profile), List.of()))
			{
				sentences.add("CAT added " + TomlText.quote(path) + " = \"write\" to the Codex permission profile " +
					TomlText.quote(profile) + " in " + file + ", so that sandboxed commands can take CAT's capacity " +
					"locks; every project that uses this profile can now write that folder too.");
			}
		}
		return List.copyOf(sentences);
	}

	/**
	 * Tells the user when uninstall left {@code default_permissions} or CAT's profile of a built-in profile in place.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if {@code config.toml} cannot be read or is not valid TOML
	 */
	@Override
	public List<String> describeRetained(Path home, Map<String, List<String>> added) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(added, "added").isNotNull();
		Path file = new CodexInstallation(home).configuration();
		List<String> builtIns = recordedBuiltIns(added);
		if (builtIns.isEmpty() || Files.isSymbolicLink(home) || Files.isSymbolicLink(file))
			return List.of();
		ObjectNode document = readDocument(file);
		String selection = document.path(BuiltInProfile.SELECTION).asString("");
		List<String> sentences = new ArrayList<>();
		for (String builtIn : builtIns)
		{
			String profile = BuiltInProfile.catProfile(builtIn);
			if (added.getOrDefault(SELECTION_KEY, List.of()).contains(builtIn) && !selection.equals(builtIn))
			{
				if (selection.equals(profile))
				{
					sentences.add("CAT could not set default_permissions in " + file + " back to " +
						TomlText.quote(builtIn) + ", because it cannot edit that line; set it yourself.");
				}
				else
				{
					sentences.add("CAT left default_permissions in " + file + " as it is, because it no longer " +
						"names CAT's permission profile " + TomlText.quote(profile) + ".");
				}
			}
			if (document.path("permissions").has(profile))
			{
				String reason = "it holds settings that CAT did not write";
				if (selection.equals(profile))
					reason = "default_permissions still names it";
				sentences.add("CAT left its permission profile " + TomlText.quote(profile) + " in " + file +
					", because " + reason + ".");
			}
		}
		return List.copyOf(sentences);
	}

	/**
	 * Returns the built-in profiles whose replacement by CAT's profile an install recorded.
	 *
	 * @param added the recorded entries, by entry key
	 * @return the built-in profiles, without repeats
	 */
	private static List<String> recordedBuiltIns(Map<String, List<String>> added)
	{
		Set<String> builtIns = new TreeSet<>(added.getOrDefault(SELECTION_KEY, List.of()));
		for (String builtIn : BuiltInProfile.LIMITED)
			builtIns.addAll(added.getOrDefault(catProfileKey(builtIn), List.of()));
		builtIns.retainAll(BuiltInProfile.LIMITED);
		return List.copyOf(builtIns);
	}

	/**
	 * Deletes {@code rules/cat-installer.rules} when its content equals a recorded content, removes each recorded
	 * writable root from {@code config.toml}, and removes each recorded write entry from the permission profile that it
	 * was added to, when that entry still grants write access. Then sets {@code default_permissions} back to the
	 * recorded built-in profile while it still names CAT's profile of it, and removes CAT's profile while it holds only
	 * what CAT wrote and is not selected. Leaves a changed rules file, a file reached through a symbolic link, the
	 * writable roots' array and table, each profile's filesystem table of the user's profiles, and every other entry in
	 * place.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if a file cannot be read or written, or {@code config.toml} holds a recorded
	 *                                  entry in a form that CAT does not edit
	 */
	@Override
	public void removePermissions(Path home, Map<String, List<String>> added, FileReplacer replacer)
		throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(added, "added").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		removeRules(home, added.getOrDefault(RULES_KEY, List.of()));
		removeSandboxEntries(home, added, replacer);
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
		Path file = new CodexInstallation(home).rules().resolve(RULES_FILE);
		if (contents.isEmpty() || Files.isSymbolicLink(home) || Files.isSymbolicLink(file.getParent()) ||
			!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
		{
			return;
		}
		if (contents.contains(read(file)))
			Files.delete(file);
	}

	/**
	 * Removes recorded writable roots and recorded profile write entries from {@code config.toml}, then sets
	 * {@code default_permissions} back to a recorded built-in profile while it names CAT's profile of it, and removes
	 * CAT's profile while it holds only what CAT wrote. A recorded entry of a profile that the file no longer defines
	 * needs no removal.
	 *
	 * @param home     the Codex home
	 * @param added    the recorded entries, by entry key
	 * @param replacer replaces Codex's configuration
	 * @throws IOException if the file cannot be read or written, or holds a recorded entry in a form that CAT does not
	 *                     edit
	 */
	private static void removeSandboxEntries(Path home, Map<String, List<String>> added, FileReplacer replacer)
		throws IOException
	{
		Path file = new CodexInstallation(home).configuration();
		boolean recorded = added.keySet().stream().anyMatch(key -> key.startsWith(CONFIGURATION_KEY));
		if (!recorded || Files.isSymbolicLink(home) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return;
		String content = read(file);
		String edited = content;
		for (String root : added.getOrDefault(WRITABLE_ROOTS_KEY, List.of()))
			edited = WritableRoots.remove(edited, root, file);
		ObjectNode document = TomlText.parse(content, file);
		for (String profile : document.path("permissions").propertyNames())
		{
			for (String path : added.getOrDefault(profileKey(profile), List.of()))
				edited = PermissionProfile.remove(edited, profile, path, file);
		}
		for (String builtIn : recordedBuiltIns(added))
			edited = BuiltInProfile.remove(edited, builtIn, file);
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
	 * Returns the home that Codex finds when the environment holds no {@code CODEX_HOME}.
	 *
	 * @param variables the user's home variables
	 * @return the home, or null when Codex cannot find one, or finds one that depends on its working directory
	 */
	private static Path defaultHome(Map<String, String> variables)
	{
		String userHome = variables.get(Platform.current().homeVariable());
		try
		{
			if (userHome != null && !userHome.isEmpty() && !Path.of(userHome).isAbsolute())
				return null;
			return CodexInstallation.resolveHome(variables);
		}
		catch (IOException | InvalidPathException _)
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
	 * Requires Codex to load exactly the validated marketplace source from the version folder that {@code version}
	 * names.
	 *
	 * @param installation where Codex keeps CAT's installation
	 * @param version      the registered plugin version
	 * @throws IOException if Codex loads another version folder or its cache copy differs from the source
	 */
	private static void requireCacheMatches(CodexInstallation installation, PluginVersion version) throws IOException
	{
		Path plugin = installation.marketplacePlugin();
		InstalledState state = new CodexActivation().installed(installation.home());
		if (!state.equals(new InstalledState(Optional.of(version.toString()), true)))
		{
			throw new IOException("After \"codex plugin add\", Codex does not load CAT " + version + " consistently: " +
				installation.versionFolders());
		}
		Path cache = installation.versionFolder(version);
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
