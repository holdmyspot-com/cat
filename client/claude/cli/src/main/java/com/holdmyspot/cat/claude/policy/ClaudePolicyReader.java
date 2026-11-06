package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads the sandbox policy of a Claude Code session from its process's command line and the settings files that the
 * session loads.
 * <p>
 * Each source that CAT cannot read is refused. A source whose effect on the session is uncertain, such as a settings
 * file that appeared after the session started, contributes only the entries that narrow the policy.
 */
public final class ClaudePolicyReader
{
	/**
	 * The name of the harness as the user knows it, for {@link PolicyRefusal#messageFor(String)}.
	 */
	public static final String HARNESS_NAME = "Claude Code";
	/**
	 * The largest settings file that CAT reads.
	 */
	private static final int MAXIMUM_SETTINGS_BYTES = 1024 * 1024;
	/**
	 * The most sources of uncertain effect that one reading combines.
	 */
	private static final int MAXIMUM_UNCERTAIN_SOURCES = 8;
	/**
	 * The fix of a refusal that a settings file causes.
	 */
	private static final String FILE_FIX = "Correct or remove that file, then start a new session";
	/**
	 * The fix of a refusal that the session's process causes.
	 */
	private static final String PROCESS_FIX = "Start a new Claude Code session";
	/**
	 * The running processes.
	 */
	private final HarnessProcesses processes;
	/**
	 * The JSON parser.
	 */
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a reader.
	 *
	 * @param processes the running processes
	 * @throws NullPointerException if {@code processes} is null
	 */
	public ClaudePolicyReader(HarnessProcesses processes)
	{
		requireThat(processes, "processes").isNotNull();
		this.processes = processes;
	}

	/**
	 * Reads a session's policy when CAT first meets the session.
	 *
	 * @param session the session
	 * @return the reading
	 * @throws NullPointerException if {@code session} is null
	 * @throws IOException          if a file cannot be read
	 * @throws PolicyRefusal        if CAT cannot read the policy safely
	 */
	public ClaudePolicyReading read(ClaudeSession session) throws IOException, PolicyRefusal
	{
		requireThat(session, "session").isNotNull();
		return read(session, null);
	}

	/**
	 * Reads a session's policy again, for a child session that the parent requests.
	 *
	 * @param session  the session
	 * @param snapshot the reading of the session when CAT first met it
	 * @return the accesses that both readings give
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a file cannot be read
	 * @throws PolicyRefusal        if CAT cannot read the policy safely, or the session's process is not the one of
	 *                              {@code snapshot}
	 */
	public ParentPolicy reread(ClaudeSession session, ClaudePolicyReading snapshot) throws IOException, PolicyRefusal
	{
		requireThat(session, "session").isNotNull();
		requireThat(snapshot, "snapshot").isNotNull();
		ClaudePolicyReading current = read(session, snapshot);
		return snapshot.policy().intersect(current.policy());
	}

	/**
	 * Reads a session's policy.
	 *
	 * @param session  the session
	 * @param snapshot the earlier reading, or null if there is none
	 * @return the reading
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if CAT cannot read the policy safely
	 */
	private ClaudePolicyReading read(ClaudeSession session, ClaudePolicyReading snapshot)
		throws IOException, PolicyRefusal
	{
		long processId = session.processId();
		ProcessDescription process = processes.find(processId).orElseThrow(() -> new PolicyRefusal("the Claude Code " +
			"process " + processId + " that the hook environment's CLAUDE_PID names is not running", PROCESS_FIX));
		Instant start = process.start().orElseThrow(() -> new PolicyRefusal("the operating system does not report " +
			"when the Claude Code process " + processId + " started, so CAT cannot tell it from a later process " +
			"with the same ID", PROCESS_FIX));
		if (snapshot != null && !start.equals(snapshot.processStart()))
		{
			throw new PolicyRefusal("the process " + processId + " started at " + start + ", not at " +
				snapshot.processStart() + " like the session's Claude Code process, so it is another process",
				PROCESS_FIX);
		}
		if (process.command().isEmpty() || process.arguments().isEmpty())
		{
			throw new PolicyRefusal("the operating system does not report the command line of the Claude Code " +
				"process " + processId, PROCESS_FIX);
		}
		ClaudeCommandLine commandLine = ClaudeCommandLine.parse(process.arguments().get());

		for (Path source : session.unreadableManagedSources())
		{
			if (Files.exists(source))
			{
				throw new PolicyRefusal("the managed settings in " + source + " are in a form that CAT cannot read",
					"Move those settings to " + session.managedDirectory().resolve(ClaudeRelease.MANAGED_FILE) +
						", then start a new session");
			}
		}

		// Each group is a set of sources whose effect is either certain, or uncertain as a whole
		List<SettingsLayer> certain = new ArrayList<>();
		List<List<SettingsLayer>> uncertain = new ArrayList<>();
		List<SettingsLayer> fileManaged = managedLayers(session.managedDirectory());
		List<List<SettingsLayer>> otherManaged = new ArrayList<>();
		Path remote = session.configurationDirectory().resolve(ClaudeRelease.REMOTE_SETTINGS);
		if (Files.exists(remote))
			otherManaged.add(List.of(fileLayer(SettingsScope.MANAGED, remote, session.configurationDirectory())));
		for (Path directory : session.otherManagedDirectories())
		{
			List<SettingsLayer> layers = managedLayers(directory);
			if (!layers.isEmpty())
				otherManaged.add(layers);
		}
		if (otherManaged.isEmpty())
			certain.addAll(fileManaged);
		else
		{
			// Which managed source wins when several exist is not certain
			if (!fileManaged.isEmpty())
				uncertain.add(fileManaged);
			uncertain.addAll(otherManaged);
		}
		certain.addAll(scopedLayers(session, commandLine));

		Set<Path> sources = new LinkedHashSet<>();
		for (SettingsLayer layer : certain)
			layer.file().ifPresent(sources::add);
		for (List<SettingsLayer> group : uncertain)
		{
			for (SettingsLayer layer : group)
				layer.file().ifPresent(sources::add);
		}
		if (snapshot != null)
		{
			// The session may not have loaded a settings file that appeared after CAT first read its policy
			for (SettingsLayer layer : List.copyOf(certain))
			{
				if (layer.file().isPresent() && !snapshot.sources().contains(layer.file().get()))
				{
					certain.remove(layer);
					uncertain.add(List.of(layer));
				}
			}
		}
		if (uncertain.size() > MAXIMUM_UNCERTAIN_SOURCES)
		{
			throw new PolicyRefusal(uncertain.size() + " of the session's settings sources may or may not apply " +
				"to it, more than CAT combines", "Remove the settings files that appeared after the session " +
				"started, then start a new session");
		}

		ParentPolicy policy = null;
		for (int subset = 0; subset < 1 << uncertain.size(); ++subset)
		{
			List<SettingsLayer> layers = new ArrayList<>(certain);
			for (int i = 0; i < uncertain.size(); ++i)
			{
				if ((subset & 1 << i) != 0)
					layers.addAll(uncertain.get(i));
			}
			// A stable sort keeps the order of the layers within a scope
			layers.sort(Comparator.comparing(SettingsLayer::scope));
			ParentPolicy candidate = ClaudePolicyComputation.compute(layers, commandLine, session);
			if (policy == null)
				policy = candidate;
			else
				policy = policy.intersect(candidate);
		}
		assert policy != null;
		for (Path source : sources)
			policy.requireTrusted(source);
		return new ClaudePolicyReading(policy, start, sources);
	}

	/**
	 * Returns the managed settings of a folder.
	 *
	 * @param directory the folder
	 * @return the settings, from the highest precedence to the lowest
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if a file cannot be parsed
	 */
	private List<SettingsLayer> managedLayers(Path directory) throws IOException, PolicyRefusal
	{
		List<SettingsLayer> layers = new ArrayList<>();
		Path dropIns = directory.resolve(ClaudeRelease.MANAGED_DROP_INS);
		if (Files.isDirectory(dropIns))
		{
			List<Path> files = new ArrayList<>();
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dropIns, "*.json"))
			{
				for (Path file : stream)
					files.add(file);
			}
			// Later files override earlier ones
			files.sort(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed());
			for (Path file : files)
				layers.add(fileLayer(SettingsScope.MANAGED, file, directory));
		}
		Path file = directory.resolve(ClaudeRelease.MANAGED_FILE);
		if (Files.exists(file))
			layers.add(fileLayer(SettingsScope.MANAGED, file, directory));
		return layers;
	}

	/**
	 * Returns the settings of the command line and of the user, project and local scopes that the session loads.
	 *
	 * @param session     the session
	 * @param commandLine the command line
	 * @return the settings, from the highest precedence to the lowest
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if a file cannot be read or parsed
	 */
	private List<SettingsLayer> scopedLayers(ClaudeSession session, ClaudeCommandLine commandLine)
		throws IOException, PolicyRefusal
	{
		List<SettingsLayer> layers = new ArrayList<>();
		Path project = session.projectDirectory();
		if (commandLine.settings().isPresent())
		{
			String value = commandLine.settings().get();
			if (value.strip().startsWith("{"))
			{
				layers.add(new SettingsLayer(SettingsScope.FLAG, "the settings of Claude Code's option --settings",
					Optional.empty(), parse(value, "the settings of Claude Code's option --settings",
						"Correct the JSON of Claude Code's option --settings, then start a new session"),
					project));
			}
			else
			{
				Path file = project.resolve(value).normalize();
				if (!Files.isRegularFile(file))
				{
					throw new PolicyRefusal("the settings file " + file + " of Claude Code's option --settings " +
						"does not exist", "Start Claude Code with an existing settings file, then start a new session");
				}
				layers.add(fileLayer(SettingsScope.FLAG, file, file.getParent()));
			}
		}
		Set<SettingsScope> scopes = EnumSet.of(SettingsScope.LOCAL, SettingsScope.PROJECT, SettingsScope.USER);
		if (commandLine.restricted())
			scopes.clear();
		commandLine.settingSources().ifPresent(scopes::retainAll);
		Path projectSettings = project.resolve(".claude");
		if (scopes.contains(SettingsScope.LOCAL))
			optionalLayer(layers, SettingsScope.LOCAL, projectSettings.resolve("settings.local.json"), project);
		if (scopes.contains(SettingsScope.PROJECT))
			optionalLayer(layers, SettingsScope.PROJECT, projectSettings.resolve("settings.json"), project);
		if (scopes.contains(SettingsScope.USER))
		{
			Path configuration = session.configurationDirectory();
			optionalLayer(layers, SettingsScope.USER, configuration.resolve("settings.json"), configuration);
		}
		return layers;
	}

	/**
	 * Adds the settings of a file if the file exists.
	 *
	 * @param layers the settings to add to
	 * @param scope  the scope of the file
	 * @param file   the file
	 * @param root   the folder that relative sandbox paths of the file are relative to
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the file cannot be parsed
	 */
	private void optionalLayer(List<SettingsLayer> layers, SettingsScope scope, Path file, Path root)
		throws IOException, PolicyRefusal
	{
		if (Files.exists(file))
			layers.add(fileLayer(scope, file, root));
	}

	/**
	 * Returns the settings of a file. An empty file holds no settings: Claude Code's Linux sandbox leaves one in the
	 * project for each settings file that it hides from the commands while the file does not exist.
	 *
	 * @param scope the scope of the file
	 * @param file  the file
	 * @param root  the folder that relative sandbox paths of the file are relative to
	 * @return the settings
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the file is not empty and cannot be parsed
	 */
	private SettingsLayer fileLayer(SettingsScope scope, Path file, Path root) throws IOException, PolicyRefusal
	{
		String source = "the settings file " + file;
		String text = BoundedInput.readUtf8(file, MAXIMUM_SETTINGS_BYTES, "Claude Code settings");
		JsonNode settings;
		if (text.isEmpty())
			settings = json.createObjectNode();
		else
			settings = parse(text, source, FILE_FIX);
		return new SettingsLayer(scope, source, Optional.of(file), settings, root);
	}

	/**
	 * Parses settings.
	 *
	 * @param text   the settings as JSON
	 * @param source the source of the settings, as the user knows it
	 * @param fix    the fix of a refusal
	 * @return the settings
	 * @throws PolicyRefusal if the text is not a JSON object
	 */
	private JsonNode parse(String text, String source, String fix) throws PolicyRefusal
	{
		JsonNode settings;
		try
		{
			settings = json.readTree(text);
		}
		catch (JacksonException e)
		{
			throw new PolicyRefusal(source + " cannot be parsed as JSON: " + e.getOriginalMessage(), fix);
		}
		if (settings == null || !settings.isObject())
			throw new PolicyRefusal(source + " does not hold a JSON object", fix);
		return settings;
	}
}
