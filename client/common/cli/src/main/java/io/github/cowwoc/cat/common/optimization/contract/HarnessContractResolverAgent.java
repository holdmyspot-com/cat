package io.github.cowwoc.cat.common.optimization.contract;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationOperation;
import io.github.cowwoc.cat.common.runner.HarnessRequestBinding;
import io.github.cowwoc.cat.common.RecordStore;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ProfileRole;
import io.github.cowwoc.cat.common.runner.SupportedModelProfiles;
import io.github.cowwoc.cat.common.runner.HarnessTranscript;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the bounded semantic-disagreement resolver in an independent harness session for each resolution pass.
 */
public final class HarnessContractResolverAgent implements ContractExtractionWorkflow.Resolver
{
	private final JsonMapper json;
	private final HarnessModelProfile profile;
	private final Path operationRoot;
	private final RequestFactory requestFactory;
	private final HarnessEvaluationRunner runner;

	/**
	 * Creates one bounded resolver actor.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param profile the resolver's exact model and reasoning profile
	 * @param operationRoot the durable resolver-operation root
	 * @param requestFactory the harness-specific request factory
	 * @param runner the harness-neutral execution boundary
	 */
	public HarnessContractResolverAgent(JsonMapper json, HarnessModelProfile profile, Path operationRoot,
		RequestFactory requestFactory, HarnessEvaluationRunner runner)
	{
		this.json = Objects.requireNonNull(json, "json must not be null");
		this.profile = SupportedModelProfiles.requireSupported(ProfileRole.OPTIMIZER_EXECUTOR,
			Objects.requireNonNull(profile, "profile must not be null"));
		this.operationRoot = Objects.requireNonNull(operationRoot, "operationRoot must not be null").
			toAbsolutePath().normalize();
		this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
		this.runner = Objects.requireNonNull(runner, "runner must not be null");
	}

	/**
	 * Resolves only decisions supported by the read-only merged draft and named disagreements.
	 *
	 * @param disagreements unresolved semantic disagreements
	 * @param mergedDraft the retained non-conflicting draft
	 * @param resolutionPass the one-based bounded resolution pass
	 * @return strict typed decisions; omitted decisions remain unresolved
	 * @throws IOException if preparation, execution, or strict response parsing fails
	 */
	@Override
	public List<ContractExtractionReducer.ResolverDecision> resolve(
		List<ContractExtractionReducer.Disagreement> disagreements, CertificationContract.Draft mergedDraft,
		int resolutionPass) throws IOException
	{
		Objects.requireNonNull(disagreements, "disagreements must not be null");
		Objects.requireNonNull(mergedDraft, "mergedDraft must not be null");
		if (resolutionPass < 1 || resolutionPass > 2)
			throw new IllegalArgumentException("resolutionPass must be between one and two");
		if (disagreements.isEmpty())
			return List.of();
		Path passRoot = operationRoot.resolve("pass-" + resolutionPass);
		Path inputRoot = passRoot.resolve("input");
		CertificationContractDocument.writeDraft(json, inputRoot.resolve("draft.json"), mergedDraft);
		ObjectNode disagreementDocument = json.createObjectNode();
		disagreementDocument.put("schema_version", "contract-disagreements-v1");
		disagreementDocument.put("resolution_pass", resolutionPass);
		disagreementDocument.set("disagreements", json.valueToTree(disagreements));
		RecordStore.write(json, inputRoot.resolve("disagreements.json"), disagreementDocument);
		String prompt = trustedPrompt(inputRoot);
		HarnessEvaluationRequest request = requestFactory.create(profile, prompt, inputRoot, passRoot);
		if (!request.profile().equals(profile))
			throw new IOException("Contract resolver request changed the frozen model profile");
		HarnessRequestBinding.requireExactPrompt(json, request, prompt, "Contract resolver");
		if (!request.readOnlyEvidence().contains(inputRoot))
			throw new IOException("Contract resolver request does not expose its input as read-only evidence");
		HarnessEvaluationExecution execution = HarnessEvaluationOperation.execute(json, request, runner);
		String response = HarnessTranscript.finalAssistantResponse(json, execution.result().rawResult());
		Path responsePath = passRoot.resolve("decisions.json");
		RecordStore.writeText(responsePath, response + System.lineSeparator());
		return readResponse(response, responsePath).decisions();
	}

	/**
	 * Creates trusted resolver instructions without copying a disagreement or customer source into the prompt.
	 *
	 * @param inputRoot the read-only resolver input root
	 * @return the trusted prompt
	 */
	private static String trustedPrompt(Path inputRoot)
	{
		return "You are CAT's semantic contract-disagreement resolver. Read draft.json and disagreements.json under " +
			inputRoot.toAbsolutePath().normalize() + ". For each disagreement, choose CHOOSE_WITH_TRACE only when one " +
			"competing requirement preserves every cited source span, choose MERGE_PRESERVING_SOURCE only when one atomic " +
			"merged requirement preserves both propositions and every source span, or choose UNRESOLVED. Never invent or " +
			"delete a source trace. Return exactly one JSON object with a decisions array whose items use the fields " +
			"disagreementIdentifier, action, retainedRequirement, and rationale. Return no Markdown or commentary.";
	}

	/**
	 * Parses a strict resolver response.
	 *
	 * @param response the exact assistant response
	 * @param source the retained response path used in diagnostics
	 * @return the typed response
	 * @throws IOException if the response contains unsupported syntax, fields, or values
	 */
	private ResolverResponse readResponse(String response, Path source) throws IOException
	{
		try
		{
			JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
				DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			return strict.readValue(response, ResolverResponse.class);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid contract resolver response at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Creates a harness request while preserving harness-specific launcher and artifact ownership outside this actor.
	 */
	@FunctionalInterface
	public interface RequestFactory
	{
		/**
		 * Creates one independent resolver request.
		 *
		 * @param profile the resolver's exact model profile
		 * @param trustedPrompt trusted instructions containing no customer content
		 * @param readOnlyInput the immutable resolver input
		 * @param runRoot the durable pass root
		 * @return the typed harness request
		 * @throws IOException if request preparation fails
		 */
		HarnessEvaluationRequest create(HarnessModelProfile profile, String trustedPrompt, Path readOnlyInput,
			Path runRoot) throws IOException;
	}

	/**
	 * Holds the complete resolver response.
	 *
	 * @param decisions typed resolver decisions
	 */
	private record ResolverResponse(List<ContractExtractionReducer.ResolverDecision> decisions)
	{
		/**
		 * Validates and snapshots one response.
		 *
		 * @param decisions typed resolver decisions
		 */
		private ResolverResponse
		{
			decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions must not be null"));
		}
	}
}
