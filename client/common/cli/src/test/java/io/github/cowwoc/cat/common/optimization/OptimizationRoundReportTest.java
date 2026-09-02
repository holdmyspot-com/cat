package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimization.cost.MoneyAmount;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.testng.annotations.Test;

/**
 * Tests the concise user-facing optimization-round projection.
 */
public final class OptimizationRoundReportTest
{
	/**
	 * Creates the test fixture.
	 */
	public OptimizationRoundReportTest()
	{
	}

	/**
	 * Verifies a development round preserves the formal value and labels its new evidence accurately.
	 */
	@Test
	public void preservesFormalClaimAfterDevelopment()
	{
		OptimizationRoundReport.Snapshot snapshot = new OptimizationRoundReport.Snapshot(
			OptionalDouble.of(0.928), 0.95, "v14",
			new OptimizationRoundReport.Usage(Optional.of(new MoneyAmount(new BigDecimal("23.10"),
				Currency.getInstance("USD"))), 12_000, 900, Duration.ofMinutes(17), 8),
			OptimizationRoundReport.EvidenceKind.DEVELOPMENT,
			List.of(new OptimizationRoundReport.LimitingFamily("Cross-file interaction", 12, 2,
				OptimizationRoundReport.FamilyStatus.BELOW_TARGET)),
			new OptimizationRoundReport.NextAction(OptimizationRoundReport.Action.CONTINUE_TARGET_ONLY,
				"Generate candidates for Cross-file interaction, then run paired development tests.",
				"This keeps the edit scope limited to the selected prompt file."));

		String report = OptimizationRoundReport.render(snapshot);

		requireThat(report, "report").contains("Current lower bound: 92.8%").
			contains("Target: above 95%").
			contains("Best validated version: v14").
			contains("Amount spent: USD $23.10").
			contains("Round evidence: development only; Current lower bound unchanged").
			contains("- Cross-file interaction: 12 formal trials, 2 failures, not yet above target").
			contains("Recommended next action: continue with this prompt file").
			contains("Why: This keeps the edit scope limited to the selected prompt file.");
	}

	/**
	 * Verifies unavailable formal evidence and provider price retain useful local usage evidence.
	 */
	@Test
	public void reportsUnavailableLowerBoundAndCost()
	{
		OptimizationRoundReport.Snapshot snapshot = new OptimizationRoundReport.Snapshot(OptionalDouble.empty(),
			0.95, "candidate-3", new OptimizationRoundReport.Usage(Optional.empty(), 1_200, 300,
				Duration.ofSeconds(83), 2), OptimizationRoundReport.EvidenceKind.FORMAL,
			List.of(), new OptimizationRoundReport.NextAction(OptimizationRoundReport.Action.FINISH_NOT_CERTIFIED,
				"Retain candidate-3 and record the terminal not-certified result.",
				"No formal evidence exists under the current contract."));

		String report = OptimizationRoundReport.render(snapshot);

		requireThat(report, "report").contains("Current lower bound: unavailable").
			contains("Amount spent: unavailable").
			contains("Observed usage: 1,200 input tokens, 300 output tokens, 2 operations, 1m 23s").
			contains("Recommended next action: finish as not certified");
	}

	/**
	 * Verifies a report cannot label an unsupported raw action.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Unsupported optimization report action: retry-everything")
	public void rejectsUnsupportedAction()
	{
		OptimizationRoundReport.Action.fromExternalValue("retry-everything");
	}
}
