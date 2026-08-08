package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * A reviewer claim that a source commit's semantic units share one output or require multiple outputs.
 *
 * @param source the reviewed source commit
 * @param disposition {@code whole} for one destination, {@code split} for multiple destinations, or {@code absorbed}
 *        when the source output is discarded
 * @param destinations the reviewed output IDs that receive this source's batches
 * @param placements every changed path's reviewed responsibility and destination
 * @param counterfactual why omitting the source does not leave an earlier output incomplete
 * @param evidence the reviewed predecessor, consumer, and destination evidence
 */
record SourceReview(String source, String disposition, List<String> destinations, List<UnitPlacement> placements,
	String counterfactual, String evidence)
{
}
