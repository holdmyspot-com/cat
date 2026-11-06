package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads the sandbox policy of a Claude Code session from the settings files that the session loads. A command of the
 * session cannot see the session's Claude Code process, so the reader assumes that the session's command line sets no
 * sandbox option.
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
	 * The JSON parser.
	 */
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a reader.
	 */
	public ClaudePolicyReader()
	{
	}

	/**
	 * Reads a session's policy.
	 *
	 * @param session the session
	 * @return the policy
	 * @throws NullPointerException if {@code session} is null
	 * @throws IOException          if a file cannot be read
	 * @throws PolicyRefusal        if CAT cannot read the policy safely
	 */
	public ParentPolicy read(ClaudeSession session) throws IOException, PolicyRefusal
	{
		requireThat(session, "session").isNotNull();
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
		certain.addAll(scopedLayers(session));
		if (uncertain.size() > MAXIMUM_UNCERTAIN_SOURCES)
		{
			throw new PolicyRefusal(uncertain.size() + " of the session's managed settings sources may or may not " +
				"apply to it, more than CAT combines", "Remove the managed settings sources that do not apply, then " +
				"start a new session");
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
			ParentPolicy candidate = ClaudePolicyComputation.compute(layers, session);
			if (policy == null)
				policy = candidate;
			else
				policy = policy.intersect(candidate);
		}
		assert policy != null;
		return policy;
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
	 * Returns the settings of the local, project and user scopes.
	 *
	 * @param session the session
	 * @return the settings, from the highest precedence to the lowest
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if a file cannot be parsed
	 */
	private List<SettingsLayer> scopedLayers(ClaudeSession session) throws IOException, PolicyRefusal
	{
		List<SettingsLayer> layers = new ArrayList<>();
		Path project = session.projectDirectory();
		Path projectSettings = project.resolve(".claude");
		optionalLayer(layers, SettingsScope.LOCAL, projectSettings.resolve("settings.local.json"), project);
		optionalLayer(layers, SettingsScope.PROJECT, projectSettings.resolve("settings.json"), project);
		Path configuration = session.configurationDirectory();
		optionalLayer(layers, SettingsScope.USER, configuration.resolve("settings.json"), configuration);
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
