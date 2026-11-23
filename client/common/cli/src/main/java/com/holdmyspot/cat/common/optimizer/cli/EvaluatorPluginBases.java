package com.holdmyspot.cat.common.optimizer.cli;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes the ordered plugin bases given to a peer's read-only loader inspection.
 */
final class EvaluatorPluginBases
{
	private static final int MAXIMUM_BYTES = 1_048_576;
	private static final String DIRECTORY = "directory";
	private static final String NAME = "name";
	private static final String BASE_DIGEST = "base_digest";
	private static final String REPOSITORY = "repository";
	private static final String MAPPINGS = "mappings";
	private static final String PROJECT_BUILT = "project_built";

	/**
	 * Writes the ordered inspection input, retaining only the fields read by the native loader.
	 *
	 * @param json the protocol mapper
	 * @param file the owned exchange path
	 * @param bases the selected bases
	 * @throws IOException if the input cannot be written within the size limit
	 */
	static void write(JsonMapper json, Path file, List<PluginBase> bases) throws IOException
	{
		ArrayNode plugins = json.createArrayNode();
		for (PluginBase base : bases)
		{
			TrialPlugin plugin = base.plugin();
			ObjectNode node = plugins.addObject();
			node.put(DIRECTORY, base.directory().toString());
			node.put(NAME, plugin.name());
			node.put(BASE_DIGEST, plugin.baseDigest());
			if (plugin.repository() != null)
				node.put(REPOSITORY, plugin.repository());
			node.put(PROJECT_BUILT, plugin.isProjectBuilt());
			ArrayNode mappings = node.putArray(MAPPINGS);
			for (TrialPlugin.Mapping mapping : plugin.mappings())
			{
				ObjectNode entry = mappings.addObject();
				entry.put("source", PortablePaths.text(mapping.source()));
				entry.put("target", PortablePaths.text(mapping.target()));
			}
		}
		byte[] content = json.writeValueAsBytes(plugins);
		if (content.length > MAXIMUM_BYTES)
			throw new IOException("Evaluator plugin input exceeds the maximum supported size");
		Files.write(file, content);
	}

	/**
	 * Reads an ordered list of plugin bases for loader inspection.
	 *
	 * @param json the protocol mapper
	 * @param file the absolute input path
	 * @return the frozen bases to inspect
	 * @throws IOException if the input path or any of the recorded plugin fields is invalid
	 */
	static List<PluginBase> read(JsonMapper json, Path file) throws IOException
	{
		if (!file.isAbsolute() || !Files.isRegularFile(file, NOFOLLOW_LINKS))
			throw new IOException("Expected an absolute evaluator plugin input file");
		JsonNode plugins = json.readTree(BoundedInput.readUtf8(file, MAXIMUM_BYTES, "Evaluator plugin input"));
		if (!plugins.isArray())
			throw new IOException("Expected an evaluator plugin array");
		List<PluginBase> bases = new ArrayList<>();
		for (JsonNode node : plugins)
			bases.add(base(node));
		return List.copyOf(bases);
	}

	/**
	 * Reads the selected base and reconstructs its loader inputs.
	 *
	 * @param node a plugin descriptor
	 * @return the base directory and loader data
	 * @throws IOException if the descriptor has an invalid field
	 */
	private static PluginBase base(JsonNode node) throws IOException
	{
		Path directory = Path.of(text(node, DIRECTORY));
		if (!directory.isAbsolute())
			throw new IOException("Expected an absolute evaluator plugin base path");
		JsonNode built = node.path(PROJECT_BUILT);
		JsonNode files = node.path(MAPPINGS);
		if (!built.isBoolean() || !files.isArray())
			throw new IOException("Expected evaluator plugin build and mapping fields");
		List<TrialPlugin.Mapping> mappings = new ArrayList<>();
		for (JsonNode mapping : files)
			mappings.add(new TrialPlugin.Mapping(Path.of(text(mapping, "source")), Path.of(text(mapping, "target"))));
		// Only the kind of base matters to the loader. Build commands and outputs stay in the owner process.
		TrialPlugin.BaseSource source = new TrialPlugin.Installed();
		if (built.booleanValue())
			source = new TrialPlugin.Build(List.of(), Path.of(""));
		String repository = null;
		if (node.has(REPOSITORY))
			repository = text(node, REPOSITORY);
		TrialPlugin plugin = new TrialPlugin(text(node, NAME), text(node, BASE_DIGEST), source, mappings, repository);
		return new PluginBase(plugin, directory.normalize());
	}

	/**
	 * Checks that the loader will inspect the same immutable base a trial would use.
	 *
	 * @param bases the selected bases
	 * @throws IOException if a base is missing or has changed
	 */
	static void verify(List<PluginBase> bases) throws IOException
	{
		for (PluginBase base : bases)
		{
			if (!base.directory().isAbsolute() || !Files.isDirectory(base.directory(), NOFOLLOW_LINKS))
				throw new IOException("Missing evaluator plugin base");
			if (!InventoryScanner.scan(base.directory(), Set.of()).identity().hex().equals(base.plugin().baseDigest()))
				throw new IOException("Changed evaluator plugin base");
		}
	}

	/**
	 * Requires a text property in a plugin descriptor or installation report.
	 *
	 * @param node the report object
	 * @param name the required property
	 * @return the stated value
	 * @throws IOException if the value is missing or has another type
	 */
	static String text(JsonNode node, String name) throws IOException
	{
		JsonNode field = node.path(name);
		if (!field.isString())
			throw new IOException("Expected evaluator text field " + name);
		return field.stringValue();
	}

	/**
	 * Prevents construction.
	 */
	private EvaluatorPluginBases()
	{
	}
}
