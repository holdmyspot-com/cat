package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * One sealed output disposition and its retained batches.
 *
 * @param outputId the logical output identifier
 * @param disposition {@code retained} or {@code discarded}
 * @param batches the ordered, digest-captured retained batches
 */
record PlannedOutput(String outputId, String disposition, List<PlannedBatch> batches)
{
}
