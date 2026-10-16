package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.runner.ClaudeRunnerCli;
import io.github.cowwoc.cat.common.optimizer.adapter.Bridge;
import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.adapter.PromptSources;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.claude.secrets.ClaudeSessionsAccess;
import io.github.cowwoc.cat.claude.secrets.ClaudeSessionsSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	private static final int SESSION_ENVIRONMENT_VERSION = 2;
	/**
	 * The version of the judge that Claude sessions run. Increment it in every release that changes the judge's role
	 * profile, prompt, rubric, decision rule or result schema, or the evidence section rendered for the same harness
	 * events, and register the new version with the hash of the judge profile and the rendered evidence section.
	 */
	private static final int JUDGE_VERSION = 8;
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
	protected HarnessEvents newEvents(JsonMapper json)
	{
		return new StreamJsonEvents(json);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Claude Code reads {@code CLAUDE.md}, {@code .claude/CLAUDE.md} and {@code CLAUDE.local.md} at the project root,
	 * and the Markdown rules below {@code .claude/rules}. The files that a listed file imports are not listed.
	 */
	@Override
	protected List<DerivedSource> instructionFiles(Path project) throws IOException
	{
		requireThat(project, "project").isNotNull();
		List<DerivedSource> files = new ArrayList<>();
		for (String name : List.of("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md"))
		{
			if (Files.isRegularFile(project.resolve(name)))
			{
				files.add(new DerivedSource(name, "Claude Code reads " + name + " at the project root in every " +
					"session"));
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

	@Override
	protected PromptSources newPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		return new StreamJsonPromptSources(json, directories);
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
