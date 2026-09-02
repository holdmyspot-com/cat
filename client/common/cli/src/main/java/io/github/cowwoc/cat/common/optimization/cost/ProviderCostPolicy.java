package io.github.cowwoc.cat.common.optimization.cost;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Produces an exact metered provider-cost estimate or an explicit unavailable result for flat subscriptions.
 */
public final class ProviderCostPolicy
{
	private final BillingMode billingMode;
	private final Map<ProviderPrice.PricingUnit, ProviderPrice> prices;

	/**
	 * Creates one provider cost policy.
	 *
	 * @param billingMode the provider billing mode
	 * @param prices the complete metered price catalog, or an empty map for a flat subscription
	 */
	private ProviderCostPolicy(BillingMode billingMode, Map<ProviderPrice.PricingUnit, ProviderPrice> prices)
	{
		this.billingMode = billingMode;
		this.prices = prices;
	}

	/**
	 * Creates a metered API policy from a complete per-million-token catalog.
	 *
	 * @param prices one price for every supported usage category
	 * @return the validated metered policy
	 */
	public static ProviderCostPolicy metered(List<ProviderPrice> prices)
	{
		Objects.requireNonNull(prices, "prices must not be null");
		Map<ProviderPrice.PricingUnit, ProviderPrice> byUnit = new EnumMap<>(ProviderPrice.PricingUnit.class);
		Currency currency = null;
		for (ProviderPrice price : prices)
		{
			Objects.requireNonNull(price, "prices must not contain null");
			if (byUnit.putIfAbsent(price.unit(), price) != null)
				throw new IllegalArgumentException("Duplicate provider price: " + price.unit());
			if (currency == null)
				currency = price.pricePerMillionUnits().currency();
			else if (!currency.equals(price.pricePerMillionUnits().currency()))
				throw new IllegalArgumentException("Provider prices must use one currency");
		}
		if (byUnit.size() != ProviderPrice.PricingUnit.values().length)
			throw new IllegalArgumentException("Metered provider catalog must price every usage category");
		return new ProviderCostPolicy(BillingMode.METERED_API, Map.copyOf(byUnit));
	}

	/**
	 * Creates a flat-subscription policy that never invents a marginal monetary price.
	 *
	 * @return the flat-subscription policy
	 */
	public static ProviderCostPolicy flatSubscription()
	{
		return new ProviderCostPolicy(BillingMode.FLAT_SUBSCRIPTION, Map.of());
	}

	/**
	 * Estimates one operation's conservative maximum provider usage.
	 *
	 * @param usage the maximum token counts by provider category
	 * @return an exact monetary estimate or its explicit unavailable reason
	 */
	public Estimate estimate(Usage usage)
	{
		Objects.requireNonNull(usage, "usage must not be null");
		if (billingMode == BillingMode.FLAT_SUBSCRIPTION)
			return Estimate.unavailable(UnavailableReason.FLAT_SUBSCRIPTION, usage);

		ProviderPrice firstPrice = prices.values().iterator().next();
		Currency currency = firstPrice.pricePerMillionUnits().currency();
		MoneyAmount total = new MoneyAmount(BigDecimal.ZERO, currency);
		total = total.add(prices.get(ProviderPrice.PricingUnit.INPUT_TOKENS).cost(usage.inputTokens()));
		total = total.add(prices.get(ProviderPrice.PricingUnit.CACHED_INPUT_TOKENS).cost(usage.cachedInputTokens()));
		total = total.add(prices.get(ProviderPrice.PricingUnit.OUTPUT_TOKENS).cost(usage.outputTokens()));
		return Estimate.available(total, usage);
	}

	/**
	 * Names the internal provider billing mode.
	 */
	private enum BillingMode
	{
		METERED_API,
		FLAT_SUBSCRIPTION
	}

	/**
	 * Names why CAT cannot calculate a marginal monetary cost.
	 */
	public enum UnavailableReason
	{
		FLAT_SUBSCRIPTION,
		PRICE_CATALOG_UNAVAILABLE
	}

	/**
	 * Holds conservative provider usage by semantically distinct token category.
	 *
	 * @param inputTokens uncached input tokens
	 * @param cachedInputTokens cached input tokens
	 * @param outputTokens output tokens
	 */
	public record Usage(long inputTokens, long cachedInputTokens, long outputTokens)
	{
		/**
		 * Validates one conservative usage estimate.
		 *
		 * @param inputTokens uncached input tokens
		 * @param cachedInputTokens cached input tokens
		 * @param outputTokens output tokens
		 */
		public Usage
		{
			if (inputTokens < 0 || cachedInputTokens < 0 || outputTokens < 0)
				throw new IllegalArgumentException("Token estimates must not be negative");
			try
			{
				Math.addExact(Math.addExact(inputTokens, cachedInputTokens), outputTokens);
			}
			catch (ArithmeticException e)
			{
				throw new IllegalArgumentException("Combined token estimate exceeds the supported range", e);
			}
		}

		/**
		 * Returns the conservative total token count.
		 *
		 * @return the exact total
		 */
		public long totalTokens()
		{
			return Math.addExact(Math.addExact(inputTokens, cachedInputTokens), outputTokens);
		}
	}

	/**
	 * Holds either an exact monetary estimate or one explicit unavailable reason, plus the usage bound either way.
	 *
	 * @param availableCost the exact conservative monetary maximum
	 * @param unavailableReason why no monetary estimate exists
	 * @param usage the conservative provider usage maximum
	 */
	public record Estimate(Optional<MoneyAmount> availableCost, Optional<UnavailableReason> unavailableReason,
		Usage usage)
	{
		/**
		 * Validates one exclusive estimate result.
		 *
		 * @param availableCost the exact conservative monetary maximum
		 * @param unavailableReason why no monetary estimate exists
		 * @param usage the conservative provider usage maximum
		 */
		public Estimate
		{
			Objects.requireNonNull(availableCost, "availableCost must not be null");
			Objects.requireNonNull(unavailableReason, "unavailableReason must not be null");
			Objects.requireNonNull(usage, "usage must not be null");
			if (availableCost.isPresent() == unavailableReason.isPresent())
				throw new IllegalArgumentException("Estimate must contain exactly one cost result");
		}

		/**
		 * Creates an exact available estimate.
		 *
		 * @param cost the conservative monetary maximum
		 * @param usage the conservative provider usage maximum
		 * @return the available result
		 */
		public static Estimate available(MoneyAmount cost, Usage usage)
		{
			return new Estimate(Optional.of(cost), Optional.empty(), usage);
		}

		/**
		 * Creates an explicitly unavailable monetary estimate.
		 *
		 * @param reason the unavailable reason
		 * @param usage the conservative provider usage maximum
		 * @return the unavailable result
		 */
		public static Estimate unavailable(UnavailableReason reason, Usage usage)
		{
			return new Estimate(Optional.empty(), Optional.of(reason), usage);
		}
	}
}
