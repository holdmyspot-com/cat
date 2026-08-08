package io.github.cowwoc.cat.common.git.replay;

import java.util.Set;

/**
 * Validated topic-reviewer discovery identifiers and pairwise behavior evidence.
 *
 * @param reviewerCandidates topic-reviewer candidate identifiers
 * @param reviewerMatrix topic-reviewer observable-behavior evidence
 */
record CandidateUniverseEvidence(Set<String> reviewerCandidates, ActionResultMatrix reviewerMatrix)
{
}
