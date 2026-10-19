package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * Immutable Git facts that a reviewer needs to make one semantic origin decision.
 *
 * @param inventoryId the changed inventory item
 * @param path the changed repository-relative path
 * @param candidates the chronological prior source/output pairs for that path
 */
record OriginReviewScaffold(String inventoryId, String path, List<OriginReviewCandidate> candidates)
{
}
