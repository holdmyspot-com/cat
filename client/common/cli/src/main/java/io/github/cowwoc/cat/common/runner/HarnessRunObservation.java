package io.github.cowwoc.cat.common.runner;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A validated point-in-time view of one retained runner manifest for exact-call recovery.
 *
 * @param run the exact owned run
 * @param state retained lifecycle state
 * @param phase retained activity or terminal phase
 * @param nativePid recorded native process ID, when published
 * @param nativeStartedAt recorded native process start marker, when published
 * @param turnNumber latest retained turn number
 * @param exitCode last recorded native turn or runner exit code, when present
 * @param finishedAt terminal timestamp, when present
 * @param ownerAlive whether the recorded owner was alive when observed
 * @param nativeAlive whether the recorded native process was alive when observed
 * @param diagnostic retained non-secret failure diagnostic
 */
public record HarnessRunObservation(HarnessRun run, State state, Phase phase, Optional<Long> nativePid,
	Optional<Instant> nativeStartedAt, int turnNumber, Optional<Integer> exitCode, Optional<Instant> finishedAt,
	boolean ownerAlive, boolean nativeAlive, String diagnostic)
{
	/** Retained run lifecycle states. */
	public enum State
	{
		RUNNING,
		COMPLETED,
		FAILED
	}

	/** Retained runner activity and terminal phases. */
	public enum Phase
	{
		INITIALIZING,
		IDLE,
		WORKING,
		COMPLETED,
		FAILED
	}

	/**
	 * Validates the observation without treating absent liveness as proof that a native call never started.
	 *
	 * @throws NullPointerException if a required value is null
	 * @throws IllegalArgumentException if the lifecycle or native identity is contradictory
	 */
	public HarnessRunObservation
	{
		Objects.requireNonNull(run, "run");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(nativePid, "nativePid");
		Objects.requireNonNull(nativeStartedAt, "nativeStartedAt");
		Objects.requireNonNull(exitCode, "exitCode");
		Objects.requireNonNull(finishedAt, "finishedAt");
		Objects.requireNonNull(diagnostic, "diagnostic");
		if (turnNumber < 0 || nativePid.isPresent() != nativeStartedAt.isPresent() ||
			nativeAlive && nativePid.isEmpty() ||
			state == State.RUNNING && (finishedAt.isPresent() || phase == Phase.COMPLETED || phase == Phase.FAILED) ||
			state == State.COMPLETED && (finishedAt.isEmpty() || phase != Phase.COMPLETED) ||
			state == State.FAILED && (finishedAt.isEmpty() || phase != Phase.FAILED))
			throw new IllegalArgumentException("Runner observation has contradictory lifecycle or native identity");
	}
}
