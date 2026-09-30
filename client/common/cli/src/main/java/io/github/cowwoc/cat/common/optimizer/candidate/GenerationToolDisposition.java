package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable terminal or unresolved status for one recorded native tool call.
 *
 * @param schemaVersion durable schema version
 * @param toolCallRef exact recorded C3 tool call
 * @param status accepted, rejected, or unresolved
 * @param editReceiptRef accepted edit receipt only
 * @param editRejectionRef denied edit record only
 * @param reason infrastructure reason only for unresolved
 */
public record GenerationToolDisposition(int schemaVersion, EvidenceRef toolCallRef, Status status,
	Optional<EvidenceRef> editReceiptRef, Optional<EvidenceRef> editRejectionRef, Optional<String> reason)
{
	/** The only supported disposition schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates exact status-dependent evidence. */
	public GenerationToolDisposition
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported generation tool disposition schema: " + schemaVersion);
		Objects.requireNonNull(toolCallRef, "toolCallRef");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(editReceiptRef, "editReceiptRef");
		Objects.requireNonNull(editRejectionRef, "editRejectionRef");
		Objects.requireNonNull(reason, "reason");
		if (!toolCallRef.collection().equals("generation-tool-calls"))
			throw new IllegalArgumentException("Generation tool disposition call collection is invalid");
		switch (status)
		{
			case ACCEPTED ->
			{
				if (editReceiptRef.isEmpty() || editRejectionRef.isPresent() || reason.isPresent() ||
					!editReceiptRef.orElseThrow().collection().equals("generation-edit-receipts"))
					throw new IllegalArgumentException("ACCEPTED disposition requires only one edit receipt");
			}
			case REJECTED ->
			{
				if (editReceiptRef.isPresent() || editRejectionRef.isEmpty() || reason.isPresent() ||
					!editRejectionRef.orElseThrow().collection().equals("generation-edit-rejections"))
					throw new IllegalArgumentException("REJECTED disposition requires only one edit rejection");
			}
			case UNRESOLVED ->
			{
				if (editReceiptRef.isPresent() || editRejectionRef.isPresent() || reason.isEmpty() ||
					reason.orElseThrow().isBlank())
					throw new IllegalArgumentException("UNRESOLVED disposition requires only an infrastructure reason");
			}
		}
	}

	/** Terminal or unresolved call outcomes. */
	public enum Status
	{
		/** Exact edit receipt published. */
		ACCEPTED,
		/** Exact edit rejection published. */
		REJECTED,
		/** No terminal edit record can be established. */
		UNRESOLVED
	}
}
