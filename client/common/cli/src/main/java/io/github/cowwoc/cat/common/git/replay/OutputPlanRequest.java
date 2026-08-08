package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * One reviewer-authored output disposition in the file submitted for plan sealing.
 *
 * @param outputId the logical output in chronological order
 * @param disposition {@code retained} or {@code discarded}
 * @param batches the ordered retained-output batches, or an empty list for a discarded output
 */
record OutputPlanRequest(String outputId, String disposition, List<BatchRequest> batches)
{
}
