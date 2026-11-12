package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.installer.PermissionProfile.Access;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.ConsentRequest;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * Codex runs a plugin's hook only after {@code config.toml} records the hash that Codex computes for the hook, which a
 * user records by reviewing the hook. Install records the hash of each of CAT's hooks, replaces a hash that an update
 * made stale, and uninstall removes the hashes that install recorded.
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
	 * The suffix that turns the sandbox key of a profile's write entries into the key of its read entries.
	 */
	private static final String READ_SUFFIX = ".read";
	/**
	 * The record key of the built-in permission profile that {@code default_permissions} selected before CAT selected
	 * its own profile in its place.
	 */
	private static final String SELECTION_KEY = CONFIGURATION_KEY + BuiltInProfile.SELECTION;
	/**
	 * The record key of CAT's selection of its profile in place of the sandbox mode that {@code config.toml} sets,
	 * whose value is the sandbox mode.
	 */
	private static final String MODE_KEY = CONFIGURATION_KEY + SandboxModeProfile.MODE;
	/**
	 * The shell startup files that the question and the notices name as examples.
	 */
	private static final String STARTUP_FILES = "your shell startup files, such as ~/.bashrc";
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
	public String productName()
	{
		return "Codex";
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
	 *                     writable roots, whose selected permission profile, whose selection of a built-in profile, or
	 *                     whose sandbox mode CAT does not edit
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
		{
			PermissionProfile.requireEditable(content, profile.get(), file);
			return;
		}
		WritableRoots.requireEditable(content, file);
		Optional<String> mode = SandboxModeProfile.unselected(document);
		if (mode.isPresent())
		{
			String created = SandboxModeProfile.create(content, mode.get(), file);
			PermissionProfile.requireEditable(created, SandboxModeProfile.catProfile(mode.get()), file);
		}
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
		requireThat(cli, "cli").isNotNull();
		prepareRegistration(home, version, replacer);
		for (CodexPluginCommand command : CodexPluginCommand.registration(new CodexInstallation(home).marketplaceRoot()))
			command.run(cli);
		requireLoaded(home, version);
	}

	/**
	 * Requires the marketplace source to hold a plugin version, and writes the marketplace metadata from it, unless the
	 * metadata is already there. Registration does this before it runs Codex, because Codex copies the source into the
	 * folder of the version that the source states.
	 *
	 * @param home     the absolute, normalized Codex home
	 * @param version  the plugin version to register
	 * @param replacer replaces the marketplace metadata, retrying while Windows refuses the replacement
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if a path component is a symbolic link, the marketplace source is not activated,
	 *                                  holds another version than {@code version} or lacks its marketplace metadata, or
	 *                                  the metadata cannot be written
	 */
	public static void prepareRegistration(Path home, PluginVersion version, FileReplacer replacer) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(version, "version").isNotNull();
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
		for (CodexPluginCommand command : CodexPluginCommand.unregistration())
			command.run(cli);
	}

	/**
	 * Writes {@code rules/cat-installer.rules} unless it already holds exactly the rules for {@code installer}, deletes
	 * a {@code rules/cat-updater.rules} file whose content is exactly what CAT wrote there before its installer was
	 * called the installer, and records in {@code config.toml} the hash that Codex computes for each hook of the plugin
	 * that holds {@code installer}, so that Codex runs the hooks without the user reviewing them. A hash that differs
	 * from the one that {@code config.toml} records for a hook replaces it.
	 *
	 * @return {@code {"rules/cat-installer.rules#": [<file content>]}} when this call wrote the file, and for each hash
	 * 	that this call recorded or replaced, {@code {"config.toml#hooks.state.\"<hook>\".trusted_hash": [<hash>]}};
	 * 	otherwise an empty map
	 * @throws IllegalArgumentException if any of the paths are relative or not normalized
	 * @throws IOException              if {@link #validateConfiguration} refuses the configuration, a file cannot be
	 *                                  written, or {@code config.toml} holds a hook's state in a form that CAT does not
	 *                                  edit
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
		Map<String, List<String>> added = new LinkedHashMap<>();
		String content = InstallerRules.render(installer, isWindows());
		Path file = new CodexInstallation(home).rules().resolve(RULES_FILE);
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) || !read(file).equals(content))
		{
			write(file, content, replacer);
			added.put(RULES_KEY, List.of(content));
		}
		added.putAll(trustHooks(home, installer, replacer));
		return Map.copyOf(added);
	}

	/**
	 * Records in {@code config.toml} the hash of each hook of the plugin that holds an installer.
	 *
	 * @param home      the Codex home
	 * @param installer the installer launcher in the plugin's version folder
	 * @param replacer  replaces Codex's configuration
	 * @return each recorded or replaced hash, by record key
	 * @throws IOException if {@code config.toml} or a hooks file cannot be read, or {@code config.toml} cannot be
	 *                     written or holds a hook's state in a form that CAT does not edit
	 */
	private static Map<String, List<String>> trustHooks(Path home, Path installer, FileReplacer replacer)
		throws IOException
	{
		Map<String, String> hashes = hookHashes(installer);
		if (hashes.isEmpty())
			return Map.of();
		Path file = new CodexInstallation(home).configuration();
		String content = "";
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			content = read(file);
		String edited = content;
		Map<String, List<String>> added = new LinkedHashMap<>();
		for (Map.Entry<String, String> hook : hashes.entrySet())
		{
			String trusted = HookTrust.add(edited, hook.getKey(), hook.getValue(), file);
			if (!trusted.equals(edited))
				added.put(HookTrust.recordKey(hook.getKey()), List.of(hook.getValue()));
			edited = trusted;
		}
		if (!edited.equals(content))
			write(file, edited, replacer);
		return added;
	}

	/**
	 * Returns the hash that Codex computes for each hook of the plugin that holds an installer.
	 *
	 * @param installer the installer launcher in the plugin's version folder
	 * @return each hook's key in {@code config.toml}, mapped to its hash; empty if the plugin declares no hooks
	 * @throws IOException if the plugin's manifest or hooks file cannot be read
	 */
	private static Map<String, String> hookHashes(Path installer) throws IOException
	{
		Path plugin = installer.getParent().getParent();
		return HookTrust.hashes(plugin, CodexPluginCommand.PLUGIN_ID, isWindows());
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
	 * Returns the content of {@code rules/cat-installer.rules} that {@link #installPermissions} writes, and the hash of
	 * each hook of the plugin that holds {@code installer}, which it records in {@code config.toml}.
	 *
	 * @return {@code {"rules/cat-installer.rules#": [<file content>]}}, and for each hook,
	 * 	{@code {"config.toml#hooks.state.\"<hook>\".trusted_hash": [<hash>]}}
	 * @throws IllegalArgumentException if {@code installer} is relative or not normalized
	 * @throws IOException              if the plugin's manifest or hooks file cannot be read
	 */
	@Override
	public Map<String, List<String>> permissions(Path installer) throws IOException
	{
		Map<String, List<String>> permissions = new LinkedHashMap<>();
		permissions.put(RULES_KEY, List.of(InstallerRules.render(installer, isWindows())));
		for (Map.Entry<String, String> hook : hookHashes(installer).entrySet())
			permissions.put(HookTrust.recordKey(hook.getKey()), List.of(hook.getValue()));
		return Map.copyOf(permissions);
	}

	/**
	 * Asks for permission to select a profile of CAT's when {@code config.toml} selects {@code :workspace} or
	 * {@code :read-only}, which are built into Codex, and install stops without it. When {@code config.toml} selects no
	 * permission profile and sets {@code sandbox_mode} to {@code workspace-write} or {@code read-only}, asks for
	 * permission to select a profile of CAT's in place of that mode, and install goes on without it.
	 *
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative or not normalized
	 * @throws IOException              if {@code config.toml} cannot be read or is not valid TOML
	 */
	@Override
	public Optional<ConsentRequest> consentRequest(Path home, Path catLocks) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(catLocks, "catLocks").isAbsolute().isEqualTo(catLocks.normalize());
		Path file = new CodexInstallation(home).configuration();
		Optional<String> builtIn = selectedBuiltIn(file);
		if (builtIn.isEmpty())
			return unselectedMode(file).map(mode -> new ConsentRequest(sandboxModeQuestion(file, mode), false));
		String profile = TomlText.quote(BuiltInProfile.catProfile(builtIn.get()));
		String quoted = TomlText.quote(builtIn.get());
		return Optional.of(new ConsentRequest("Codex's configuration " + file + " sets default_permissions to the " +
			"permission profile " + quoted + ", which is built into Codex, so CAT cannot add its capacity lock folder " +
			catLocks + " to it. Without write access to that folder, CAT's runners cannot start Codex or Claude Code " +
			"sessions inside Codex's sandbox, because each session takes a capacity lock there. With your " +
			"permission, CAT creates the permission profile " + profile + ", which extends " + quoted + " and adds " +
			"only write access to " + catLocks + ", and sets default_permissions to it. Uninstalling CAT sets it back " +
			"to " + quoted + ".", true));
	}

	/**
	 * Returns the question that asks for permission to select CAT's profile in place of a sandbox mode.
	 *
	 * @param file the {@code config.toml}
	 * @param mode the sandbox mode that it sets
	 * @return the question
	 */
	private static String sandboxModeQuestion(Path file, String mode)
	{
		String profile = TomlText.quote(SandboxModeProfile.catProfile(mode));
		String builtIn = TomlText.quote(SandboxModeProfile.builtIn(mode));
		String copied = "";
		if (mode.equals(SandboxModeProfile.WORKSPACE_WRITE))
		{
			copied = ", copies into it the writable folders, network access and temporary-folder exclusions of " +
				"your [sandbox_workspace_write] table";
		}
		return "Codex's configuration " + file + " selects no permission profile, so nothing keeps sandboxed " +
			"commands from writing " + STARTUP_FILES + ", which run outside the sandbox whenever a shell starts. " +
			"With your permission, CAT creates the permission profile " + profile + ", which extends " + builtIn +
			copied + ", adds read-only entries for those files and the entries that CAT's runners need, and sets " +
			"default_permissions to it. Codex then uses that profile in place of sandbox_mode = " +
			TomlText.quote(mode) + " in every project, unless a project's own configuration or a command-line option " +
			"selects another. Uninstalling CAT removes the profile and the default_permissions line. If you answer " +
			"no, CAT installs without them and the startup files stay writable.";
	}

	/**
	 * Sets {@code default_permissions} to CAT's profile of the selected built-in profile, and creates that profile with
	 * {@code extends} set to the built-in profile unless the file already defines it. When {@code config.toml} selects
	 * no permission profile and sets {@code sandbox_mode} to {@code workspace-write} or {@code read-only}, adds a
	 * {@code default_permissions} line that selects CAT's profile of that mode instead, and creates that profile with
	 * {@code extends} set to {@code :workspace} or {@code :read-only}; {@link #installSandboxEntries} then copies the
	 * {@code [sandbox_workspace_write]} settings into it.
	 *
	 * @return {@code {"config.toml#default_permissions": [<built-in>], "config.toml#permissions.cat-<name>":
	 * 	[<built-in>]}}, {@code {"config.toml#sandbox_mode": [<mode>], "config.toml#permissions.cat-sandbox-<mode>":
	 * 	[<mode>]}}, or an empty map when {@code config.toml} selects neither a built-in profile that limits which files
	 * 	commands may write nor, without a selected profile, such a sandbox mode
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
		if (builtIn.isPresent())
		{
			write(file, BuiltInProfile.create(read(file), builtIn.get(), file), replacer);
			return Map.of(SELECTION_KEY, List.of(builtIn.get()),
				catProfileKey(builtIn.get()), List.of(builtIn.get()));
		}
		Optional<String> mode = unselectedMode(file);
		if (mode.isEmpty())
			return Map.of();
		write(file, SandboxModeProfile.create(read(file), mode.get(), file), replacer);
		return Map.of(MODE_KEY, List.of(mode.get()), sandboxProfileKey(mode.get()), List.of(mode.get()));
	}

	/**
	 * Returns the sandbox mode of which CAT offers to select a profile, because a {@code config.toml} selects no
	 * permission profile.
	 *
	 * @param file the {@code config.toml}
	 * @return {@code workspace-write} or {@code read-only}, or empty when the file is absent, not a regular file, sets
	 * 	{@code default_permissions} or sets no such mode
	 * @throws IOException if the file cannot be read or is not valid TOML
	 */
	private static Optional<String> unselectedMode(Path file) throws IOException
	{
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		return SandboxModeProfile.unselected(TomlText.parse(read(file), file));
	}

	/**
	 * Returns the record key of CAT's profile of a sandbox mode.
	 *
	 * @param mode the sandbox mode
	 * @return {@code config.toml#permissions.cat-sandbox-<mode>}
	 */
	private static String sandboxProfileKey(String mode)
	{
		return CONFIGURATION_KEY + "permissions." + SandboxModeProfile.catProfile(mode);
	}

	/**
	 * Returns the record key of the {@code enabled = true} entry that CAT adds to a profile's network table.
	 *
	 * @param profile the profile's name
	 * @return {@code config.toml#permissions.<profile>.network.enabled}
	 */
	private static String networkKey(String profile)
	{
		return CONFIGURATION_KEY + PermissionProfile.tableKey(profile, PermissionProfile.NETWORK) + ".enabled";
	}

	/**
	 * Adds a value to the values of a record key, once.
	 *
	 * @param entries the entries, by record key
	 * @param key     the record key
	 * @param value   the value
	 */
	private static void record(Map<String, List<String>> entries, String key, String value)
	{
		List<String> values = new ArrayList<>(entries.getOrDefault(key, List.of()));
		if (!values.contains(value))
			values.add(value);
		entries.put(key, List.copyOf(values));
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
	 * Otherwise adds {@code catLocks} to
	 * {@code sandbox_workspace_write.writable_roots}, creating the file or the table when it is absent, unless the
	 * writable roots already hold it. Codex's own sandbox already keeps sandboxed commands from writing the other
	 * harness's CAT installation, so this call adds nothing for it. When the selected profile is CAT's profile of
	 * {@code sandbox_mode = "workspace-write"}, also adds to it each entry that copies the user's
	 * {@code [sandbox_workspace_write]} settings, unless the profile already has an entry for its path: a write entry
	 * for each writable folder, a read entry for {@code :tmpdir} and {@code :slash_tmp} when the settings exclude them,
	 * and {@code enabled = true} in its {@code network} table when the settings allow network access.
	 *
	 * @param home     the absolute Codex home
	 * @param other    the other harness's CAT installation
	 * @param catLocks the capacity lock directory
	 * @param replacer replaces Codex's configuration
	 * @return each entry that {@link #sandboxEntries} returns and that this call added
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
		ObjectNode document = TomlText.parse(content, file);
		Optional<String> profile = PermissionProfile.selected(document);
		String edited;
		Map<String, List<String>> entries = new HashMap<>();
		if (profile.isPresent())
		{
			Map<String, Access> wanted = new LinkedHashMap<>();
			wanted.put(catLocks.toString(), Access.WRITE);
			Optional<String> mode = SandboxModeProfile.extended(document, profile.get());
			if (mode.isPresent())
			{
				for (Map.Entry<String, Access> copied : SandboxModeProfile.filesystemEntries(document, mode.get(), home).
					entrySet())
				{
					wanted.putIfAbsent(copied.getKey(), copied.getValue());
				}
			}
			edited = content;
			for (Map.Entry<String, Access> entry : wanted.entrySet())
			{
				String next = PermissionProfile.add(edited, profile.get(), entry.getKey(), entry.getValue(), file);
				if (!next.equals(edited))
					record(entries, entryKey(profile.get(), entry.getValue()), entry.getKey());
				edited = next;
			}
			if (mode.isPresent() && SandboxModeProfile.networkAccess(document, mode.get()))
			{
				String next = PermissionProfile.enableNetwork(edited, profile.get(), file);
				if (!next.equals(edited))
					record(entries, networkKey(profile.get()), "true");
				edited = next;
			}
		}
		else
		{
			edited = WritableRoots.add(content, catLocks.toString(), file);
			entries.put(WRITABLE_ROOTS_KEY, List.of(catLocks.toString()));
		}
		if (exists && edited.equals(content))
			return Map.of();
		write(file, edited, replacer);
		return Map.copyOf(entries);
	}

	/**
	 * Returns the sandbox entries for {@code catLocks} that {@link #installSandboxEntries} adds to the current
	 * {@code config.toml}.
	 *
	 * @param home     the absolute Codex home
	 * @param other    the other harness's CAT installation
	 * @param catLocks the capacity lock directory
	 * @return {@code {"config.toml#permissions.<profile>.filesystem": [<catLocks>]}} when {@code config.toml} selects a
	 * 	permission profile that it defines, with the profile's name quoted when TOML requires it, otherwise
	 * 	{@code {"config.toml#sandbox_workspace_write.writable_roots": [<catLocks>]}}; when the selected profile is
	 * 	CAT's profile of a built-in profile or of a sandbox mode, also the entries that {@link #installConsentedEntries}
	 * 	returns for it, and for CAT's profile of {@code workspace-write} the entries that copy the current
	 * 	{@code [sandbox_workspace_write]} settings
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
		record(entries, profileKey(profile.get()), catLocks.toString());
		Optional<String> builtIn = BuiltInProfile.extended(document, profile.get());
		if (builtIn.isPresent())
		{
			entries.put(SELECTION_KEY, List.of(builtIn.get()));
			entries.put(catProfileKey(builtIn.get()), List.of(builtIn.get()));
		}
		Optional<String> mode = SandboxModeProfile.extended(document, profile.get());
		if (mode.isPresent())
		{
			entries.put(MODE_KEY, List.of(mode.get()));
			entries.put(sandboxProfileKey(mode.get()), List.of(mode.get()));
			for (Map.Entry<String, Access> copied : SandboxModeProfile.filesystemEntries(document, mode.get(), home).
				entrySet())
			{
				record(entries, entryKey(profile.get(), copied.getValue()), copied.getKey());
			}
			if (SandboxModeProfile.networkAccess(document, mode.get()))
				entries.put(networkKey(profile.get()), List.of("true"));
		}
		return Map.copyOf(entries);
	}

	/**
	 * Keeps sandboxed commands from writing the given files. When {@code config.toml} selects with
	 * {@code default_permissions} a permission profile that it defines, adds {@code "<file>" = "read"} for each file to
	 * that profile's {@code filesystem} table, creating the table when it is absent, unless the profile already has an
	 * entry for the file. Codex applies the most specific entry, so a read entry keeps the commands from writing the
	 * file even when the profile lets them write a folder that contains it. Otherwise adds nothing, because the
	 * writable roots that Codex's other sandbox settings name cannot deny a path inside them.
	 *
	 * @param home     the absolute Codex home
	 * @param files    the absolute files
	 * @param replacer replaces Codex's configuration
	 * @return {@code {"config.toml#permissions.<profile>.filesystem.read": [<file>...]}} with the files whose entry
	 * 	this call added; empty when it added none
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or a file is relative or not normalized
	 * @throws IOException              if {@code config.toml} is a symbolic link, not a regular file, not valid TOML or
	 *                                  in a form whose selected permission profile CAT does not edit, or cannot be
	 *                                  written
	 */
	@Override
	public Map<String, List<String>> installReadOnlyEntries(Path home, List<Path> files, FileReplacer replacer)
		throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireNormalized(files);
		requireThat(replacer, "replacer").isNotNull();
		validateSandboxEntry(home);

		Path file = new CodexInstallation(home).configuration();
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return Map.of();
		String content = read(file);
		Optional<String> profile = PermissionProfile.selected(TomlText.parse(content, file));
		if (profile.isEmpty())
			return Map.of();
		String edited = content;
		List<String> added = new ArrayList<>();
		for (Path readOnly : files)
		{
			String denied = PermissionProfile.add(edited, profile.get(), readOnly.toString(), Access.READ, file);
			if (!denied.equals(edited))
				added.add(readOnly.toString());
			edited = denied;
		}
		if (added.isEmpty())
			return Map.of();
		write(file, edited, replacer);
		return Map.of(profileKey(profile.get()) + READ_SUFFIX, List.copyOf(added));
	}

	/**
	 * Returns the entries that {@link #installReadOnlyEntries} adds to the current {@code config.toml}.
	 *
	 * @param home  the absolute Codex home
	 * @param files the absolute files
	 * @return {@code {"config.toml#permissions.<profile>.filesystem.read": [<file>...]}} when {@code config.toml}
	 * 	selects a permission profile that it defines, otherwise empty
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or a file is relative or not normalized
	 * @throws UncheckedIOException     if {@code config.toml} cannot be read or is not valid TOML
	 */
	@Override
	public Map<String, List<String>> readOnlyEntries(Path home, List<Path> files)
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireNormalized(files);
		ObjectNode document;
		try
		{
			document = readDocument(new CodexInstallation(home).configuration());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		Optional<String> profile = PermissionProfile.selected(document);
		if (profile.isEmpty() || files.isEmpty())
			return Map.of();
		return Map.of(profileKey(profile.get()) + READ_SUFFIX, files.stream().map(Path::toString).toList());
	}

	/**
	 * Checks that every file is absolute and normalized.
	 *
	 * @param files the files
	 * @throws NullPointerException     if {@code files} or a file is null
	 * @throws IllegalArgumentException if a file is relative or not normalized
	 */
	private static void requireNormalized(List<Path> files)
	{
		requireThat(files, "files").isNotNull();
		for (Path file : files)
			requireThat(file, "file").isAbsolute().isEqualTo(file.normalize());
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
	 * Returns the record key of the entries of an access that CAT adds to a permission profile.
	 *
	 * @param profile the profile's name
	 * @param access  the entries' access
	 * @return {@code config.toml#permissions.<profile>.filesystem}, followed by {@code .read} for read entries
	 */
	private static String entryKey(String profile, Access access)
	{
		if (access == Access.READ)
			return profileKey(profile) + READ_SUFFIX;
		return profileKey(profile);
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
	 * Tells the user that CAT selected its profile of a built-in profile or of a sandbox mode, that a write entry that
	 * CAT added to a profile of the user's applies to every project that uses the profile, and that the shell startup
	 * files stay writable when {@code config.toml} selects no permission profile.
	 *
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if {@code config.toml} cannot be read or is not valid TOML
	 */
	@Override
	public List<String> describeAdded(Path home, Map<String, List<String>> added) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(added, "added").isNotNull();
		List<String> sentences = new ArrayList<>(describeAddedSandbox(home, added));
		long hooks = added.keySet().stream().filter(key -> HookTrust.hookOf(key).isPresent()).count();
		if (hooks > 0)
		{
			sentences.add("CAT recorded in " + new CodexInstallation(home).configuration() + " the hash of each of " +
				"its " + hooks + " hooks that this install added or changed, so Codex runs them without asking you to " +
				"review them; uninstalling CAT removes the hashes that CAT recorded.");
		}
		return List.copyOf(sentences);
	}

	/**
	 * Returns the sentences about the profile and sandbox entries that an install added.
	 *
	 * @param home  the Codex home
	 * @param added the entries that the install added
	 * @return the sentences
	 * @throws IOException if {@code config.toml} cannot be read or is not valid TOML
	 */
	private static List<String> describeAddedSandbox(Path home, Map<String, List<String>> added) throws IOException
	{
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
		List<String> modes = added.getOrDefault(MODE_KEY, List.of());
		for (String mode : modes)
		{
			String copy = "";
			if (mode.equals(SandboxModeProfile.WORKSPACE_WRITE))
				copy = " and holds a copy of your [sandbox_workspace_write] settings";
			sentences.add("CAT set default_permissions in " + file + " to its permission profile " +
				TomlText.quote(SandboxModeProfile.catProfile(mode)) + ", which extends " +
				TomlText.quote(SandboxModeProfile.builtIn(mode)) + copy + ", so that sandboxed commands cannot write " +
				STARTUP_FILES + "; Codex uses it in place of sandbox_mode = " + TomlText.quote(mode) + " in every " +
				"project, and uninstalling CAT removes it.");
		}
		ObjectNode document = readDocument(file);
		describeUnprotected(document, file, !added.isEmpty()).ifPresent(sentences::add);
		if (added.keySet().stream().noneMatch(key -> key.startsWith(CONFIGURATION_KEY + "permissions.")))
			return List.copyOf(sentences);
		for (String profile : document.path("permissions").propertyNames())
		{
			if (BuiltInProfile.extended(document, profile).filter(selected::contains).isPresent() ||
				SandboxModeProfile.extended(document, profile).isPresent())
			{
				continue;
			}
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
	 * Tells the user that the shell startup files stay writable when {@code config.toml} selects no permission
	 * profile, and why. The user hears it after each install that asked for permission to select CAT's profile and did
	 * not get it, and otherwise after each install that added an entry, so that an install that changes nothing says
	 * nothing.
	 *
	 * @param document the parsed {@code config.toml}
	 * @param file     the {@code config.toml}
	 * @param changed  whether the install added an entry
	 * @return the sentence, or empty when the file sets {@code default_permissions}, sets a sandbox mode that CAT does
	 * 	not know, or sets no sandbox mode that CAT follows and the install added nothing
	 */
	private static Optional<String> describeUnprotected(ObjectNode document, Path file, boolean changed)
	{
		if (document.has(BuiltInProfile.SELECTION))
			return Optional.empty();
		String writable = "sandboxed commands can still write " + STARTUP_FILES;
		JsonNode mode = document.path(SandboxModeProfile.MODE);
		if (!changed && !(mode.isString() && SandboxModeProfile.MODES.contains(mode.stringValue())))
			return Optional.empty();
		if (mode.isMissingNode())
		{
			return Optional.of("Codex's configuration " + file + " sets neither default_permissions nor " +
				"sandbox_mode, so Codex chooses a built-in permission profile for each project and no single profile " +
				"of CAT's matches them all; CAT created none, so " + writable + ".");
		}
		if (!mode.isString())
			return Optional.empty();
		if (mode.stringValue().equals("danger-full-access"))
		{
			return Optional.of("Codex's configuration " + file + " sets sandbox_mode to \"danger-full-access\", " +
				"which runs commands without a sandbox, so CAT created no permission profile and commands can still " +
				"write " + STARTUP_FILES + ".");
		}
		if (!SandboxModeProfile.MODES.contains(mode.stringValue()))
			return Optional.empty();
		return Optional.of("Codex's configuration " + file + " selects no permission profile, and CAT did not get " +
			"your permission to create one in place of sandbox_mode = " + TomlText.quote(mode.stringValue()) + ", so " +
			writable + "; run install again and allow it to protect them.");
	}

	/**
	 * Groups the recorded entries: the rules of {@code rules/cat-installer.rules} as {@link InstallerRules} describes
	 * them, the writable roots and each profile's write entries as paths that the sandbox may write, each profile's
	 * read entries as paths that it may not write, the hooks whose hashes {@code config.toml} records, and
	 * {@code default_permissions}, CAT's profile of a built-in profile or of a sandbox mode, and a profile's
	 * {@code network.enabled} as the values that they are set to, each entry as {@code config.toml} writes it. Any
	 * other key is a group of the values that it records.
	 *
	 * @throws IllegalArgumentException if {@code installer} is relative or not normalized
	 */
	@Override
	public List<EntryGroup> describeEntries(Map<String, List<String>> recorded, Path installer)
	{
		requireThat(recorded, "recorded").isNotNull();
		requireThat(installer, "installer").isAbsolute().isEqualTo(installer.normalize());
		List<EntryGroup> groups = new ArrayList<>();
		Path hookFile = null;
		List<String> hooks = new ArrayList<>();
		List<String> hashes = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : recorded.entrySet())
		{
			String key = entry.getKey();
			List<String> values = entry.getValue();
			int separator = key.indexOf('#');
			Path file = Path.of(key.substring(0, separator));
			String setting = key.substring(separator + 1);
			Optional<String> hook = HookTrust.hookOf(key);
			if (key.equals(RULES_KEY))
			{
				for (String content : values)
					groups.addAll(InstallerRules.describe(content, file, installer, isWindows()));
			}
			else if (hook.isPresent())
			{
				hookFile = file;
				hooks.add(hook.get());
				for (String hash : values)
					hashes.add(setting + " = " + TomlText.quote(hash));
			}
			else if (key.equals(WRITABLE_ROOTS_KEY))
			{
				groups.add(new EntryGroup(EntryEffect.SANDBOX_MAY_WRITE, file, setting, values,
					values.stream().map(TomlText::quote).toList()));
			}
			else if (key.endsWith("." + PermissionProfile.FILESYSTEM))
			{
				List<String> lines = values.stream().map(path -> TomlText.quote(path) + " = \"write\"").toList();
				groups.add(new EntryGroup(EntryEffect.SANDBOX_MAY_WRITE, file, setting, values, lines));
			}
			else if (key.endsWith("." + PermissionProfile.FILESYSTEM + READ_SUFFIX))
			{
				String table = setting.substring(0, setting.length() - READ_SUFFIX.length());
				List<String> lines = values.stream().map(path -> TomlText.quote(path) + " = \"read\"").toList();
				groups.add(new EntryGroup(EntryEffect.SANDBOX_MAY_NOT_WRITE, file, table, values, lines));
			}
			else if (key.equals(SELECTION_KEY))
			{
				List<String> profiles = values.stream().map(BuiltInProfile::catProfile).toList();
				List<String> lines = profiles.stream().map(profile -> setting + " = " + TomlText.quote(profile)).
					toList();
				groups.add(new EntryGroup(EntryEffect.SETS_VALUE, file, setting, profiles, lines));
			}
			else if (!values.isEmpty() && values.stream().allMatch(builtIn -> key.equals(catProfileKey(builtIn))))
			{
				List<String> lines = values.stream().map(BuiltInProfile::definition).toList();
				groups.add(new EntryGroup(EntryEffect.SETS_VALUE, file, setting, lines, lines));
			}
			else if (key.equals(MODE_KEY))
			{
				List<String> profiles = values.stream().map(SandboxModeProfile::catProfile).toList();
				List<String> lines = profiles.stream().
					map(profile -> BuiltInProfile.SELECTION + " = " + TomlText.quote(profile)).toList();
				groups.add(new EntryGroup(EntryEffect.SETS_VALUE, file, BuiltInProfile.SELECTION, profiles, lines));
			}
			else if (!values.isEmpty() && values.stream().allMatch(mode ->
				SandboxModeProfile.MODES.contains(mode) && key.equals(sandboxProfileKey(mode))))
			{
				List<String> lines = values.stream().
					map(mode -> BuiltInProfile.definition(SandboxModeProfile.builtIn(mode))).toList();
				groups.add(new EntryGroup(EntryEffect.SETS_VALUE, file, setting, lines, lines));
			}
			else if (key.endsWith("." + PermissionProfile.NETWORK + ".enabled"))
			{
				String table = setting.substring(0, setting.length() - ".enabled".length());
				List<String> lines = values.stream().map(value -> "enabled = " + value).toList();
				groups.add(new EntryGroup(EntryEffect.SETS_VALUE, file, table, lines, lines));
			}
			else
				groups.add(new EntryGroup(EntryEffect.SETS_VALUE, file, setting, values, values));
		}
		if (hookFile != null)
		{
			groups.add(new EntryGroup(EntryEffect.HOOKS_RUN_WITHOUT_REVIEW, hookFile, HookTrust.STATE_TABLE, hooks,
				hashes));
		}
		return List.copyOf(groups);
	}

	/**
	 * Tells the user when uninstall left {@code default_permissions}, CAT's profile of a built-in profile or of a
	 * sandbox mode, or the hash of a hook that the user changed after install recorded it, in place.
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
		List<String> modes = recordedModes(added);
		List<String> hooks = added.keySet().stream().map(HookTrust::hookOf).flatMap(Optional::stream).toList();
		if ((builtIns.isEmpty() && modes.isEmpty() && hooks.isEmpty()) || Files.isSymbolicLink(home) ||
			Files.isSymbolicLink(file))
		{
			return List.of();
		}
		ObjectNode document = readDocument(file);
		String selection = document.path(BuiltInProfile.SELECTION).asString("");
		List<String> sentences = new ArrayList<>();
		for (String hook : hooks)
		{
			if (HookTrust.trusted(document, hook).isPresent())
			{
				sentences.add("CAT left the hash that " + file + " records for the Codex hook " + TomlText.quote(hook) +
					" as it is, because it is no longer the hash that CAT recorded.");
			}
		}
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
		for (String mode : modes)
		{
			String profile = SandboxModeProfile.catProfile(mode);
			if (added.getOrDefault(MODE_KEY, List.of()).contains(mode) && selection.equals(profile))
			{
				sentences.add("CAT could not remove the line default_permissions = " + TomlText.quote(profile) +
					" from " + file + ", because it cannot edit that line; remove it yourself.");
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
	 * Returns the sandbox modes whose replacement by CAT's profile an install recorded.
	 *
	 * @param added the recorded entries, by entry key
	 * @return the sandbox modes, without repeats
	 */
	private static List<String> recordedModes(Map<String, List<String>> added)
	{
		Set<String> modes = new TreeSet<>(added.getOrDefault(MODE_KEY, List.of()));
		for (String mode : SandboxModeProfile.MODES)
			modes.addAll(added.getOrDefault(sandboxProfileKey(mode), List.of()));
		modes.retainAll(SandboxModeProfile.MODES);
		return List.copyOf(modes);
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
	 * writable root from {@code config.toml}, and removes each recorded write or read entry from the permission profile
	 * that it was added to, when that entry still has the access that CAT gave it, and the recorded
	 * {@code enabled = true} from a profile's network table. Then sets {@code default_permissions} back to the
	 * recorded built-in profile while it still names CAT's profile of it, removes the {@code default_permissions} line
	 * that CAT added in place of a sandbox mode while it still names CAT's profile of that mode, and removes CAT's
	 * profiles while they hold only what CAT wrote and are not selected. Leaves a changed rules file, a file
	 * reached through a symbolic link, the writable roots' array and table, each profile's filesystem table of the
	 * user's profiles, and every other entry in place. Removes each recorded hook hash from {@code config.toml} while
	 * the file still records it for its hook, with the hook's table when the hash was all that the table held.
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
	 * Removes recorded writable roots and recorded profile write, read and network entries from {@code config.toml},
	 * then sets {@code default_permissions} back to a recorded built-in profile while it names CAT's profile of it,
	 * removes the {@code default_permissions} line that CAT added in place of a sandbox mode while it names CAT's
	 * profile of that mode, removes CAT's profiles while they hold only what CAT wrote, and removes each recorded
	 * hook hash that the file still records for its hook. A recorded entry of a profile that the file no longer
	 * defines needs no removal.
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
				edited = PermissionProfile.remove(edited, profile, path, Access.WRITE, file);
			for (String path : added.getOrDefault(profileKey(profile) + READ_SUFFIX, List.of()))
				edited = PermissionProfile.remove(edited, profile, path, Access.READ, file);
			if (added.getOrDefault(networkKey(profile), List.of()).contains("true"))
				edited = PermissionProfile.disableNetwork(edited, profile, file);
		}
		for (String builtIn : recordedBuiltIns(added))
			edited = BuiltInProfile.remove(edited, builtIn, file);
		for (String mode : recordedModes(added))
			edited = SandboxModeProfile.remove(edited, mode, file);
		for (Map.Entry<String, List<String>> entry : added.entrySet())
		{
			Optional<String> hook = HookTrust.hookOf(entry.getKey());
			if (hook.isEmpty())
				continue;
			for (String hash : entry.getValue())
				edited = HookTrust.remove(edited, hook.get(), hash, file);
		}
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
	 * Requires Codex to load exactly the validated marketplace source from the version folder that {@code version}
	 * names.
	 *
	 * @param home    the absolute, normalized Codex home
	 * @param version the registered plugin version
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or not normalized
	 * @throws IOException              if Codex loads another version folder or its cache copy differs from the source;
	 *                                  the message of the second failure names the first path that differs
	 */
	public static void requireLoaded(Path home, PluginVersion version) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(version, "version").isNotNull();
		CodexInstallation installation = new CodexInstallation(home);
		Path plugin = installation.marketplacePlugin();
		InstalledState state = new CodexActivation().installed(home);
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
