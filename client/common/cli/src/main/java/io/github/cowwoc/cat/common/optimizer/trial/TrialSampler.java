package io.github.cowwoc.cat.common.optimizer.trial;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractValidator;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.VariationDimension;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws concrete test instances from a confirmed contract before attaching a candidate.
 */
public final class TrialSampler
{
	private static final String GENERATOR_ID = "cat-contract-sampler-v1";
	private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{[^{}]+}}");

	private final ConfirmedContract contract;
	private final Random development;
	private final Random formal;

	/**
	 * Opens separate development and formal random streams for the confirmed contract.
	 *
	 * @param contract frozen test definitions and variation probabilities
	 */
	public TrialSampler(ConfirmedContract contract)
	{
		this(contract, new SecureRandom(), new SecureRandom());
	}

	/**
	 * Supplies separate streams for a controlled sampler check.
	 *
	 * @param contract frozen test definitions and variation probabilities
	 * @param development development draw stream
	 * @param formal formal draw stream
	 */
	TrialSampler(ConfirmedContract contract, Random development, Random formal)
	{
		this.contract = Objects.requireNonNull(contract, "contract");
		this.development = Objects.requireNonNull(development, "development");
		this.formal = Objects.requireNonNull(formal, "formal");
		if (development == formal)
			throw new IllegalArgumentException("Development and formal draws require separate random streams");
		ContractValidator.validateConfirmed(contract);
	}

	/**
	 * Draws one independent instance for the named confirmed category.
	 *
	 * @param purpose development or formal stream
	 * @param categoryId confirmed category to sample
	 * @return concrete instance without a candidate assignment
	 */
	public SampledTrialInstance sample(EvidencePurpose purpose, String categoryId)
	{
		Objects.requireNonNull(purpose, "purpose");
		if (categoryId == null || categoryId.isBlank())
			throw new IllegalArgumentException("Confirmed category must be nonblank");
		Random random = switch (purpose)
		{
			case DEVELOPMENT -> development;
			case FORMAL -> formal;
			case VALIDATION -> throw new IllegalArgumentException(
				"Trial sampling requires development or formal purpose");
		};
		List<TestDefinition> definitions = contract.tests().stream().filter(definition ->
			definition.categoryId().equals(categoryId)).toList();
		if (definitions.isEmpty())
			throw new IllegalArgumentException("Unknown confirmed category: " + categoryId);
		TestDefinition selected = definitions.get(random.nextInt(definitions.size()));

		List<SampledTrialInstance.SelectedVariation> variations = new ArrayList<>();
		Map<String, String> replacements = new HashMap<>();
		for (VariationDimension dimension: selected.variationDimensions())
		{
			VariationValue value = drawVariation(dimension, random);
			variations.add(new SampledTrialInstance.SelectedVariation(dimension.id(), dimension.placeholder(),
				value.value(), value.probability()));
			replacements.put(dimension.placeholder(), value.value());
		}
		List<String> turns = selected.turnTemplates().stream().map(template -> render(template, replacements)).toList();
		return new SampledTrialInstance(UUID.randomUUID().toString(), purpose, GENERATOR_ID,
			contract.contractId(), selected, variations, turns);
	}

	/**
	 * Checks that a retained draw matches a confirmed definition and its literal substitutions.
	 *
	 * @param contract frozen definitions and probabilities
	 * @param instance retained draw to check
	 */
	public static void verifySelection(ConfirmedContract contract, SampledTrialInstance instance)
	{
		Objects.requireNonNull(contract, "contract");
		Objects.requireNonNull(instance, "instance");
		if (!instance.contractId().equals(contract.contractId()) ||
			!instance.generatorId().equals(GENERATOR_ID) ||
			contract.tests().stream().noneMatch(definition -> definition.equals(instance.definition())))
			throw new IllegalArgumentException("Retained selection differs from the confirmed definition");
		List<VariationDimension> dimensions = instance.definition().variationDimensions();
		if (instance.variations().size() != dimensions.size())
			throw new IllegalArgumentException("Retained selection has a different variation shape");
		Map<String, String> replacements = new HashMap<>();
		for (int index = 0; index < dimensions.size(); ++index)
		{
			VariationDimension dimension = dimensions.get(index);
			SampledTrialInstance.SelectedVariation selected = instance.variations().get(index);
			if (!selected.dimensionId().equals(dimension.id()) ||
				!selected.placeholder().equals(dimension.placeholder()) ||
				dimension.values().stream().noneMatch(value -> value.value().equals(selected.value()) &&
					value.probability().equals(selected.probability())))
				throw new IllegalArgumentException("Retained selection contains an unapproved variation");
			replacements.put(dimension.placeholder(), selected.value());
		}
		List<String> expectedTurns = instance.definition().turnTemplates().stream().map(template ->
			render(template, replacements)).toList();
		if (!instance.turns().equals(expectedTurns))
			throw new IllegalArgumentException("Retained selection has different concrete turns");
	}

	/**
	 * Selects one approved value using the confirmed exact decimal probabilities.
	 *
	 * @param dimension frozen variation dimension
	 * @param random purpose-specific draw stream
	 * @return selected value with its confirmed probability
	 */
	private static VariationValue drawVariation(VariationDimension dimension, Random random)
	{
		int scale = dimension.values().stream().mapToInt(value -> value.probability().scale()).max().orElse(0);
		scale = Math.max(0, scale);
		BigInteger total = BigInteger.TEN.pow(scale);
		BigInteger ticket = drawBelow(total, random);
		BigInteger cumulative = BigInteger.ZERO;
		for (VariationValue value: dimension.values())
		{
			cumulative = cumulative.add(value.probability().movePointRight(scale).toBigIntegerExact());
			if (ticket.compareTo(cumulative) < 0)
				return value;
		}
		throw new IllegalStateException("Confirmed variation probabilities do not cover the draw");
	}

	/**
	 * Draws an unbiased integer below the exact probability denominator.
	 *
	 * @param upperExclusive positive upper bound
	 * @param random purpose-specific draw stream
	 * @return uniformly drawn integer below the bound
	 */
	private static BigInteger drawBelow(BigInteger upperExclusive, Random random)
	{
		BigInteger candidate;
		do
		{
			candidate = new BigInteger(upperExclusive.bitLength(), random);
		}
		while (candidate.compareTo(upperExclusive) >= 0);
		return candidate;
	}

	/**
	 * Fills only tokens in the original template, treating selected values as literal text.
	 *
	 * @param template one confirmed turn template
	 * @param replacements selected value by confirmed placeholder
	 * @return concrete turn without rescanning inserted text
	 */
	private static String render(String template, Map<String, String> replacements)
	{
		Matcher matcher = TEMPLATE_TOKEN.matcher(template);
		StringBuilder rendered = new StringBuilder();
		while (matcher.find())
		{
			String value = replacements.get(matcher.group());
			if (value == null)
				throw new IllegalStateException("Confirmed template contains an unknown variation placeholder");
			matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(rendered);
		return rendered.toString();
	}
}
