package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedRequirement;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureSummary;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.report.EnumNames;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief.EndedAttempt;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorProtocol;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorManifest;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorReport;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorConflict;
import io.github.cowwoc.cat.common.optimizer.execution.NeededEdit;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Operation;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.proposal.JsonResponse;
import io.github.cowwoc.cat.common.optimizer.proposal.PromptTemplate;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds requests for sessions that edit prompt files, and reads their reports of needed edits outside the allowed
 * paths and operations, together with their evidence manifest.
 * <p>
 * <b>Thread Safety:</b> Callers may build requests and read reports concurrently.
 */
public final class AuthorPrompt implements AuthorProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	private static final String PROMPT_RESOURCE = "/optimizer/author/author.md";
	private static final String REPORT_SCHEMA_RESOURCE = "/optimizer/author/report.schema.json";
	private static final String NEEDED_EDITS = "needed_edits";
	private static final Set<String> REQUIRED_EDIT_PROPERTIES = Set.of("requirement", "path", "operation");
	private static final String TARGET = "target";
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final JsonResponse responses;
	private final PromptTemplate prompt;
	private final String reportSchema;
	private final Set<Path> promptSources;
	private final String loaderConfiguration;

	/**
	 * Creates the author prompt for the confirmed edit authority.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema writes the confirmed requirements, tests and evaluation settings as JSON for the author
	 * @param promptSources the in-project prompt files and directories, relative to the project directory, at or below
	 *   which the author may edit, create, rename and delete ordinary files; a request leaves out each one whose path
	 *   is a symbolic link to a file outside the project, because that file is read-only
	 * @param loaderConfiguration the files and directories that decide which prompt files load, at or below which
	 *   nothing may change
	 * @throws NullPointerException if any of the arguments are null or contain null
	 * @throws UncheckedIOException if the packaged author prompt or report schema cannot be read
	 */
	public AuthorPrompt(JsonMapper json, ProposalSchema schema, Set<Path> promptSources,
		Set<Path> loaderConfiguration)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		this.json = json;
		this.schema = schema;
		this.responses = new JsonResponse(json);
		this.prompt = PromptTemplate.load(PROMPT_RESOURCE);
		this.reportSchema = pretty(json.readTree(PromptTemplate.load(REPORT_SCHEMA_RESOURCE).text()));
		this.promptSources = Set.copyOf(promptSources);
		this.loaderConfiguration = list(loaderConfiguration);
	}

	/**
	 * Returns paths as a Markdown list.
	 *
	 * @param paths the paths
	 * @return one {@code - path} line per path in path order, or {@code - none} if there are none
	 * @throws NullPointerException if {@code paths} is null or contains null
	 */
	private static String list(Set<Path> paths)
	{
		List<Path> sorted = Set.copyOf(paths).stream().sorted().toList();
		if (sorted.isEmpty())
			return "- none";
		return sorted.stream().map(path -> "- " + path).collect(Collectors.joining("\n"));
	}

	/**
	 * Returns symbolic links as a Markdown list.
	 *
	 * @param links the resolved target of each link, keyed by the link's path
	 * @return one {@code - path -> target} line per link in path order, or {@code - none} if there are none
	 */
	private static String links(Map<Path, Path> links)
	{
		if (links.isEmpty())
			return "- none";
		return new TreeMap<>(links).entrySet().stream().
			map(entry -> "- " + entry.getKey() + " -> " + entry.getValue()).
			collect(Collectors.joining("\n"));
	}

	/**
	 * Returns the prompt sources that the author may edit as a Markdown list.
	 *
	 * @param links the resolved target of each symbolic link, keyed by the link's path
	 * @return one {@code - path} line per prompt source in path order, leaving out each source whose path is a link to
	 *   a file outside the project, or {@code - none} if no source remains
	 */
	private String editableSources(Map<Path, Path> links)
	{
		Set<Path> editable = new HashSet<>();
		for (Path source : promptSources)
		{
			Path target = links.get(source);
			if (target == null || !Authority.isExternal(target))
				editable.add(source);
		}
		return list(editable);
	}

	/**
	 * Returns the edits outside the edit authority that the user declined as a Markdown list.
	 *
	 * @param warnings the warnings that the user answered by using an authorized source, in the order answered
	 * @return one {@code - requirement <requirement>: <path> -> <target> (<limit>)} line per warning in order, without
	 *   {@code -> <target>} when the path is not a link, or {@code - none} if there are none
	 */
	private static String declinedEdits(List<AuthorityWarning> warnings)
	{
		if (warnings.isEmpty())
			return "- none";
		List<String> lines = new ArrayList<>();
		for (AuthorityWarning warning : warnings)
		{
			StringBuilder line = new StringBuilder("- requirement ").append(warning.requirement()).append(": ").
				append(warning.path());
			if (!warning.target().equals(warning.path()))
				line.append(" -> ").append(warning.target());
			line.append(" (").append(EnumNames.lowerCase(warning.limit())).append(')');
			lines.add(line.toString());
		}
		return String.join("\n", lines);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The prompt lists the editable prompt sources, leaving out each one whose path is a link to a file outside the
	 * project, and the edits outside the edit authority that the user declined.
	 */
	@Override
	public SessionRequest request(AuthorBrief brief, Path copy)
	{
		requireThat(brief, "brief").isNotNull();
		requireThat(copy, "copy").isNotNull();
		ArrayNode failures = json.createArrayNode();
		for (int index = 0; index < brief.developmentFailures().size(); ++index)
		{
			Failure failure = brief.developmentFailures().get(index);
			String id = brief.developmentId(index);
			ObjectNode node = encodeFailure(failure, id, brief.evidenceSections().get(id));
			ArrayNode reruns = node.putArray("diagnostic_reruns");
			for (DiagnosticRerun rerun : failure.reruns())
			{
				ObjectNode rerunNode = reruns.addObject();
				rerunNode.put("verdict", rerun.verdict().name().toLowerCase(Locale.ROOT));
				rerunNode.put("rationale", rerun.rationale());
			}
			failures.add(node);
		}
		ArrayNode ended = json.createArrayNode();
		for (EndedAttempt attempt : brief.endedAttempts())
		{
			ObjectNode node = ended.addObject();
			node.put("index", attempt.index());
			node.put("outcome", attempt.outcome().label());
			node.set("counts", encodeCounts(attempt.counts()));
			ArrayNode attemptFailures = node.putArray("failures");
			for (FailureSummary failure : attempt.failures())
			{
				ObjectNode failureNode = attemptFailures.addObject();
				failureNode.put("cell", failure.cellId());
				failureNode.put("test", failure.testId());
				ArrayNode failedRequirements = failureNode.putArray("failed_requirements");
				for (FailedRequirement failed : failure.failedRequirements())
				{
					failedRequirements.addObject().put("requirement_id", failed.requirementId()).
						put("behavior", failed.behavior());
				}
			}
		}
		String turn = prompt.fill(Map.ofEntries(
			Map.entry("hunk_definition", Hunk.DEFINITION),
			Map.entry("prompt_sources", editableSources(brief.links())),
			Map.entry("loader_configuration", loaderConfiguration),
			Map.entry("links", links(brief.links())),
			Map.entry("declined_edits", declinedEdits(brief.answeredWarnings())),
			Map.entry("cell", brief.cell()),
			Map.entry("approach", brief.approach().text()),
			Map.entry("contract", json.writeValueAsString(DevelopmentContract.toJson(json, schema,
				brief.contract()))),
			Map.entry("campaign_edits", brief.campaignEdits()),
			Map.entry("failures", pretty(failures)),
			Map.entry("development_counts", pretty(encodeCounts(brief.developmentCounts()))),
			Map.entry("ended_attempts", pretty(ended)),
			Map.entry("report_schema", reportSchema)));
		return new SessionRequest(SessionRole.AUTHOR, Part.DEVELOPMENT, copy, List.of(turn), List.of(), TIME_LIMIT,
			brief.contract().judge().profile());
	}

	/**
	 * Writes a failing trial instance and the evidence that explains its failure.
	 *
	 * @param failure the instance and rationale to show the author
	 * @param id the scoped identifier of the supplied failure
	 * @param evidenceSection the failing trial's evidence section, or {@code null} if the trial's harness events are
	 *   no longer retained
	 * @return the failing instance with the reason for its failure in {@code rationale}: the judge's rationale, or
	 *   the record of each failed deterministic check; the failure's {@code impact}, or {@code null} if no triage
	 *   decided one; and the trial's {@code evidence_section}, or {@code null} if its events are no longer retained
	 */
	private ObjectNode encodeFailure(Failure failure, String id, String evidenceSection)
	{
		ObjectNode node = InstanceJson.encode(json, failure.instance());
		node.put("id", id);
		node.put("cell", failure.instance().cellId());
		node.put("rationale", failure.rationale());
		if (failure.triage() == null)
			node.putNull("impact");
		else
			node.put("impact", failure.triage().impact().label());
		ArrayNode responses = node.putArray("responses");
		for (List<String> turn : failure.responses())
		{
			ArrayNode messages = responses.addArray();
			turn.forEach(messages::add);
		}
		// A missing section is written as null rather than left out, so the author reads that the trial's events or
		// file changes are no longer retained instead of assuming that the trial made no tool calls
		node.put("evidence_section", evidenceSection);
		return node;
	}

	/**
	 * Writes outcome counts per cell: one category under one trial profile.
	 *
	 * @param counts the trial passes and failures to show the author, keyed by cell identifier
	 * @return an object with one {@code passes}, {@code failures} pair per cell, in cell identifier order
	 */
	private ObjectNode encodeCounts(Map<String, CellCounts> counts)
	{
		ObjectNode node = json.createObjectNode();
		for (Map.Entry<String, CellCounts> entry : new TreeMap<>(counts).entrySet())
		{
			ObjectNode cell = node.putObject(entry.getKey());
			cell.put("passes", entry.getValue().passes());
			cell.put("failures", entry.getValue().failures());
		}
		return node;
	}

	/**
	 * Returns JSON as indented text.
	 *
	 * @param node the JSON
	 * @return the text
	 */
	private String pretty(JsonNode node)
	{
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The report is the final response when it is exactly one JSON object, bare or alone inside one Markdown code
	 * fence, that holds the manifest and a {@code needed_edits} array as the report schema requires. Each edit holds
	 * a non-blank {@code requirement}, a {@code path}, an {@code operation} that names an {@link Operation} in lower
	 * case and, optionally, a {@code target}. Path validation follows the schema's parsed-path constraints, including
	 * normalized paths with no leading {@code ..} segment.
	 */
	@Override
	public Optional<AuthorReport> report(SessionResult.Completed result)
	{
		requireThat(result, "result").isNotNull();
		Optional<ObjectNode> output = responses.parseFinalResponse(result);
		if (output.isEmpty() ||
			!properties(output.get()).equals(Set.of(NEEDED_EDITS, "classifications", "hunks", "conflicts")))
			return Optional.empty();
		JsonNode edits = output.get().get(NEEDED_EDITS);
		if (edits == null || !edits.isArray())
			return Optional.empty();
		List<NeededEdit> neededEdits = new ArrayList<>();
		for (JsonNode edit : edits)
		{
			Optional<NeededEdit> neededEdit = parseNeededEdit(edit);
			if (neededEdit.isEmpty())
				return Optional.empty();
			neededEdits.add(neededEdit.get());
		}
		try
		{
			Map<String, FailureKind> classifications = new LinkedHashMap<>();
			JsonNode diagnoses = output.get().get("classifications");
			JsonNode hunks = output.get().get("hunks");
			if (!diagnoses.isArray() || !hunks.isArray())
				return Optional.empty();
			for (JsonNode diagnosis : diagnoses)
			{
				if (!properties(diagnosis).equals(Set.of("instance", "classification")))
					return Optional.empty();
				String id = text(diagnosis, "instance");
				FailureKind kind;
				try
				{
					kind = EnumNames.parse(text(diagnosis, "classification"), FailureKind.class);
				}
				catch (IllegalArgumentException _)
				{
					return Optional.empty();
				}
				if (classifications.put(id, kind) != null)
					return Optional.empty();
			}
			List<AuthorManifest.Evidence> evidence = new ArrayList<>();
			for (JsonNode hunk : hunks)
			{
				if (!properties(hunk).equals(Set.of("path", "before_start", "before_end", "after_start", "after_end",
					"instances", "analogous", "dissimilar")) ||
					!hunk.get("path").isString() || !isRangeInteger(hunk, "before_start") ||
					!isRangeInteger(hunk, "before_end") ||
					!isRangeInteger(hunk, "after_start") || !isRangeInteger(hunk, "after_end") ||
					!hunk.get("instances").isArray())
				{
					return Optional.empty();
				}
				List<String> instances = new ArrayList<>();
				for (JsonNode instance : hunk.get("instances"))
				{
					if (!instance.isString() || instance.asString().isBlank())
						return Optional.empty();
					instances.add(instance.asString());
				}
				evidence.add(new AuthorManifest.Evidence(Path.of(hunk.get("path").asString()), hunk.get("before_start").asInt(),
					hunk.get("before_end").asInt(), hunk.get("after_start").asInt(), hunk.get("after_end").asInt(),
					instances, text(hunk, "analogous"), text(hunk, "dissimilar")));
			}
			List<AuthorConflict> conflicts = new ArrayList<>();
			if (!output.get().get("conflicts").isArray())
				return Optional.empty();
			for (JsonNode conflict : output.get().get("conflicts"))
				conflicts.add(parseConflict(conflict));
			return Optional.of(new AuthorReport(neededEdits, new AuthorManifest(classifications, evidence), conflicts));
		}
		catch (IllegalArgumentException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Reads a conflict's complete shape and constrained values.
	 *
	 * @param node the conflict object
	 * @return the conflict
	 * @throws IllegalArgumentException if its shape or values are invalid
	 */
	private static AuthorConflict parseConflict(JsonNode node)
	{
		if (!properties(node).equals(Set.of("instances", "requirement_ids", "quotations", "resolutions",
			"recommended", "reason")) || !isRangeInteger(node, "recommended") ||
			!node.get("quotations").isArray())
		{
			throw new IllegalArgumentException("Invalid conflict properties");
		}
		List<AuthorConflict.Quotation> quotations = new ArrayList<>();
		for (JsonNode quotation : node.get("quotations"))
		{
			if (!properties(quotation).equals(Set.of("path", "text")) || !quotation.get("path").isString())
				throw new IllegalArgumentException("Invalid quotation properties");
			quotations.add(new AuthorConflict.Quotation(Path.of(quotation.get("path").asString()),
				text(quotation, "text")));
		}
		return new AuthorConflict(texts(node, "instances"), texts(node, "requirement_ids"), quotations,
			texts(node, "resolutions"), node.get("recommended").asInt(), text(node, "reason"));
	}

	/**
	 * Reads an array of non-blank strings.
	 *
	 * @param node the containing object
	 * @param name the array property
	 * @return its text values, in order
	 * @throws IllegalArgumentException if the array or an element is invalid
	 */
	private static List<String> texts(JsonNode node, String name)
	{
		JsonNode array = node.get(name);
		if (array == null || !array.isArray())
			throw new IllegalArgumentException("Missing text array: " + name);
		List<String> result = new ArrayList<>();
		for (JsonNode value : array)
		{
			if (!value.isString() || value.asString().isBlank())
				throw new IllegalArgumentException("Invalid text in array: " + name);
			result.add(value.asString());
		}
		return result;
	}

	/**
	 * Checks an annotation coordinate's integer representation.
	 *
	 * @param node the annotation
	 * @param name the coordinate property
	 * @return whether it is an integer fitting in a Java line index
	 */
	private static boolean isRangeInteger(JsonNode node, String name)
	{
		JsonNode value = node.get(name);
		return value.isIntegralNumber() && value.canConvertToInt();
	}

	/**
	 * Returns the names of an object's properties.
	 *
	 * @param node the report value
	 * @return the names, empty for a non-object
	 */
	private static Set<String> properties(JsonNode node)
	{
		Set<String> result = new HashSet<>();
		if (node.isObject())
		{
			for (Map.Entry<String, JsonNode> property : node.properties())
				result.add(property.getKey());
		}
		return result;
	}

	/**
	 * Reads required non-blank report text.
	 *
	 * @param node the object
	 * @param name the property
	 * @return the text
	 * @throws IllegalArgumentException if the property is absent, non-textual or blank
	 */
	private static String text(JsonNode node, String name)
	{
		JsonNode value = node.get(name);
		if (value == null || !value.isString() || value.asString().isBlank())
			throw new IllegalArgumentException("Missing non-blank text: " + name);
		return value.asString();
	}

	/**
	 * Reads one element of the {@code needed_edits} array.
	 *
	 * @param node the element
	 * @return the needed edit, or empty if its fields or paths are invalid
	 */
	private static Optional<NeededEdit> parseNeededEdit(JsonNode node)
	{
		if (!node.isObject())
			return Optional.empty();
		Set<String> names = new HashSet<>();
		for (Map.Entry<String, JsonNode> property : node.properties())
		{
			if (!property.getValue().isString())
				return Optional.empty();
			names.add(property.getKey());
		}
		names.remove(TARGET);
		if (!names.equals(REQUIRED_EDIT_PROPERTIES))
			return Optional.empty();
		Optional<Operation> operation = findOperation(node.get("operation").asString());
		if (operation.isEmpty())
			return Optional.empty();
		try
		{
			Path target = null;
			if (node.has(TARGET))
				target = Path.of(node.get(TARGET).asString());
			return Optional.of(new NeededEdit(node.get("requirement").asString(),
				Path.of(node.get("path").asString()), operation.get(), target));
		}
		// Invalid path syntax and edits that violate NeededEdit's constraints both make the report malformed.
		catch (IllegalArgumentException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Returns the operation that the report names.
	 *
	 * @param name the operation's name in lower case
	 * @return the operation, or empty if no operation has the name
	 */
	private static Optional<Operation> findOperation(String name)
	{
		for (Operation operation : Operation.values())
		{
			if (operation.name().toLowerCase(Locale.ROOT).equals(name))
				return Optional.of(operation);
		}
		return Optional.empty();
	}
}
