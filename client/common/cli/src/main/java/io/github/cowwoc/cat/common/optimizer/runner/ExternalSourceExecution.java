package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.check;
import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.positive;

/**
 * Immutable ExternalSourceExecution observation at a native runner boundary.
 *
 * @param sourceId the recorded sourceId
 * @param kind the recorded kind
 * @param observableIdentity the recorded observableIdentity
 * @param materialResponseEvidence the recorded materialResponseEvidence
 * @param nativeEventEvidence the recorded nativeEventEvidence
 * @param runId the recorded runId
 * @param conversationId the recorded conversationId
 * @param eventOrdinal the recorded eventOrdinal
 * @param observedAt the recorded observedAt
 * @param limitation the recorded limitation
 */
public record ExternalSourceExecution(String sourceId,
	String kind,
	Optional<String> observableIdentity,
	Optional<EvidenceRef> materialResponseEvidence,
	EvidenceRef nativeEventEvidence,
	String runId,
	String conversationId,
	long eventOrdinal,
	Instant observedAt,
	Optional<String> limitation)
{
    /**
     * Validates the observed fields.
     */
    public ExternalSourceExecution
    {
		check(sourceId, "sourceId");
		check(kind, "kind");
		Objects.requireNonNull(observableIdentity, "observableIdentity");
		observableIdentity.ifPresent(value -> check(value, "observableIdentity"));
		Objects.requireNonNull(materialResponseEvidence, "materialResponseEvidence");
		Objects.requireNonNull(nativeEventEvidence, "nativeEventEvidence");
		check(runId, "runId");
		check(conversationId, "conversationId");
		positive(eventOrdinal);
		Objects.requireNonNull(observedAt, "observedAt");
		Objects.requireNonNull(limitation, "limitation");
		limitation.ifPresent(value -> check(value, "limitation"));
		if ((observableIdentity.isEmpty() || materialResponseEvidence.isEmpty()) && limitation.isEmpty())
			throw new IllegalArgumentException("unobserved external identity or response requires a limitation");
    }
}
