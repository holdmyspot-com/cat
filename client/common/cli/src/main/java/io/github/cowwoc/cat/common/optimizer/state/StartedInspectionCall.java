package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import java.util.Objects;

/**
 * Carries the first inspection's retained identity and its caller-owned active native handle.
 *
 * @param ownerRef indexed inspection owner
 * @param attemptRef indexed inspection transport attempt
 * @param logicalCallId durable scheduler call identity
 * @param call active native handle
 */
public record StartedInspectionCall(EvidenceRef ownerRef, EvidenceRef attemptRef, String logicalCallId,
	ActiveCall call)
{
	/** Validates the retained and live identities. */
	public StartedInspectionCall
	{
		Objects.requireNonNull(ownerRef, "ownerRef");
		Objects.requireNonNull(attemptRef, "attemptRef");
		if (logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Inspection logical call ID must not be blank");
		Objects.requireNonNull(call, "call");
	}
}
