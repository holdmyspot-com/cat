package io.github.cowwoc.cat.common.optimization.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests monotonic requirement union, exact duplicate collapse, and bounded disagreement resolution.
 */
public final class ContractExtractionReducerTest
{
	/**
	 * Creates the test fixture.
	 */
	public ContractExtractionReducerTest()
	{
	}

	/**
	 * Verifies a challenger-found obligation is retained without deleting the primary extraction.
	 */
	@Test
	public void retainsChallengerAddition()
	{
		ContractExtractionReducer.Merge result = ContractExtractionReducer.merge(List.of(requirement("primary",
			"Explain the decision.", "span-a")), List.of(requirement("challenger", "State the affected scope.",
			"span-b")));

		requireThat(result.requirements().size(), "requirementCount").isEqualTo(2);
		requireThat(result.disagreements(), "disagreements").isEmpty();
	}

	/**
	 * Verifies normalized duplicate propositions with identical traces collapse deterministically.
	 */
	@Test
	public void collapsesExactDuplicate()
	{
		ContractExtractionReducer.Merge result = ContractExtractionReducer.merge(List.of(requirement("primary",
			"Explain   the decision.", "span-a")), List.of(requirement("challenger", " explain the decision. ",
			"span-a")));

		requireThat(result.requirements().size(), "requirementCount").isEqualTo(1);
		requireThat(result.requirements().getFirst().identifier(), "retainedIdentifier").isEqualTo("challenger");
	}

	/**
	 * Verifies competing interpretations of the same source span become a named disagreement.
	 */
	@Test
	public void recordsSubstantiveDisagreement()
	{
		ContractExtractionReducer.Merge result = ContractExtractionReducer.merge(List.of(requirement("primary",
			"Always explain the decision.", "span-a")), List.of(requirement("challenger",
			"Explain only surprising decisions.", "span-a")));

		requireThat(result.requirements(), "requirements").isEmpty();
		requireThat(result.disagreements().size(), "disagreementCount").isEqualTo(1);
	}

	/**
	 * Verifies an unresolved disagreement blocks extraction after the second permitted resolver pass.
	 */
	@Test
	public void blocksAfterTwoResolverPasses()
	{
		ContractExtractionReducer.Merge merge = ContractExtractionReducer.merge(List.of(requirement("primary",
			"Always explain the decision.", "span-a")), List.of(requirement("challenger",
			"Explain only surprising decisions.", "span-a")));

		ContractExtractionReducer.Resolution first = ContractExtractionReducer.resolve(merge, List.of(), 1);
		ContractExtractionReducer.Resolution second = ContractExtractionReducer.resolve(merge, List.of(), 2);

		requireThat(first.status(), "firstStatus").isEqualTo(ContractExtractionReducer.ResolutionStatus.PENDING);
		requireThat(second.status(), "secondStatus").isEqualTo(ContractExtractionReducer.ResolutionStatus.BLOCKED);
	}

	/**
	 * Creates one requirement proposal.
	 *
	 * @param identifier the proposal identifier
	 * @param proposition the atomic proposition
	 * @param span the source span identifier
	 * @return the requirement
	 */
	private static CertificationContract.Requirement requirement(String identifier, String proposition, String span)
	{
		return new CertificationContract.Requirement(identifier, proposition, List.of(span), List.of("always"), List.of());
	}
}
