package io.github.cowwoc.cat.common.optimization;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Applies the operation registry as the authoritative executable workflow graph.
 */
public final class OptimizationStateMachine
{
	/**
	 * Prevents construction of this static state-transition owner.
	 */
	private OptimizationStateMachine()
	{
	}

	/**
	 * Starts a new preparation workflow at prompt-system discovery.
	 *
	 * @return the initial preparation state
	 */
	public static State beginPreparation()
	{
		return activeAt(OperationKind.DISCOVER_PROMPT_SYSTEM);
	}

	/**
	 * Starts an already prepared certification process at candidate generation.
	 *
	 * @return the initial active certification process state
	 */
	public static State beginCertificationProcess()
	{
		return activeAt(OperationKind.GENERATE_CANDIDATES);
	}

	/**
	 * Creates the initial state recorded by a certification-process manifest.
	 *
	 * @param initialOperation the registered first operation
	 * @return the initial active state
	 */
	static State initial(OperationKind initialOperation)
	{
		return activeAt(initialOperation);
	}

	/**
	 * Creates a verified active state at a registered operation.
	 *
	 * @param operation the operation to execute
	 * @return the active state
	 */
	public static State activeAt(OperationKind operation)
	{
		Objects.requireNonNull(operation, "operation must not be null");
		if (!OptimizationOperationRegistry.definitions().containsKey(operation))
			throw new IllegalArgumentException("Unregistered optimization operation: " + operation);
		return new State(Optional.of(operation), Optional.empty(), Optional.empty(), 0);
	}

	/**
	 * Applies one terminal operation result and its selected registered successor.
	 *
	 * @param state the current state
	 * @param result the completed operation's result
	 * @param successor the selected successor, or empty for a certification process terminal result
	 * @return the resulting active or terminal state
	 */
	public static State advance(State state, OperationResultKind result,
		Optional<OperationKind> successor)
	{
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(result, "result must not be null");
		Objects.requireNonNull(successor, "successor must not be null");
		if (state.terminal())
			throw new IllegalStateException("A terminal certification process cannot advance");
		OperationKind current = state.currentOperation().orElseThrow();
		Set<OperationKind> permitted =
			OptimizationOperationRegistry.successors(current, result);
		if (permitted.isEmpty())
		{
			if (successor.isPresent())
				throw new IllegalArgumentException("Terminal result " + result.externalValue() + " has no successor");
			Transition transition = new Transition(current, result, Optional.empty());
			return new State(Optional.empty(), Optional.of(result), Optional.of(transition), state.sequence() + 1);
		}
		OperationKind selected = successor.orElseThrow(() ->
			new IllegalArgumentException("Result " + result.externalValue() + " requires a successor"));
		if (!permitted.contains(selected))
		{
			throw new IllegalArgumentException(selected.externalValue() + " is not permitted after " +
				result.externalValue() + " from " + current.externalValue());
		}
		Transition transition = new Transition(current, result, Optional.of(selected));
		return new State(Optional.of(selected), Optional.empty(), Optional.of(transition), state.sequence() + 1);
	}

	/**
	 * Holds one active or terminal certification process state.
	 *
	 * @param currentOperation the active operation
	 * @param terminalResult the certification process terminal result
	 * @param lastTransition the last applied transition
	 * @param sequence the monotonic transition count
	 */
	public record State(Optional<OperationKind> currentOperation,
		Optional<OperationResultKind> terminalResult, Optional<Transition> lastTransition,
		long sequence)
	{
		/**
		 * Validates one certification process state.
		 *
		 * @param currentOperation the active operation
		 * @param terminalResult the certification process terminal result
		 * @param lastTransition the last applied transition
		 * @param sequence the monotonic transition count
		 */
		public State
		{
			Objects.requireNonNull(currentOperation, "currentOperation must not be null");
			Objects.requireNonNull(terminalResult, "terminalResult must not be null");
			Objects.requireNonNull(lastTransition, "lastTransition must not be null");
			if (currentOperation.isPresent() == terminalResult.isPresent())
				throw new IllegalArgumentException("State must be exactly one of active or terminal");
			if (sequence < 0)
				throw new IllegalArgumentException("sequence must not be negative");
			if (sequence == 0 && lastTransition.isPresent())
				throw new IllegalArgumentException("Initial state cannot contain a transition");
			if (sequence > 0 && lastTransition.isEmpty())
				throw new IllegalArgumentException("Advanced state must contain its transition");
		}

		/**
		 * Returns whether the certification process has reached a terminal result.
		 *
		 * @return {@code true} for a terminal certification process
		 */
		public boolean terminal()
		{
			return terminalResult.isPresent();
		}
	}

	/**
	 * Holds the exact edge that produced a state.
	 *
	 * @param operation the completed operation
	 * @param result the operation result
	 * @param successor the selected successor, or empty at certification process termination
	 */
	public record Transition(OperationKind operation,
		OperationResultKind result,
		Optional<OperationKind> successor)
	{
		/**
		 * Validates one transition.
		 *
		 * @param operation the completed operation
		 * @param result the operation result
		 * @param successor the selected successor, or empty at certification process termination
		 */
		public Transition
		{
			Objects.requireNonNull(operation, "operation must not be null");
			Objects.requireNonNull(result, "result must not be null");
			Objects.requireNonNull(successor, "successor must not be null");
		}
	}
}
