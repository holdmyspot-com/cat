package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;

/**
 * Immutable persisted state for one logical trial and all transport attempts.
 *
 * @param logicalTrialId logical identity
 * @param lineageId lineage identity
 * @param candidateId candidate identity
 * @param purpose evidence purpose
 * @param status trial status
 * @param failureKind failure classification
 * @param selectionEvidence selection evidence
 * @param outcomeEvidence outcome evidence
 */
public record TrialState(String logicalTrialId, String lineageId, String candidateId, EvidencePurpose purpose,
	TrialStatus status, FailureKind failureKind, EvidenceRef selectionEvidence, EvidenceRef outcomeEvidence)
{
	/** Validates the state discriminators and immutable evidence references. */
	public TrialState
	{
		if (logicalTrialId == null || logicalTrialId.isBlank() || lineageId == null || lineageId.isBlank() ||
			candidateId == null || candidateId.isBlank() || selectionEvidence == null)
		{
			throw new IllegalArgumentException("Trial identity and selection evidence must be non-blank");
		}
		if (purpose == null || status == null || failureKind != expectedFailureKind(status))
			throw new IllegalArgumentException("Trial discriminators are inconsistent");
		if (terminal(status) != (outcomeEvidence != null))
			throw new IllegalArgumentException("Outcome evidence must be present exactly for terminal trial state");
	}

	/**
	 * Returns the failure kind prescribed by one trial status.
	 *
	 * @param status the trial status
	 * @return the required failure kind, or null
	 */
	public static FailureKind expectedFailureKind(TrialStatus status)
	{
		return switch (status)
		{
			case ASSIGNED, RUNNING, COMPLIANT -> null;
			case NONCOMPLIANT -> FailureKind.COMPLIANCE;
			case RETRYABLE, INVALID, INFRASTRUCTURE_FAILED -> FailureKind.INFRASTRUCTURE;
		};
	}

	/**
	 * Returns whether a trial status permits no further transition.
	 *
	 * @param status the trial status
	 * @return whether the status is terminal
	 */
	public static boolean terminal(TrialStatus status)
	{
		return status == TrialStatus.COMPLIANT || status == TrialStatus.NONCOMPLIANT || status == TrialStatus.INVALID ||
			status == TrialStatus.INFRASTRUCTURE_FAILED;
	}
}
