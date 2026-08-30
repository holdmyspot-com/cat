package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reserves ordered whole-suite trial ranges under the evaluation-record lock.
 */
public final class TrialClaims
{
	/**
	 * Performs one operation while the evaluation record is locked.
	 */
	@FunctionalInterface
	public interface LockedOperation
	{
		/**
		 * Updates the locked run record.
		 *
		 * @param run the locked evaluation record
		 * @throws IOException if the record cannot be updated
		 */
		void execute(ObjectNode run) throws IOException;
	}

	/**
	 * Supplies coordinator-owned record operations used during reservation.
	 */
	public interface Access
	{
		/**
		 * Runs an operation under the record lock.
		 *
		 * @param runPath the evaluation record
		 * @param operation the locked operation
		 * @throws IOException if locking or the operation fails
		 */
		void withLock(Path runPath, LockedOperation operation) throws IOException;

		/**
		 * Reads the current evaluation record.
		 *
		 * @param runPath the evaluation record
		 * @return the parsed record
		 * @throws IOException if the record cannot be read
		 */
		ObjectNode read(Path runPath) throws IOException;

		/**
		 * Validates immutable evaluation inputs before an ordinary claim.
		 *
		 * @param run the evaluation record
		 * @param runPath the evaluation record path
		 * @throws IOException if an immutable input changed
		 */
		void validateInputs(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Returns the immutable suite path declared by the run.
		 *
		 * @param run the evaluation record
		 * @param runPath the evaluation record path
		 * @return the suite path
		 * @throws IOException if the declaration is invalid
		 */
		Path suitePath(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Returns the maximum reserveable trial number.
		 *
		 * @param run the evaluation record
		 * @return the trial limit
		 * @throws IOException if the limit declaration is invalid
		 */
		int trialLimit(ObjectNode run) throws IOException;

		/**
		 * Determines whether a further trial may change the terminal result.
		 *
		 * @param run the evaluation record
		 * @param suitePath the immutable suite path
		 * @param runPath the evaluation record path
		 * @return whether the evaluation is terminal
		 * @throws IOException if terminal state cannot be determined
		 */
		boolean isTerminal(ObjectNode run, Path suitePath, Path runPath) throws IOException;

		/**
		 * Persists the locked run record.
		 *
		 * @param runPath the evaluation record path
		 * @param run the updated record
		 * @throws IOException if persistence fails
		 */
		void write(Path runPath, ObjectNode run) throws IOException;
	}

	private static volatile Access access;

	/**
	 * Prevents construction of this static reservation owner.
	 */
	private TrialClaims()
	{
	}

	/**
	 * Registers coordinator-owned record operations.
	 *
	 * @param access the record operations
	 */
	public static void configure(Access access)
	{
		TrialClaims.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Reserves a contiguous range of unclaimed whole-suite trials.
	 *
	 * @param runPath the evaluation record
	 * @param count the number of trials to reserve
	 * @param verifyInputs whether immutable inputs must be revalidated
	 * @return the reserved trials in execution order
	 * @throws IOException if the record is terminal, invalid, or exhausted
	 */
	public static List<Integer> reserve(Path runPath, int count, boolean verifyInputs) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		if (count < 1)
			throw new IOException("Trial claim count must be positive");
		List<Integer> result = new ArrayList<>();
		access().withLock(normalizedRunPath, run ->
		{
			ObjectNode current = access().read(normalizedRunPath);
			Path suitePath = access().suitePath(current, normalizedRunPath);
			if (verifyInputs)
				access().validateInputs(current, normalizedRunPath);
			int nextTrial = current.path("next_trial").asInt(-1);
			int trialLimit = access().trialLimit(current);
			if (nextTrial < 1 || nextTrial > trialLimit || count > trialLimit - nextTrial + 1)
				throw new IOException("Evaluation cannot reserve more than " + trialLimit + " suite trials");
			if (access().isTerminal(current, suitePath, normalizedRunPath))
				throw new IOException("Evaluation already reached a terminal decision");
			for (int trial = nextTrial; trial < nextTrial + count; ++trial)
				result.add(trial);
			current.put("next_trial", nextTrial + count);
			access().write(normalizedRunPath, current);
		});
		return List.copyOf(result);
	}

	/**
	 * Reserves the exact next whole-suite trial for a transaction that must run at a declared trial number.
	 *
	 * @param runPath the evaluation record
	 * @param trial the required next trial number
	 * @throws IOException if immutable inputs changed or {@code trial} is not the next unclaimed trial
	 */
	public static void reserveExact(Path runPath, int trial) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		access().withLock(normalizedRunPath, run ->
		{
			ObjectNode current = access().read(normalizedRunPath);
			access().validateInputs(current, normalizedRunPath);
			if (current.path("next_trial").asInt(-1) != trial)
				throw new IOException("Execution trial must be the next unclaimed trial: " + trial);
			current.put("next_trial", trial + 1);
			access().write(normalizedRunPath, current);
		});
	}

	/**
	 * Returns the configured coordinator record operations.
	 *
	 * @return the coordinator operations
	 */
	private static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure trial-claim access");
		return result;
	}
}
