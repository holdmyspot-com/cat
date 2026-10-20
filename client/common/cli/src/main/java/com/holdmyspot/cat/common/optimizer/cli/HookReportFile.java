package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The file in a campaign's directory that holds what running the hooks that run before the model acts showed, so
 * that every presentation of the proposal lists the same differences without running the hooks again.
 */
final class HookReportFile
{
	/**
	 * The file's name.
	 */
	static final String NAME = "hooks.json";
	/**
	 * The version of the file's format.
	 */
	private static final int FORMAT_VERSION = 1;
	private final Path file;
	private final JsonMapper json;

	/**
	 * Creates the file's accessor.
	 *
	 * @param directory the campaign's directory
	 * @param json      the JSON mapper
	 * @throws NullPointerException if any of the arguments are null
	 */
	HookReportFile(Path directory, JsonMapper json)
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(json, "json").isNotNull();
		this.file = directory.resolve(NAME);
		this.json = json;
	}

	/**
	 * Replaces the stored report.
	 *
	 * @param report the report
	 * @throws NullPointerException if {@code report} is null
	 * @throws IOException          if the file cannot be written
	 */
	void write(HookReport report) throws IOException
	{
		requireThat(report, "report").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", FORMAT_VERSION);
		root.put("checked", report.checked());
		ArrayNode differences = root.putArray("differences");
		for (HookReport.HookDifference difference : report.differences())
		{
			ObjectNode node = differences.addObject();
			node.put("hook", difference.hook());
			ArrayNode trial = node.putArray("trial");
			difference.trial().forEach(trial::add);
			ArrayNode session = node.putArray("session");
			difference.session().forEach(session::add);
		}
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		PortableFiles.replace(temporary, file);
	}

	/**
	 * Reads the stored report.
	 *
	 * @return the report, or {@link HookReport#NONE} if the campaign's hooks were not run
	 * @throws IOException if the file exists but cannot be read, or holds another format
	 */
	HookReport read() throws IOException
	{
		if (!Files.exists(file))
			return HookReport.NONE;
		JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
		int version = root.path("format_version").asInt(0);
		if (version != FORMAT_VERSION)
			throw new IOException(file + " has format version " + version + " instead of " + FORMAT_VERSION + ".");
		List<HookReport.HookDifference> differences = new ArrayList<>();
		for (JsonNode node : root.path("differences"))
		{
			differences.add(new HookReport.HookDifference(node.path("hook").asString(), strings(node.path("trial")),
				strings(node.path("session"))));
		}
		return new HookReport(root.path("checked").asInt(), differences);
	}

	/**
	 * Reads an array of strings.
	 *
	 * @param array the array
	 * @return its elements
	 */
	private static List<String> strings(JsonNode array)
	{
		List<String> values = new ArrayList<>();
		for (JsonNode value : array)
			values.add(value.asString());
		return values;
	}
}
