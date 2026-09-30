package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable exact-invocation registration created before host listener exposure.
 *
 * @param registrationRef original immutable registration evidence
 * @param binding exact campaign binding
 * @param invocationId command invocation identity
 * @param registeredCounter campaign counter at registration
 * @param observedOwnerFence prior owner fence if present
 * @param hostObservationRef host observation attached to this invocation
 * @param status open or closed registration
 * @param boundFence fence bound by lease acquisition
 */
public record InvocationStopRegistration(EvidenceRef registrationRef, CampaignBinding binding,
	String invocationId, long registeredCounter, Optional<Long> observedOwnerFence,
	Optional<EvidenceRef> hostObservationRef, Status status, Optional<Long> boundFence)
{
	/**
	 * Closed invocation registration states.
	 */
	public enum Status
	{
		OPEN,
		CLOSED
	}

	/**
	 * Requires exact binding and complete optional fields.
	 */
	public InvocationStopRegistration
	{
		Objects.requireNonNull(registrationRef, "registrationRef");
		Objects.requireNonNull(binding, "binding");
		if (invocationId == null || invocationId.isBlank() || registeredCounter < 0)
			throw new IllegalArgumentException("Invocation registration identity or counter is invalid");
		Objects.requireNonNull(observedOwnerFence, "observedOwnerFence");
		Objects.requireNonNull(hostObservationRef, "hostObservationRef");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(boundFence, "boundFence");
		if (!registrationRef.collection().equals("invocation-stop-registrations") ||
			hostObservationRef.isPresent() &&
				!hostObservationRef.orElseThrow().collection().equals("host-interrupt-observations"))
			throw new IllegalArgumentException("Invocation evidence has the wrong collection");
		if (observedOwnerFence.isPresent() && observedOwnerFence.orElseThrow() < 1 ||
			boundFence.isPresent() && boundFence.orElseThrow() < 1)
			throw new IllegalArgumentException("Invocation owner fence must be positive");
	}
}
