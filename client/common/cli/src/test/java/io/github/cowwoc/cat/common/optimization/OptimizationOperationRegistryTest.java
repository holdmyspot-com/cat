package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests the executable optimization workflow registry.
 */
public final class OptimizationOperationRegistryTest
{
	/**
	 * Creates the test fixture.
	 */
	public OptimizationOperationRegistryTest()
	{
	}

	/**
	 * Verifies every closed operation has one complete definition and only registered successors.
	 */
	@Test
	public void coversEveryOperationAndSuccessor()
	{
		OptimizationOperationRegistry.validate();

		requireThat(OptimizationOperationRegistry.definitions().size(), "definitions.size()").
			isEqualTo(OperationKind.values().length);
		for (OptimizationOperationRegistry.Definition definition :
			OptimizationOperationRegistry.definitions().values())
		{
			requireThat(definition.transitions(), "transitions").isNotEmpty();
			for (Set<OperationKind> successors : definition.transitions().values())
			{
				for (OperationKind successor : successors)
				{
					requireThat(OptimizationOperationRegistry.definitions().containsKey(successor),
						"definitions.containsKey(successor)").isTrue();
				}
			}
		}
	}

	/**
	 * Verifies only the matching finish operations own preparation and certification process terminal results.
	 */
	@Test
	public void confinesTerminalResultsToFinish()
	{
		for (OptimizationOperationRegistry.Definition definition :
			OptimizationOperationRegistry.definitions().values())
		{
			boolean ownsTerminalResult = definition.transitions().values().stream().anyMatch(Set::isEmpty);
			if (definition.operationId() == OperationKind.FINISH_CERTIFICATION_PROCESS ||
				definition.operationId() == OperationKind.FINISH_PREPARATION)
			{
				requireThat(ownsTerminalResult, "finishOwnsTerminalResult").isTrue();
			}
			else
				requireThat(ownsTerminalResult, "nonFinishOwnsTerminalResult").isFalse();
		}
	}

	/**
	 * Verifies certification-process lifecycle operations have the dedicated process-service owner.
	 */
	@Test
	public void usesProcessServiceOwner()
	{
		for (OperationKind operation : Set.of(OperationKind.START_CERTIFICATION_PROCESS,
			OperationKind.RECOVER_CERTIFICATION_PROCESS, OperationKind.FINISH_PREPARATION,
			OperationKind.FINISH_CERTIFICATION_PROCESS))
			requireThat(OptimizationOperationRegistry.definitions().get(operation).owner(), "owner").
				isEqualTo(OptimizationOperationRegistry.Owner.CERTIFICATION_PROCESS_SERVICE);
	}

	/**
	 * Verifies a caller cannot invent a result for an otherwise valid operation.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Operation extract-contract does not support result certified.*")
	public void rejectsAnUnregisteredResult()
	{
		OptimizationOperationRegistry.successors(OperationKind.EXTRACT_CONTRACT,
			OperationResultKind.CERTIFIED);
	}

	/**
	 * Verifies invalid readiness evidence is reported before the workflow terminates automatically.
	 */
	@Test
	public void reportsInvalidReadinessEvidence()
	{
		requireThat(OptimizationOperationRegistry.successors(
			OperationKind.DECIDE_READINESS,
			OperationResultKind.INVALID_EVIDENCE), "successors").
			containsExactly(Set.of(OperationKind.REPORT_OPTIMIZATION_ROUND));
	}

	/**
	 * Verifies local cost uncertainty never introduces spending authorization or blocks automatic execution.
	 */
	@Test
	public void continuesWhenCostIsUnavailable()
	{
		requireThat(OptimizationOperationRegistry.successors(
			OperationKind.FORECAST_COST,
			OperationResultKind.COST_UNAVAILABLE), "successors").
			containsExactly(Set.of(OperationKind.START_CERTIFICATION_PROCESS,
				OperationKind.GENERATE_CANDIDATES));
	}

	/**
	 * Verifies the active workflow exposes no user-controlled pause, resume, stop, or spending-authorization operation.
	 */
	@Test
	public void exposesNoUserControlledExecutionGate()
	{
		for (OperationKind operation : OperationKind.values())
		{
			requireThat(operation.externalValue(), "operation.externalValue()").doesNotContain("pause").
				doesNotContain("resume").doesNotContain("stop").doesNotContain("spending").
				doesNotContain("authorize-bounded");
		}
	}
}
