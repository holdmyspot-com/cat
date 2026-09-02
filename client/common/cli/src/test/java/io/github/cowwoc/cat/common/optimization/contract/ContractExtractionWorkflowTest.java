package io.github.cowwoc.cat.common.optimization.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * Tests the bounded automatic multi-agent contract-extraction graph.
 */
public final class ContractExtractionWorkflowTest
{
	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	/**
	 * Creates the test fixture.
	 */
	public ContractExtractionWorkflowTest()
	{
	}

	/**
	 * Verifies primary and challenger receive the same inventory, a challenger-only requirement is retained, and every
	 * later semantic stage runs before the contract freezes.
	 */
	@Test
	public void retainsChallengerDiscovery() throws IOException
	{
		CertificationContract.Draft complete = CertificationContractTest.validDraft(SHA, false);
		CertificationContract.Draft inventory = emptySemantics(complete);
		List<CertificationContract.Draft> extractorInputs = new ArrayList<>();
		List<ContractExtractionWorkflow.Stage> laterStages = new ArrayList<>();
		ContractExtractionWorkflow.ProposalAgent primary = input ->
		{
			extractorInputs.add(input);
			return emptySemantics(complete);
		};
		ContractExtractionWorkflow.ProposalAgent challenger = input ->
		{
			extractorInputs.add(input);
			return complete;
		};
		ContractExtractionWorkflow.StagedAgent laterAgent = (stage, input) ->
		{
			laterStages.add(stage);
			return input;
		};

		ContractExtractionWorkflow.Result result = ContractExtractionWorkflow.extract(inventory, primary, challenger,
			laterAgent, laterAgent, laterAgent, laterAgent, (disagreements, draft, pass) -> List.of());

		requireThat(result.status(), "status").isEqualTo(ContractExtractionWorkflow.Status.READY);
		requireThat(extractorInputs, "extractorInputs").containsExactly(List.of(inventory, inventory));
		requireThat(laterStages, "laterStages").containsExactly(List.of(
			ContractExtractionWorkflow.Stage.FAMILY_DESIGN,
			ContractExtractionWorkflow.Stage.INTERACTION_DESIGN,
			ContractExtractionWorkflow.Stage.EVALUATOR_DESIGN,
			ContractExtractionWorkflow.Stage.MONOTONIC_REVIEW));
		requireThat(result.contract().orElseThrow().content().requirements(), "requirements").
			containsExactly(complete.requirements());
	}

	/**
	 * Verifies an unresolved semantic disagreement blocks after exactly two resolver passes and prevents later agents
	 * from running.
	 */
	@Test
	public void blocksAfterTwoUnresolvedPasses() throws IOException
	{
		CertificationContract.Draft primary = CertificationContractTest.validDraft(SHA, false);
		CertificationContract.Requirement competing = new CertificationContract.Requirement("req-competing",
			"Do not explain surprising decisions.", primary.requirements().getFirst().sourceSpanIdentifiers(),
			primary.requirements().getFirst().applicabilityRuleIdentifiers(), List.of());
		CertificationContract.Draft challenger = new CertificationContract.Draft(primary.schemaVersion(),
			primary.artifacts(), primary.sourceSpans(), List.of(competing), primary.applicabilityRules(),
			primary.distributions(), primary.families(), primary.assertions(), primary.evaluatorProtocolIds(),
			primary.promptTests());
		AtomicInteger resolverCalls = new AtomicInteger();
		AtomicInteger laterAgentCalls = new AtomicInteger();

		ContractExtractionWorkflow.Result result = ContractExtractionWorkflow.extract(emptySemantics(primary),
			ignored -> primary, ignored -> challenger, (stage, input) ->
			{
				laterAgentCalls.incrementAndGet();
				return input;
			}, (stage, input) -> input, (stage, input) -> input, (stage, input) -> input,
			(disagreements, draft, pass) ->
			{
				resolverCalls.incrementAndGet();
				return disagreements.stream().map(disagreement -> new ContractExtractionReducer.ResolverDecision(
					disagreement.identifier(), ContractExtractionReducer.ResolverAction.UNRESOLVED, null,
					"The source permits both interpretations.")).toList();
			});

		requireThat(result.status(), "status").isEqualTo(ContractExtractionWorkflow.Status.BLOCKED);
		requireThat(result.blockedStage().orElseThrow(), "blockedStage").isEqualTo(
			ContractExtractionWorkflow.Stage.REQUIREMENT_MERGE);
		requireThat(result.disagreements().size(), "disagreementCount").isEqualTo(1);
		requireThat(result.disagreements().getFirst().resolutionPassCount(), "resolutionPassCount").isEqualTo(2);
		requireThat(resolverCalls.get(), "resolverCalls").isEqualTo(2);
		requireThat(laterAgentCalls.get(), "laterAgentCalls").isEqualTo(0);
	}

	/**
	 * Removes semantic outputs while retaining the immutable source inventory.
	 *
	 * @param complete the complete fixture
	 * @return the inventory-only draft
	 */
	private static CertificationContract.Draft emptySemantics(CertificationContract.Draft complete)
	{
		return new CertificationContract.Draft(complete.schemaVersion(), complete.artifacts(), complete.sourceSpans(),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), complete.promptTests());
	}
}
