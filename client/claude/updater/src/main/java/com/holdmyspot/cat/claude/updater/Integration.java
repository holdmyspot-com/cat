package com.holdmyspot.cat.claude.updater;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Registers CAT with Claude Code through its {@code claude} program, and lets agents run the trusted updater through
 * entries in Claude Code's user {@code settings.json}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Its methods change files that Claude Code also writes; the updater
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
	 * Claude Code 2.1.318's answer, with exit status 1, to {@code plugin uninstall cat@cat} when CAT is not installed.
	 */
	private static final String PLUGIN_NOT_FOUND = "Plugin \"cat@cat\" not found";
	/**
	 * Claude Code 2.1.318's answer, with exit status 1, to {@code plugin marketplace remove cat} when the marketplace
	 * is not added.
	 */
	private static final String MARKETPLACE_NOT_FOUND = "Marketplace 'cat' not found";
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
	 * @param platform the platform that Claude Code and the updater run on
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
			throw new IOException(homeVariable + " is not set, so setup cannot find Claude Code's default directory");
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
				"rerun setup.");
		}
	}

	/**
	 * Refuses a {@code settings.json} that permission installation cannot change safely.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if {@code settings.json} or the configuration directory is a symbolic link,
	 *                                  {@code settings.json} is not a regular file or not a JSON object, or its
	 *                                  {@code sandbox} or {@code permissions} member is not an object or its
	 *                                  {@code excludedCommands} or {@code allow} member is not an array
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
		if (!Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Claude Code's settings file is not a regular file: " + settings);
		JsonNode root;
		try
		{
			// The settings file is small user configuration whose merge needs the whole document.
			root = MAPPER.readTree(Files.readString(settings));
		}
		catch (JacksonException e)
		{
			throw new IOException("Claude Code's settings file is not valid JSON: " + settings + ": " +
				e.getOriginalMessage(), e);
		}
		if (!(root instanceof ObjectNode object))
			throw new IOException("Claude Code's settings file does not hold a JSON object: " + settings);
		for (String setting : List.of(EXCLUDED_COMMANDS, ALLOWED_TOOLS))
		{
			String[] names = setting.split("\\.");
			JsonNode parent = object.get(names[0]);
			if (parent == null)
				continue;
			if (!parent.isObject())
				throw new IOException(settings + ": the \"" + names[0] + "\" member is not a JSON object");
			JsonNode member = parent.get(names[1]);
			if (member != null && !member.isArray())
				throw new IOException(settings + ": the \"" + setting + "\" member is not a JSON array");
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
	 * Writes the release's marketplace manifest beside the activated plugin, adds the marketplace and installs CAT
	 * from it. Claude Code answers success when either is already registered.
	 *
	 * @param home     the absolute Claude Code configuration directory recorded in policy
	 * @param cli      runs the recorded {@code claude} program
	 * @param replacer replaces the marketplace manifest
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the activated release lacks its marketplace metadata, a path component is
	 *                                  a symbolic link, or Claude Code refuses a command
	 */
	@Override
	public void register(Path home, HarnessCli cli, FileReplacer replacer) throws IOException
	{
		requireThat(cli, "cli").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		Path root = Locations.marketplaceRoot(home);
		Path metadata = Locations.pluginDirectory(home).resolve("install/claude-marketplace.json");
		Path manifest = root.resolve(".claude-plugin/marketplace.json");
		Locations.checkLinks(home, metadata);
		Locations.checkLinks(home, manifest);
		if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The activated release lacks its marketplace metadata: " + metadata);
		byte[] content = Files.readAllBytes(metadata);
		if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) ||
			!Arrays.equals(Files.readAllBytes(manifest), content))
		{
			replace(manifest, content, replacer);
		}

		require(cli, List.of("plugin", "marketplace", "add", root.toString()), "");
		require(cli, List.of("plugin", "install", "cat@cat"), "");
	}

	/**
	 * Runs a {@code claude} command and refuses its failure.
	 *
	 * @param cli       runs the recorded program
	 * @param arguments the command's arguments
	 * @param absence   the answer that means the command had nothing to do, or empty if every failure counts
	 * @throws IOException if the command fails with another answer
	 */
	private static void require(HarnessCli cli, List<String> arguments, String absence) throws IOException
	{
		CommandResult result = cli.run(arguments);
		if (result.exitCode() == 0)
			return;
		if (!absence.isEmpty() && result.output().contains(absence))
			return;
		throw new IOException("claude " + String.join(" ", arguments) + " failed with exit status " +
			result.exitCode() + ": " + result.output().strip());
	}

	/**
	 * Uninstalls CAT and then removes its marketplace. Claude Code's answers that either is absent are success.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @param cli  runs the recorded {@code claude} program
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if Claude Code refuses a command for another reason
	 */
	@Override
	public void unregister(Path home, HarnessCli cli) throws IOException
	{
		requireThat(home, "home").isAbsolute();
		requireThat(cli, "cli").isNotNull();
		require(cli, List.of("plugin", "uninstall", "cat@cat"), PLUGIN_NOT_FOUND);
		require(cli, List.of("plugin", "marketplace", "remove", "cat"), MARKETPLACE_NOT_FOUND);
	}

	/**
	 * Merges the updater's entries into {@code sandbox.excludedCommands} and {@code permissions.allow} of
	 * {@code settings.json}, creating the file and members when absent. Every operation runs outside the shell
	 * sandbox; install, update and status are also allowed without asking the user, while rollback and uninstall
	 * keep Claude Code's approval prompt. Setup is never named.
	 *
	 * @param home           the absolute Claude Code configuration directory recorded in policy
	 * @param stableLauncher the absolute path of the installed stable updater launcher
	 * @param replacer       replaces {@code settings.json}
	 * @return the added entries, keyed {@code settings.json#sandbox.excludedCommands} and
	 * 	{@code settings.json#permissions.allow}; a key is absent when nothing was added to it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either path is relative
	 * @throws IOException              if the settings cannot be changed safely, or on Windows the launcher path
	 *                                  holds a character that the shell would expand inside double quotes
	 */
	@Override
	public Map<String, List<String>> installPermissions(Path home, Path stableLauncher, FileReplacer replacer)
		throws IOException
	{
		requireThat(stableLauncher, "stableLauncher").isAbsolute();
		requireThat(replacer, "replacer").isNotNull();
		Map<String, List<String>> entries = entries(stableLauncher);
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
	 * Returns the entries for each setting. Every sandbox exclusion is one exact command, because Claude Code runs a
	 * whole command line outside the sandbox when an exclusion matches any command in it, so a wildcard would let a
	 * command chained after the launcher escape the sandbox. An update to a named release is allowed by a permission
	 * pattern only, which Claude Code matches against each command separately, so that update runs inside the sandbox.
	 *
	 * @param stableLauncher the absolute path of the installed stable updater launcher
	 * @return the sandbox exclusions and the prompt-free permissions, in a stable order
	 * @throws IOException if the launcher path cannot be named safely
	 */
	private Map<String, List<String>> entries(Path stableLauncher) throws IOException
	{
		String launcher = launcherCommand(stableLauncher);
		String install = launcher + " install --harness claude";
		String update = launcher + " update --harness claude";
		List<String> status = List.of(launcher + " status", launcher + " status --harness claude");
		List<String> uninstall = List.of(launcher + " uninstall --harness claude",
			launcher + " uninstall --harness claude --delete-data");

		List<String> excluded = new ArrayList<>(List.of(install, update, update + " latest"));
		excluded.addAll(status);
		excluded.add(launcher + " rollback --harness claude");
		excluded.addAll(uninstall);

		List<String> promptFree = new ArrayList<>(List.of(install, update, versionPattern(update)));
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
	 * Returns the shell text that runs the stable launcher.
	 *
	 * @param stableLauncher the launcher's absolute path
	 * @return on Windows, PowerShell's fixed non-interactive form for the launcher script; elsewhere, the quoted path
	 * @throws IOException if, on Windows, the path holds {@code "}, {@code $} or {@code `}, which the shell that
	 *                     Claude Code runs would interpret inside double quotes
	 */
	private String launcherCommand(Path stableLauncher) throws IOException
	{
		String path = stableLauncher.toString();
		if (platform.isWindows())
		{
			for (String character : List.of("\"", "$", "`"))
			{
				if (path.contains(character))
				{
					throw new IOException("The updater's launcher path contains " + character + ", which a permission " +
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
	 * Returns a setting's array, creating it and its parent object when absent.
	 *
	 * @param root    the validated settings
	 * @param setting the setting's dotted name
	 * @return the array
	 */
	private static ArrayNode settingArray(ObjectNode root, String setting)
	{
		String[] names = setting.split("\\.");
		ObjectNode parent;
		if (root.get(names[0]) instanceof ObjectNode existing)
			parent = existing;
		else
			parent = root.putObject(names[0]);
		if (parent.get(names[1]) instanceof ArrayNode existing)
			return existing;
		return parent.putArray(names[1]);
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
	 * Returns the exact and the version-pattern {@code update} entries that earlier builds asked the user to add to both
	 * settings, which named the launcher in POSIX quoting on every platform.
	 *
	 * @param legacyLauncher the absolute path of the shared installation's stable launcher
	 * @return the sandbox exclusions and the prompt-free permissions
	 * @throws NullPointerException if {@code legacyLauncher} is null
	 */
	@Override
	public Map<String, List<String>> legacyPermissions(Path legacyLauncher)
	{
		requireThat(legacyLauncher, "legacyLauncher").isNotNull();
		String update = ShellQuoting.posix(legacyLauncher.toString()).replace("*", "'\\*'") +
			" update --harness claude";
		List<String> commands = List.of(update, versionPattern(update));
		Map<String, List<String>> entries = new LinkedHashMap<>();
		entries.put(SETTINGS + "#" + EXCLUDED_COMMANDS, commands);
		entries.put(SETTINGS + "#" + ALLOWED_TOOLS, commands.stream().map(command -> "Bash(" + command + ")").toList());
		return entries;
	}

	/**
	 * Removes the given entries that {@code settings.json} still holds, keeping every other member and entry.
	 *
	 * @param home     the absolute Claude Code configuration directory recorded in policy
	 * @param added    the entries that {@link #installPermissions} returned
	 * @param replacer replaces {@code settings.json}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code added} has a key that
	 *                                  {@link #installPermissions} does not return
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
			if (!key.equals(SETTINGS + "#" + setting) || !List.of(EXCLUDED_COMMANDS, ALLOWED_TOOLS).contains(setting))
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
			String[] names = removal.getKey().split("\\.");
			if (!(root.get(names[0]) instanceof ObjectNode parent) ||
				!(parent.get(names[1]) instanceof ArrayNode array))
			{
				continue;
			}
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
