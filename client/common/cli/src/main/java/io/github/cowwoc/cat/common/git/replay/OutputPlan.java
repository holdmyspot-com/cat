package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * The immutable complete output plan recorded in the replay manifest.
 *
 * @param outputs every logical output disposition in chronological order
 */
record OutputPlan(List<PlannedOutput> outputs)
{
}
