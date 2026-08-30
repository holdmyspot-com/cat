package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;

import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns retained elapsed-time measurements for benchmark records.
 */
public final class Timing
{
	/**
	 * Supplies coordinator-owned retained-record interpretation.
	 */
	@FunctionalInterface
	public interface Access
	{
		/**
		 * Returns a required non-negative retained numeric field.
		 *
		 * @param value the retained record that contains the field
		 * @param field the required field name
		 * @param source the record location used in diagnostics
		 * @return the retained numeric value
		 * @throws IOException if the field is missing or invalid
		 */
		long longValue(ObjectNode value, String field, Path source) throws IOException;
	}

	private static volatile Access access;

	/**
	 * Prevents construction of benchmark timing operations.
	 */
	private Timing()
	{
	}

	/**
	 * Starts elapsed-time measurement for a prepared benchmark record.
	 *
	 * @param json the mapper that reads and persists the benchmark record
	 * @param runPath the prepared benchmark record
	 * @throws IOException if the record cannot be initialized
	 */
	public static void start(JsonMapper json, Path runPath) throws IOException
	{
		RecordStore.withLock(runPath, () ->
		{
			ObjectNode run = PromptEvaluation.object(RecordStore.read(json, runPath),
				"benchmark record");
			if (!run.path("mode").asString().equals("benchmark"))
				throw new IOException("Only a benchmark record has benchmark timing: " + runPath);
			if (!run.has("benchmark_started_at_epoch_millis"))
			{
				run.put("benchmark_started_at_epoch_millis", System.currentTimeMillis());
				RecordStore.write(json, runPath, run);
			}
		});
	}

	/**
	 * Completes elapsed-time measurement after the evaluator determines a terminal decision while the record remains
	 * locked.
	 *
	 * @param json the mapper that reads and persists the benchmark record
	 * @param runPath the terminal benchmark record
	 * @param decision the evaluator operation that determines terminal status
	 * @throws IOException if the benchmark was not initialized or is not terminal
	 */
	public static void complete(JsonMapper json, Path runPath, Decision decision) throws IOException
	{
		RecordStore.withLock(runPath, () ->
		{
			ObjectNode run = PromptEvaluation.object(RecordStore.read(json, runPath),
				"benchmark record");
			if (!run.path("mode").asString().equals("benchmark"))
				throw new IOException("Only a benchmark record has benchmark timing: " + runPath);
			if (decision.get().path("overall_decision").asString().equals("RUNNING"))
				throw new IOException("Cannot complete timing for a running benchmark: " + runPath);
			if (run.has("benchmark_elapsed_millis"))
				return;
			long started = access().longValue(run, "benchmark_started_at_epoch_millis", runPath);
			long completed = System.currentTimeMillis();
			run.put("benchmark_completed_at_epoch_millis", completed);
			run.put("benchmark_elapsed_millis", Math.max(0, completed - started));
			RecordStore.write(json, runPath, run);
		});
	}

	/**
	 * Determines a benchmark record's terminal evaluator decision while its timing transition holds the record lock.
	 */
	@FunctionalInterface
	public interface Decision
	{
		/**
		 * Determines the terminal decision.
		 *
		 * @return the evaluator decision
		 * @throws IOException if retained evidence cannot be evaluated
		 */
		ObjectNode get() throws IOException;
	}

	/**
	 * Registers retained-record interpretation used by benchmark timing.
	 *
	 * @param access the retained-record interpretation
	 */
	public static void configure(Access access)
	{
		Timing.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Returns the configured retained-record interpretation.
	 *
	 * @return the retained-record interpretation
	 * @throws AssertionError if evaluator composition did not configure benchmark timing
	 */
	private static Access access()
	{
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure benchmark timing");
		return result;
	}
}
