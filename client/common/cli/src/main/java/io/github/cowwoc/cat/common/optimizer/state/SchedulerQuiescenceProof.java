package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable proof that every call slot at the barrier was adopted.
 *
 * @param schemaVersion proof schema
 * @param binding exact campaign binding
 * @param purpose lifecycle purpose
 * @param predecessorCounter campaign counter at barrier creation
 * @param schedulerFence owning scheduler fence
 * @param lineage active lineage
 * @param phase campaign phase
 * @param callSlotRootRef immutable rooted slot snapshot
 * @param provenAt proof time
 */
public record SchedulerQuiescenceProof(int schemaVersion, CampaignBinding binding,
	QuiescencePurpose purpose, long predecessorCounter, long schedulerFence, String lineage,
	CampaignStatus phase, EvidenceRef callSlotRootRef, Instant provenAt)
{
	/** Validates exact proof shape. */
	public SchedulerQuiescenceProof
	{
		if (schemaVersion != 1 || predecessorCounter < 0 || schedulerFence < 1)
			throw new IllegalArgumentException("Quiescence proof version or counters are invalid");
		Objects.requireNonNull(binding, "binding");
		Objects.requireNonNull(purpose, "purpose");
		if (lineage == null || lineage.isBlank())
			throw new IllegalArgumentException("Quiescence proof lineage is missing");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(callSlotRootRef, "callSlotRootRef");
		Objects.requireNonNull(provenAt, "provenAt");
	}
}
