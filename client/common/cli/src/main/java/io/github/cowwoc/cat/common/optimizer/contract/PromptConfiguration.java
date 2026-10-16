package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A target's prompt test configuration: the committed file {@value #FILE_NAME} in the target's test directory, which
 * lists for each harness the plugins that every trial of a campaign started in that harness installs:
 * <pre>{@code
 * {
 *   "codex": {
 *     "plugins": [
 *       {
 *         "name": "cat",
 *         "build": {
 *           "command": ["client/mvnw", "-f", "client/pom.xml", "-B", "package", "-DskipTests"],
 *           "output": "client/common/distribution/target/runtime/codex"
 *         }
 *       },
 *       {"name": "formatter@acme-tools"}
 *     ]
 *   }
 * }
 * }</pre>
 * A plugin with {@code build} is one that the project builds; any other is the user's installed copy, named
 * {@code <plugin>@<marketplace>}. Unknown keys are refused.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param harnesses the plugins of each harness that the configuration has a key for, by the key
 */
public record PromptConfiguration(Map<String, List<ConfiguredPlugin>> harnesses)
{
	/**
	 * The configuration's file name in a target's test directory.
	 */
	public static final String FILE_NAME = "prompt.json";
	/**
	 * The keys of the harnesses.
	 */
	private static final List<String> HARNESSES = List.of("claude", "codex");
	private static final String PLUGINS = "plugins";
	private static final String NAME = "name";
	private static final String BUILD = "build";
	private static final String COMMAND = "command";
	private static final String OUTPUT = "output";

	/**
	 * Creates a configuration.
	 *
	 * @param harnesses the plugins of each harness that the configuration has a key for, by the key
	 * @throws NullPointerException if {@code harnesses} is null
	 */
	public PromptConfiguration
	{
		Map<String, List<ConfiguredPlugin>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, List<ConfiguredPlugin>> entry : harnesses.entrySet())
			copy.put(entry.getKey(), List.copyOf(entry.getValue()));
		harnesses = Map.copyOf(copy);
	}

	/**
	 * Returns where a target's configuration lies.
	 *
	 * @param target the target prompt file, relative to the project
	 * @return the configuration file, relative to the project
	 * @throws NullPointerException if {@code target} is null
	 * @throws IllegalArgumentException if {@code target} is absolute
	 */
	public static Path location(Path target)
	{
		return PromptTestLocations.scenarioDirectory(target).resolve(FILE_NAME);
	}

	/**
	 * Returns the plugins that a harness's trials install.
	 *
	 * @param harness the harness's key, such as {@code codex}
	 * @return the plugins in configuration order, or empty if the configuration has no key for the harness
	 * @throws NullPointerException if {@code harness} is null
	 */
	public Optional<List<ConfiguredPlugin>> plugins(String harness)
	{
		requireThat(harness, "harness").isNotNull();
		return Optional.ofNullable(harnesses.get(harness));
	}

	/**
	 * Reads a configuration.
	 *
	 * @param json the JSON mapper
	 * @param content the file's content
	 * @return the configuration
	 * @throws NullPointerException if any of the arguments are null
	 * @throws PromptConfigurationException if the content is not a valid configuration; the exception lists every
	 *   problem
	 */
	public static PromptConfiguration parse(JsonMapper json, String content) throws PromptConfigurationException
	{
		requireThat(json, "json").isNotNull();
		requireThat(content, "content").isNotNull();
		JsonNode root;
		try
		{
			root = json.readTree(content);
		}
		catch (JacksonException e)
		{
			throw new PromptConfigurationException(List.of("$: is not valid JSON: " + e.getOriginalMessage()));
		}
		if (root == null || !root.isObject())
			throw new PromptConfigurationException(List.of("$: must be an object"));
		List<String> problems = new ArrayList<>();
		Map<String, List<ConfiguredPlugin>> harnesses = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> property : root.properties())
		{
			String key = property.getKey();
			String location = "$." + key;
			if (!HARNESSES.contains(key))
			{
				problems.add(location + ": is unknown. Keys: " + String.join(", ", HARNESSES));
				continue;
			}
			harnesses.put(key, harness(property.getValue(), location, problems));
		}
		if (!problems.isEmpty())
			throw new PromptConfigurationException(problems);
		return new PromptConfiguration(harnesses);
	}

	/**
	 * Reads one harness's plugins.
	 *
	 * @param node the harness's object
	 * @param location the object's JSON path
	 * @param problems receives each problem
	 * @return the plugins that were read without a problem
	 */
	private static List<ConfiguredPlugin> harness(JsonNode node, String location, List<String> problems)
	{
		if (!node.isObject())
		{
			problems.add(location + ": must be an object");
			return List.of();
		}
		List<ConfiguredPlugin> plugins = new ArrayList<>();
		boolean listed = false;
		for (Map.Entry<String, JsonNode> property : node.properties())
		{
			String key = property.getKey();
			String keyLocation = location + "." + key;
			if (!key.equals(PLUGINS))
			{
				problems.add(keyLocation + ": is unknown. Keys: " + PLUGINS);
				continue;
			}
			listed = true;
			JsonNode list = property.getValue();
			if (!list.isArray())
			{
				problems.add(keyLocation + ": must be a list");
				continue;
			}
			Set<String> names = new HashSet<>();
			for (int i = 0; i < list.size(); ++i)
				plugin(list.get(i), keyLocation + "[" + i + "]", names, problems).ifPresent(plugins::add);
		}
		if (!listed)
			problems.add(location + "." + PLUGINS + ": is missing");
		return plugins;
	}

	/**
	 * Reads one plugin.
	 *
	 * @param node the plugin's object
	 * @param location the object's JSON path
	 * @param names the names of the harness's plugins read so far; receives this plugin's name
	 * @param problems receives each problem
	 * @return the plugin, or empty if it has a problem
	 */
	private static Optional<ConfiguredPlugin> plugin(JsonNode node, String location, Set<String> names,
		List<String> problems)
	{
		if (!node.isObject())
		{
			problems.add(location + ": must be an object");
			return Optional.empty();
		}
		int problemsBefore = problems.size();
		String name = null;
		boolean named = false;
		Optional<TrialPlugin.Build> build = Optional.empty();
		boolean built = false;
		for (Map.Entry<String, JsonNode> property : node.properties())
		{
			String key = property.getKey();
			String keyLocation = location + "." + key;
			switch (key)
			{
				case NAME ->
				{
					named = true;
					name = nonBlank(property.getValue(), keyLocation, problems);
				}
				case BUILD ->
				{
					built = true;
					build = build(property.getValue(), keyLocation, problems);
				}
				default -> problems.add(keyLocation + ": is unknown. Keys: " + NAME + ", " + BUILD);
			}
		}
		String nameLocation = location + "." + NAME;
		if (!named)
			problems.add(nameLocation + ": is missing");
		else if (name != null)
		{
			if (!built && !isInstalledName(name))
			{
				problems.add(nameLocation + ": a plugin without build is named <plugin>@<marketplace>, as the " +
					"harness's install records name it");
			}
			else if (!names.add(name))
				problems.add(nameLocation + ": names the plugin " + name + " a second time");
		}
		if (problems.size() > problemsBefore)
			return Optional.empty();
		return Optional.of(new ConfiguredPlugin(name, build));
	}

	/**
	 * Indicates whether a name has the form {@code <plugin>@<marketplace>}.
	 *
	 * @param name a plugin name
	 * @return {@code true} if the name has a plugin and a marketplace part
	 */
	private static boolean isInstalledName(String name)
	{
		int at = name.indexOf('@');
		return at > 0 && at < name.length() - 1 && name.indexOf('@', at + 1) == -1;
	}

	/**
	 * Reads a plugin's build.
	 *
	 * @param node the build's object
	 * @param location the object's JSON path
	 * @param problems receives each problem
	 * @return the build, or empty if it has a problem
	 */
	private static Optional<TrialPlugin.Build> build(JsonNode node, String location, List<String> problems)
	{
		if (!node.isObject())
		{
			problems.add(location + ": must be an object");
			return Optional.empty();
		}
		int problemsBefore = problems.size();
		List<String> command = List.of();
		Path output = null;
		boolean hasOutput = false;
		for (Map.Entry<String, JsonNode> property : node.properties())
		{
			String key = property.getKey();
			String keyLocation = location + "." + key;
			switch (key)
			{
				case COMMAND -> command = command(property.getValue(), keyLocation, problems);
				case OUTPUT ->
				{
					hasOutput = true;
					output = output(property.getValue(), keyLocation, problems);
				}
				default -> problems.add(keyLocation + ": is unknown. Keys: " + COMMAND + ", " + OUTPUT);
			}
		}
		if (!hasOutput)
			problems.add(location + "." + OUTPUT + ": is missing");
		if (problems.size() > problemsBefore)
			return Optional.empty();
		return Optional.of(new TrialPlugin.Build(command, output));
	}

	/**
	 * Reads a build command.
	 *
	 * @param node the command's list
	 * @param location the list's JSON path
	 * @param problems receives each problem
	 * @return the command's arguments
	 */
	private static List<String> command(JsonNode node, String location, List<String> problems)
	{
		if (!node.isArray())
		{
			problems.add(location + ": must be a list");
			return List.of();
		}
		if (node.isEmpty())
		{
			problems.add(location + ": must list at least the program; leave it out if the output needs no build");
			return List.of();
		}
		List<String> arguments = new ArrayList<>();
		for (int i = 0; i < node.size(); ++i)
		{
			JsonNode argument = node.get(i);
			String argumentLocation = location + "[" + i + "]";
			if (!argument.isString())
				problems.add(argumentLocation + ": must be a string");
			else if (i == 0 && argument.asString().isBlank())
				problems.add(argumentLocation + ": must be a non-blank string");
			else
				arguments.add(argument.asString());
		}
		return arguments;
	}

	/**
	 * Reads a build's output directory.
	 *
	 * @param node the output's value
	 * @param location the value's JSON path
	 * @param problems receives each problem
	 * @return the normalized directory, relative to the project root, or null if it has a problem
	 */
	private static Path output(JsonNode node, String location, List<String> problems)
	{
		String text = nonBlank(node, location, problems);
		if (text == null)
			return null;
		Path path;
		try
		{
			path = Path.of(text);
		}
		catch (InvalidPathException _)
		{
			path = null;
		}
		if (path == null || path.getRoot() != null || path.normalize().startsWith(".."))
		{
			problems.add(location + ": must be a directory relative to the project root, inside the project: " + text);
			return null;
		}
		return path.normalize();
	}

	/**
	 * Reads a non-blank string.
	 *
	 * @param node the value
	 * @param location the value's JSON path
	 * @param problems receives each problem
	 * @return the string, or null if it has a problem
	 */
	private static String nonBlank(JsonNode node, String location, List<String> problems)
	{
		if (!node.isString() || node.asString().isBlank())
		{
			problems.add(location + ": must be a non-blank string");
			return null;
		}
		return node.asString();
	}

	/**
	 * One plugin that a configuration lists.
	 *
	 * @param name the plugin's name: its manifest name if the project builds it, or {@code <plugin>@<marketplace>}
	 *   if it does not
	 * @param build how the project builds the plugin, or empty if trials install the user's installed copy
	 */
	public record ConfiguredPlugin(String name, Optional<TrialPlugin.Build> build)
	{
		/**
		 * Creates a configured plugin.
		 *
		 * @param name the plugin's name: its manifest name if the project builds it, or
		 *   {@code <plugin>@<marketplace>} if it does not
		 * @param build how the project builds the plugin, or empty if trials install the user's installed copy
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code name} is blank
		 */
		public ConfiguredPlugin
		{
			requireThat(name, "name").isNotBlank();
			requireThat(build, "build").isNotNull();
		}
	}
}
