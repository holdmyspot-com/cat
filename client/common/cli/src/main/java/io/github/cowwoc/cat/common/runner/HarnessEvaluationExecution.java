package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.optimization.ReproductionState;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Map;
import java.util.Set;

/**
 * Identifies retained evidence and the native-process timing for one completed harness evaluation.
 *
 * @param result the evidence retained by the harness
 * @param startedAtNanos the monotonic instant immediately after the harness process starts
 * @param completedAtNanos the monotonic instant immediately after the harness process exits
 * @param interrupted whether the operation owner requested interruption while the harness process was active
 * @param harnessVersion the harness version used for the execution
 * @param runnerVersion the CAT runner version used for the execution
 * @param usage provider-reported token usage
 * @param reproductionState whether retained evidence remains reproducible
 * @param evidenceFingerprints fingerprints of retained evidence artifacts
 */
public record HarnessEvaluationExecution(HarnessEvaluationResult result, long startedAtNanos, long completedAtNanos,
	boolean interrupted, String harnessVersion, String runnerVersion, ProviderUsage usage,
	ReproductionState reproductionState, Map<String, String> evidenceFingerprints)
{
	/**
	 * Creates an execution that completed without an owner interruption.
	 *
	 * @param result the evidence retained by the harness
	 * @param startedAtNanos the monotonic instant immediately after the harness process starts
	 * @param completedAtNanos the monotonic instant immediately after the harness process exits
	 */
	public HarnessEvaluationExecution(HarnessEvaluationResult result, long startedAtNanos, long completedAtNanos)
	{
		this(result, startedAtNanos, completedAtNanos, false, "unknown", "unknown", ProviderUsage.unavailable(),
			ReproductionState.UNAVAILABLE, Map.of());
	}

	/** Creates a legacy execution with an interruption flag. */
	public HarnessEvaluationExecution(HarnessEvaluationResult result, long startedAtNanos, long completedAtNanos,
		boolean interrupted)
	{
		this(result, startedAtNanos, completedAtNanos, interrupted, "unknown", "unknown", ProviderUsage.unavailable(),
			ReproductionState.UNAVAILABLE, Map.of());
	}

	/**
	 * Validates the result and its monotonic process timing.
	 *
	 * @param result the evidence retained by the harness
	 * @param startedAtNanos the monotonic instant immediately after the harness process starts
	 * @param completedAtNanos the monotonic instant immediately after the harness process exits
	 * @param interrupted whether the operation owner requested interruption while the harness process was active
	 * @param harnessVersion the harness version used for the execution
	 * @param runnerVersion the runner version used for the execution
	 * @param usage provider token usage
	 * @param reproductionState retained-evidence reproduction state
	 * @param evidenceFingerprints retained-evidence fingerprints
	 */
	public HarnessEvaluationExecution
	{
		requireThat(result, "result").isNotNull();
		requireThat(harnessVersion, "harnessVersion").isNotBlank();
		requireThat(runnerVersion, "runnerVersion").isNotBlank();
		requireThat(usage, "usage").isNotNull();
		requireThat(reproductionState, "reproductionState").isNotNull();
		requireThat(evidenceFingerprints, "evidenceFingerprints").isNotNull();
		evidenceFingerprints = Map.copyOf(evidenceFingerprints);
		for (Map.Entry<String, String> entry : evidenceFingerprints.entrySet())
		{
			if (!Set.of("retained_project", "raw_result", "transcript").contains(entry.getKey()) ||
				entry.getValue() == null || !entry.getValue().matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("Evidence fingerprints must use approved keys and lowercase " +
					"SHA-256 values");
		}
		if (completedAtNanos < startedAtNanos)
			throw new IllegalArgumentException("completedAtNanos must not precede startedAtNanos");
	}
}
