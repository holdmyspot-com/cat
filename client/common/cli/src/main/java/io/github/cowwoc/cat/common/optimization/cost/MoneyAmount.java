package io.github.cowwoc.cat.common.optimization.cost;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Holds one exact, nonnegative monetary amount without applying a display-rounding policy.
 *
 * @param amount the exact nonnegative amount in major currency units
 * @param currency the ISO currency identity
 */
public record MoneyAmount(BigDecimal amount, Currency currency)
{
	/**
	 * Validates one exact monetary amount.
	 *
	 * @param amount the exact nonnegative amount in major currency units
	 * @param currency the ISO currency identity
	 */
	public MoneyAmount
	{
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		if (amount.signum() < 0)
			throw new IllegalArgumentException("amount must not be negative");
	}

	/**
	 * Adds an amount in the same currency.
	 *
	 * @param other the amount to add
	 * @return the exact sum
	 */
	public MoneyAmount add(MoneyAmount other)
	{
		verifyCurrency(other);
		return new MoneyAmount(amount.add(other.amount), currency);
	}

	/**
	 * Subtracts an amount in the same currency without permitting a negative result.
	 *
	 * @param other the amount to subtract
	 * @return the exact difference
	 */
	public MoneyAmount subtract(MoneyAmount other)
	{
		verifyCurrency(other);
		return new MoneyAmount(amount.subtract(other.amount), currency);
	}

	/**
	 * Verifies another amount has the same currency.
	 *
	 * @param other the other amount
	 */
	private void verifyCurrency(MoneyAmount other)
	{
		Objects.requireNonNull(other, "other must not be null");
		if (!currency.equals(other.currency))
			throw new IllegalArgumentException("Currency mismatch: " + currency + " != " + other.currency);
	}
}
