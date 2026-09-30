package io.github.cowwoc.cat.common.optimizer.loading;

import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import java.nio.file.Path;
import java.util.List;

/**
 * A validated prompt-source graph bound to durable loading evidence.
 *
 * @param targetPath selected target path
 * @param sources ordered prompt source graph
 * @param receipts ordered receipts that produced the graph
 * @param loadingEvidenceId canonical loading identity
 */
public record ValidatedPromptLoading(Path targetPath, List<PromptSource> sources,
	List<PromptSourceReceipt> receipts, String loadingEvidenceId)
{
	/** Validates and copies the bound graph. */
	public ValidatedPromptLoading
	{
		if (targetPath == null || sources == null || receipts == null || loadingEvidenceId == null ||
			loadingEvidenceId.isBlank())
			throw new IllegalArgumentException("Validated prompt loading is invalid");
		sources = List.copyOf(sources);
		receipts = List.copyOf(receipts);
	}
}
