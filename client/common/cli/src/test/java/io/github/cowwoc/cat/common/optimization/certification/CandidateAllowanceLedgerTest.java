package io.github.cowwoc.cat.common.optimization.certification;
import io.github.cowwoc.cat.common.RecordStore;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests durable candidate allowance assignment across one optimization lineage.
 */
public final class CandidateAllowanceLedgerTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public CandidateAllowanceLedgerTest()
	{
	}

	/**
	 * Closes the JSON scope after the tests complete.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies the fixed schedule is summable and an identical candidate assignment is idempotent.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void assignsSummableAllowancesIdempotently() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-allowance-ledger-"));
			Path ledger = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(scope.json(), ledger, "lineage-a", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);

			CandidateAllowanceLedger.Assignment first = CandidateAllowanceLedger.assign(scope.json(), ledger,
				"candidate-a", Instant.parse("2026-09-01T12:00:00Z"));
			CandidateAllowanceLedger.Assignment retry = CandidateAllowanceLedger.assign(scope.json(), ledger,
				"candidate-a", Instant.parse("2026-09-01T12:01:00Z"));
			CandidateAllowanceLedger.Assignment second = CandidateAllowanceLedger.assign(scope.json(), ledger,
				"candidate-b", Instant.parse("2026-09-01T12:02:00Z"));
			CandidateAllowanceLedger.Snapshot snapshot = CandidateAllowanceLedger.read(scope.json(), ledger);

			requireThat(first.allowance().toPlainString(), "firstAllowance").isEqualTo("0.025");
			requireThat(retry, "retry").isEqualTo(first);
			requireThat(second.allowance().toPlainString(), "secondAllowance").isEqualTo("0.0125");
			requireThat(snapshot.assignmentCount(), "assignmentCount").isEqualTo(2);
			requireThat(snapshot.assignedAllowance().compareTo(snapshot.totalBudget()), "budgetComparison").
				isLessThanOrEqualTo(0);
		}
	}

	/**
	 * Verifies an unlimited schedule remains below its lineage budget after many assignments.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void remainsWithinBudgetAcrossManyCandidates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-allowance-ledger-"));
			Path ledger = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(scope.json(), ledger, "lineage-a", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			for (int candidate = 1; candidate <= 200; ++candidate)
			{
				CandidateAllowanceLedger.assign(scope.json(), ledger, "candidate-" + candidate,
					Instant.parse("2026-09-01T12:00:00Z").plusSeconds(candidate));
			}

			CandidateAllowanceLedger.Snapshot snapshot = CandidateAllowanceLedger.read(scope.json(), ledger);

			requireThat(snapshot.assignmentCount(), "assignmentCount").isEqualTo(200);
			requireThat(snapshot.assignedAllowance().compareTo(snapshot.totalBudget()), "budgetComparison").isLessThan(0);
			requireThat(snapshot.remainingAllowance().signum(), "remainingAllowance.signum()").isGreaterThan(0);
		}
	}

	/**
	 * Verifies the reader rejects fields outside the versioned ledger schema.
	 *
	 * @throws IOException for the expected schema failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Candidate allowance ledger has unsupported or missing fields.*")
	public void rejectsUnknownLedgerFields() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-allowance-ledger-"));
			Path ledger = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(scope.json(), ledger, "lineage-a", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			tools.jackson.databind.node.ObjectNode contents = (tools.jackson.databind.node.ObjectNode)
				RecordStore.read(scope.json(), ledger);
			contents.put("unsupported", true);
			RecordStore.write(scope.json(), ledger, contents);

			CandidateAllowanceLedger.read(scope.json(), ledger);
		}
	}

	/**
	 * Verifies a torn final append is rejected instead of being mistaken for durable evidence.
	 *
	 * @throws IOException for the expected incomplete-record failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Candidate allowance ledger ends with an incomplete record.*")
	public void rejectsAnIncompleteFinalRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-allowance-ledger-"));
			Path manifest = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(scope.json(), manifest, "lineage-a", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			CandidateAllowanceLedger.assign(scope.json(), manifest, "candidate-a",
				Instant.parse("2026-09-01T12:00:00Z"));
			Path assignments = Path.of(((tools.jackson.databind.node.ObjectNode)
				RecordStore.read(scope.json(), manifest)).path("assignment_ledger").asString());
			Files.writeString(assignments, Files.readString(assignments).stripTrailing());

			CandidateAllowanceLedger.read(scope.json(), manifest);
		}
	}
}
