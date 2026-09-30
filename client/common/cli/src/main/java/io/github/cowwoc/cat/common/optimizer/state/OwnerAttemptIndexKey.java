package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Produces the same canonical owner-attempt key for a lineage producer and the scheduler.
 */
public final class OwnerAttemptIndexKey
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prevents construction.
	 */
	private OwnerAttemptIndexKey()
	{
	}

	/**
	 * Derives the fixed lineage attempt key from its durable request and claim.
	 *
	 * @param kind the lineage attempt kind
	 * @param ownerRequestRef the prepared lineage call
	 * @param runRequestRef the native request
	 * @param slotClaimRef the original scheduler claim
	 * @param attemptOrdinal the nonnegative attempt ordinal
	 * @return canonical JSON key
	 * @throws IllegalArgumentException if the kind or ordinal is invalid
	 */
	public static String deriveLineage(ProducerAttemptKind kind, EvidenceRef ownerRequestRef,
		EvidenceRef runRequestRef, EvidenceRef slotClaimRef, int attemptOrdinal)
	{
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(ownerRequestRef, "ownerRequestRef");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		if (attemptOrdinal < 0 || switch (kind)
		{
			case GENERATOR_VALIDATION, CANDIDATE, EVALUATOR, TRIAL -> false;
		default -> true;
		})
			throw new IllegalArgumentException("Lineage attempt kind or ordinal is invalid");
		return new String(JSON.writeValueAsBytes(List.of("owner-attempt-v1", kind.name(),
			List.of(reference(ownerRequestRef), reference(runRequestRef), reference(slotClaimRef),
				Integer.toString(attemptOrdinal)))), java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * Derives the preconfirmation inspection attempt key from its owner and original slot claim.
	 *
	 * @param ownerRequestRef the indexed inspection owner
	 * @param runRequestRef the native request
	 * @param slotClaimRef the original scheduler claim
	 * @param attemptOrdinal the nonnegative attempt ordinal
	 * @return canonical JSON key
	 */
	public static String deriveInspection(EvidenceRef ownerRequestRef, EvidenceRef runRequestRef,
		EvidenceRef slotClaimRef, int attemptOrdinal)
	{
		Objects.requireNonNull(ownerRequestRef, "ownerRequestRef");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		if (attemptOrdinal < 0)
			throw new IllegalArgumentException("Inspection attempt ordinal must be nonnegative");
		return new String(JSON.writeValueAsBytes(List.of("inspection-attempt-v1",
			List.of(reference(ownerRequestRef), reference(runRequestRef), reference(slotClaimRef),
				Integer.toString(attemptOrdinal)))), java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * Represents one evidence identity without Java record property names.
	 *
	 * @param value evidence identity
	 * @return collection and content hash
	 */
	private static List<String> reference(EvidenceRef value)
	{
		return List.of(value.collection(), value.evidenceId());
	}
}
