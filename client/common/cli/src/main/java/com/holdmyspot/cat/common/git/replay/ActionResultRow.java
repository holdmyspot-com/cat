package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * One independently observable user- or developer-facing behavior discovered during topic review.
 *
 * @param candidateId the candidate-universe entry that owns the behavior
 * @param observableBehavior the user- or developer-facing behavior
 * @param observableResult the observable result of that behavior
 * @param acceptanceCheck the focused check that proves the result
 * @param requiredArtifacts the smallest artifact contract needed for the behavior
 * @param deferredArtifacts final-tree artifacts deliberately absent from that smaller contract
 */
public record ActionResultRow(String candidateId, String observableBehavior, String observableResult,
	String acceptanceCheck, List<String> requiredArtifacts, List<String> deferredArtifacts)
{
}
