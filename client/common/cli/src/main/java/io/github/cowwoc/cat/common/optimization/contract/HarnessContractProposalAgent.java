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
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one fixed contract-extraction role in an independent harness session against a read-only draft.
 */
public final class HarnessContractProposalAgent implements ContractExtractionWorkflow.ProposalAgent,
	ContractExtractionWorkflow.StagedAgent
{
	private final JsonMapper json;
	private final ActorRole role;
	private final HarnessModelProfile profile;
	private final Path operationRoot;
	private final RequestFactory requestFactory;
	private final HarnessEvaluationRunner runner;

	/**
	 * Creates one isolated contract actor.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param role the actor's fixed semantic responsibility
	 * @param profile the exact model and reasoning profile
	 * @param operationRoot the durable actor-operation root
	 * @param requestFactory the harness-specific request factory
	 * @param runner the harness-neutral execution boundary
	 */
	public HarnessContractProposalAgent(JsonMapper json, ActorRole role, HarnessModelProfile profile, Path operationRoot,
		RequestFactory requestFactory, HarnessEvaluationRunner runner)
	{
		this.json = Objects.requireNonNull(json, "json must not be null");
		this.role = Objects.requireNonNull(role, "role must not be null");
		this.profile = SupportedModelProfiles.requireSupported(ProfileRole.OPTIMIZER_EXECUTOR,
			Objects.requireNonNull(profile, "profile must not be null"));
		this.operationRoot = Objects.requireNonNull(operationRoot, "operationRoot must not be null").
			toAbsolutePath().normalize();
		this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
		this.runner = Objects.requireNonNull(runner, "runner must not be null");
	}

	/**
	 * Produces one primary or challenger requirement proposal.
	 *
	 * @param input the immutable inventory draft
	 * @return the strict proposed draft
	 * @throws IOException if the role is incompatible or model execution cannot produce a strict draft
	 */
	@Override
	public CertificationContract.Draft propose(CertificationContract.Draft input) throws IOException
	{
		if (role.stage() != ContractExtractionWorkflow.Stage.REQUIREMENT_EXTRACTION_PRIMARY &&
			role.stage() != ContractExtractionWorkflow.Stage.REQUIREMENT_EXTRACTION_CHALLENGER)
		{
			throw new IOException("Contract actor " + role + " cannot run as an initial requirement extractor");
		}
		return execute(input);
	}

	/**
	 * Produces one later-stage contract proposal after verifying that the workflow selected this actor's fixed role.
	 *
	 * @param stage the requested workflow stage
	 * @param input the immutable retained draft
	 * @return the strict proposed draft
	 * @throws IOException if the stage differs from this actor's role or execution cannot produce a strict draft
	 */
	@Override
	public CertificationContract.Draft propose(ContractExtractionWorkflow.Stage stage,
		CertificationContract.Draft input) throws IOException
	{
		Objects.requireNonNull(stage, "stage must not be null");
		if (role.stage() != stage)
			throw new IOException("Contract actor " + role + " cannot execute stage " + stage);
		return execute(input);
	}

	/**
	 * Executes the fixed actor and parses its exact response as an intermediate draft.
	 *
	 * @param input the immutable actor input
	 * @return the strict actor proposal
	 * @throws IOException if preparation, execution, or response validation fails
	 */
	private CertificationContract.Draft execute(CertificationContract.Draft input) throws IOException
	{
		Objects.requireNonNull(input, "input must not be null");
		Path inputRoot = operationRoot.resolve("input");
		Path draftPath = inputRoot.resolve("draft.json");
		CertificationContractDocument.writeDraft(json, draftPath, input);
		String prompt = trustedPrompt(draftPath);
		HarnessEvaluationRequest request = requestFactory.create(profile, prompt, inputRoot, operationRoot);
		if (!request.profile().equals(profile))
			throw new IOException("Contract actor request changed the frozen model profile");
		HarnessRequestBinding.requireExactPrompt(json, request, prompt, "Contract actor");
		if (!request.readOnlyEvidence().contains(inputRoot))
			throw new IOException("Contract actor request does not expose its input as read-only evidence");
		HarnessEvaluationExecution execution = HarnessEvaluationOperation.execute(json, request, runner);
		String response = HarnessTranscript.finalAssistantResponse(json, execution.result().rawResult());
		Path proposal = operationRoot.resolve("proposal.json");
		RecordStore.writeText(proposal, response + System.lineSeparator());
		return CertificationContractDocument.readDraft(json, proposal);
	}

	/**
	 * Creates role-specific trusted instructions without copying customer content into the turn prompt.
	 *
	 * @param draftPath the read-only draft document
	 * @return the trusted prompt
	 */
	private String trustedPrompt(Path draftPath)
	{
		return "You are CAT's " + role.displayName() + ". Read the immutable certification-contract draft at " +
			draftPath.toAbsolutePath().normalize() + ". " + role.instructions() + " Preserve every input record unless " +
			"your assigned role requires a monotonic addition or a more precise split. Never delete a source-traced " +
			"obligation. Return exactly one complete certification-contract-v1 JSON object, with no Markdown fence, " +
			"commentary, or unknown field.";
	}

	/**
	 * Creates a harness request while preserving harness-specific launcher and artifact ownership outside this actor.
	 */
	@FunctionalInterface
	public interface RequestFactory
	{
		/**
		 * Creates one independent actor request.
		 *
		 * @param profile the actor's exact model profile
		 * @param trustedPrompt trusted instructions containing no draft content
		 * @param readOnlyInput the immutable draft root
		 * @param runRoot the durable actor-operation root
		 * @return the typed harness request
		 * @throws IOException if request preparation fails
		 */
		HarnessEvaluationRequest create(HarnessModelProfile profile, String trustedPrompt, Path readOnlyInput,
			Path runRoot) throws IOException;
	}

	/**
	 * Names every fixed proposal-producing actor in the automatic extraction graph.
	 */
	public enum ActorRole
	{
		PRIMARY_REQUIREMENT_EXTRACTOR(ContractExtractionWorkflow.Stage.REQUIREMENT_EXTRACTION_PRIMARY,
			"primary requirement extractor",
			"Extract every atomic requirement, applicability branch, dependency, and exact source trace."),
		INDEPENDENT_REQUIREMENT_CHALLENGER(ContractExtractionWorkflow.Stage.REQUIREMENT_EXTRACTION_CHALLENGER,
			"independent requirement challenger",
			"Independently search for obligations, branches, and dependencies that another extractor could miss."),
		FAMILY_DESIGNER(ContractExtractionWorkflow.Stage.FAMILY_DESIGN, "leaf-family and generator designer",
			"Define substantive leaf families, applicability rules, within-family distributions, and executable generators."),
		INTERACTION_DESIGNER(ContractExtractionWorkflow.Stage.INTERACTION_DESIGN, "interaction-family designer",
			"Add only interactions whose combined requirements create behavior not covered by individual leaf families."),
		EVALUATOR_DESIGNER(ContractExtractionWorkflow.Stage.EVALUATOR_DESIGN, "evaluator-protocol designer",
			"Create atomic assertions, evidence requirements, deterministic classifications, and protocol assignments."),
		MONOTONIC_REVIEWER(ContractExtractionWorkflow.Stage.MONOTONIC_REVIEW, "monotonic contract reviewer",
			"Compare the complete draft with every source span; add omissions and split overbroad families without " +
				"deletion.");

		private final ContractExtractionWorkflow.Stage stage;
		private final String displayName;
		private final String instructions;

		/**
		 * Creates one fixed actor role.
		 *
		 * @param stage the workflow stage
		 * @param displayName the prompt-facing role name
		 * @param instructions the role's bounded semantic assignment
		 */
		ActorRole(ContractExtractionWorkflow.Stage stage, String displayName, String instructions)
		{
			this.stage = stage;
			this.displayName = displayName;
			this.instructions = instructions;
		}

		/**
		 * Returns the exact workflow stage owned by this role.
		 *
		 * @return the stage
		 */
		public ContractExtractionWorkflow.Stage stage()
		{
			return stage;
		}

		/**
		 * Returns the prompt-facing role name.
		 *
		 * @return the display name
		 */
		public String displayName()
		{
			return displayName;
		}

		/**
		 * Returns the role's bounded semantic assignment.
		 *
		 * @return the instructions
		 */
		public String instructions()
		{
			return instructions;
		}
	}
}
