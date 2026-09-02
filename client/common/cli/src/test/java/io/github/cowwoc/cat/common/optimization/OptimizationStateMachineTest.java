package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests the executable optimization state machine against every registered edge and terminal result.
 */
public final class OptimizationStateMachineTest
{
	/**
	 * Creates the test fixture.
	 */
	public OptimizationStateMachineTest()
	{
	}

	/**
	 * Verifies every registered nonterminal edge is executable and preserves its typed transition evidence.
	 */
	@Test
	public void executesEveryRegisteredEdge()
	{
		int visited = 0;
		for (OptimizationOperationRegistry.Definition definition :
			OptimizationOperationRegistry.definitions().values())
		{
			for (Map.Entry<OperationResultKind,
				Set<OperationKind>> transition : definition.transitions().entrySet())
			{
				for (OperationKind successor : transition.getValue())
				{
					OptimizationStateMachine.State state = OptimizationStateMachine.activeAt(definition.operationId());
					OptimizationStateMachine.State next = OptimizationStateMachine.advance(state, transition.getKey(),
						Optional.of(successor));

					requireThat(next.currentOperation().orElseThrow(), "currentOperation").isEqualTo(successor);
					requireThat(next.lastTransition().orElseThrow().result(), "result").isEqualTo(transition.getKey());
					++visited;
				}
			}
		}
		requireThat(visited, "visited").isGreaterThan(0);
	}

	/**
	 * Verifies all preparation and certification process finish results produce terminal states without a successor.
	 */
	@Test
	public void executesAllTerminalOutcomes()
	{
		for (OperationResultKind result : new OperationResultKind[]{
			OperationResultKind.CERTIFIED,
			OperationResultKind.NOT_CERTIFIED})
		{
			OptimizationStateMachine.State terminal = OptimizationStateMachine.advance(
				OptimizationStateMachine.activeAt(OperationKind.FINISH_CERTIFICATION_PROCESS), result,
				Optional.empty());

			requireThat(terminal.terminal(), "terminal").isTrue();
			requireThat(terminal.terminalResult().orElseThrow(), "terminalResult").isEqualTo(result);
		}
		OptimizationStateMachine.State notStarted = OptimizationStateMachine.advance(
			OptimizationStateMachine.activeAt(OperationKind.FINISH_PREPARATION),
			OperationResultKind.NOT_STARTED, Optional.empty());
		requireThat(notStarted.terminal(), "notStarted.terminal()").isTrue();
		requireThat(notStarted.terminalResult().orElseThrow(), "notStarted.terminalResult()").
			isEqualTo(OperationResultKind.NOT_STARTED);
	}

	/**
	 * Verifies preparation and an already frozen certification process begin at their distinct executable boundaries.
	 */
	@Test
	public void startsEachWorkflowAtItsOwnedBoundary()
	{
		requireThat(OptimizationStateMachine.beginPreparation().currentOperation().orElseThrow(),
			"preparationOperation").isEqualTo(OperationKind.DISCOVER_PROMPT_SYSTEM);
		requireThat(OptimizationStateMachine.beginCertificationProcess().currentOperation().orElseThrow(),
			"certificationProcessOperation").
			isEqualTo(OperationKind.GENERATE_CANDIDATES);
	}

	/**
	 * Verifies a caller cannot select an unregistered successor.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*is not permitted after ready from extract-contract.*")
	public void rejectsUnregisteredSuccessor()
	{
		OptimizationStateMachine.advance(
			OptimizationStateMachine.activeAt(OperationKind.EXTRACT_CONTRACT),
			OperationResultKind.READY,
			Optional.of(OperationKind.FINISH_CERTIFICATION_PROCESS));
	}
}
