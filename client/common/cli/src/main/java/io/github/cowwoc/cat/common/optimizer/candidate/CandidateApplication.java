package io.github.cowwoc.cat.common.optimizer.candidate;

import java.util.Objects;
import java.nio.file.Path;
import java.util.List;

/**
 * A completed, constrained candidate reconciliation.
 *
 * @param disposition successful reconciliation outcome
 * @param transactionId durable transaction identity
 * @param receiptId immutable archived receipt identity
 * @param affectedPaths ordered changed project-relative paths
 * @param diff selected candidate difference
 */
public record CandidateApplication(ApplyDisposition disposition, String transactionId, String receiptId,
	List<Path> affectedPaths, CandidateDiff diff)
{
	/** Validates a reconciliation result. */
	public CandidateApplication
	{
		Objects.requireNonNull(disposition, "disposition");
		Objects.requireNonNull(transactionId, "transactionId");
		Objects.requireNonNull(receiptId, "receiptId");
		affectedPaths = List.copyOf(Objects.requireNonNull(affectedPaths, "affectedPaths"));
		Objects.requireNonNull(diff, "diff");
	}

	/**
	 * Retains the older in-memory delivery result while its callers migrate to durable reconciliation.
	 *
	 * @param disposition application disposition
	 * @param diff structured candidate diff
	 */
	public CandidateApplication(ApplyDisposition disposition, CandidateDiff diff)
	{
		this(disposition, "", "", diff.changes().stream().map(CandidateDiff.Change::path).toList(), diff);
	}
}
