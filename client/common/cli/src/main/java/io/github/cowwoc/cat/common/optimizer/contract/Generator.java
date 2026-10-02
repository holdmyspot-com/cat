package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a test and variation values from a contract to produce a trial instance: its concrete turns, expected
 * result and draw identity. Rejects instances that their test definition does not permit.
 * <p>
 * A category groups tests; a cell pairs that group with a model and reasoning-effort profile. A draw picks one test
 * of the chosen cell's category with equal probability, then picks each variation value at its probability fixed
 * by the contract. The draw depends only on the stream's kind, seed and position, never on candidate identity or
 * outcomes.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent draws from one stream are serialized and use
 * consecutive positions.
 */
public final class Generator
{
	/**
	 * The identity that every instance records, which changes whenever a draw would select differently for the same
	 * stream position.
	 */
	public static final String IDENTITY = "contract-generator/1";
	private final Contract contract;

	/**
	 * Creates a generator.
	 *
	 * @param contract the confirmed contract
	 * @throws NullPointerException if {@code contract} is null
	 */
	public Generator(Contract contract)
	{
		requireThat(contract, "contract").isNotNull();
		this.contract = contract;
	}

	/**
	 * Draws one randomly sampled instance of a cell's category, eligible to measure compliance.
	 *
	 * @param cellId the identifier of the category/profile pair chosen before the draw
	 * @param stream the stream to draw from
	 * @return the validated instance
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the contract has no such cell
	 * @throws IllegalStateException if {@code stream} is closed
	 * @throws GeneratorValidationException if the drawn instance is not permitted by its test definition
	 */
	public TrialInstance draw(String cellId, DrawStream stream)
	{
		requireThat(cellId, "cellId").isNotNull();
		requireThat(stream, "stream").isNotNull();
		Cell cell = contract.findCell(cellId).orElseThrow(() -> new IllegalArgumentException(
			"The contract has no cell '" + cellId + "'"));
		Category category = contract.categoryOf(cell);

		TrialInstance instance;
		// A draw reads several values. Holding the stream's lock keeps another thread's draw from taking some of them.
		synchronized (stream)
		{
			DrawStream.State start = stream.state();
			List<TestDefinition> tests = category.tests();
			TestDefinition test = tests.get((int) (stream.nextDouble() * tests.size()));
			List<VariationChoice> choices = new ArrayList<>();
			Map<String, String> textByDimension = new LinkedHashMap<>();
			for (VariationRule rule : test.variations())
			{
				VariationValue value = select(rule, stream.nextDouble());
				choices.add(new VariationChoice(rule.dimension(), value.label(), value.probability()));
				textByDimension.put(rule.dimension(), value.text());
			}
			instance = new TrialInstance(cell.id(), category.id(), test.id(), choices,
				test.expandTurns(textByDimension), test.expectedBehaviour(), IDENTITY, start.kind(), start.position(),
				false);
		}
		validate(instance);
		return instance;
	}

	/**
	 * Selects the value whose cumulative probability range contains a uniform draw.
	 *
	 * @param rule the dimension to sample
	 * @param uniform a uniform value in [0, 1)
	 * @return the selected value, which is the last one if rounding leaves the draw above the cumulative sum
	 */
	static VariationValue select(VariationRule rule, double uniform)
	{
		List<VariationValue> values = rule.values();
		double cumulative = 0;
		for (VariationValue value : values)
		{
			cumulative += value.probability();
			if (uniform < cumulative)
				return value;
		}
		return values.get(values.size() - 1);
	}

	/**
	 * Marks an instance for a selected rerun, without drawing. Such targeted work is excluded from the randomly
	 * sampled evidence used to measure compliance.
	 *
	 * @param instance an instance that was drawn earlier
	 * @return the same instance, marked targeted
	 * @throws NullPointerException if {@code instance} is null
	 * @throws GeneratorValidationException if the instance is not permitted by its test definition
	 */
	public TrialInstance markTargeted(TrialInstance instance)
	{
		validate(instance);
		return new TrialInstance(instance.cellId(), instance.categoryId(), instance.testId(), instance.choices(),
			instance.turns(), instance.expectedBehaviour(), instance.generator(), instance.streamKind(),
			instance.streamPosition(), true);
	}

	/**
	 * Checks that an instance is one its test definition permits.
	 *
	 * @param instance the instance to check
	 * @throws NullPointerException if {@code instance} is null
	 * @throws GeneratorValidationException if the instance is outside its definition
	 */
	public void validate(TrialInstance instance)
	{
		requireThat(instance, "instance").isNotNull();
		if (!instance.generator().equals(IDENTITY))
		{
			throw new GeneratorValidationException("The instance was drawn by '" + instance.generator() +
				"', not by '" + IDENTITY + "'");
		}
		Cell cell = contract.findCell(instance.cellId()).orElseThrow(() ->
			new GeneratorValidationException("The contract has no cell '" + instance.cellId() + "'"));
		if (!cell.categoryId().equals(instance.categoryId()))
		{
			throw new GeneratorValidationException("The cell '" + cell.id() + "' belongs to the category '" +
				cell.categoryId() + "', not to '" + instance.categoryId() + "'");
		}
		Category category = contract.categoryOf(cell);
		TestDefinition test = category.tests().stream().filter(candidate -> candidate.id().equals(instance.testId())).
			findFirst().orElseThrow(() -> new GeneratorValidationException("The category '" + category.id() +
				"' has no test '" + instance.testId() + "'"));

		List<VariationRule> rules = test.variations();
		List<VariationChoice> choices = instance.choices();
		if (choices.size() != rules.size())
		{
			throw new GeneratorValidationException("The test '" + test.id() + "' has " + rules.size() +
				" variation dimensions, but the instance has " + choices.size() + " choices");
		}
		Map<String, String> textByDimension = new LinkedHashMap<>();
		for (int i = 0; i < rules.size(); ++i)
		{
			VariationRule rule = rules.get(i);
			VariationChoice choice = choices.get(i);
			if (!choice.dimension().equals(rule.dimension()))
			{
				throw new GeneratorValidationException("Choice " + i + " is for the dimension '" + choice.dimension() +
					"', but the test's dimension " + i + " is '" + rule.dimension() + "'");
			}
			VariationValue value = rule.values().stream().filter(candidate -> candidate.label().equals(choice.label())).
				findFirst().orElseThrow(() -> new GeneratorValidationException("The dimension '" + rule.dimension() +
					"' does not permit the value '" + choice.label() + "'"));
			if (Double.compare(choice.probability(), value.probability()) != 0)
			{
				throw new GeneratorValidationException("The value '" + value.label() + "' of dimension '" +
					rule.dimension() + "' has the frozen probability " + value.probability() +
					", but the instance records " + choice.probability());
			}
			textByDimension.put(rule.dimension(), value.text());
		}
		if (!instance.turns().equals(test.expandTurns(textByDimension)))
		{
			throw new GeneratorValidationException("The turns of the instance are not the test '" + test.id() +
				"' turns with the chosen values inserted");
		}
		if (!instance.expectedBehaviour().equals(test.expectedBehaviour()))
		{
			throw new GeneratorValidationException("The instance's expected behaviour differs from the test '" +
				test.id() + "' expected behaviour");
		}
	}
}
