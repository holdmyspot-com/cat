package io.github.cowwoc.cat.common.optimization.cost;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.util.Currency;
import org.testng.annotations.Test;

/**
 * Tests exact provider pricing and currency boundaries.
 */
public final class ProviderPriceTest
{
	/**
	 * Creates the test fixture.
	 */
	public ProviderPriceTest()
	{
	}

	/**
	 * Verifies per-million pricing preserves every decimal digit without an implicit display rounding boundary.
	 */
	@Test
	public void calculatesExactProviderCost()
	{
		ProviderPrice price = new ProviderPrice(money("2.500000001", "USD"),
			ProviderPrice.PricingUnit.INPUT_TOKENS);

		MoneyAmount result = price.cost(8_345_678_901_234_567_890L);

		requireThat(result.amount().toPlainString(), "amount").isEqualTo("20864197261432.09862623456789");
		requireThat(result.currency().getCurrencyCode(), "currency").isEqualTo("USD");
	}

	/**
	 * Verifies amounts in different currencies cannot be combined without an explicit conversion contract.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Currency mismatch: USD != CAD.*")
	public void rejectsMixedCurrencies()
	{
		money("1.00", "USD").add(money("1.00", "CAD"));
	}

	/**
	 * Verifies subtraction cannot turn a nonnegative spending amount into an unrelated negative-money domain.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*amount must not be negative.*")
	public void rejectsANegativeDifference()
	{
		money("1.00", "USD").subtract(money("2.00", "USD"));
	}

	/**
	 * Creates an exact monetary fixture.
	 *
	 * @param amount the exact decimal amount
	 * @param currencyCode the ISO currency code
	 * @return the typed amount
	 */
	private static MoneyAmount money(String amount, String currencyCode)
	{
		return new MoneyAmount(new BigDecimal(amount), Currency.getInstance(currencyCode));
	}
}
