package io.github.cowwoc.cat.common.optimization.cost;

import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests calibrated cost-forecast applicability and explicit unavailable results.
 */
public final class CostForecastCatalogTest
{
	private static final Currency USD = Currency.getInstance("USD");
	private final CostForecastCatalog catalog = new CostForecastCatalog(List.of(new CostForecastCatalog.Envelope(
		"codex-luna-medium-v1", CostForecastCatalog.Harness.CODEX,
		new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM), "single-evaluator-v1",
		new CostForecastCatalog.IntBand(1, 20), new CostForecastCatalog.IntBand(0, 8),
		new CostForecastCatalog.IntBand(1, 5), new CostForecastCatalog.Range(money("1.25"), money("4.50")), 200)));

	/**
	 * Creates the test fixture.
	 */
	public CostForecastCatalogTest()
	{
	}

	/**
	 * Verifies a request inside every calibration band returns the qualified range.
	 */
	@Test
	public void forecastsInsideEnvelope()
	{
		CostForecastCatalog.Forecast forecast = catalog.forecast(request(12, 4, 3));

		requireThat(forecast.status(), "status").isEqualTo(CostForecastCatalog.Status.AVAILABLE);
		requireThat(forecast.range().orElseThrow().maximum(), "maximum").isEqualTo(money("4.50"));
		requireThat(forecast.envelopeIdentifier().orElseThrow(), "envelopeIdentifier").
			isEqualTo("codex-luna-medium-v1");
	}

	/**
	 * Verifies a family count outside calibration returns a precise unavailable reason before execution.
	 */
	@Test
	public void rejectsUncalibratedFamilyCount()
	{
		CostForecastCatalog.Forecast forecast = catalog.forecast(request(21, 4, 3));

		requireThat(forecast.status(), "status").isEqualTo(CostForecastCatalog.Status.COST_UNAVAILABLE);
		requireThat(forecast.unavailableDimensions(), "unavailableDimensions").
			containsExactly(List.of(CostForecastCatalog.Dimension.FAMILY_COUNT));
		requireThat(forecast.userMessage(), "userMessage").
			isEqualTo("No reliable certification cost forecast is available for family count 21; " +
				"the calibrated range is 1–20.");
	}

	/**
	 * Verifies an unsupported external harness value is rejected at the untyped boundary.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported harness: other.*")
	public void rejectsUnsupportedHarness()
	{
		CostForecastCatalog.Harness.fromExternalValue("other");
	}

	/**
	 * Creates an in-envelope request except for the supplied count values.
	 *
	 * @param familyCount leaf-family count
	 * @param interactionCount interaction-family count
	 * @param candidateCount candidate count
	 * @return the request
	 */
	private static CostForecastCatalog.Request request(int familyCount, int interactionCount, int candidateCount)
	{
		return new CostForecastCatalog.Request(CostForecastCatalog.Harness.CODEX,
			new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM), "single-evaluator-v1", familyCount,
			interactionCount, candidateCount);
	}

	/**
	 * Creates an exact USD amount.
	 *
	 * @param amount the decimal amount
	 * @return the money value
	 */
	private static MoneyAmount money(String amount)
	{
		return new MoneyAmount(new BigDecimal(amount), USD);
	}
}
