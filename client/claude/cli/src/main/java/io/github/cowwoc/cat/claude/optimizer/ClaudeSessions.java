package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.runner.ClaudeRunnerCli;
import io.github.cowwoc.cat.common.optimizer.adapter.Bridge;
import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.adapter.PromptSources;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
	protected HarnessEvents newEvents(JsonMapper json)
	{
		return new StreamJsonEvents(json);
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
