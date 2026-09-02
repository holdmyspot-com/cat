package io.github.cowwoc.cat.common.optimization.cost;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Calculates exact metered provider cost for one explicitly named pricing unit.
 *
 * @param pricePerMillionUnits the exact price for 1,000,000 units
 * @param unit the provider usage category
 */
public record ProviderPrice(MoneyAmount pricePerMillionUnits, PricingUnit unit)
{
	private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

	/**
	 * Validates one provider price.
	 *
	 * @param pricePerMillionUnits the exact price for 1,000,000 units
	 * @param unit the provider usage category
	 */
	public ProviderPrice
	{
		Objects.requireNonNull(pricePerMillionUnits, "pricePerMillionUnits must not be null");
		Objects.requireNonNull(unit, "unit must not be null");
	}

	/**
	 * Calculates the exact cost of a nonnegative unit count.
	 *
	 * @param units the metered unit count
	 * @return the exact unrounded cost
	 */
	public MoneyAmount cost(long units)
	{
		if (units < 0)
			throw new IllegalArgumentException("units must not be negative");
		BigDecimal amount = pricePerMillionUnits.amount().multiply(BigDecimal.valueOf(units)).divide(ONE_MILLION);
		return new MoneyAmount(amount, pricePerMillionUnits.currency());
	}

	/**
	 * Names the semantically distinct provider usage categories priced per million units.
	 */
	public enum PricingUnit
	{
		INPUT_TOKENS,
		CACHED_INPUT_TOKENS,
		OUTPUT_TOKENS
	}
}
