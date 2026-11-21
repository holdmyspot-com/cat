package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The file in a campaign's directory that holds what running the hooks that run before the model acts showed, so
 * that every presentation of the proposal lists the same differences without running the hooks again. A report may
 * also hold an inspection's approval limitations. An empty array records a completed inspection with no limitations.
 */
final class HookReportFile
{
	/**
	 * The file's name.
	 */
	static final String NAME = "hooks.json";
	/**
	 * The version of a report without evaluator names.
	 */
	private static final int SINGLETON_VERSION = 1;
	/**
	 * The version of a report that records each evaluator independently.
	 */
	private static final int SCOPED_VERSION = 2;
	private static final String UNAPPROVED = "unapproved";
	private final Path file;
	private final JsonMapper json;
	private final FileReplacer replacer;

	/**
	 * Creates the file's accessor.
	 *
	 * @param directory the campaign's directory
	 * @param json      the JSON mapper
	 * @param replacer  replaces the file
	 * @throws NullPointerException if any of the arguments are null
	 */
	HookReportFile(Path directory, JsonMapper json, FileReplacer replacer)
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.file = directory.resolve(NAME);
		this.json = json;
		this.replacer = replacer;
	}

	/**
	 * Replaces the stored report.
	 *
	 * @param report the report
	 * @param unapproved the hooks without verified approval, when inspected
	 * @throws NullPointerException if {@code report} is null
	 * @throws IOException          if the file cannot be written
	 */
	void write(HookReport report, Optional<List<String>> unapproved) throws IOException
	{
		requireThat(report, "report").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", SINGLETON_VERSION);
		writeReport(root, report, unapproved);
		replace(root);
	}

	/**
	 * Replaces the stored report with the results of each evaluator.
	 *
	 * @param reports the report of each evaluator
	 * @param unapproved the hooks without verified approval for evaluators that were inspected
	 * @throws NullPointerException if {@code reports} or any of its entries is null
	 * @throws IllegalArgumentException if {@code reports} is empty
	 * @throws IOException if the file cannot be written
	 */
	void writeByEvaluator(Map<Harness, HookReport> reports, Map<Harness, List<String>> unapproved) throws IOException
	{
		requireThat(reports, "reports").isNotEmpty();
		Map<Harness, HookReport> ordered = new EnumMap<>(Harness.class);
		ordered.putAll(Map.copyOf(reports));
		ObjectNode root = json.createObjectNode();
		root.put("format_version", SCOPED_VERSION);
		ObjectNode evaluators = root.putObject("evaluators");
		ordered.forEach((evaluator, report) -> writeReport(evaluators.putObject(evaluator.commandName()), report,
			Optional.ofNullable(unapproved.get(evaluator))));
		replace(root);
	}

	/**
	 * Writes the count and differences of a report.
	 *
	 * @param reportNode the document to describe the report in
	 * @param report the result of checking the hooks
	 * @param unapproved the hooks without verified approval, when inspected
	 */
	private static void writeReport(ObjectNode reportNode, HookReport report, Optional<List<String>> unapproved)
	{
		reportNode.put("checked", report.checked());
		unapproved.ifPresent(hooks ->
		{
			ArrayNode values = reportNode.putArray(UNAPPROVED);
			hooks.forEach(values::add);
		});
		ArrayNode differences = reportNode.putArray("differences");
		for (HookReport.HookDifference difference : report.differences())
		{
			ObjectNode node = differences.addObject();
			node.put("hook", difference.hook());
			ArrayNode trial = node.putArray("trial");
			difference.trial().forEach(trial::add);
			ArrayNode session = node.putArray("session");
			difference.session().forEach(session::add);
		}
	}

	/**
	 * Reads the proposal's native approval limitations when an inspection recorded them.
	 *
	 * @param evaluator the evaluator whose plugins were inspected
	 * @return the recorded limitations, or empty when there is no inspection for the evaluator
	 * @throws IOException if the report cannot be read or the recorded limitations are invalid
	 */
	Optional<List<String>> unapproved(Harness evaluator) throws IOException
	{
		if (!Files.exists(file))
			return Optional.empty();
		JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
		int version = root.path("format_version").asInt(0);
		JsonNode report = switch (version)
		{
			case SINGLETON_VERSION -> root;
			case SCOPED_VERSION -> root.path("evaluators").path(evaluator.commandName());
			default -> throw new IOException(file + " has unsupported format version " + version + ".");
		};
		JsonNode value = report.path(UNAPPROVED);
		if (value.isMissingNode())
			return Optional.empty();
		if (!value.isArray() || value.valueStream().anyMatch(item -> !item.isString()))
			throw new IOException(file + " has invalid unapproved hooks for " + evaluator.commandName() + ".");
		return Optional.of(strings(value));
	}

	/**
	 * Replaces the report file with a document.
	 *
	 * @param root the document
	 * @throws IOException if the file cannot be written
	 */
	private void replace(ObjectNode root) throws IOException
	{
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		replacer.replace(temporary, file);
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
		return switch (version)
		{
			case SINGLETON_VERSION -> readReport(root);
			case SCOPED_VERSION -> readScoped(root.path("evaluators"));
			default -> throw new IOException(file + " has unsupported format version " + version + ".");
		};
	}

	/**
	 * Reads the reports in evaluator order and qualifies each difference with its evaluator.
	 *
	 * @param evaluators the reports keyed by evaluator command name
	 * @return the combined report
	 * @throws IOException if the evaluator reports are invalid
	 */
	private HookReport readScoped(JsonNode evaluators) throws IOException
	{
		if (!evaluators.isObject() || evaluators.isEmpty())
			throw new IOException(file + " must record at least one evaluator report.");
		Map<Harness, HookReport> reports = new EnumMap<>(Harness.class);
		for (Map.Entry<String, JsonNode> entry : evaluators.properties())
		{
			Harness evaluator = Harness.fromCommandName(entry.getKey()).orElseThrow(() ->
				new IOException(file + " names an unknown evaluator " + entry.getKey() + "."));
			JsonNode report = entry.getValue();
			if (!report.path("checked").isIntegralNumber() || !report.path("differences").isArray())
				throw new IOException(file + " has an invalid report for " + evaluator.commandName() + ".");
			reports.put(evaluator, readReport(report));
		}
		int checked = 0;
		List<HookReport.HookDifference> differences = new ArrayList<>();
		for (Map.Entry<Harness, HookReport> entry : reports.entrySet())
		{
			checked = Math.addExact(checked, entry.getValue().checked());
			String label = entry.getKey().productName() + " — ";
			for (HookReport.HookDifference difference : entry.getValue().differences())
			{
				differences.add(new HookReport.HookDifference(label + difference.hook(), difference.trial(),
					difference.session()));
			}
		}
		return new HookReport(checked, differences);
	}

	/**
	 * Reads the count and differences of a report in either format.
	 *
	 * @param root the report's document
	 * @return the stored report
	 */
	private static HookReport readReport(JsonNode root)
	{
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
