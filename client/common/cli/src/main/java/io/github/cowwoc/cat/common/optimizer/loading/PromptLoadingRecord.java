package io.github.cowwoc.cat.common.optimizer.loading;

import java.nio.file.Path;
import java.util.List;

/**
 * Durable, content-addressed prompt loading record.
 *
 * @param schemaVersion durable schema discriminator
 * @param loadingEvidenceId canonical loading identity
 * @param targetPath selected target path
 * @param bindings receipt-to-path bindings
 * @param receipts ordered production receipts
 */
public record PromptLoadingRecord(int schemaVersion, String loadingEvidenceId, Path targetPath,
	List<PromptSourceBinding> bindings, List<PromptSourceReceipt> receipts)
{
	/** Current durable loading schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates and copies the record. */
	public PromptLoadingRecord
	{
		if (schemaVersion != SCHEMA_VERSION || loadingEvidenceId == null || loadingEvidenceId.isBlank() ||
			targetPath == null || targetPath.isAbsolute() || !targetPath.equals(targetPath.normalize()) ||
			targetPath.startsWith(".."))
		{
			throw new IllegalArgumentException("Prompt loading record is invalid");
		}
		bindings = List.copyOf(bindings);
		receipts = List.copyOf(receipts);
	}
}
