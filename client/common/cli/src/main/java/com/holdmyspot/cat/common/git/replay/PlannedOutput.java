package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * One sealed output disposition and its retained batches.
 *
 * @param outputId the logical output identifier
 * @param disposition {@code retained} or {@code discarded}
 * @param batches the ordered, digest-captured retained batches
 */
public record PlannedOutput(String outputId, String disposition, List<PlannedBatch> batches)
{
}
