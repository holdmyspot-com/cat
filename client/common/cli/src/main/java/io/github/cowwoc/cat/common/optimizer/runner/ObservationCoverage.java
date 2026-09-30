package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.check;

/**
 * Immutable ObservationCoverage observation at a native runner boundary.
 *
 * @param domain the recorded domain
 * @param status the recorded status
 * @param limitation the recorded limitation
 * @param evidence the recorded evidence
 */
public record ObservationCoverage(ObservationDomain domain,
	ObservationCoverageStatus status,
	Optional<String> limitation,
	List<EvidenceRef> evidence)
{
    /**
     * Validates the observed fields.
     */
    public ObservationCoverage
    {
		Objects.requireNonNull(domain, "domain");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(limitation, "limitation");
		evidence = List.copyOf(evidence);
		if (status == ObservationCoverageStatus.COMPLETE &&
			limitation.isPresent() ||
			status == ObservationCoverageStatus.LIMITED &&
			limitation.isEmpty())
			throw new IllegalArgumentException("coverage limitation mismatch");
		limitation.ifPresent(value -> check(value, "limitation"));
    }
}
