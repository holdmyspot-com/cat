package io.github.cowwoc.cat.common.optimization.contract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs the bounded automatic workflow that extracts, challenges, completes, and reviews a certification contract.
 */
public final class ContractExtractionWorkflow
{
	private static final int MAXIMUM_RESOLUTION_PASSES = 2;

	/**
	 * Prevents construction of this static workflow.
	 */
	private ContractExtractionWorkflow()
	{
	}

	/**
	 * Runs every contract-extraction stage, retaining all monotonic additions and blocking unresolved disagreements.
	 *
	 * @param inventory the immutable artifact and source inventory presented independently to both extractors
	 * @param primary the primary requirement extractor
	 * @param challenger the independent requirement challenger
	 * @param familyDesigner the leaf-family designer
	 * @param interactionDesigner the interaction-family designer
	 * @param evaluatorDesigner the evaluator-protocol designer
	 * @param reviewer the final monotonic reviewer
	 * @param resolver the bounded semantic-disagreement resolver
	 * @return the frozen contract or the retained blocking evidence
	 */
	public static Result extract(CertificationContract.Draft inventory, ProposalAgent primary,
		ProposalAgent challenger, StagedAgent familyDesigner, StagedAgent interactionDesigner,
		StagedAgent evaluatorDesigner, StagedAgent reviewer, Resolver resolver) throws IOException
	{
		Objects.requireNonNull(inventory, "inventory must not be null");
		Objects.requireNonNull(primary, "primary must not be null");
		Objects.requireNonNull(challenger, "challenger must not be null");
		Objects.requireNonNull(familyDesigner, "familyDesigner must not be null");
		Objects.requireNonNull(interactionDesigner, "interactionDesigner must not be null");
		Objects.requireNonNull(evaluatorDesigner, "evaluatorDesigner must not be null");
		Objects.requireNonNull(reviewer, "reviewer must not be null");
		Objects.requireNonNull(resolver, "resolver must not be null");

		CertificationContract.Draft primaryProposal = requireProposal(primary.propose(inventory),
			Stage.REQUIREMENT_EXTRACTION_PRIMARY);
		CertificationContract.Draft challengerProposal = requireProposal(challenger.propose(inventory),
			Stage.REQUIREMENT_EXTRACTION_CHALLENGER);
		List<Stage> completedStages = new ArrayList<>(List.of(Stage.REQUIREMENT_EXTRACTION_PRIMARY,
			Stage.REQUIREMENT_EXTRACTION_CHALLENGER));

		ContractDraftReducer.Result reduction = merge(primaryProposal, challengerProposal, resolver);
		if (reduction.status() != ContractDraftReducer.Status.READY)
			return blocked(Stage.REQUIREMENT_MERGE, reduction, completedStages);
		completedStages.add(Stage.REQUIREMENT_MERGE);

		CertificationContract.Draft current = reduction.mergedDraft();
		List<StageOperation> laterStages = List.of(
			new StageOperation(Stage.FAMILY_DESIGN, familyDesigner),
			new StageOperation(Stage.INTERACTION_DESIGN, interactionDesigner),
			new StageOperation(Stage.EVALUATOR_DESIGN, evaluatorDesigner),
			new StageOperation(Stage.MONOTONIC_REVIEW, reviewer));
		for (StageOperation operation : laterStages)
		{
			CertificationContract.Draft proposal = requireProposal(
				operation.agent().propose(operation.stage(), current), operation.stage());
			reduction = merge(current, proposal, resolver);
			if (reduction.status() != ContractDraftReducer.Status.READY)
				return blocked(operation.stage(), reduction, completedStages);
			current = reduction.mergedDraft();
			completedStages.add(operation.stage());
		}

		return new Result(Status.READY, current, reduction.contract(), Optional.empty(), List.of(), List.of(),
			List.of(), completedStages);
	}

	/**
	 * Applies at most two semantic-resolution passes to one pair of complete proposals.
	 *
	 * @param primary the retained proposal
	 * @param challenger the new independent proposal
	 * @param resolver the semantic resolver
	 * @return the terminal reduction result
	 */
	private static ContractDraftReducer.Result merge(CertificationContract.Draft primary,
		CertificationContract.Draft challenger, Resolver resolver) throws IOException
	{
		ContractDraftReducer.Result reduction = ContractDraftReducer.merge(primary, challenger, List.of(), 1);
		for (int pass = 1; pass <= MAXIMUM_RESOLUTION_PASSES &&
			reduction.status() == ContractDraftReducer.Status.PENDING; ++pass)
		{
			List<ContractExtractionReducer.ResolverDecision> decisions = resolver.resolve(reduction.disagreements(),
				reduction.mergedDraft(), pass);
			if (decisions == null)
				throw new NullPointerException("resolver decisions must not be null");
			reduction = ContractDraftReducer.merge(primary, challenger, decisions, pass);
		}
		return reduction;
	}

	/**
	 * Rejects an agent that returned no complete proposal.
	 *
	 * @param proposal the returned proposal
	 * @param stage the producing stage
	 * @return the nonnull proposal
	 */
	private static CertificationContract.Draft requireProposal(CertificationContract.Draft proposal, Stage stage)
	{
		if (proposal == null)
			throw new NullPointerException(stage + " returned a null contract proposal");
		return proposal;
	}

	/**
	 * Converts a non-ready reduction into one terminal blocked workflow result.
	 *
	 * @param stage the blocked stage
	 * @param reduction the retained reducer evidence
	 * @param completedStages the stages completed before the block
	 * @return the blocked workflow result
	 */
	private static Result blocked(Stage stage, ContractDraftReducer.Result reduction, List<Stage> completedStages)
	{
		return new Result(Status.BLOCKED, reduction.mergedDraft(), Optional.empty(), Optional.of(stage),
			reduction.conflicts(), reduction.disagreements(), reduction.contractIssues(), completedStages);
	}

	/**
	 * Proposes one complete contract draft from an immutable input inventory.
	 */
	@FunctionalInterface
	public interface ProposalAgent
	{
		/**
		 * Produces one complete proposal without changing the input.
		 *
		 * @param input the immutable input draft
		 * @return the proposed complete draft
		 */
		CertificationContract.Draft propose(CertificationContract.Draft input) throws IOException;
	}

	/**
	 * Proposes one complete contract draft for a named semantic stage.
	 */
	@FunctionalInterface
	public interface StagedAgent
	{
		/**
		 * Produces one complete proposal without deleting retained records.
		 *
		 * @param stage the requested stage
		 * @param input the current retained draft
		 * @return the proposed complete draft
		 */
		CertificationContract.Draft propose(Stage stage, CertificationContract.Draft input) throws IOException;
	}

	/**
	 * Resolves named semantic disagreements during one bounded pass.
	 */
	@FunctionalInterface
	public interface Resolver
	{
		/**
		 * Returns one decision for each disagreement it can resolve.
		 *
		 * @param disagreements the unresolved disagreements
		 * @param mergedDraft the retained non-conflicting draft
		 * @param resolutionPass the one-based resolution pass
		 * @return resolver decisions; omitted decisions remain unresolved
		 */
		List<ContractExtractionReducer.ResolverDecision> resolve(
			List<ContractExtractionReducer.Disagreement> disagreements, CertificationContract.Draft mergedDraft,
			int resolutionPass) throws IOException;
	}

	/**
	 * Names each observable workflow stage.
	 */
	public enum Stage
	{
		REQUIREMENT_EXTRACTION_PRIMARY,
		REQUIREMENT_EXTRACTION_CHALLENGER,
		REQUIREMENT_MERGE,
		FAMILY_DESIGN,
		INTERACTION_DESIGN,
		EVALUATOR_DESIGN,
		MONOTONIC_REVIEW
	}

	/**
	 * Names the terminal workflow state.
	 */
	public enum Status
	{
		READY,
		BLOCKED
	}

	/**
	 * Holds one terminal workflow result and all retained blocking evidence.
	 *
	 * @param status the terminal state
	 * @param draft the last retained monotonic draft
	 * @param contract the frozen contract when ready
	 * @param blockedStage the stage that blocked completion
	 * @param conflicts deterministic identity conflicts
	 * @param disagreements unresolved semantic disagreements
	 * @param contractIssues deterministic contract-validation issues
	 * @param completedStages stages completed before the terminal result
	 */
	public record Result(Status status, CertificationContract.Draft draft, Optional<CertificationContract> contract,
		Optional<Stage> blockedStage, List<ContractDraftReducer.Conflict> conflicts,
		List<ContractExtractionReducer.Disagreement> disagreements,
		List<CertificationContract.Issue> contractIssues, List<Stage> completedStages)
	{
		/**
		 * Validates and snapshots one workflow result.
		 *
		 * @param status the terminal state
		 * @param draft the last retained monotonic draft
		 * @param contract the frozen contract when ready
		 * @param blockedStage the stage that blocked completion
		 * @param conflicts deterministic identity conflicts
		 * @param disagreements unresolved semantic disagreements
		 * @param contractIssues deterministic contract-validation issues
		 * @param completedStages stages completed before the terminal result
		 */
		public Result
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(draft, "draft must not be null");
			Objects.requireNonNull(contract, "contract must not be null");
			Objects.requireNonNull(blockedStage, "blockedStage must not be null");
			conflicts = List.copyOf(conflicts);
			disagreements = List.copyOf(disagreements);
			contractIssues = List.copyOf(contractIssues);
			completedStages = List.copyOf(completedStages);
			if ((status == Status.READY) != contract.isPresent())
				throw new IllegalArgumentException("Only a ready workflow may contain a frozen contract");
			if ((status == Status.BLOCKED) != blockedStage.isPresent())
				throw new IllegalArgumentException("Only a blocked workflow must name a blocked stage");
		}
	}

	/**
	 * Associates a later semantic stage with its agent.
	 *
	 * @param stage the semantic stage
	 * @param agent the responsible agent
	 */
	private record StageOperation(Stage stage, StagedAgent agent)
	{
		/**
		 * Validates one stage operation.
		 *
		 * @param stage the semantic stage
		 * @param agent the responsible agent
		 */
		private StageOperation
		{
			Objects.requireNonNull(stage, "stage must not be null");
			Objects.requireNonNull(agent, "agent must not be null");
		}
	}
}
