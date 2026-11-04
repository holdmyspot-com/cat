package com.holdmyspot.cat.claude.updater;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
	 * How long {@code claude --version} may run during discovery.
	 */
	private static final Duration VERSION_TIMEOUT = Duration.ofMinutes(1);
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
	private final Path workingDirectory;
	private final Path temporaryDirectory;

	/**
	 * Creates a provider for service loading, for the running platform, working directory and temporary directory.
	 */
	public Integration()
	{
		this(Platform.current(), Path.of("").toAbsolutePath(),
			Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());
	}

	/**
	 * Creates a provider.
	 *
	 * @param platform           the platform that Claude Code and the updater run on
	 * @param workingDirectory   the setup process's working directory, whose programs discovery never records
	 * @param temporaryDirectory the system temporary directory, whose programs discovery never records
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either directory is relative
	 */
	public Integration(Platform platform, Path workingDirectory, Path temporaryDirectory)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isAbsolute();
		requireThat(temporaryDirectory, "temporaryDirectory").isAbsolute();
		this.platform = platform;
		this.workingDirectory = workingDirectory.normalize();
		this.temporaryDirectory = temporaryDirectory.normalize();
	}

	@Override
	public String harness()
	{
		return "claude";
	}

	/**
	 * Finds the {@code claude} program and runs {@code claude --version} with the environment that it records.
	 * <p>
	 * The recorded search path keeps only absolute entries outside the working directory, the temporary directories,
	 * the configuration directory and {@code <user home>/.cat}, so that a project or a download cannot place a
	 * program that registration later runs. The recorded environment holds the user's home directory variable, that
	 * search path and, when the configuration directory is not {@code <user home>/.claude}, {@code CLAUDE_CONFIG_DIR},
	 * which also moves Claude Code's {@code .claude.json}. On Windows it also holds {@code SystemRoot}, without which
	 * Windows programs cannot load system libraries, {@code PATHEXT}, without which the batch file that npm installs
	 * cannot find {@code node}, and {@code NoDefaultCurrentDirectoryInExePath=1}, so that {@code cmd.exe} never runs a
	 * program from the working directory; a batch-file program runs through {@code %SystemRoot%\System32\cmd.exe}.
	 *
	 * @param home        the absolute, normalized Claude Code configuration directory that setup records
	 * @param environment the setup process's environment variables
	 * @return the program's real path, with its command prefix, and its environment
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the user's home directory is unknown, Claude Code is not on the search
	 *                                  path, or {@code claude --version} does not identify Claude Code
	 */
	@Override
	public HarnessCommand discover(Path home, Map<String, String> environment) throws IOException
	{
		requireThat(home, "home").isAbsolute();
		requireThat(environment, "environment").isNotNull();
		home = home.normalize();
		Map<String, String> variables = platform.environment(environment);
		String homeVariable = platform.homeVariable();
		String userHome = variables.get(homeVariable);
		if (userHome == null || userHome.isBlank())
			throw new IOException(homeVariable + " is not set, so setup cannot find Claude Code's default directory");
		Path userHomePath = Path.of(userHome).toAbsolutePath().normalize();

		List<Path> excluded = new ArrayList<>(List.of(workingDirectory, temporaryDirectory, home,
			userHomePath.resolve(".cat")));
		for (String name : List.of("TMPDIR", "TEMP", "TMP"))
		{
			Path directory = absolutePath(variables.get(name));
			if (directory != null)
				excluded.add(directory);
		}
		String searchPath = recordedSearchPath(variables.getOrDefault("PATH", ""), excluded);
		Map<String, String> recorded = new HashMap<>();
		recorded.put(homeVariable, userHome);
		recorded.put("PATH", searchPath);
		if (!home.equals(userHomePath.resolve(".claude")))
			recorded.put("CLAUDE_CONFIG_DIR", home.toString());
		List<String> command = new ArrayList<>();
		Path program = findClaude(variables, searchPath);
		if (platform.isWindows())
		{
			String systemRoot = variables.get("SystemRoot");
			if (systemRoot == null || systemRoot.isBlank())
				throw new IOException("SystemRoot is not set, so setup cannot run Claude Code");
			recorded.put("SystemRoot", systemRoot);
			String extensions = variables.get("PATHEXT");
			if (extensions != null)
				recorded.put("PATHEXT", extensions);
			recorded.put("NoDefaultCurrentDirectoryInExePath", "1");
			String name = program.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.endsWith(".cmd"))
				command.addAll(List.of(Path.of(systemRoot, "System32/cmd.exe").toString(), "/d", "/s", "/c"));
		}
		command.add(program.toString());

		verifyVersion(command, recorded, home);
		return new HarnessCommand(command, recorded);
	}

	/**
	 * Converts an environment value to an absolute path.
	 *
	 * @param value the value, or null
	 * @return the normalized absolute path, or null if {@code value} is null, blank, relative or not a path
	 */
	private static Path absolutePath(String value)
	{
		if (value == null || value.isBlank())
			return null;
		try
		{
			Path path = Path.of(value.strip());
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
	 * Keeps the search path entries that discovery may record.
	 *
	 * @param searchPath the setup process's search path
	 * @param excluded   directories whose entries, and entries below them, are dropped
	 * @return the remaining absolute, normalized entries, without repeats, in their original order
	 */
	private String recordedSearchPath(String searchPath, List<Path> excluded)
	{
		String separator;
		if (platform.isWindows())
			separator = ";";
		else
			separator = ":";
		List<Path> realExcluded = new ArrayList<>();
		for (Path directory : excluded)
		{
			realExcluded.add(directory);
			realExcluded.add(realPath(directory));
		}
		Set<Path> entries = new LinkedHashSet<>();
		for (String text : searchPath.split(separator, -1))
		{
			Path entry = absolutePath(text);
			if (entry == null)
				continue;
			Path real = realPath(entry);
			boolean allowed = true;
			for (Path directory : realExcluded)
			{
				if (entry.startsWith(directory) || real.startsWith(directory))
				{
					allowed = false;
					break;
				}
			}
			if (allowed)
				entries.add(entry);
		}
		StringJoiner joiner = new StringJoiner(separator);
		for (Path entry : entries)
			joiner.add(entry.toString());
		return joiner.toString();
	}

	/**
	 * Resolves the links of a path that may not exist.
	 *
	 * @param path an absolute, normalized path
	 * @return the real path, or {@code path} if it does not exist or cannot be resolved
	 */
	private static Path realPath(Path path)
	{
		try
		{
			return path.toRealPath();
		}
		catch (IOException _)
		{
			return path;
		}
	}

	/**
	 * Finds the {@code claude} program in the recorded search path.
	 *
	 * @param variables  the setup process's environment
	 * @param searchPath the search path that discovery records
	 * @return the real path of the first {@code claude} program on the search path, which on Windows is
	 * 	{@code claude.exe} or {@code claude.cmd}
	 * @throws IOException if no program is found
	 */
	private Path findClaude(Map<String, String> variables, String searchPath) throws IOException
	{
		Map<String, String> search = new HashMap<>();
		search.put("PATH", searchPath);
		if (platform.isWindows())
		{
			// Claude Code installs claude.exe natively or claude.cmd through npm; keep the user's order
			StringJoiner startable = new StringJoiner(";");
			for (String extension : variables.getOrDefault("PATHEXT", ".EXE;.CMD").split(";"))
			{
				String lowerCase = extension.strip().toLowerCase(Locale.ROOT);
				if (lowerCase.equals(".exe") || lowerCase.equals(".cmd"))
					startable.add(lowerCase);
			}
			if (startable.length() == 0)
				startable.add(".exe").add(".cmd");
			search.put("PATHEXT", startable.toString());
		}
		String found = platform.findProgram("claude", search);
		if (found.equals("claude"))
		{
			throw new IOException("Claude Code's claude program is not on PATH (" + searchPath + "). Install " +
				"Claude Code, or add its directory to PATH, and rerun setup.");
		}
		return Path.of(found).toRealPath();
	}

	/**
	 * Runs {@code claude --version} exactly as registration will run the program.
	 *
	 * @param command     the command prefix
	 * @param environment the complete environment
	 * @param home        the configuration directory, the working directory when it exists
	 * @throws IOException if the program does not exit successfully within the time limit or does not identify
	 *                     itself as Claude Code
	 */
	private static void verifyVersion(List<String> command, Map<String, String> environment, Path home)
		throws IOException
	{
		List<String> arguments = new ArrayList<>(command);
		arguments.add("--version");
		ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(environment);
		if (Files.isDirectory(home))
			builder.directory(home.toFile());
		String output;
		int exitCode;
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			FutureTask<byte[]> reader = new FutureTask<>(process.getInputStream()::readAllBytes);
			Thread.ofVirtual().start(reader);
			if (!process.waitFor(VERSION_TIMEOUT))
			{
				process.destroyForcibly();
				throw new IOException("claude --version did not exit within " + VERSION_TIMEOUT.toSeconds() +
					" seconds: " + command.getLast());
			}
			exitCode = process.exitValue();
			output = new String(reader.get(VERSION_TIMEOUT.toSeconds(), TimeUnit.SECONDS), UTF_8).strip();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			InterruptedIOException failure = new InterruptedIOException("Interrupted while running claude --version");
			failure.initCause(e);
			throw failure;
		}
		catch (ExecutionException e)
		{
			throw new IOException("Could not read the output of claude --version", e.getCause());
		}
		catch (TimeoutException e)
		{
			throw new IOException("claude --version kept its output open after exiting: " + command.getLast(), e);
		}
		if (exitCode != 0 || !output.contains("Claude Code"))
		{
			throw new IOException("claude --version did not identify Claude Code (exit status " + exitCode + "): " +
				command.getLast() + ": " + output);
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
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @param cli  runs the recorded {@code claude} program
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the activated release lacks its marketplace metadata, a path component is
	 *                                  a symbolic link, or Claude Code refuses a command
	 */
	@Override
	public void register(Path home, HarnessCli cli) throws IOException
	{
		requireThat(cli, "cli").isNotNull();
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
			replace(manifest, content);
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
	 * @return the added entries, keyed {@code settings.json#sandbox.excludedCommands} and
	 * 	{@code settings.json#permissions.allow}; a key is absent when nothing was added to it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either path is relative
	 * @throws IOException              if the settings cannot be changed safely, or on Windows the launcher path
	 *                                  holds a character that the shell would expand inside double quotes
	 */
	@Override
	public Map<String, List<String>> installPermissions(Path home, Path stableLauncher) throws IOException
	{
		requireThat(stableLauncher, "stableLauncher").isAbsolute();
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
			writeSettings(home, root);
		return Map.copyOf(added);
	}

	/**
	 * Returns the entries for each setting.
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
		List<String> promptFree = new ArrayList<>(List.of(install, versionPattern(install), update,
			versionPattern(update), launcher + " status", launcher + " status --harness claude"));
		List<String> uninstall = List.of(launcher + " uninstall --harness claude",
			launcher + " uninstall --harness claude --delete-data");
		if (UNINSTALL_PROMPT_FREE)
			promptFree.addAll(uninstall);
		List<String> excluded = new ArrayList<>(promptFree);
		excluded.add(launcher + " rollback --harness claude");
		for (String entry : uninstall)
		{
			if (!excluded.contains(entry))
				excluded.add(entry);
		}
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
	 * @param home the configuration directory
	 * @param root the settings
	 * @throws IOException if the file cannot be written
	 */
	private static void writeSettings(Path home, ObjectNode root) throws IOException
	{
		replace(settingsFile(home), (WRITER.writeValueAsString(root) + "\n").getBytes(UTF_8));
	}

	/**
	 * Replaces a file's content through a temporary sibling and an atomic rename.
	 *
	 * @param target  the file
	 * @param content the new content
	 * @throws IOException if the file cannot be written
	 */
	private static void replace(Path target, byte[] content) throws IOException
	{
		Files.createDirectories(target.getParent());
		Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".new");
		try
		{
			Files.write(temporary, content);
			// The interface passes no retrying replacer, and only a main method may create a sleeping pause.
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Removes the given entries that {@code settings.json} still holds, keeping every other member and entry.
	 *
	 * @param home  the absolute Claude Code configuration directory recorded in policy
	 * @param added the entries that {@link #installPermissions} returned
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code added} has a key that
	 *                                  {@link #installPermissions} does not return
	 * @throws IOException              if the settings cannot be changed safely
	 */
	@Override
	public void removePermissions(Path home, Map<String, List<String>> added) throws IOException
	{
		requireThat(added, "added").isNotNull();
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
			writeSettings(home, root);
	}
}
