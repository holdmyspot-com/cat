package com.holdmyspot.cat.claude.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Registers CAT's version folders with Claude Code through its {@code claude} program, lets agents run the trusted
 * installer through entries in Claude Code's user {@code settings.json}, and keeps sandboxed commands from writing the
 * other harness's CAT installation.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Its methods change files that Claude Code also writes; the installer
 * holds its harness lock while calling them, but a running Claude Code session can still rewrite {@code settings.json}.
 */
public final class Integration implements HarnessIntegration
{
	/**
	 * Whether an agent may run {@code uninstall} without Claude Code asking the user. The user chose the approval
	 * prompt, so uninstall gets only a sandbox exclusion.
	 */
	private static final boolean UNINSTALL_PROMPT_FREE = false;
	/**
	 * The settings file, relative to the configuration directory, that holds CAT's permission entries.
	 */
	private static final String SETTINGS = "settings.json";
	/**
	 * The commands that run outside Claude Code's shell sandbox.
	 */
	private static final String EXCLUDED_COMMANDS = "sandbox.excludedCommands";
	/**
	 * The tool uses that Claude Code allows without asking the user.
	 */
	private static final String ALLOWED_TOOLS = "permissions.allow";
	/**
	 * The paths that sandboxed commands may not write, even inside a path that they may write.
	 */
	private static final String DENY_WRITE = "sandbox.filesystem.denyWrite";
	/**
	 * The paths that sandboxed commands may write in addition to the working directory.
	 */
	private static final String ALLOW_WRITE = "sandbox.filesystem.allowWrite";
	/**
	 * Every setting that this integration adds entries to.
	 */
	private static final List<String> MAINTAINED_SETTINGS = List.of(EXCLUDED_COMMANDS, ALLOWED_TOOLS, DENY_WRITE,
		ALLOW_WRITE);
	/**
	 * The file names of the runner launchers that CAT's installation guide told users to add entries for.
	 */
	private static final Set<String> RUNNER_LAUNCHERS = Set.of("claude-runner", "codex-runner", "claude-runner.cmd",
		"codex-runner.cmd");
	/**
	 * The marketplace metadata that a plugin version folder ships, relative to that folder.
	 */
	private static final Path MARKETPLACE_METADATA = Path.of("install/claude-marketplace.json");
	/**
	 * The scope that {@code claude plugin install} installs CAT in.
	 */
	private static final String USER_SCOPE = "user";
	/**
	 * Reads settings, refusing duplicate members, whose rewrite would silently drop all but one.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
		build();
	/**
	 * Writes settings in the layout that Claude Code writes: two-space indentation, one array element per line, a
	 * space only after each colon, and a final line feed added by the caller.
	 */
	private static final ObjectWriter WRITER = MAPPER.writer().with(new DefaultPrettyPrinter(Separators.
		createDefaultInstance().
		withObjectNameValueSpacing(Separators.Spacing.AFTER).
		withObjectEmptySeparator("").
		withArrayEmptySeparator("")).
		withObjectIndenter(new DefaultIndenter("  ", "\n")).
		withArrayIndenter(new DefaultIndenter("  ", "\n")));

	private final Platform platform;

	/**
	 * Creates a provider for service loading, for the running platform.
	 */
	public Integration()
	{
		this(Platform.current());
	}

	/**
	 * Creates a provider.
	 *
	 * @param platform the platform that Claude Code and the installer run on
	 * @throws NullPointerException if {@code platform} is null
	 */
	public Integration(Platform platform)
	{
		requireThat(platform, "platform").isNotNull();
		this.platform = platform;
	}

	@Override
	public String harness()
	{
		return "claude";
	}

	@Override
	public String productName()
	{
		return "Claude Code";
	}

	@Override
	public String agentSessionVariable()
	{
		return "CLAUDE_CODE_SESSION_ID";
	}

	@Override
	public HarnessInstallation installation(Path home)
	{
		return new ClaudeInstallation(home);
	}

	@Override
	public HarnessInstallation installation(Map<String, String> environment, Path userHome)
	{
		return ClaudeInstallation.fromEnvironment(environment, userHome);
	}

	/**
	 * Returns the plugin version's version folder: Claude Code runs a plugin from CAT's marketplace in place.
	 */
	@Override
	public Path marketplaceFolder(Path home, PluginVersion version)
	{
		return new ClaudeInstallation(home).versionFolder(version);
	}

	@Override
	public String programName()
	{
		return "claude";
	}

	/**
	 * Returns the user's home directory variable and, when the configuration directory is not
	 * {@code <user home>/.claude}, {@code CLAUDE_CONFIG_DIR}, which also moves Claude Code's {@code .claude.json}.
	 *
	 * @param home        the absolute, normalized Claude Code configuration directory that setup records
	 * @param environment the setup process's environment variables, looked up case-insensitively on Windows
	 * @return the variables
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the user's home directory is unknown
	 */
	@Override
	public Map<String, String> programEnvironment(Path home, Map<String, String> environment) throws IOException
	{
		requireThat(home, "home").isAbsolute();
		requireThat(environment, "environment").isNotNull();
		String homeVariable = platform.homeVariable();
		String userHome = environment.get(homeVariable);
		if (userHome == null || userHome.isBlank())
			throw new IOException(homeVariable + " is not set, so install cannot find Claude Code's default directory");
		Map<String, String> variables = new HashMap<>();
		variables.put(homeVariable, userHome);
		if (!home.normalize().equals(Path.of(userHome).toAbsolutePath().normalize().resolve(".claude")))
			variables.put("CLAUDE_CONFIG_DIR", home.normalize().toString());
		return variables;
	}

	/**
	 * Fails unless {@code claude --version} exited successfully and named Claude Code.
	 *
	 * @param version the exit status and output of {@code claude --version}
	 * @throws NullPointerException if {@code version} is null
	 * @throws IOException          if the program is not Claude Code
	 */
	@Override
	public void requireIdentity(CommandResult version) throws IOException
	{
		requireThat(version, "version").isNotNull();
		if (version.exitCode() != 0 || !version.output().contains("Claude Code"))
		{
			throw new IOException("claude --version did not identify Claude Code (exit status " + version.exitCode() +
				"): " + version.output().strip() + ". Install Claude Code, or put its directory first on PATH, and " +
				"run CAT's install again.");
		}
	}

	/**
	 * Refuses a {@code settings.json} that permission and sandbox entry installation cannot change safely.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if {@code settings.json} or the configuration directory is a symbolic link,
	 *                                  {@code settings.json} is not a regular file or not a JSON object, or its
	 *                                  {@code sandbox}, {@code sandbox.filesystem} or {@code permissions} member is not
	 *                                  an object, or its {@code sandbox.excludedCommands}, {@code permissions.allow},
	 *                                  {@code sandbox.filesystem.denyWrite} or {@code sandbox.filesystem.allowWrite}
	 *                                  member is not an array
	 */
	@Override
	public void validateConfiguration(Path home) throws IOException
	{
		readSettings(home);
	}

	/**
	 * Reads and validates {@code settings.json}.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the settings, or an empty object if the file does not exist
	 * @throws IOException if the settings cannot be changed safely
	 */
	private static ObjectNode readSettings(Path home) throws IOException
	{
		Path settings = settingsFile(home);
		if (Files.notExists(settings, LinkOption.NOFOLLOW_LINKS))
			return MAPPER.createObjectNode();
		// The settings file is small user configuration whose merge needs the whole document.
		ObjectNode object = readObject(settings, "Claude Code's settings file");
		for (String setting : MAINTAINED_SETTINGS)
		{
			String[] names = setting.split("\\.");
			JsonNode node = object;
			for (int index = 0; index < names.length; ++index)
			{
				node = node.get(names[index]);
				if (node == null)
					break;
				String member = String.join(".", Arrays.copyOf(names, index + 1));
				boolean leaf = index == names.length - 1;
				if (leaf && !node.isArray())
					throw new IOException(settings + ": the \"" + member + "\" member is not a JSON array");
				if (!leaf && !node.isObject())
					throw new IOException(settings + ": the \"" + member + "\" member is not a JSON object");
			}
		}
		return object;
	}

	/**
	 * Locates {@code settings.json} and refuses links that could redirect its change.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the settings file's path
	 * @throws IOException if the configuration directory or the settings file is a symbolic link
	 */
	private static Path settingsFile(Path home) throws IOException
	{
		requireThat(home, "home").isAbsolute();
		Path settings = home.normalize().resolve(SETTINGS);
		Locations.checkLinks(home, settings);
		return settings;
	}

	/**
	 * Points CAT's marketplace at a plugin version folder and asks Claude Code to load CAT from it. Claude Code loads a
	 * directory marketplace's plugin in place, from the folder that the marketplace manifest's source names.
	 * <ol>
	 *   <li>Writes the marketplace manifest from the version folder's marketplace metadata, with CAT's source set to
	 *   the version folder.</li>
	 *   <li>Adds the marketplace, which Claude Code accepts when it is already added.</li>
	 *   <li>Runs {@code plugin install cat@cat} when Claude Code records no CAT installation in the user scope, and
	 *   otherwise {@code plugin update cat@cat}, because install leaves an installed plugin as it is.</li>
	 *   <li>Requires Claude Code to record {@code version} as CAT's installed version.</li>
	 * </ol>
	 *
	 * @param home     the absolute Claude Code configuration directory recorded in policy
	 * @param version  the plugin version, whose version folder install has written
	 * @param cli      runs the recorded {@code claude} program
	 * @param replacer replaces the marketplace manifest
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the version folder lacks its marketplace metadata, a path component is a
	 *                                  symbolic link, Claude Code refuses a command, or Claude Code records another
	 *                                  version afterwards
	 */
	@Override
	public void register(Path home, PluginVersion version, HarnessCli cli, FileReplacer replacer) throws IOException
	{
		requireThat(cli, "cli").isNotNull();
		writeMarketplaceManifest(home, version, replacer);
		PluginCommand.addMarketplace(new ClaudeInstallation(home).marketplaceRoot()).run(cli);
		PluginCommand command = registrationCommand(home);
		command.run(cli);
		requireSwitched(home, version, command);
	}

	/**
	 * Writes the marketplace manifest that names a plugin version's folder as CAT's source, unless the manifest already
	 * holds exactly that content. The folder's marketplace metadata, which the plugin archive ships, supplies the rest
	 * of the manifest.
	 *
	 * @param home     the absolute Claude Code configuration directory recorded in policy
	 * @param version  the plugin version, whose version folder install has written
	 * @param replacer replaces the marketplace manifest
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the version folder lacks its marketplace metadata, the metadata is not a JSON
	 *                                  object with a {@code cat} plugin entry, or a path component is a symbolic link
	 */
	public static void writeMarketplaceManifest(Path home, PluginVersion version, FileReplacer replacer)
		throws IOException
	{
		requireThat(version, "version").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		ClaudeInstallation installation = new ClaudeInstallation(home);
		Path root = installation.marketplaceRoot();
		Path versionFolder = installation.versionFolder(version);
		Path metadata = versionFolder.resolve(MARKETPLACE_METADATA);
		Path manifest = installation.marketplaceManifest();
		Locations.checkLinks(home, metadata);
		Locations.checkLinks(home, manifest);
		if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The plugin version folder lacks its marketplace metadata: " + metadata);
		byte[] content = marketplaceManifest(metadata, root.relativize(versionFolder));
		if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) ||
			!Arrays.equals(Files.readAllBytes(manifest), content))
		{
			replace(manifest, content, replacer);
		}
	}

	/**
	 * Returns the command that makes Claude Code load CAT from the marketplace.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return {@code plugin update cat@cat} when Claude Code records a CAT installation in the user scope, otherwise
	 * 	{@code plugin install cat@cat}
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the record is a symbolic link, cannot be read or is not a JSON object
	 */
	public static PluginCommand registrationCommand(Path home) throws IOException
	{
		return PluginCommand.registerPlugin(installedVersion(home).isPresent());
	}

	/**
	 * Requires Claude Code to record a plugin version as CAT's installed version, so that registration does not report
	 * a version that Claude Code does not load.
	 *
	 * @param home    the absolute Claude Code configuration directory recorded in policy
	 * @param version the plugin version that registration asked Claude Code to load
	 * @param command the command that Claude Code ran to load it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the record cannot be read, or records another version or none; the message
	 *                                  names {@code command}, {@code version} and the recorded version
	 */
	public static void requireSwitched(Path home, PluginVersion version, PluginCommand command) throws IOException
	{
		requireThat(version, "version").isNotNull();
		requireThat(command, "command").isNotNull();
		Optional<String> installed = installedVersion(home);
		if (!installed.equals(Optional.of(version.toString())))
		{
			throw new IOException("claude " + String.join(" ", command.arguments()) + " did not switch CAT to " + version +
				": " + new ClaudeInstallation(home).installedPlugins() + " records " + installed.orElse("no CAT installation") +
				".");
		}
	}

	/**
	 * Returns the marketplace manifest that names a version folder as CAT's source.
	 *
	 * @param metadata the version folder's marketplace metadata
	 * @param source   the version folder relative to the marketplace root
	 * @return the manifest's content, with a final line feed
	 * @throws IOException if the metadata cannot be read, is not a JSON object or has no {@code cat} plugin entry
	 */
	private static byte[] marketplaceManifest(Path metadata, Path source) throws IOException
	{
		ObjectNode root = readObject(metadata, "The plugin version folder's marketplace metadata");
		StringJoiner relative = new StringJoiner("/", "./", "");
		for (Path name : source)
			relative.add(name.toString());
		boolean named = false;
		for (JsonNode entry : root.path("plugins"))
		{
			if (entry instanceof ObjectNode plugin && plugin.path("name").asString("").equals("cat"))
			{
				plugin.put("source", relative.toString());
				named = true;
			}
		}
		if (!named)
			throw new IOException("The marketplace metadata " + metadata + " has no plugin entry named cat");
		return (WRITER.writeValueAsString(root) + "\n").getBytes(UTF_8);
	}

	/**
	 * Returns the version that Claude Code records for CAT's installation in the user scope.
	 *
	 * @param home the absolute Claude Code configuration directory
	 * @return the version, or empty if Claude Code records no such installation
	 * @throws IOException if the record is a symbolic link, cannot be read or is not a JSON object
	 */
	static Optional<String> installedVersion(Path home) throws IOException
	{
		Path record = new ClaudeInstallation(home).installedPlugins();
		Locations.checkLinks(home, record);
		if (Files.notExists(record, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		ObjectNode root = readObject(record, "Claude Code's installed plugin record");
		for (JsonNode installation : root.path("plugins").path("cat@cat"))
		{
			if (installation.path("scope").asString("").equals(USER_SCOPE) && installation.path("version").isString())
				return Optional.of(installation.path("version").asString());
		}
		return Optional.empty();
	}

	/**
	 * Reads a small JSON file that holds an object.
	 *
	 * @param file        the file
	 * @param description the file's description in error messages
	 * @return the object
	 * @throws IOException if the file is not a regular file, cannot be read or does not hold a JSON object
	 */
	private static ObjectNode readObject(Path file, String description) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException(description + " is not a regular file: " + file);
		JsonNode root;
		try
		{
			root = MAPPER.readTree(Files.readString(file));
		}
		catch (JacksonException e)
		{
			throw new IOException(description + " is not valid JSON: " + file + ": " + e.getOriginalMessage(), e);
		}
		if (!(root instanceof ObjectNode object))
			throw new IOException(description + " does not hold a JSON object: " + file);
		return object;
	}

	/**
	 * Uninstalls CAT and then removes its marketplace. Claude Code's answers that either is absent are success. Claude
	 * Code's uninstall deletes the plugin's data directory unless it is given {@code --keep-data}.
	 *
	 * @param home     the absolute Claude Code configuration directory recorded in policy
	 * @param cli      runs the recorded {@code claude} program
	 * @param keepData true to pass {@code --keep-data}, which keeps the plugin's data directory
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if Claude Code refuses a command for another reason
	 */
	@Override
	public void unregister(Path home, HarnessCli cli, boolean keepData) throws IOException
	{
		requireThat(home, "home").isAbsolute();
		requireThat(cli, "cli").isNotNull();
		for (PluginCommand command : PluginCommand.unregistration(keepData))
			command.run(cli);
	}

	/**
	 * Merges the installer's entries into {@code sandbox.excludedCommands} and {@code permissions.allow} of
	 * {@code settings.json}, creating the file and members when absent. Every operation runs outside the shell
	 * sandbox; install into Claude Code, of the latest or a named release, and status are also allowed without asking
	 * the user, while uninstall keeps Claude Code's approval prompt.
	 *
	 * @param home      the absolute Claude Code configuration directory recorded in policy
	 * @param installer the absolute path of the installer launcher in the plugin's version folder
	 * @param replacer  replaces {@code settings.json}
	 * @return the added entries, keyed {@code settings.json#sandbox.excludedCommands} and
	 * 	{@code settings.json#permissions.allow}; a key is absent when nothing was added to it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either path is relative
	 * @throws IOException              if the settings cannot be changed safely, or on Windows the launcher path
	 *                                  holds a character that the shell would expand inside double quotes
	 */
	@Override
	public Map<String, List<String>> installPermissions(Path home, Path installer, FileReplacer replacer)
		throws IOException
	{
		requireThat(installer, "installer").isAbsolute();
		requireThat(replacer, "replacer").isNotNull();
		return addEntries(home, entries(installer), replacer);
	}

	/**
	 * Removes from {@code settings.json} the runner entries that CAT's installation guide told users to add: each
	 * {@code sandbox.excludedCommands} entry {@code <launcher> *} and each {@code permissions.allow} entry
	 * {@code Bash(<launcher> *)}, where {@code <launcher>} is the {@code claude-runner} or {@code codex-runner}
	 * launcher, with or without {@code .cmd}, in {@code bin} of CAT's plugin folder or of one of its version folders.
	 * CAT's runners no longer run outside the sandbox. Every other entry stays, and settings without such an entry
	 * stay as they are.
	 *
	 * @param home     the absolute Claude Code configuration directory recorded in policy
	 * @param replacer replaces {@code settings.json}
	 * @return one sentence for each setting that lost entries, naming the entries and the file; empty when nothing
	 * 	was removed
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the settings cannot be changed safely
	 */
	@Override
	public List<String> removeRunnerExceptions(Path home, FileReplacer replacer) throws IOException
	{
		requireThat(replacer, "replacer").isNotNull();
		Path settings = settingsFile(home);
		if (Files.notExists(settings, LinkOption.NOFOLLOW_LINKS))
			return List.of();
		ObjectNode root = readSettings(home);
		Path plugin = new ClaudeInstallation(home.normalize()).marketplacePlugin();

		List<String> sentences = new ArrayList<>();
		for (String setting : List.of(EXCLUDED_COMMANDS, ALLOWED_TOOLS))
		{
			Optional<ArrayNode> found = findArray(root, setting);
			if (found.isEmpty())
				continue;
			ArrayNode array = found.get();
			List<String> removed = new ArrayList<>();
			for (int index = array.size() - 1; index >= 0; --index)
			{
				JsonNode element = array.get(index);
				if (element.isString() && isRunnerException(setting, element.asString(), plugin))
				{
					array.remove(index);
					removed.addFirst(MAPPER.writeValueAsString(element.asString()));
				}
			}
			if (!removed.isEmpty())
			{
				sentences.add("CAT removed " + String.join(", ", removed) + " from " + setting + " in " + settings +
					", which CAT's installation guide told you to add for its runners; CAT's runners no longer run " +
					"outside the sandbox.");
			}
		}
		if (!sentences.isEmpty())
			writeSettings(home, root, replacer);
		return List.copyOf(sentences);
	}

	/**
	 * Reports whether a setting's entry is a runner entry that CAT's installation guide told users to add.
	 *
	 * @param setting the entry's dotted setting name, {@code sandbox.excludedCommands} or {@code permissions.allow}
	 * @param entry   the entry
	 * @param plugin  CAT's plugin folder in the configuration directory
	 * @return true if the entry is {@code <launcher> *} in {@code sandbox.excludedCommands} or
	 * 	{@code Bash(<launcher> *)} in {@code permissions.allow}, for a runner launcher of {@code plugin}
	 */
	private static boolean isRunnerException(String setting, String entry, Path plugin)
	{
		String command;
		if (setting.equals(EXCLUDED_COMMANDS))
			command = entry;
		else if (entry.startsWith("Bash(") && entry.endsWith(")"))
			command = entry.substring("Bash(".length(), entry.length() - 1);
		else
			return false;
		return command.endsWith(" *") &&
			isRunnerLauncher(command.substring(0, command.length() - " *".length()), plugin);
	}

	/**
	 * Reports whether a path names a runner launcher in {@code bin} of CAT's plugin folder or of one of its version
	 * folders, spelled exactly as that path.
	 *
	 * @param text   the path
	 * @param plugin CAT's plugin folder in the configuration directory
	 * @return true if {@code text} names {@code claude-runner} or {@code codex-runner}, with or without {@code .cmd}
	 */
	private static boolean isRunnerLauncher(String text, Path plugin)
	{
		Path launcher;
		try
		{
			launcher = Path.of(text);
		}
		catch (InvalidPathException _)
		{
			return false;
		}
		// Windows accepts either separator; any other difference in spelling is not the documented entry
		if (!launcher.isAbsolute() || !launcher.equals(launcher.normalize()) ||
			!launcher.toString().equals(text.replace('/', File.separatorChar)))
		{
			return false;
		}
		Path bin = launcher.getParent();
		if (!RUNNER_LAUNCHERS.contains(launcher.getFileName().toString()) || bin == null ||
			bin.getFileName() == null || !bin.getFileName().toString().equals("bin"))
		{
			return false;
		}
		Path folder = bin.getParent();
		return folder.equals(plugin) || plugin.equals(folder.getParent());
	}

	/**
	 * Adds the entries that a setting's array lacks, creating the file, objects and arrays when absent, and writes the
	 * settings only when something was added.
	 *
	 * @param home     the absolute Claude Code configuration directory
	 * @param entries  the entries, by dotted setting name
	 * @param replacer replaces {@code settings.json}
	 * @return the added entries, by entry key; a key is absent when nothing was added to its setting
	 * @throws IOException if the settings cannot be changed safely
	 */
	private static Map<String, List<String>> addEntries(Path home, Map<String, List<String>> entries,
		FileReplacer replacer) throws IOException
	{
		ObjectNode root = readSettings(home);

		Map<String, List<String>> added = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> setting : entries.entrySet())
		{
			ArrayNode array = settingArray(root, setting.getKey());
			Set<String> present = new LinkedHashSet<>();
			for (JsonNode element : array)
			{
				if (element.isString())
					present.add(element.asString());
			}
			List<String> addedToSetting = new ArrayList<>();
			for (String entry : setting.getValue())
			{
				if (present.add(entry))
				{
					array.add(entry);
					addedToSetting.add(entry);
				}
			}
			if (!addedToSetting.isEmpty())
				added.put(SETTINGS + "#" + setting.getKey(), List.copyOf(addedToSetting));
		}
		if (!added.isEmpty())
			writeSettings(home, root, replacer);
		return Map.copyOf(added);
	}

	/**
	 * Returns the sandbox exclusions and prompt-free permissions that {@link #installPermissions} merges into
	 * {@code settings.json}.
	 *
	 * @param installer the absolute path of the installer launcher in the plugin's version folder
	 * @return the entries, keyed {@code settings.json#sandbox.excludedCommands} and
	 * 	{@code settings.json#permissions.allow}
	 * @throws NullPointerException     if {@code installer} is null
	 * @throws IllegalArgumentException if {@code installer} is relative
	 * @throws IOException              if, on Windows, the launcher path holds a character that the shell would expand
	 *                                  inside double quotes
	 */
	@Override
	public Map<String, List<String>> permissions(Path installer) throws IOException
	{
		requireThat(installer, "installer").isAbsolute();
		return keyed(entries(installer));
	}

	/**
	 * Keys entries by entry key.
	 *
	 * @param entries the entries, by dotted setting name
	 * @return the entries, keyed {@code settings.json#<setting>}, in the same order
	 */
	private static Map<String, List<String>> keyed(Map<String, List<String>> entries)
	{
		Map<String, List<String>> keyed = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> setting : entries.entrySet())
			keyed.put(SETTINGS + "#" + setting.getKey(), setting.getValue());
		return keyed;
	}

	/**
	 * Returns the entries for each setting. Every sandbox exclusion is one exact command except the pattern for
	 * {@code install --harness claude} followed by a release, so an install of a named release runs outside the
	 * sandbox without a prompt. Claude Code 2.1.277 and later run a call outside the sandbox only when every command
	 * in it matches an exclusion, and a redirect, a {@code cd} or a command substitution keeps the call sandboxed, so
	 * a command chained after the launcher stays inside the sandbox. Earlier versions ran the whole call outside the
	 * sandbox when one command matched a wildcard exclusion, so CAT's Claude Code support requires 2.1.277 or later.
	 * Every other exclusion stays exact, because no other operation takes a release.
	 *
	 * @param installer the absolute path of the installer launcher in the plugin's version folder
	 * @return the sandbox exclusions and the prompt-free permissions, in a stable order
	 * @throws IOException if the launcher path cannot be named safely
	 */
	private Map<String, List<String>> entries(Path installer) throws IOException
	{
		String launcher = launcherCommand(installer);
		String install = launcher + " install --harness claude";
		List<String> status = List.of(launcher + " status", launcher + " status --harness claude");
		List<String> uninstall = List.of(launcher + " uninstall --harness claude",
			launcher + " uninstall --harness claude --delete-data");

		List<String> excluded = new ArrayList<>(List.of(install, install + " latest", versionPattern(install)));
		excluded.addAll(status);
		excluded.addAll(uninstall);

		List<String> promptFree = new ArrayList<>(List.of(install, versionPattern(install)));
		promptFree.addAll(status);
		if (UNINSTALL_PROMPT_FREE)
			promptFree.addAll(uninstall);
		List<String> allowed = new ArrayList<>();
		for (String entry : promptFree)
			allowed.add("Bash(" + entry + ")");
		Map<String, List<String>> entries = new LinkedHashMap<>();
		entries.put(EXCLUDED_COMMANDS, List.copyOf(excluded));
		entries.put(ALLOWED_TOOLS, List.copyOf(allowed));
		return entries;
	}

	/**
	 * Returns the shell text that runs the installer launcher.
	 *
	 * @param installer the launcher's absolute path
	 * @return on Windows, PowerShell's fixed non-interactive form for the launcher script; elsewhere, the quoted path
	 * @throws IOException if, on Windows, the path holds {@code "}, {@code $} or {@code `}, which the shell that
	 *                     Claude Code runs would interpret inside double quotes
	 */
	private String launcherCommand(Path installer) throws IOException
	{
		String path = installer.toString();
		if (platform.isWindows())
		{
			for (String character : List.of("\"", "$", "`"))
			{
				if (path.contains(character))
				{
					throw new IOException("The installer's launcher path contains " + character + ", which a permission " +
						"entry cannot name: " + path);
				}
			}
			return "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + path + "\"";
		}
		// Claude's wildcard classification ignores shell quotes. Escape a literal star outside the quoted path
		// so an exact entry cannot permit another executable.
		return ShellQuoting.posix(path).replace("*", "'\\*'");
	}

	/**
	 * Returns the pattern that matches an exact command followed by a version argument.
	 *
	 * @param command the exact command
	 * @return the matcher pattern, whose escapes keep the command's spelling literal
	 */
	private static String versionPattern(String command)
	{
		return command.replace("\\", "\\\\").replace("*", "\\*") + " *";
	}

	/**
	 * Returns a setting's array, creating it and its parent objects when absent.
	 *
	 * @param root    the validated settings
	 * @param setting the setting's dotted name
	 * @return the array
	 */
	private static ArrayNode settingArray(ObjectNode root, String setting)
	{
		String[] names = setting.split("\\.");
		ObjectNode parent = root;
		for (int index = 0; index < names.length - 1; ++index)
		{
			if (parent.get(names[index]) instanceof ObjectNode existing)
				parent = existing;
			else
				parent = parent.putObject(names[index]);
		}
		if (parent.get(names[names.length - 1]) instanceof ArrayNode existing)
			return existing;
		return parent.putArray(names[names.length - 1]);
	}

	/**
	 * Finds a setting's array.
	 *
	 * @param root    the validated settings
	 * @param setting the setting's dotted name
	 * @return the array, or empty if the setting or one of its parent objects is absent
	 */
	private static Optional<ArrayNode> findArray(ObjectNode root, String setting)
	{
		JsonNode node = root;
		for (String name : setting.split("\\."))
		{
			node = node.get(name);
			if (node == null)
				return Optional.empty();
		}
		return Optional.of((ArrayNode) node);
	}

	/**
	 * Writes the settings through a temporary sibling and an atomic replacement.
	 *
	 * @param home     the configuration directory
	 * @param root     the settings
	 * @param replacer replaces the file
	 * @throws IOException if the file cannot be written
	 */
	private static void writeSettings(Path home, ObjectNode root, FileReplacer replacer) throws IOException
	{
		replace(settingsFile(home), (WRITER.writeValueAsString(root) + "\n").getBytes(UTF_8), replacer);
	}

	/**
	 * Replaces a file's content through a temporary sibling and an atomic rename.
	 *
	 * @param target   the file
	 * @param content  the new content
	 * @param replacer replaces the file, retrying while Windows refuses the replacement
	 * @throws IOException if the file cannot be written
	 */
	private static void replace(Path target, byte[] content, FileReplacer replacer) throws IOException
	{
		Files.createDirectories(target.getParent());
		Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".new");
		try
		{
			Files.write(temporary, content);
			replacer.replace(temporary, target);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Merges the sandbox entries into {@code sandbox.filesystem.denyWrite} and {@code sandbox.filesystem.allowWrite}
	 * of {@code settings.json}, creating the file and members when absent.
	 *
	 * @param home          the absolute Claude Code configuration directory
	 * @param other         the other harness's CAT installation
	 * @param catLocks      the absolute capacity lock directory
	 * @param launcherState the absolute directory that holds the session launcher's state
	 * @param replacer      replaces {@code settings.json}
	 * @return the added entries, keyed {@code settings.json#sandbox.filesystem.denyWrite} and
	 * 	{@code settings.json#sandbox.filesystem.allowWrite}; a key is absent when nothing was added to it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home}, {@code catLocks} or {@code launcherState} are relative
	 * @throws IOException              if the settings cannot be changed safely
	 */
	@Override
	public Map<String, List<String>> installSandboxEntries(Path home, HarnessInstallation other, Path catLocks,
		Path launcherState, FileReplacer replacer) throws IOException
	{
		requireThat(replacer, "replacer").isNotNull();
		return addEntries(home, filesystemEntries(home, other, catLocks, launcherState), replacer);
	}

	/**
	 * Returns the entries that keep sandboxed commands from writing the other harness's CAT installation and the session
	 * launcher's state, and that let them take the capacity locks. The denied folders are the other harness's
	 * marketplace folder, which decides what that harness installs, its CAT version folders, from which it runs CAT,
	 * CAT's data directory there, which holds the installer's policy, and the session launcher's state, which the
	 * launcher trusts only while no sandboxed command can write it. Claude Code's sandbox already denies writes below
	 * its own {@code plugins} folder.
	 *
	 * @param home          the absolute Claude Code configuration directory
	 * @param other         the other harness's CAT installation
	 * @param catLocks      the absolute capacity lock directory
	 * @param launcherState the absolute directory that holds the session launcher's state
	 * @return the entries, keyed {@code settings.json#sandbox.filesystem.denyWrite} and
	 * 	{@code settings.json#sandbox.filesystem.allowWrite}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home}, {@code catLocks} or {@code launcherState} are relative
	 */
	@Override
	public Map<String, List<String>> sandboxEntries(Path home, HarnessInstallation other, Path catLocks,
		Path launcherState)
	{
		return keyed(filesystemEntries(home, other, catLocks, launcherState));
	}

	/**
	 * Returns the sandbox entries for each setting.
	 *
	 * @param home          the absolute Claude Code configuration directory
	 * @param other         the other harness's CAT installation
	 * @param catLocks      the absolute capacity lock directory
	 * @param launcherState the absolute directory that holds the session launcher's state
	 * @return the denied and the allowed paths, by dotted setting name, in a stable order
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home}, {@code catLocks} or {@code launcherState} are relative
	 */
	private static Map<String, List<String>> filesystemEntries(Path home, HarnessInstallation other, Path catLocks,
		Path launcherState)
	{
		requireThat(home, "home").isAbsolute();
		requireThat(other, "other").isNotNull();
		requireThat(catLocks, "catLocks").isAbsolute();
		requireThat(launcherState, "launcherState").isAbsolute();
		Map<String, List<String>> entries = new LinkedHashMap<>();
		entries.put(DENY_WRITE, List.of(other.marketplaceRoot().toString(), other.versionFolders().toString(),
			other.dataDirectory().toString(), launcherState.toString()));
		entries.put(ALLOW_WRITE, List.of(catLocks.toString()));
		return entries;
	}

	/**
	 * Asks nothing: install adds every entry that Claude Code needs to settings that only CAT's entries change.
	 *
	 * @return empty
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative
	 */
	@Override
	public Optional<String> consentRequest(Path home, Path catLocks)
	{
		requireThat(home, "home").isAbsolute();
		requireThat(catLocks, "catLocks").isAbsolute();
		return Optional.empty();
	}

	/**
	 * Changes nothing, because {@link #consentRequest} asks nothing.
	 *
	 * @return an empty map
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	@Override
	public Map<String, List<String>> installConsentedEntries(Path home, FileReplacer replacer)
	{
		requireThat(home, "home").isAbsolute();
		requireThat(replacer, "replacer").isNotNull();
		return Map.of();
	}

	/**
	 * Returns no sentences: install reports nothing about the entries that it adds to {@code settings.json}.
	 *
	 * @return an empty list
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	@Override
	public List<String> describeAdded(Path home, Map<String, List<String>> added)
	{
		requireThat(home, "home").isAbsolute();
		requireThat(added, "added").isNotNull();
		return List.of();
	}

	/**
	 * Groups the recorded entries of {@code settings.json} by setting: {@code permissions.allow} names the installer
	 * operations that Claude Code runs without asking, {@code sandbox.excludedCommands} the operations that it runs
	 * outside its sandbox, {@code sandbox.filesystem.allowWrite} the paths that sandboxed commands may write, and
	 * {@code sandbox.filesystem.denyWrite} the paths that they may not write. Any other setting is a group of the
	 * values that it records.
	 *
	 * @throws IllegalArgumentException if {@code installer} is relative
	 * @throws IOException              if, on Windows, the launcher path holds {@code "}, {@code $} or {@code `}
	 */
	@Override
	public List<EntryGroup> describeEntries(Map<String, List<String>> recorded, Path installer) throws IOException
	{
		requireThat(recorded, "recorded").isNotNull();
		requireThat(installer, "installer").isAbsolute();
		String launcher = launcherCommand(installer) + " ";
		List<EntryGroup> groups = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : recorded.entrySet())
		{
			String key = entry.getKey();
			int separator = key.indexOf('#');
			Path file = Path.of(key.substring(0, separator));
			String setting = key.substring(separator + 1);
			List<String> values = entry.getValue();
			EntryGroup group = switch (setting)
			{
				case ALLOWED_TOOLS -> new EntryGroup(EntryEffect.RUNS_WITHOUT_ASKING, file, setting,
					operations(values.stream().map(Integration::allowedCommand).toList(), launcher), values);
				case EXCLUDED_COMMANDS -> new EntryGroup(EntryEffect.RUNS_OUTSIDE_SANDBOX, file, setting,
					operations(values, launcher), values);
				case ALLOW_WRITE -> new EntryGroup(EntryEffect.SANDBOX_MAY_WRITE, file, setting, values, values);
				case DENY_WRITE -> new EntryGroup(EntryEffect.SANDBOX_MAY_NOT_WRITE, file, setting, values, values);
				default -> new EntryGroup(EntryEffect.SETS_VALUE, file, setting, values, values);
			};
			groups.add(group);
		}
		return List.copyOf(groups);
	}

	/**
	 * Returns the command that a {@code permissions.allow} entry allows.
	 *
	 * @param entry the entry
	 * @return the command inside {@code Bash(...)}, or the entry when it does not name a Bash command
	 */
	private static String allowedCommand(String entry)
	{
		if (entry.startsWith("Bash(") && entry.endsWith(")"))
			return entry.substring("Bash(".length(), entry.length() - 1);
		return entry;
	}

	/**
	 * Returns the installer operations that commands run.
	 *
	 * @param commands the commands
	 * @param launcher the shell text that runs the installer launcher, followed by a space
	 * @return the first word after {@code launcher} of each command that starts with it, such as {@code install}
	 */
	private static List<String> operations(List<String> commands, String launcher)
	{
		List<String> operations = new ArrayList<>();
		for (String command : commands)
		{
			if (command.startsWith(launcher))
				operations.add(command.substring(launcher.length()).split(" ", 2)[0]);
		}
		return operations;
	}

	/**
	 * Returns no sentences, because {@link #removePermissions} removes each recorded entry that is still present and
	 * leaves nothing of CAT's in place.
	 *
	 * @return an empty list
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	@Override
	public List<String> describeRetained(Path home, Map<String, List<String>> added)
	{
		requireThat(home, "home").isAbsolute();
		requireThat(added, "added").isNotNull();
		return List.of();
	}

	/**
	 * Removes the given entries that {@code settings.json} still holds, keeping every other member and entry.
	 *
	 * @param home     the absolute Claude Code configuration directory recorded in policy
	 * @param added    the entries that {@link #installPermissions} or {@link #installSandboxEntries} returned
	 * @param replacer replaces {@code settings.json}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code added} has a key that neither
	 *                                  {@link #installPermissions} nor {@link #installSandboxEntries} returns
	 * @throws IOException              if the settings cannot be changed safely
	 */
	@Override
	public void removePermissions(Path home, Map<String, List<String>> added, FileReplacer replacer)
		throws IOException
	{
		requireThat(added, "added").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		Map<String, List<String>> removals = new HashMap<>();
		for (Map.Entry<String, List<String>> entry : added.entrySet())
		{
			String key = entry.getKey();
			String setting = key.substring(key.indexOf('#') + 1);
			if (!key.equals(SETTINGS + "#" + setting) || !MAINTAINED_SETTINGS.contains(setting))
				throw new IllegalArgumentException("added has a key that this integration never returns: " + key);
			removals.put(setting, entry.getValue());
		}
		Path settings = settingsFile(home);
		if (Files.notExists(settings, LinkOption.NOFOLLOW_LINKS))
			return;
		ObjectNode root = readSettings(home);

		boolean changed = false;
		for (Map.Entry<String, List<String>> removal : removals.entrySet())
		{
			Optional<ArrayNode> found = findArray(root, removal.getKey());
			if (found.isEmpty())
				continue;
			ArrayNode array = found.get();
			for (int index = array.size() - 1; index >= 0; --index)
			{
				JsonNode element = array.get(index);
				if (element.isString() && removal.getValue().contains(element.asString()))
				{
					array.remove(index);
					changed = true;
				}
			}
		}
		if (changed)
			writeSettings(home, root, replacer);
	}
}
