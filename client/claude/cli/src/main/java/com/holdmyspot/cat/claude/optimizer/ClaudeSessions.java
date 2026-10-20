package com.holdmyspot.cat.claude.optimizer;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.HarnessEvents;
import com.holdmyspot.cat.common.optimizer.adapter.PluginFiles;
import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.UnreachableReference;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.claude.secrets.ClaudeSessionsAccess;
import com.holdmyspot.cat.claude.secrets.ClaudeSessionsSecrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs nested sessions through the Claude runner.
 * <p>
 * Evidence in {@link SessionRequest#readOnly()} is passed to the runner as read-only evidence. The runner exposes it
 * as an added directory, which Claude treats as advisory.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ClaudeSessions extends RunnerSessions
{
	static
	{
		ClaudeSessionsSecrets.setAccess(new ClaudeSessionsAccess()
		{
			@Override
			public ClaudeSessions newClaudeSessions(RunnerSessions.Settings settings, Bridge bridge)
			{
				return new ClaudeSessions(settings, bridge);
			}

			@Override
			public ClaudeSessions newClaudeSessions(RunnerSessions.Settings settings, Bridge bridge,
				List<Path> managedSettings)
			{
				return new ClaudeSessions(settings, bridge, managedSettings);
			}
		});
	}

	/**
	 * The version of the environment that Claude sessions run in. Increment it in every release that changes what a
	 * session's model receives or how its actions take effect, and register the new version with the hash of the
	 * settings that the runner generates.
	 */
	private static final int SESSION_ENVIRONMENT_VERSION = 3;
	/**
	 * The version of the judge that Claude sessions run. Increment it in every release that changes the judge's role
	 * profile, prompt, rubric, decision rule or result schema, or the evidence section rendered for the same harness
	 * events, and register the new version with the hash of the judge profile and the rendered evidence section.
	 */
	private static final int JUDGE_VERSION = 9;
	/**
	 * The managed settings files that an administrator installs on Linux and on macOS.
	 */
	private static final List<Path> MANAGED_SETTINGS = List.of(Path.of("/etc/claude-code/managed-settings.json"),
		Path.of("/Library/Application Support/ClaudeCode/managed-settings.json"));
	/**
	 * The project settings files, relative to the project's root directory.
	 */
	private static final List<String> PROJECT_SETTINGS = List.of(".claude/settings.json",
		".claude/settings.local.json");
	/**
	 * The number of hops that Claude Code follows from an instruction file through the files that it imports.
	 */
	private static final int IMPORT_HOPS = 4;
	/**
	 * An {@code @path} import: an {@code @} at the start of the text or after whitespace, then a path that ends at the
	 * first space that no backslash precedes. The path is group 1.
	 */
	private static final Pattern IMPORT = Pattern.compile("(?<!\\S)@((?:\\\\ |\\S)+)");
	/**
	 * A Markdown code span: a run of backticks, the code, and a run of backticks of the same length.
	 */
	private static final Pattern CODE_SPAN = Pattern.compile("(`+).*?(?<!`)\\1(?!`)");
	/**
	 * The line that opens or closes a fenced Markdown code block.
	 */
	private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~)");
	private final List<Path> managedSettings;

	/**
	 * Creates a port that runs sessions through the real Claude runner.
	 *
	 * @param settings how the runner is configured
	 * @throws NullPointerException if {@code settings} is null
	 */
	public ClaudeSessions(Settings settings)
	{
		this(settings, productionBridge(settings), MANAGED_SETTINGS);
	}

	/**
	 * Creates a port.
	 *
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @throws NullPointerException if any argument is null
	 */
	ClaudeSessions(Settings settings, Bridge bridge)
	{
		this(settings, bridge, MANAGED_SETTINGS);
	}

	/**
	 * Creates a port that reads the given managed settings files.
	 *
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @param managedSettings the managed settings files that the host's administrator may install
	 * @throws NullPointerException if any argument is null
	 */
	ClaudeSessions(Settings settings, Bridge bridge, List<Path> managedSettings)
	{
		super(HARNESS_ID, settings, bridge);
		requireThat(managedSettings, "managedSettings").isNotNull();
		this.managedSettings = List.copyOf(managedSettings);
	}

	@Override
	public int sessionEnvironmentVersion()
	{
		return SESSION_ENVIRONMENT_VERSION;
	}

	@Override
	public int judgeVersion()
	{
		return JUDGE_VERSION;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Claude's events state a command's exit status only in the error text of some failed commands, and otherwise only
	 * whether the command succeeded, so a check of an exit status is not supported.
	 */
	@Override
	public boolean reportsExitStatus()
	{
		return false;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The runner's settings turn the sandbox on for every session and deny held-out reads through it, but managed
	 * settings and the project's {@code .claude/settings.json} and {@code .claude/settings.local.json} outrank them.
	 * This method reports the first of those files that sets {@code sandbox.enabled} to {@code false},
	 * {@code sandbox.allowUnsandboxedCommands} to {@code true}, a non-empty {@code sandbox.excludedCommands}, or a
	 * non-empty {@code sandbox.filesystem.allowRead}, each of which lets a command read what the sandbox denies. Any
	 * {@code allowRead} entry is reported, whatever it names, because which entries re-allow a denied path depends on
	 * how Claude resolves paths. A file that is not a JSON object is reported too, because
	 * the settings it holds cannot be checked. The host's user settings are not consulted, because the runner's
	 * settings replace them.
	 */
	@Override
	public Optional<String> findHeldOutExposure(Path project) throws IOException
	{
		requireThat(project, "project").isNotNull();
		List<Path> files = new ArrayList<>(managedSettings);
		for (String name : PROJECT_SETTINGS)
			files.add(project.resolve(name));
		JsonMapper json = JsonMapper.builder().build();
		for (Path file : files)
		{
			if (Files.notExists(file))
				continue;
			Optional<String> exposure = findSandboxBypass(json, file);
			if (exposure.isPresent())
				return exposure;
		}
		return Optional.empty();
	}

	/**
	 * Finds a setting in a settings file that lets a command read outside the sandbox.
	 *
	 * @param json the JSON mapper
	 * @param file the settings file
	 * @return a message that names the file and the setting and asks the user to remove it, or empty if the file holds
	 *   no such setting
	 * @throws IOException if the file cannot be read
	 */
	private static Optional<String> findSandboxBypass(JsonMapper json, Path file) throws IOException
	{
		JsonNode root;
		try
		{
			root = json.readTree(Files.readString(file));
		}
		catch (JacksonException _)
		{
			root = null;
		}
		if (root == null || !root.isObject())
		{
			return Optional.of(file + " is not a JSON object, so CAT cannot check that its sandbox settings keep " +
				"Claude sessions from reading the campaign's held-out content. Fix the file and run `start` again.");
		}
		JsonNode sandbox = root.path("sandbox");
		String setting;
		if (sandbox.path("enabled").isBoolean() && !sandbox.path("enabled").asBoolean())
			setting = "sets sandbox.enabled to false, which turns off the sandbox";
		else if (sandbox.path("allowUnsandboxedCommands").isBoolean() &&
			sandbox.path("allowUnsandboxedCommands").asBoolean())
		{
			setting = "sets sandbox.allowUnsandboxedCommands to true, which lets commands run outside the sandbox";
		}
		else if (sandbox.path("excludedCommands").isArray() && !sandbox.path("excludedCommands").isEmpty())
			setting = "sets sandbox.excludedCommands, which runs those commands outside the sandbox";
		else if (sandbox.path("filesystem").path("allowRead").isArray() &&
			!sandbox.path("filesystem").path("allowRead").isEmpty())
		{
			setting = "sets sandbox.filesystem.allowRead, which re-allows reads that the sandbox denies";
		}
		else
			return Optional.empty();
		return Optional.of(file + " " + setting + ", so Claude sessions could read the campaign's held-out content. " +
			"Remove the setting and run `start` again.");
	}

	@Override
	public HostLogin hostLogin() throws IOException
	{
		return Credentials.classify(settings().configurationDirectory(), settings().environment(),
			JsonMapper.builder().build());
	}

	@Override
	public List<String> unapprovedHooks(Path plugin)
	{
		requireThat(plugin, "plugin").isNotNull();
		// Claude Code runs every hook of an installed plugin without asking the user to approve it
		return List.of();
	}

	@Override
	public List<Path> installedPlugins(String name) throws IOException
	{
		return InstalledPlugins.of(settings().configurationDirectory(), name, JsonMapper.builder().build());
	}

	@Override
	protected HarnessEvents newEvents(JsonMapper json)
	{
		return new StreamJsonEvents(json);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Claude Code reads {@code CLAUDE.md}, {@code .claude/CLAUDE.md} and {@code CLAUDE.local.md} at the project root,
	 * and the Markdown rules below {@code .claude/rules}. Each of those three files is followed by the files that it
	 * imports through {@code @path}, and the files that those import in turn, for at most four hops. An import in a
	 * Markdown code span or fenced code block is not followed, and only an import that resolves to a file in the project
	 * is listed. The imports of a rule below {@code .claude/rules} are not listed.
	 */
	@Override
	protected List<DerivedSource> instructionFiles(Path project) throws IOException
	{
		requireThat(project, "project").isNotNull();
		return instructionFiles(project, new ArrayList<>());
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Claude Code silently skips an import that names no file, so an import is reported only when it is written as a
	 * file path: when it starts with {@code ./}, {@code ../}, {@code /} or {@code ~}, or its last segment holds a dot,
	 * as in {@code @docs/missing.md}. A mention such as the package scope {@code @types/node} is not reported. An import
	 * that names the home directory, or resolves outside the project, is reported whether or not its file exists.
	 */
	@Override
	protected List<UnreachableReference> unreachableReferences(Path project) throws IOException
	{
		requireThat(project, "project").isNotNull();
		List<UnreachableReference> unreachable = new ArrayList<>();
		instructionFiles(project, unreachable);
		return unreachable;
	}

	/**
	 * Lists the instruction files that Claude Code reads from the project, as {@link #instructionFiles(Path)}
	 * describes.
	 *
	 * @param project the project's root directory
	 * @param unreachable the list that receives each import, as {@link #unreachableReferences(Path)} describes, whose
	 *   file trials cannot receive
	 * @return the instruction files, in loading order
	 * @throws IOException if a file cannot be read
	 */
	private static List<DerivedSource> instructionFiles(Path project, List<UnreachableReference> unreachable)
		throws IOException
	{
		List<DerivedSource> files = new ArrayList<>();
		Set<String> listed = new HashSet<>();
		for (String name : List.of("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md"))
		{
			if (Files.isRegularFile(project.resolve(name)) && listed.add(name))
			{
				files.add(new DerivedSource(name, "Claude Code reads " + name + " at the project root in every " +
					"session"));
				addImports(project, name, IMPORT_HOPS, files, listed, unreachable);
			}
		}
		Path rules = project.resolve(".claude/rules");
		if (!Files.isDirectory(rules))
			return files;
		List<String> names = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(rules))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md"))
					names.add(project.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/"));
			}
		}
		names.sort(null);
		for (String name : names)
		{
			files.add(new DerivedSource(name, "Claude Code loads the rules below .claude/rules: each one in every " +
				"session, or when the agent reads a file that its paths match"));
		}
		return files;
	}

	/**
	 * Lists the project files that an instruction file imports, each followed by the files that it imports in turn.
	 *
	 * @param project the project's root directory
	 * @param name the importing file's path relative to {@code project}, with {@code /} separators
	 * @param hops the number of import hops that remain
	 * @param files the list that receives each imported file
	 * @param listed the paths that are already listed; each imported file's path is added to it
	 * @param unreachable the list that receives each import whose file trials cannot receive
	 * @throws IOException if a file cannot be read
	 */
	private static void addImports(Path project, String name, int hops, List<DerivedSource> files,
		Set<String> listed, List<UnreachableReference> unreachable) throws IOException
	{
		if (hops == 0)
			return;
		Path file = project.resolve(name);
		// Bytes that are not valid UTF-8 become replacement characters, so an imported file that is not text does not
		// stop the listing
		String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		boolean fenced = false;
		for (String line : (Iterable<String>) text.lines()::iterator)
		{
			if (FENCE.matcher(line).find())
			{
				fenced = !fenced;
				continue;
			}
			if (fenced)
				continue;
			Matcher matcher = IMPORT.matcher(CODE_SPAN.matcher(line).replaceAll(" "));
			while (matcher.find())
			{
				String reference = matcher.group(1);
				String path = reference.replace("\\ ", " ");
				switch (resolve(project, file.getParent(), path))
				{
					case Resolution.InProject inProject ->
					{
						String imported = inProject.path();
						if (!listed.add(imported))
							continue;
						files.add(new DerivedSource(imported, "Claude Code imports " + imported + " into " + name +
							" through @" + reference));
						addImports(project, imported, hops - 1, files, listed, unreachable);
					}
					case Resolution.Unreachable(UnreachableReference.Problem problem) ->
					{
						UnreachableReference entry = new UnreachableReference(name, path, problem);
						if (!unreachable.contains(entry))
							unreachable.add(entry);
					}
					case Resolution.NoFile _ ->
					{
					}
				}
			}
		}
	}

	/**
	 * Resolves an import's path.
	 *
	 * @param project the project's root directory
	 * @param directory the directory of the importing file, which a relative path resolves against
	 * @param path the imported path, without its escapes
	 * @return the file in the project, the reason that trials cannot receive a file that the path names, or
	 *   {@link Resolution.NoFile} for a path that is not written as a file path and names no file
	 */
	private static Resolution resolve(Path project, Path directory, String path)
	{
		boolean filePath = path.startsWith("./") || path.startsWith("../") || path.startsWith("/") ||
			path.startsWith("~") || path.substring(path.lastIndexOf('/') + 1).contains(".");
		if (path.startsWith("~"))
			return new Resolution.Unreachable(UnreachableReference.Problem.OUTSIDE_PROJECT);
		Path file;
		try
		{
			file = directory.resolve(path).normalize();
		}
		catch (InvalidPathException _)
		{
			return new Resolution.NoFile();
		}
		Path root = project.normalize();
		if (!file.startsWith(root))
		{
			if (filePath)
				return new Resolution.Unreachable(UnreachableReference.Problem.OUTSIDE_PROJECT);
			return new Resolution.NoFile();
		}
		if (Files.isRegularFile(file))
		{
			return new Resolution.InProject(root.relativize(file).toString().
				replace(file.getFileSystem().getSeparator(), "/"));
		}
		if (filePath && Files.notExists(file))
			return new Resolution.Unreachable(UnreachableReference.Problem.MISSING);
		return new Resolution.NoFile();
	}

	/**
	 * What an import's path names.
	 */
	private sealed interface Resolution
	{
		/**
		 * A regular file in the project.
		 *
		 * @param path the file's path relative to the project, with {@code /} separators
		 */
		record InProject(String path) implements Resolution
		{
		}

		/**
		 * A file that trials cannot receive.
		 *
		 * @param problem why trials cannot receive it
		 */
		record Unreachable(UnreachableReference.Problem problem) implements Resolution
		{
		}

		/**
		 * No file, such as a mention that is not written as a file path, or a directory.
		 */
		record NoFile() implements Resolution
		{
		}
	}

	@Override
	protected PromptSources newPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		return new StreamJsonPromptSources(json, directories);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Claude Code registers the hooks of the project's shared and local settings.
	 */
	@Override
	protected List<Path> projectHookFiles()
	{
		return List.of(Path.of(".claude/settings.json"), Path.of(".claude/settings.local.json"));
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Claude Code registers the hooks of the plugin's {@code hooks/hooks.json}, of each hooks file that the manifest's
	 * {@code hooks} property names and of each declaration that the property holds inline.
	 */
	@Override
	protected List<HookDeclaration> pluginHooks(PluginFiles plugin, JsonMapper json) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(json, "json").isNotNull();
		Path manifest = Path.of(".claude-plugin/plugin.json");
		JsonNode hooks = readHooksFile(plugin.resolve(manifest), json).map(node -> node.path("hooks")).
			orElse(json.missingNode());
		List<JsonNode> entries = new ArrayList<>();
		if (hooks.isArray())
			hooks.forEach(entries::add);
		else
			entries.add(hooks);

		List<Path> files = new ArrayList<>();
		files.add(Path.of("hooks/hooks.json"));
		List<HookDeclaration> inline = new ArrayList<>();
		for (JsonNode entry : entries)
		{
			if (entry.isString())
			{
				Path file = Path.of(entry.asString()).normalize();
				if (!files.contains(file))
					files.add(file);
			}
			else if (entry.isObject())
			{
				// An inline declaration may wrap its events in a "hooks" property, as a hooks file does
				JsonNode events = entry.path("hooks");
				if (!events.isObject())
					events = entry;
				inline.add(new HookDeclaration(manifest, events));
			}
		}
		List<HookDeclaration> declarations = new ArrayList<>();
		for (Path file : files)
		{
			Optional<JsonNode> content = readHooksFile(plugin.resolve(file), json);
			content.ifPresent(node -> declarations.add(new HookDeclaration(file, node.path("hooks"))));
		}
		declarations.addAll(inline);
		return declarations;
	}

	@Override
	protected List<String> readOnlyArguments(List<Path> evidence)
	{
		List<String> arguments = new ArrayList<>();
		for (Path path : evidence)
			arguments.addAll(List.of("--read-only-evidence", path.toString()));
		return arguments;
	}

	/**
	 * Creates the bridge that runs the real runner.
	 *
	 * @param settings how the runner is configured
	 * @return the bridge
	 */
	private static Bridge productionBridge(Settings settings)
	{
		requireThat(settings, "settings").isNotNull();
		return (arguments, input, output) -> ClaudeRunnerCli.serve(arguments.toArray(String[]::new), input, output,
			settings.environment(), _ -> HarnessCapacity.unlimited());
	}
}
