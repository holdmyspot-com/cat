package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.port.TurnObserver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The state of one undecided control trial, keyed by its draw position in the pilot.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param requestId the original launch, or empty when no earlier launch needs reconciliation
 * @param progress the latest observer decision saved for that launch
 * @param infrastructureAttempts why earlier attempts of the same trial ended without an outcome
 * @param discard the recorded reason to discard the launch even if it could resume, or empty to allow recovery
 */
public record PilotTrialRecovery(Optional<UUID> requestId, TurnObserver.Progress progress,
	List<String> infrastructureAttempts, Optional<Discard> discard)
{
	/**
	 * Creates the recovery state of one control trial.
	 *
	 * @throws NullPointerException if an argument or infrastructure reason is null
	 * @throws IllegalArgumentException if an infrastructure reason is blank, or progress or discard intent has no
	 *   recorded launch
	 */
	public PilotTrialRecovery
	{
		requireThat(requestId, "requestId").isNotNull();
		requireThat(progress, "progress").isNotNull();
		requireThat(discard, "discard").isNotNull();
		infrastructureAttempts = List.copyOf(infrastructureAttempts);
		for (String reason : infrastructureAttempts)
			requireThat(reason, "reason").isNotBlank();
		if (requestId.isEmpty() && (progress.completedTurn() != 0 || progress.endedEarly() || discard.isPresent()))
			throw new IllegalArgumentException("Observer progress and discard intent require an original launch ID");
	}

	/**
	 * Records why a launch must be discarded before replacing it.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param reason why the pilot must discard the earlier launch
	 * @param charged whether acknowledgement of the discard consumes an infrastructure attempt
	 */
	public record Discard(String reason, boolean charged)
	{
		/**
		 * Creates a discard intent.
		 *
		 * @throws NullPointerException if the reason is null
		 * @throws IllegalArgumentException if the reason is blank
		 */
		public Discard
		{
			requireThat(reason, "reason").isNotBlank();
		}
	}
}
