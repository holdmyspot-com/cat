package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief.EndedAttempt;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorProtocol;
import io.github.cowwoc.cat.common.optimizer.execution.NeededEdit;
import io.github.cowwoc.cat.common.optimizer.inventory.Operation;
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
 * The author prompt: tells an author session what it may see and what it may edit, and reads its report of the edits
 * it needed outside its authority (design &sect;2.7, &sect;3.1, &sect;4.3).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
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
	private final String promptSources;
	private final String loaderConfiguration;

	/**
	 * Creates the author prompt for the confirmed edit authority.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema, which writes the contract for the author
	 * @param promptSources the in-project prompt files and directories, relative to the project directory, at or below
	 *   which the author may edit, create, rename and delete ordinary files
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
		this.promptSources = list(promptSources);
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

	@Override
	public SessionRequest request(AuthorBrief brief, Path copy)
	{
		requireThat(brief, "brief").isNotNull();
		requireThat(copy, "copy").isNotNull();
		ArrayNode failures = json.createArrayNode();
		for (Failure failure : brief.developmentFailures())
		{
			ObjectNode node = failure(failure);
			ArrayNode reruns = node.putArray("diagnostic_reruns");
			for (DiagnosticRerun rerun : failure.reruns())
			{
				ObjectNode rerunNode = reruns.addObject();
				rerunNode.put("verdict", rerun.verdict().name().toLowerCase(Locale.ROOT));
				rerunNode.put("judge_rationale", rerun.rationale());
			}
			failures.add(node);
		}
		ArrayNode ended = json.createArrayNode();
		for (EndedAttempt attempt : brief.endedAttempts())
		{
			ObjectNode node = ended.addObject();
			node.put("index", attempt.index());
			node.put("outcome", attempt.outcome().label());
			node.set("counts", counts(attempt.counts()));
			ArrayNode attemptFailures = node.putArray("failures");
			for (Failure failure : attempt.failures())
				attemptFailures.add(failure(failure));
		}
		String turn = prompt.fill(Map.of(
			"prompt_sources", promptSources,
			"loader_configuration", loaderConfiguration,
			"authorized_targets", list(brief.authorizedTargets()),
			"links", links(brief.links()),
			"category", brief.category(),
			"contract", schema.toJson(brief.contract()),
			"failures", pretty(failures),
			"development_counts", pretty(counts(brief.developmentCounts())),
			"ended_attempts", pretty(ended),
			"report_schema", reportSchema));
		return new SessionRequest(SessionRole.AUTHOR, copy, List.of(turn), List.of(), TIME_LIMIT);
	}

	/**
	 * Writes a failure.
	 *
	 * @param failure the failure
	 * @return the failing instance with the judge's rationale in {@code judge_rationale}
	 */
	private ObjectNode failure(Failure failure)
	{
		ObjectNode node = InstanceJson.of(json, failure.instance());
		node.put("judge_rationale", failure.rationale());
		return node;
	}

	/**
	 * Writes outcome counts per category.
	 *
	 * @param counts the counts of each category
	 * @return an object with one {@code passes}, {@code failures} pair per category, in category order
	 */
	private ObjectNode counts(Map<String, CategoryCounts> counts)
	{
		ObjectNode node = json.createObjectNode();
		for (Map.Entry<String, CategoryCounts> entry : new TreeMap<>(counts).entrySet())
		{
			ObjectNode category = node.putObject(entry.getKey());
			category.put("passes", entry.getValue().passes());
			category.put("failures", entry.getValue().failures());
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
	 * fence, that holds exactly a {@code needed_edits} array as the report schema requires. Each element holds exactly
	 * a non-blank {@code requirement}, a {@code path}, an {@code operation} that names an {@link Operation} in lower
	 * case and, optionally, a {@code target}; each path must be normalized and must not start with {@code ..}.
	 */
	@Override
	public Optional<List<NeededEdit>> neededEdits(SessionResult.Completed result)
	{
		requireThat(result, "result").isNotNull();
		Optional<ObjectNode> output = responses.parseFinalResponse(result);
		if (output.isEmpty() || output.get().size() != 1)
			return Optional.empty();
		JsonNode edits = output.get().get(NEEDED_EDITS);
		if (edits == null || !edits.isArray())
			return Optional.empty();
		List<NeededEdit> neededEdits = new ArrayList<>();
		for (JsonNode edit : edits)
		{
			Optional<NeededEdit> neededEdit = neededEdit(edit);
			if (neededEdit.isEmpty())
				return Optional.empty();
			neededEdits.add(neededEdit.get());
		}
		return Optional.of(neededEdits);
	}

	/**
	 * Reads one element of the {@code needed_edits} array.
	 *
	 * @param node the element
	 * @return the needed edit, or empty if the element does not satisfy the report schema
	 */
	private static Optional<NeededEdit> neededEdit(JsonNode node)
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
		Optional<Operation> operation = operation(node.get("operation").asString());
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
		catch (IllegalArgumentException _)
		{
			// Includes InvalidPathException
			return Optional.empty();
		}
	}

	/**
	 * Returns the operation that the report names.
	 *
	 * @param name the operation's name in lower case
	 * @return the operation, or empty if no operation has the name
	 */
	private static Optional<Operation> operation(String name)
	{
		for (Operation operation : Operation.values())
		{
			if (operation.name().toLowerCase(Locale.ROOT).equals(name))
				return Optional.of(operation);
		}
		return Optional.empty();
	}
}
