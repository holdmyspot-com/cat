package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests the fixed development-promotion allowance schedule.
 */
public final class PromotionAllowanceLedgerTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public PromotionAllowanceLedgerTest()
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
	 * Assigns the 0.05 / 2^c schedule once per challenger and retains retry identity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void assignsFixedHalvingScheduleIdempotently() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("promotion-allowance-"));
			Path manifest = root.resolve("promotion-allowances.json");
			PromotionAllowanceLedger.initialize(scope.json(), manifest, "development-lineage");

			PromotionAllowanceLedger.Assignment first = PromotionAllowanceLedger.assign(scope.json(), manifest,
				"challenger-a", Instant.parse("2026-09-01T12:00:00Z"));
			PromotionAllowanceLedger.Assignment retry = PromotionAllowanceLedger.assign(scope.json(), manifest,
				"challenger-a", Instant.parse("2026-09-01T12:01:00Z"));
			PromotionAllowanceLedger.Assignment second = PromotionAllowanceLedger.assign(scope.json(), manifest,
				"challenger-b", Instant.parse("2026-09-01T12:02:00Z"));
			PromotionAllowanceLedger.Snapshot summary = PromotionAllowanceLedger.read(scope.json(), manifest);

			requireThat(first.allowance().toPlainString(), "firstAllowance").isEqualTo("0.025");
			requireThat(retry, "retry").isEqualTo(first);
			requireThat(second.allowance().toPlainString(), "secondAllowance").isEqualTo("0.0125");
			requireThat(summary.assignmentCount(), "assignmentCount").isEqualTo(2);
			requireThat(summary.remainingAllowance().toPlainString(), "remainingAllowance").isEqualTo("0.0125");
		}
	}
}
