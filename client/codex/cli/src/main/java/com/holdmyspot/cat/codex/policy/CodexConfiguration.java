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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * The configuration layers of a Codex 0.160.0 session, merged the way Codex merges them.
 * <p>
 * From lowest to highest: the machine's {@code config.toml}, the user's {@code config.toml}, the
 * {@code .codex/config.toml} files of trusted project folders from the project root down to the working directory,
 * and the machine's {@code managed_config.toml}. A command cannot see the Codex process, so the layers that Codex's
 * command line adds, such as {@code -p} and {@code -c}, are taken to be absent. Tables merge key by
 * key; other values replace those of lower layers.
 *
 * @param layers        the layers that exist, the lowest first
 * @param merged        the merged values
 * @param trustDecision {@code true} if the user's configuration trusts or distrusts the working directory
 */
public record CodexConfiguration(List<Layer> layers, ObjectNode merged, boolean trustDecision)
{
	private static final TomlMapper TOML = TomlMapper.builder().build();
	/**
	 * The requirements keys that can change the sandbox, which the reader does not interpret.
	 */
	private static final Set<String> SANDBOX_REQUIREMENTS = Set.of("allowed_sandbox_modes",
		"allowed_permission_profiles", "default_permissions", "remote_sandbox_config", "experimental_network",
		"permissions");

	/**
	 * Creates a configuration.
	 *
	 * @param layers        the layers that exist, the lowest first
	 * @param merged        the merged values
	 * @param trustDecision {@code true} if the user's configuration trusts or distrusts the working directory
	 * @throws NullPointerException if any argument is null
	 */
	public CodexConfiguration
	{
		layers = List.copyOf(layers);
		requireThat(merged, "merged").isNotNull();
	}

	/**
	 * A configuration layer.
	 *
	 * @param values the layer's values, with relative paths resolved
	 */
	public record Layer(ObjectNode values)
	{
		/**
		 * Creates a layer.
		 *
		 * @param values the layer's values, with relative paths resolved
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
	 * @return the configuration
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if a file cannot be parsed, an override is malformed, or the requirements constrain the
	 *                       sandbox in a way that the reader does not interpret
	 */
	public static CodexConfiguration load(CodexSystemFiles systemFiles, Path codexHome, Path cwd)
		throws IOException, PolicyRefusal
	{
		Path requirements = systemFiles.folder().resolve("requirements.toml");
		Optional<ObjectNode> requirementValues = readFile(requirements);
		if (requirementValues.isPresent())
			requireUninterpretedRequirements(requirements, requirementValues.get());

		List<Layer> lower = new ArrayList<>();
		addFileLayer(systemFiles.folder().resolve("config.toml"), systemFiles.folder(), lower);
		addFileLayer(codexHome.resolve("config.toml"), codexHome, lower);
		List<Layer> upper = new ArrayList<>();
		addFileLayer(systemFiles.folder().resolve("managed_config.toml"), systemFiles.folder(), upper);

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
			Optional<ObjectNode> values = readFile(file);
			if (values.isEmpty())
				continue;
			resolveRelativePaths(values.get(), dotCodex);
			layers.add(new Layer(values.get()));
		}
		layers.addAll(upper);
		return new CodexConfiguration(layers, merge(layers), trust.decides(cwd));
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
	 * @param file   the file
	 * @param base   the folder that the file's relative paths are relative to
	 * @param layers the layers to add to
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the file cannot be parsed
	 */
	private static void addFileLayer(Path file, Path base, List<Layer> layers)
		throws IOException, PolicyRefusal
	{
		Optional<ObjectNode> values = readFile(file);
		if (values.isEmpty())
			return;
		resolveRelativePaths(values.get(), base);
		layers.add(new Layer(values.get()));
	}

	/**
	 * Reads a TOML file if it exists.
	 *
	 * @param file the file
	 * @return the file's values, or empty if it does not exist
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the file cannot be parsed
	 */
	private static Optional<ObjectNode> readFile(Path file) throws IOException, PolicyRefusal
	{
		if (!Files.exists(file))
			return Optional.empty();
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
