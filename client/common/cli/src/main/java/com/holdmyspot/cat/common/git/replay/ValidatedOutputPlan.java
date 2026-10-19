package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * The sealed plan together with the state it would produce before any replay decision is written.
 *
 * @param plan every sealed output disposition
 * @param batches batches produced by applying the complete plan in order
 * @param witnesses retained-output tree transitions produced by that application
 * @param discardedOutputs discarded logical outputs produced by the plan
 */
public record ValidatedOutputPlan(OutputPlan plan, List<Batch> batches, List<OutputWitness> witnesses,
	List<String> discardedOutputs)
{
}
