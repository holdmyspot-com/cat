package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * The configuration layers of a Codex 0.160.0 session, merged the way Codex merges them.
 * <p>
 * From lowest to highest: the machine's {@code config.toml}, the user's {@code config.toml}, the profile file that
 * {@code -p} selects, the {@code .codex/config.toml} files of trusted project folders from the project root down to
 * the working directory, the {@code -c} overrides, and the machine's {@code managed_config.toml}. Tables merge key by
 * key; other values replace those of lower layers.
 *
 * @param layers        the layers that exist, the lowest first
 * @param merged        the merged values
 * @param trustDecision {@code true} if the user's configuration trusts or distrusts the working directory
 * @param sources       the files that the layers and the requirements come from
 */
public record CodexConfiguration(List<Layer> layers, ObjectNode merged, boolean trustDecision, List<Path> sources)
{
	private static final TomlMapper TOML = TomlMapper.builder().build();
	/**
	 * The requirements keys that can change the sandbox, which the reader does not interpret.
	 */
	private static final Set<String> SANDBOX_REQUIREMENTS = Set.of("allowed_sandbox_modes",
		"allowed_permission_profiles", "default_permissions", "remote_sandbox_config", "experimental_network",
		"permissions");
	/**
	 * The names that {@code -p} accepts.
	 */
	private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

	/**
	 * Creates a configuration.
	 *
	 * @param layers        the layers that exist, the lowest first
	 * @param merged        the merged values
	 * @param trustDecision {@code true} if the user's configuration trusts or distrusts the working directory
	 * @param sources       the files that the layers and the requirements come from
	 * @throws NullPointerException if any argument is null
	 */
	public CodexConfiguration
	{
		layers = List.copyOf(layers);
		requireThat(merged, "merged").isNotNull();
		sources = List.copyOf(sources);
	}

	/**
	 * A configuration layer.
	 *
	 * @param values the layer's values, with relative paths resolved
	 * @param flags  {@code true} if the layer holds the {@code -c} overrides
	 */
	public record Layer(ObjectNode values, boolean flags)
	{
		/**
		 * Creates a layer.
		 *
		 * @param values the layer's values, with relative paths resolved
		 * @param flags  {@code true} if the layer holds the {@code -c} overrides
		 * @throws NullPointerException if {@code values} is null
		 */
		public Layer
		{
			requireThat(values, "values").isNotNull();
		}
	}

	/**
	 * Loads a session's configuration.
	 *
	 * @param systemFiles the machine-wide files
	 * @param codexHome   Codex's home
	 * @param cwd         the session's working directory
	 * @param launch      the session's command line
	 * @return the configuration
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if a file cannot be parsed, an override is malformed, or the requirements constrain the
	 *                       sandbox in a way that the reader does not interpret
	 */
	public static CodexConfiguration load(CodexSystemFiles systemFiles, Path codexHome, Path cwd, CodexLaunch launch)
		throws IOException, PolicyRefusal
	{
		List<Path> sources = new ArrayList<>();
		Path requirements = systemFiles.folder().resolve("requirements.toml");
		Optional<ObjectNode> requirementValues = readFile(requirements, sources);
		if (requirementValues.isPresent())
			requireUninterpretedRequirements(requirements, requirementValues.get());

		List<Layer> lower = new ArrayList<>();
		addFileLayer(systemFiles.folder().resolve("config.toml"), systemFiles.folder(), lower, sources);
		if (!launch.ignoreUserConfig())
			addFileLayer(codexHome.resolve("config.toml"), codexHome, lower, sources);
		if (launch.profile().isPresent())
		{
			String profile = launch.profile().get();
			if (!PROFILE_NAME.matcher(profile).matches())
			{
				throw new PolicyRefusal("Codex runs with the profile \"" + profile + "\", whose file CAT cannot " +
					"find", "Name a profile of letters, digits, \"_\" and \"-\", then start a new Codex session");
			}
			addFileLayer(codexHome.resolve(profile + ".config.toml"), codexHome, lower, sources);
		}
		Layer flags = new Layer(overrideLayer(launch.overrides(), cwd), true);
		List<Layer> upper = new ArrayList<>();
		upper.add(flags);
		addFileLayer(systemFiles.folder().resolve("managed_config.toml"), systemFiles.folder(), upper, sources);

		List<Layer> withoutProjects = new ArrayList<>(lower);
		withoutProjects.addAll(upper);
		ObjectNode beforeProjects = merge(withoutProjects);
		CodexProjectTrust trust = CodexProjectTrust.of(beforeProjects, cwd);
		List<Layer> layers = new ArrayList<>(lower);
		for (Path folder : trust.projectFolders())
		{
			Path dotCodex = folder.resolve(".codex");
			if (!Files.isDirectory(dotCodex) || sameFile(dotCodex, codexHome) || !trust.trusts(folder))
				continue;
			Path file = dotCodex.resolve("config.toml");
			Optional<ObjectNode> values = readFile(file, sources);
			if (values.isEmpty())
				continue;
			resolveRelativePaths(values.get(), dotCodex);
			layers.add(new Layer(values.get(), false));
		}
		layers.addAll(upper);
		return new CodexConfiguration(layers, merge(layers), trust.decides(cwd), sources);
	}

	/**
	 * Refuses requirements that constrain the sandbox.
	 *
	 * @param file   the requirements file
	 * @param values its values
	 * @throws PolicyRefusal if the requirements constrain the sandbox
	 */
	private static void requireUninterpretedRequirements(Path file, ObjectNode values) throws PolicyRefusal
	{
		for (String key : values.propertyNames())
		{
			boolean proxyFeature = (key.equals("features") || key.equals("feature_requirements")) &&
				values.path(key).has("network_proxy");
			if (SANDBOX_REQUIREMENTS.contains(key) || proxyFeature)
			{
				throw new PolicyRefusal("the Codex requirements file " + file + " sets \"" + key + "\", which " +
					"changes the session's sandbox in a way that CAT does not read", "Ask the administrator who " +
					"manages Codex on this machine to remove \"" + key + "\" from " + file + ", or run CAT from a " +
					"Codex installation without it");
			}
		}
	}

	/**
	 * Adds the layer of a configuration file if the file exists.
	 *
	 * @param file    the file
	 * @param base    the folder that the file's relative paths are relative to
	 * @param layers  the layers to add to
	 * @param sources the source files to add to
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the file cannot be parsed
	 */
	private static void addFileLayer(Path file, Path base, List<Layer> layers, List<Path> sources)
		throws IOException, PolicyRefusal
	{
		Optional<ObjectNode> values = readFile(file, sources);
		if (values.isEmpty())
			return;
		resolveRelativePaths(values.get(), base);
		layers.add(new Layer(values.get(), false));
	}

	/**
	 * Reads a TOML file if it exists.
	 *
	 * @param file    the file
	 * @param sources the source files, which the file joins if it exists
	 * @return the file's values, or empty if it does not exist
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the file cannot be parsed
	 */
	private static Optional<ObjectNode> readFile(Path file, List<Path> sources) throws IOException, PolicyRefusal
	{
		if (!Files.exists(file))
			return Optional.empty();
		sources.add(file);
		String text = Files.readString(file);
		try
		{
			JsonNode values = TOML.readTree(text);
			if (values instanceof ObjectNode table)
				return Optional.of(table);
			return Optional.of(JsonNodeFactory.instance.objectNode());
		}
		catch (JacksonException e)
		{
			throw new PolicyRefusal("CAT cannot parse the Codex configuration file " + file + ": " +
				e.getOriginalMessage(), "Fix the file so that it is valid TOML, then start a new Codex session");
		}
	}

	/**
	 * Builds the layer of the {@code -c} overrides.
	 *
	 * @param overrides the overrides, in {@code key=value} form
	 * @param cwd       the folder that relative paths are relative to
	 * @return the layer's values
	 * @throws PolicyRefusal if an override has no {@code =} or no key
	 */
	private static ObjectNode overrideLayer(List<String> overrides, Path cwd) throws PolicyRefusal
	{
		ObjectNode root = JsonNodeFactory.instance.objectNode();
		for (String override : overrides)
		{
			int equals = override.indexOf('=');
			String key;
			if (equals < 0)
				key = "";
			else
				key = override.substring(0, equals).strip();
			if (key.isEmpty())
			{
				throw new PolicyRefusal("Codex runs with the override \"-c " + override + "\", which is not " +
					"key=value", "Start Codex without that override, then start a new Codex session");
			}
			if (key.equals("use_legacy_landlock"))
				key = "features.use_legacy_landlock";
			applyOverride(root, key, overrideValue(override.substring(equals + 1).strip()));
		}
		resolveRelativePaths(root, cwd);
		return root;
	}

	/**
	 * Parses an override's value the way Codex does: as a TOML value, or else as a string without its quotes.
	 *
	 * @param raw the text after the {@code =}
	 * @return the value
	 */
	private static JsonNode overrideValue(String raw)
	{
		try
		{
			JsonNode wrapped = TOML.readTree("_x_ = " + raw);
			JsonNode value = wrapped.get("_x_");
			if (value != null)
				return value;
		}
		catch (JacksonException _)
		{
			// Codex reads a value that is not TOML as a string
		}
		String trimmed = raw.replaceAll("^[\"']+|[\"']+$", "");
		return JsonNodeFactory.instance.stringNode(trimmed);
	}

	/**
	 * Sets a dotted key, creating the tables on its way. A feature that is a table takes a {@code true} or
	 * {@code false} value as its {@code enabled} key.
	 *
	 * @param root  the layer
	 * @param key   the dotted key
	 * @param value the value
	 */
	private static void applyOverride(ObjectNode root, String key, JsonNode value)
	{
		String[] segments = key.split("\\.", -1);
		ObjectNode current = root;
		for (int index = 0; index < segments.length - 1; ++index)
		{
			JsonNode next = current.get(segments[index]);
			if (next instanceof ObjectNode table)
				current = table;
			else
				current = current.putObject(segments[index]);
		}
		String last = segments[segments.length - 1];
		JsonNode existing = current.get(last);
		boolean feature = segments.length == 2 && segments[0].equals("features");
		if (feature && existing instanceof ObjectNode table && value.isBoolean())
		{
			table.set("enabled", value);
			return;
		}
		current.set(last, value);
	}

	/**
	 * Merges layers.
	 *
	 * @param layers the layers, the lowest first
	 * @return the merged values
	 */
	private static ObjectNode merge(List<Layer> layers)
	{
		ObjectNode merged = JsonNodeFactory.instance.objectNode();
		for (Layer layer : layers)
			mergeInto(merged, layer.values());
		return merged;
	}

	/**
	 * Merges a table into another, key by key.
	 *
	 * @param target the table that receives the values
	 * @param source the table whose values win
	 */
	public static void mergeInto(ObjectNode target, ObjectNode source)
	{
		for (Entry<String, JsonNode> entry : source.properties())
		{
			JsonNode existing = target.get(entry.getKey());
			if (existing instanceof ObjectNode existingTable && entry.getValue() instanceof ObjectNode table)
				mergeInto(existingTable, table);
			else
				target.set(entry.getKey(), entry.getValue().deepCopy());
		}
	}

	/**
	 * Resolves the relative {@code writable_roots} of a layer against the layer's folder, as Codex does.
	 *
	 * @param values the layer's values
	 * @param base   the layer's folder
	 */
	private static void resolveRelativePaths(ObjectNode values, Path base)
	{
		if (!(values.path("sandbox_workspace_write").path("writable_roots") instanceof ArrayNode roots))
			return;
		for (int index = 0; index < roots.size(); ++index)
		{
			JsonNode root = roots.get(index);
			if (!root.isString())
				continue;
			try
			{
				Path path = Path.of(root.stringValue());
				if (!path.isAbsolute())
					roots.set(index, JsonNodeFactory.instance.stringNode(base.resolve(path).normalize().toString()));
			}
			catch (InvalidPathException _)
			{
				// CodexPermissions refuses the root
			}
		}
	}

	/**
	 * Indicates whether two paths name the same existing file.
	 *
	 * @param first  a path
	 * @param second another path
	 * @return {@code true} if both exist and are the same file
	 * @throws IOException if a file's attributes cannot be read
	 */
	private static boolean sameFile(Path first, Path second) throws IOException
	{
		return Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
	}
}
