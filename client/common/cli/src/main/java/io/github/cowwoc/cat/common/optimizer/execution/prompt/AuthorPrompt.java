package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief.EndedAttempt;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorProtocol;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.proposal.PromptTemplate;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The author prompt: tells an author session what it may see and what it may edit (design &sect;2.7, &sect;3.1,
 * &sect;4.3).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class AuthorPrompt implements AuthorProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	private static final String PROMPT_RESOURCE = "/optimizer/author/author.md";
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final PromptTemplate prompt;
	private final String promptSources;
	private final String loaderConfiguration;
	private final String authorizedTargets;

	/**
	 * Creates the author prompt for the confirmed edit authority.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema, which writes the contract for the author
	 * @param promptSources the in-project prompt files and directories, relative to the project directory, at or below
	 *   which the author may edit, create, rename and delete ordinary files
	 * @param loaderConfiguration the files and directories that decide which prompt files load, at or below which
	 *   nothing may change
	 * @param authorizedTargets the absolute paths of the external files whose content the user authorized CAT to edit
	 * @throws NullPointerException if any of the arguments are null or contain null
	 * @throws UncheckedIOException if the packaged author prompt cannot be read
	 */
	public AuthorPrompt(JsonMapper json, ProposalSchema schema, Set<Path> promptSources,
		Set<Path> loaderConfiguration, Set<Path> authorizedTargets)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		this.json = json;
		this.schema = schema;
		this.prompt = PromptTemplate.load(PROMPT_RESOURCE);
		this.promptSources = list(promptSources);
		this.loaderConfiguration = list(loaderConfiguration);
		this.authorizedTargets = list(authorizedTargets);
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
			"authorized_targets", authorizedTargets,
			"category", brief.category(),
			"contract", schema.toJson(brief.contract()),
			"failures", pretty(failures),
			"development_counts", pretty(counts(brief.developmentCounts())),
			"ended_attempts", pretty(ended)));
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
}
