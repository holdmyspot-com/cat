package com.holdmyspot.cat.common.git.replay;

/**
 * One Git-derived predecessor source and the logical output that represents it.
 *
 * @param source the predecessor source commit
 * @param output the predecessor's logical output identifier
 */
record OriginReviewCandidate(String source, String output)
{
}
