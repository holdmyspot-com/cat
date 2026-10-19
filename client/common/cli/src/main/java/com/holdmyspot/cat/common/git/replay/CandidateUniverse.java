package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * A reviewer-owned behavior inventory created before topic outputs are named.
 *
 * @param reviewer the reviewer identity
 * @param candidates independently discovered behavior candidates
 * @param entryPointFamilies evidence for deliberately combined selectable entry points
 */
public record CandidateUniverse(String reviewer, List<CandidateUniverseEntry> candidates,
	List<EntryPointFamily> entryPointFamilies)
{
}
