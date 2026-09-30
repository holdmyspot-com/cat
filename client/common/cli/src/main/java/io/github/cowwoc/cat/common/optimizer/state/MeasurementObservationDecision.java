package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of comparing a before-call observation with the current dynamic baseline.
 *
 * @param status accepted baseline or known material change
 * @param observationRef retained complete observation
 * @param changeRef retained difference when admission paused
 */
public record MeasurementObservationDecision(Status status, EvidenceRef observationRef,
	Optional<EvidenceRef> changeRef)
{
	/** Finite before-call decision. */
	public enum Status
	{
		ACCEPTED,
		PAUSED
	}

	/** Validates the decision's evidence collections. */
	public MeasurementObservationDecision
	{
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(observationRef, "observationRef");
		Objects.requireNonNull(changeRef, "changeRef");
		if (!observationRef.collection().equals("dynamic-baseline-roots") ||
			status == Status.ACCEPTED && changeRef.isPresent() ||
			status == Status.PAUSED && changeRef.isEmpty() ||
			changeRef.stream().anyMatch(ref -> !ref.collection().equals("measurement-changes")))
			throw new IllegalArgumentException("Measurement decision has inconsistent evidence");
	}
}
