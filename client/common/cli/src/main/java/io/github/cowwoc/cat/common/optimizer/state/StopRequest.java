package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Retained exact-invocation stop and its eventual interruption acknowledgment.
 *
 * @param stopRef immutable stop evidence
 * @param sourceInvocationId original command invocation
 * @param sourceFence fence bound to that invocation, if acquired
 * @param reason first authoritative stop reason
 * @param status pending or acknowledged
 * @param adoptingFence fence that adopted the stop
 * @param logicalCallId reconciled call slot identity, if any
 * @param interruptionReceiptId embedded committed interruption receipt ID, if acknowledged
 */
public record StopRequest(EvidenceRef stopRef, String sourceInvocationId, Optional<Long> sourceFence,
	String reason, Status status, Optional<Long> adoptingFence, Optional<String> logicalCallId,
	Optional<String> interruptionReceiptId)
{
	/**
	 * Closed stop states.
	 */
	public enum Status
	{
		PENDING,
		ACKNOWLEDGED
	}

	/**
	 * Validates the stop lifecycle fields.
	 */
	public StopRequest
	{
		Objects.requireNonNull(stopRef, "stopRef");
		if (sourceInvocationId == null || sourceInvocationId.isBlank() || reason == null || reason.isBlank())
			throw new IllegalArgumentException("Stop invocation and reason must be non-blank");
		Objects.requireNonNull(sourceFence, "sourceFence");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(adoptingFence, "adoptingFence");
		Objects.requireNonNull(logicalCallId, "logicalCallId");
		Objects.requireNonNull(interruptionReceiptId, "interruptionReceiptId");
		if (interruptionReceiptId.isPresent() && interruptionReceiptId.orElseThrow().isBlank())
			throw new IllegalArgumentException("Interruption receipt ID must be non-blank");
		if (!stopRef.collection().equals("stop-requests"))
			throw new IllegalArgumentException("Stop evidence has the wrong collection");
		if (sourceFence.isPresent() && sourceFence.orElseThrow() < 1 ||
			adoptingFence.isPresent() && adoptingFence.orElseThrow() < 1)
			throw new IllegalArgumentException("Stop fences must be positive");
		if (sourceFence.isPresent() && adoptingFence.isPresent() &&
			adoptingFence.orElseThrow() < sourceFence.orElseThrow())
			throw new IllegalArgumentException("Adopting fence precedes source fence");
		if (status == Status.PENDING && interruptionReceiptId.isPresent())
			throw new IllegalArgumentException("Pending stop cannot have interruption receipt");
		if (status == Status.ACKNOWLEDGED &&
			(adoptingFence.isEmpty() || interruptionReceiptId.isEmpty()))
			throw new IllegalArgumentException("Acknowledged stop requires fence and interruption receipt");
	}
}
