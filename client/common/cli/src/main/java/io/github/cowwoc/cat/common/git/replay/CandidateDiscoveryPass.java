package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * One exhaustive re-examination of a reviewer's candidate universe.
 *
 * @param number one-based pass number
 * @param examinedCandidateIds every candidate re-examined during the pass
 * @param newCandidateIds candidates first found during the pass
 */
record CandidateDiscoveryPass(int number, List<String> examinedCandidateIds, List<String> newCandidateIds)
{
}
