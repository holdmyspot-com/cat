package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable prohibition on unrelated scheduling while prior slots are reconciled.
 *
 * @param purpose lifecycle purpose
 * @param predecessorCounter campaign counter when barrier began
 * @param schedulerFence owner fence that began the barrier
 * @param lineage active lineage at the barrier
 * @param phase active phase at the barrier
 * @param proofRef completed proof, when all rooted slots were adopted
 */
public record SchedulerBarrier(QuiescencePurpose purpose, long predecessorCounter, long schedulerFence,
	String lineage, CampaignStatus phase, Optional<EvidenceRef> proofRef)
{
	/** Validates the barrier identity. */
	public SchedulerBarrier
	{
		Objects.requireNonNull(purpose, "purpose");
		if (predecessorCounter < 0 || schedulerFence < 1 || lineage == null || lineage.isBlank())
			throw new IllegalArgumentException("Quiescence barrier identity is invalid");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(proofRef, "proofRef");
	}
}
