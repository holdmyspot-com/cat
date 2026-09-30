package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Retains one measurement epoch, its dynamic baseline, and an optional change pause.
 *
 * @param epochRef immutable measurement identity
 * @param baselineRootRef current dynamic baseline, when established
 * @param pause unresolved material change, when present
 */
public record MeasurementState(EvidenceRef epochRef, Optional<EvidenceRef> baselineRootRef,
	Optional<MeasurementPause> pause)
{
	/**
	 * Validates the measurement evidence collections.
	 *
	 * @param epochRef immutable measurement identity
	 * @param baselineRootRef current dynamic baseline, when established
	 * @param pause unresolved material change, when present
	 */
	public MeasurementState
	{
		Objects.requireNonNull(epochRef, "epochRef");
		Objects.requireNonNull(baselineRootRef, "baselineRootRef");
		Objects.requireNonNull(pause, "pause");
		if (!epochRef.collection().equals("measurement-epochs"))
			throw new IllegalArgumentException("Measurement epoch has the wrong collection: " + epochRef);
		for (EvidenceRef root : baselineRootRef.stream().toList())
			if (!root.collection().equals("dynamic-baseline-roots"))
				throw new IllegalArgumentException("Dynamic baseline has the wrong collection: " + root);
	}
}
