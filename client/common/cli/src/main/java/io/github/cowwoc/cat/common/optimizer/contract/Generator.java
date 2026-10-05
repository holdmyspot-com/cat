package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Draws a test and variation values from a contract to produce a trial instance: its concrete turns, expected
 * result and draw identity. Rejects instances that their test definition does not permit.
 * <p>
 * A category groups tests; a cell pairs that group with a model and reasoning-effort profile. A draw picks one test
 * of the chosen cell's category with equal probability. It then picks one combination of the test's key values from
 * the part that the stream's kind draws, at the combination's frozen probability divided by the part's total, and
 * picks each other variation value at its probability fixed by the contract. A development draw of a test that has
 * {@link AddedValues added combinations} gives them, together, half of the test's draws in equal shares, and the
 * confirmed development combinations the other half. The draw depends only on the stream's kind, seed and position
 * and on the added combinations, never on candidate identity; formal and baseline draws never use added
 * combinations.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent draws from one stream are serialized and use
 * consecutive positions.
 */
public final class Generator
{
	/**
	 * The identity that every instance records, which changes whenever a draw would select differently for the same
	 * stream position and added combinations.
	 */
	public static final String IDENTITY = "contract-generator/2";
	private final Contract contract;
	private final AddedValues added;

	/**
	 * Creates a generator of a contract with no added development values.
	 *
	 * @param contract the confirmed contract
	 * @throws NullPointerException if {@code contract} is null
	 */
	public Generator(Contract contract)
	{
		this(contract, AddedValues.NONE);
	}

	/**
	 * Creates a generator.
	 *
	 * @param contract the confirmed contract
	 * @param added the key-value combinations added to the development part of the contract's tests
	 * @throws NullPointerException if any argument is null
	 */
	public Generator(Contract contract, AddedValues added)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(added, "added").isNotNull();
		this.contract = contract;
		this.added = added;
	}

	/**
	 * Returns the text of each variation value that a valid instance chose, including added development values.
	 *
	 * @param instance the instance
	 * @return the text of each chosen value, by dimension
	 * @throws NullPointerException if {@code instance} is null
	 * @throws GeneratorValidationException if the instance is not valid
	 */
	public Map<String, String> texts(TrialInstance instance)
	{
		validate(instance);
		TestDefinition test = contract.findTest(instance.testId()).orElseThrow();
		Map<String, String> textByDimension = new LinkedHashMap<>();
		for (Map.Entry<String, VariationValue> entry : values(test, instance.choices()).entrySet())
			textByDimension.put(entry.getKey(), entry.getValue().text());
		return textByDimension;
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
	 * @throws GeneratorValidationException if the drawn test has no key-value combination in the stream's part, or
	 *   the drawn instance is not permitted by its test definition
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
			Part part = start.kind().part();
			List<Split.KeyCombination> combinations = contract.split().combinations(test, part);
			if (combinations.isEmpty())
			{
				throw new GeneratorValidationException("The test '" + test.id() + "' has no key-value combination in " +
					"the " + part + " part");
			}
			Map<String, VariationValue> keyValueByDimension = new HashMap<>();
			List<VariationRule> keyRules = test.keyRules();
			List<VariationValue> keyValues = selectKeyValues(test, part, combinations, stream.nextDouble());
			for (int i = 0; i < keyRules.size(); ++i)
				keyValueByDimension.put(keyRules.get(i).dimension(), keyValues.get(i));
			instance = instantiate(cell, category, test, keyValueByDimension, stream, start);
		}
		validate(instance);
		return instance;
	}

	/**
	 * Draws one instance of a test on given key-value combinations, for a trial that tries whether those combinations
	 * reproduce a failure. The draw picks one of the combinations with equal probability and each other variation
	 * value at its probability fixed by the contract.
	 *
	 * @param cellId the identifier of the cell that the trial runs in
	 * @param testId the identifier of a test of the cell's category
	 * @param combinations the test's combinations that this generator's added values hold
	 * @param stream the development stream to draw from
	 * @return the validated instance
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the contract has no such cell, the cell's category has no such test,
	 *   {@code combinations} is empty or holds a combination of another test, or {@code stream} is not a
	 *   development stream
	 * @throws IllegalStateException if {@code stream} is closed
	 * @throws GeneratorValidationException if the drawn instance is not permitted by its test definition
	 */
	public TrialInstance drawCombination(String cellId, String testId, List<AddedCombination> combinations,
		DrawStream stream)
	{
		requireThat(cellId, "cellId").isNotNull();
		requireThat(testId, "testId").isNotNull();
		requireThat(combinations, "combinations").isNotEmpty();
		requireThat(stream, "stream").isNotNull();
		Cell cell = contract.findCell(cellId).orElseThrow(() -> new IllegalArgumentException(
			"The contract has no cell '" + cellId + "'"));
		Category category = contract.categoryOf(cell);
		TestDefinition test = category.tests().stream().filter(candidate -> candidate.id().equals(testId)).
			findFirst().orElseThrow(() -> new IllegalArgumentException("The category '" + category.id() +
				"' has no test '" + testId + "'"));
		for (AddedCombination combination : combinations)
			requireThat(combination.testId(), "combination.testId()").isEqualTo(testId, "testId");

		TrialInstance instance;
		synchronized (stream)
		{
			DrawStream.State start = stream.state();
			requireThat(start.kind(), "stream.kind()").isEqualTo(DrawStream.Kind.DEVELOPMENT);
			int index = Math.min((int) (stream.nextDouble() * combinations.size()), combinations.size() - 1);
			List<VariationValue> keyValues = combinations.get(index).values();
			Map<String, VariationValue> keyValueByDimension = new HashMap<>();
			List<VariationRule> keyRules = test.keyRules();
			for (int i = 0; i < keyRules.size(); ++i)
				keyValueByDimension.put(keyRules.get(i).dimension(), keyValues.get(i));
			instance = instantiate(cell, category, test, keyValueByDimension, stream, start);
		}
		validate(instance);
		return instance;
	}

	/**
	 * Picks every variation value that is not a key value and builds the instance.
	 *
	 * @param cell the cell that the instance runs in
	 * @param category the cell's category
	 * @param test the drawn test definition
	 * @param keyValueByDimension the chosen value of each key dimension
	 * @param stream the stream to draw the other values from, whose lock the caller holds
	 * @param start the stream's state before the draw
	 * @return the instance, not yet validated
	 */
	private static TrialInstance instantiate(Cell cell, Category category, TestDefinition test,
		Map<String, VariationValue> keyValueByDimension, DrawStream stream, DrawStream.State start)
	{
		List<VariationChoice> choices = new ArrayList<>();
		Map<String, String> textByDimension = new LinkedHashMap<>();
		for (VariationRule rule : test.variations())
		{
			VariationValue value = keyValueByDimension.get(rule.dimension());
			if (value == null)
				value = select(rule, stream.nextDouble());
			choices.add(new VariationChoice(rule.dimension(), value.label(), value.probability()));
			textByDimension.put(rule.dimension(), value.text());
		}
		return new TrialInstance(cell.id(), category.id(), test.id(), choices, test.expandTurns(textByDimension),
			test.expectedBehaviour(), IDENTITY, start.kind(), start.position(), false);
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
	 * Selects the key values of a draw. A development draw of a test with added combinations gives them, together,
	 * half of the draws in equal shares, and the confirmed combinations the other half by their frozen
	 * probabilities.
	 *
	 * @param test the drawn test definition
	 * @param part the part that the stream draws
	 * @param combinations the confirmed combinations of the part, which must not be empty
	 * @param uniform a uniform value in [0, 1)
	 * @return the value of each key dimension, in the test's key-dimension order
	 */
	private List<VariationValue> selectKeyValues(TestDefinition test, Part part,
		List<Split.KeyCombination> combinations, double uniform)
	{
		List<AddedCombination> testAdded;
		if (part == Part.DEVELOPMENT)
			testAdded = added.of(test.id());
		else
			testAdded = List.of();
		if (testAdded.isEmpty())
			return select(combinations, uniform).values();
		if (uniform < 0.5)
			return select(combinations, uniform * 2).values();
		int index = Math.min((int) ((uniform - 0.5) * 2 * testAdded.size()), testAdded.size() - 1);
		return testAdded.get(index).values();
	}

	/**
	 * Selects the key-value combination whose cumulative range, scaled to the combinations' total probability,
	 * contains a uniform draw.
	 *
	 * @param combinations the combinations of one part, which must not be empty
	 * @param uniform      a uniform value in [0, 1)
	 * @return the selected combination, which is the last one if rounding leaves the draw above the cumulative sum
	 */
	private static Split.KeyCombination select(List<Split.KeyCombination> combinations, double uniform)
	{
		double total = 0;
		for (Split.KeyCombination combination : combinations)
			total += combination.probability();
		double target = uniform * total;
		double cumulative = 0;
		for (Split.KeyCombination combination : combinations)
		{
			cumulative += combination.probability();
			if (target < cumulative)
				return combination;
		}
		return combinations.getLast();
	}

	/**
	 * Marks an instance for a selected rerun, without drawing. Such targeted work is excluded from the randomly
	 * sampled evidence used to measure compliance, and uses development instances only.
	 *
	 * @param instance an instance that was drawn earlier
	 * @return the same instance, marked targeted
	 * @throws NullPointerException if {@code instance} is null
	 * @throws GeneratorValidationException if the instance is held-out or not permitted by its test definition
	 */
	public TrialInstance markTargeted(TrialInstance instance)
	{
		validate(instance);
		Part part = instance.streamKind().part();
		if (part != Part.DEVELOPMENT)
		{
			throw new GeneratorValidationException("A targeted run uses development instances only, but the instance " +
				"is " + part + ": a " + instance.streamKind().name().toLowerCase(Locale.ROOT) + " stream drew it");
		}
		return new TrialInstance(instance.cellId(), instance.categoryId(), instance.testId(), instance.choices(),
			instance.turns(), instance.expectedBehaviour(), instance.generator(), instance.streamKind(),
			instance.streamPosition(), true);
	}

	/**
	 * Checks that an instance is one its test definition permits.
	 *
	 * @param instance the instance to check
	 * @throws NullPointerException if {@code instance} is null
	 * @throws GeneratorValidationException if the instance is outside its definition, or its key values belong to a
	 *   part that its stream does not draw
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

		Map<String, VariationValue> valueByDimension = values(test, instance.choices());
		Map<String, String> textByDimension = new LinkedHashMap<>();
		Map<String, String> labelByDimension = new HashMap<>();
		for (Map.Entry<String, VariationValue> entry : valueByDimension.entrySet())
		{
			textByDimension.put(entry.getKey(), entry.getValue().text());
			labelByDimension.put(entry.getKey(), entry.getValue().label());
		}
		Part streamPart = instance.streamKind().part();
		if (isAdded(test, labelByDimension))
		{
			if (streamPart != Part.DEVELOPMENT)
			{
				throw new GeneratorValidationException("The key values of the instance are added development values, " +
					"which belong to the development part only, but a " +
					instance.streamKind().name().toLowerCase(Locale.ROOT) + " stream draws from the " + streamPart +
					" part");
			}
		}
		else
		{
			Part part = contract.split().partOf(test, labelByDimension);
			if (part != streamPart)
			{
				throw new GeneratorValidationException("The key values of the instance belong to the " + part +
					" part, but a " + instance.streamKind().name().toLowerCase(Locale.ROOT) +
					" stream draws from the " + streamPart + " part");
			}
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

	/**
	 * Returns the variation value that each choice names, among the test's confirmed values and the values added to
	 * its key dimensions.
	 *
	 * @param test the test definition
	 * @param choices the instance's choices
	 * @return the chosen value of each dimension, in the test's dimension order
	 * @throws GeneratorValidationException if the choices do not name the test's dimensions in order, a choice names
	 *   a value that its dimension does not permit, or a choice records a probability other than its value's
	 */
	private Map<String, VariationValue> values(TestDefinition test, List<VariationChoice> choices)
	{
		List<VariationRule> rules = test.variations();
		if (choices.size() != rules.size())
		{
			throw new GeneratorValidationException("The test '" + test.id() + "' has " + rules.size() +
				" variation dimensions, but the instance has " + choices.size() + " choices");
		}
		Map<String, VariationValue> valueByDimension = new LinkedHashMap<>();
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
				findFirst().or(() -> findAdded(test, rule.dimension(), choice.label())).
				orElseThrow(() -> new GeneratorValidationException("The dimension '" + rule.dimension() +
					"' does not permit the value '" + choice.label() + "'"));
			if (Double.compare(choice.probability(), value.probability()) != 0)
			{
				throw new GeneratorValidationException("The value '" + value.label() + "' of dimension '" +
					rule.dimension() + "' has the frozen probability " + value.probability() +
					", but the instance records " + choice.probability());
			}
			valueByDimension.put(rule.dimension(), value);
		}
		return valueByDimension;
	}

	/**
	 * Finds a value that was added to a key dimension of a test.
	 *
	 * @param test the test definition
	 * @param dimension the dimension
	 * @param label the value's label
	 * @return the value, or empty if the dimension is not a key dimension or no added combination holds the label
	 */
	private Optional<VariationValue> findAdded(TestDefinition test, String dimension, String label)
	{
		int index = keyIndex(test, dimension);
		if (index < 0)
			return Optional.empty();
		for (AddedCombination combination : added.of(test.id()))
		{
			VariationValue value = combination.values().get(index);
			if (value.label().equals(label))
				return Optional.of(value);
		}
		return Optional.empty();
	}

	/**
	 * Returns the position of a dimension among a test's key dimensions.
	 *
	 * @param test the test definition
	 * @param dimension the dimension
	 * @return the position, or {@code -1} if the dimension is not a key dimension
	 */
	private static int keyIndex(TestDefinition test, String dimension)
	{
		List<VariationRule> keyRules = test.keyRules();
		for (int i = 0; i < keyRules.size(); ++i)
		{
			if (keyRules.get(i).dimension().equals(dimension))
				return i;
		}
		return -1;
	}

	/**
	 * Indicates whether an instance's key values are an added combination of its test.
	 *
	 * @param test the test definition
	 * @param labelByDimension the label of each chosen value, by dimension
	 * @return {@code true} if the key values are an added combination
	 * @throws GeneratorValidationException if the key values hold an added value but are no added combination
	 */
	private boolean isAdded(TestDefinition test, Map<String, String> labelByDimension)
	{
		List<String> keyLabels = new ArrayList<>();
		boolean holdsAddedValue = false;
		for (VariationRule rule : test.keyRules())
		{
			String label = labelByDimension.get(rule.dimension());
			keyLabels.add(label);
			if (rule.values().stream().noneMatch(value -> value.label().equals(label)))
				holdsAddedValue = true;
		}
		if (!holdsAddedValue)
			return false;
		for (AddedCombination combination : added.of(test.id()))
		{
			if (combination.values().stream().map(VariationValue::label).toList().equals(keyLabels))
				return true;
		}
		throw new GeneratorValidationException("The key values " + keyLabels + " of the instance hold an added " +
			"development value, but no combination added to the test '" + test.id() + "' holds them together");
	}
}
