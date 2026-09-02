package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.certification.CandidateAllowanceLedger;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assigns an append-only, halving error allowance to each development challenger in one optimization lineage.
 *
 * <p>The durable encoding deliberately reuses {@link CandidateAllowanceLedger}. Both formal certification and
 * development promotion therefore use the same locked, hash-chained, idempotent schedule rather than independently
 * allocating statistical error.</p>
 */
public final class PromotionAllowanceLedger
{
	private static final BigDecimal TOTAL_ALLOWANCE = new BigDecimal("0.05");

	/**
	 * Prevents construction of this static ledger adapter.
	 */
	private PromotionAllowanceLedger()
	{
	}

	/**
	 * Initializes the immutable development-promotion allowance ledger.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the new ledger manifest
	 * @param lineageId the stable development lineage identifier
	 * @throws IOException if the manifest or its assignment ledger already exists or cannot be published
	 */
	public static void initialize(JsonMapper json, Path manifestPath, String lineageId) throws IOException
	{
		CandidateAllowanceLedger.initialize(Objects.requireNonNull(json, "json must not be null"),
			Objects.requireNonNull(manifestPath, "manifestPath must not be null"), lineageId, TOTAL_ALLOWANCE,
			CandidateAllowanceLedger.Schedule.HALVING_V1);
	}

	/**
	 * Returns the retained allowance for a challenger or assigns its next one before paired evidence is collected.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the existing ledger manifest
	 * @param challengerId the immutable challenger identifier
	 * @param assignedAt the assignment time
	 * @return the retained or newly assigned challenger allowance
	 * @throws IOException if the ledger cannot be verified or updated
	 */
	public static Assignment assign(JsonMapper json, Path manifestPath, String challengerId, Instant assignedAt)
		throws IOException
	{
		CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(
			Objects.requireNonNull(json, "json must not be null"),
			Objects.requireNonNull(manifestPath, "manifestPath must not be null"), challengerId,
			Objects.requireNonNull(assignedAt, "assignedAt must not be null"));
		return Assignment.from(assignment);
	}

	/**
	 * Reads the bounded, validated summary of all assigned challenger allowances.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the existing ledger manifest
	 * @return the immutable allowance summary
	 * @throws IOException if the ledger cannot be verified
	 */
	public static Snapshot read(JsonMapper json, Path manifestPath) throws IOException
	{
		return Snapshot.from(CandidateAllowanceLedger.read(Objects.requireNonNull(json, "json must not be null"),
			Objects.requireNonNull(manifestPath, "manifestPath must not be null")));
	}

	/**
	 * Finds one previously assigned challenger allowance.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the existing ledger manifest
	 * @param challengerId the immutable challenger identifier
	 * @return the retained allowance, or empty when this challenger was not assigned one
	 * @throws IOException if the ledger cannot be verified
	 */
	public static Optional<Assignment> find(JsonMapper json, Path manifestPath, String challengerId) throws IOException
	{
		return CandidateAllowanceLedger.find(Objects.requireNonNull(json, "json must not be null"),
			Objects.requireNonNull(manifestPath, "manifestPath must not be null"), challengerId).map(Assignment::from);
	}

	/**
	 * Holds one immutable development challenger allowance.
	 *
	 * @param challengerId the stable challenger identifier
	 * @param ordinal the one-based challenger order
	 * @param allowance the assigned error allowance
	 * @param assignedAt the assignment time
	 * @param previousFingerprint the preceding hash-chain fingerprint
	 * @param entryFingerprint this entry's hash-chain fingerprint
	 */
	public record Assignment(String challengerId, int ordinal, BigDecimal allowance, Instant assignedAt,
		String previousFingerprint, String entryFingerprint)
	{
		/**
		 * Converts the shared durable assignment to promotion terminology.
		 *
		 * @param assignment the shared durable assignment
		 * @return the corresponding promotion assignment
		 */
		private static Assignment from(CandidateAllowanceLedger.Assignment assignment)
		{
			return new Assignment(assignment.candidateId(), assignment.ordinal(), assignment.allowance(),
				assignment.assignedAt(), assignment.previousFingerprint(), assignment.entryFingerprint());
		}
	}

	/**
	 * Holds the bounded summary of one development-promotion lineage ledger.
	 *
	 * @param lineageId the stable lineage identifier
	 * @param totalAllowance the fixed maximum false-promotion probability
	 * @param assignedAllowance the allowance already assigned to challengers
	 * @param remainingAllowance the unassigned allowance
	 * @param assignmentCount the number of assigned challengers
	 * @param ledgerFingerprint the final hash-chain fingerprint
	 */
	public record Snapshot(String lineageId, BigDecimal totalAllowance, BigDecimal assignedAllowance,
		BigDecimal remainingAllowance, int assignmentCount, String ledgerFingerprint)
	{
		/**
		 * Converts the shared durable summary to promotion terminology.
		 *
		 * @param snapshot the shared durable summary
		 * @return the corresponding promotion summary
		 */
		private static Snapshot from(CandidateAllowanceLedger.Snapshot snapshot)
		{
			return new Snapshot(snapshot.lineageId(), snapshot.totalBudget(), snapshot.assignedAllowance(),
				snapshot.remainingAllowance(), snapshot.assignmentCount(), snapshot.ledgerFingerprint());
		}
	}
}
