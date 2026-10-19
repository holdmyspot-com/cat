package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * A reviewer's ordered discovery passes for one complete candidate universe.
 *
 * @param passes the complete re-examinations, ending with an empty result
 */
record CandidateDiscoveryPasses(List<CandidateDiscoveryPass> passes)
{
}
