package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link Generator}.
 */
public final class GeneratorTest
{
	private static final long SEED = 7_001L;
	/**
	 * The salt of the template contracts.
	 */
	private static final long TEMPLATE_SALT = 42L;
	/**
	 * The category, and so the cell, of the template contracts.
	 */
	private static final String TEMPLATE_CELL = "trivia";
	/**
	 * The test of the template contracts.
	 */
	private static final String TEMPLATE_TEST = "capital";

	/**
	 * Creates the test fixture.
	 */
	public GeneratorTest()
	{
	}

	/**
	 * Draws until the generator picks the test that has variations.
	 *
	 * @param generator the generator to draw from
	 * @return an instance of {@code styled-summary}
	 */
	private static TrialInstance styledInstance(Generator generator)
	{
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		for (int i = 0; i < 100; ++i)
		{
			TrialInstance instance = generator.draw("formatting", stream);
			if (instance.testId().equals("styled-summary"))
				return instance;
		}
		throw new AssertionError("No styled-summary instance in 100 draws");
	}

	/**
	 * Verifies that a drawn instance names its test, replaces every placeholder, keeps the expected behaviour and
	 * records the generator, stream and position.
	 */
	@Test
	public void drawRecordsConcreteInstance()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		TrialInstance instance = styledInstance(generator);

		TrialInstance replayed = generator.draw("formatting",
			DrawStream.resume(new DrawStream.State(DrawStream.Kind.DEVELOPMENT, SEED, instance.streamPosition(), true)));

		requireThat(replayed, "replayed").isEqualTo(instance);
		requireThat(instance.generator(), "generator").isEqualTo(Generator.IDENTITY);
		requireThat(instance.streamKind(), "streamKind").isEqualTo(DrawStream.Kind.DEVELOPMENT);
		requireThat(instance.categoryId(), "categoryId").isEqualTo("formatting");
		requireThat(instance.choices().size(), "choices.size()").isEqualTo(2);
		requireThat(instance.choices().get(0).dimension(), "firstDimension").isEqualTo("tone");
		requireThat(instance.choices().get(1).dimension(), "secondDimension").isEqualTo("audience");
		for (String turn : instance.turns())
			requireThat(turn, "turn").doesNotContain("{{");
		requireThat(instance.turns().size(), "turns.size()").isEqualTo(2);
		requireThat(instance.expectedBehaviour(), "expectedBehaviour").isEqualTo(
			"The summary uses the requested tone and the shortened text suits the reader.");
		requireThat(instance.isRepresentative(), "representative").isTrue();
	}

	/**
	 * Verifies that a draw advances the stream by the one value that picks the test, the one value that picks the key
	 * values and one value for each other dimension of the picked test, so a persisted position identifies the next
	 * draw.
	 */
	@Test
	public void drawAdvancesStreamByItsOwnValues()
	{
		// Requirement: R-C4
		Contract contract = templateContract(List.of(0.25, 0.25, 0.25, 0.25), List.of("country"));
		Generator generator = new Generator(contract);
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		long expectedPosition = 0;
		for (int i = 0; i < 30; ++i)
		{
			TrialInstance instance = generator.draw(TEMPLATE_CELL, stream);
			requireThat(instance.streamPosition(), "streamPosition").isEqualTo(expectedPosition);
			// The test, the key values and the workspace
			expectedPosition += 3;
			requireThat(stream.state().position(), "position").isEqualTo(expectedPosition);
		}
	}

	/**
	 * Verifies that cumulative probability boundaries select the next value at each boundary and that rounding
	 * below one falls back to the last value.
	 */
	@Test
	public void selectsVariationAtProbabilityBoundaries()
	{
		// Requirement: R-C4
		VariationValue formal = new VariationValue("formal", "Formal.", 0.5);
		VariationValue casual = new VariationValue("casual", "Casual.", 0.3);
		VariationValue terse = new VariationValue("terse", "Terse.", 0.2);
		VariationRule tone = new VariationRule("tone", List.of(formal, casual, terse));

		requireThat(Generator.select(tone, 0), "atZero").isEqualTo(formal);
		requireThat(Generator.select(tone, 0.4999), "belowCasual").isEqualTo(formal);
		requireThat(Generator.select(tone, 0.5), "atCasual").isEqualTo(casual);
		requireThat(Generator.select(tone, 0.7999), "belowTerse").isEqualTo(casual);
		requireThat(Generator.select(tone, 0.8), "atTerse").isEqualTo(terse);
		requireThat(Generator.select(tone, Math.nextDown(1.0)), "belowOne").isEqualTo(terse);

		VariationValue roundedTerse = new VariationValue("terse", "Terse.", 0.2 - 1e-10);
		VariationRule rounded = new VariationRule("tone", List.of(formal, casual, roundedTerse));
		double cumulative = formal.probability() + casual.probability() + roundedTerse.probability();
		double uniform = Math.nextDown(1.0);
		requireThat(uniform, "uniform").isGreaterThan(cumulative);
		requireThat(Generator.select(rounded, uniform), "roundingFallback").isEqualTo(roundedTerse);
	}

	/**
	 * Verifies that a repeated draw of the same concrete instance within a part is legitimate: a part with few
	 * key-value combinations yields identical instances, and a stream resumed at an earlier position yields the
	 * instance again. Neither is rejected.
	 */
	@Test
	public void repeatedInstancesAreAllowed()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		List<List<String>> turnSequences = new ArrayList<>();
		for (int i = 0; i < 50; ++i)
			turnSequences.add(generator.draw("safety", stream).turns());
		requireThat(turnSequences.stream().distinct().count(), "distinctTurnSequences").
			isLessThan((long) turnSequences.size());

		DrawStream first = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		DrawStream second = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		requireThat(generator.draw("safety", second), "second").isEqualTo(generator.draw("safety", first));
	}

	/**
	 * Verifies that marking an instance as targeted keeps its identity and stream position, never draws, and
	 * removes it from representative evidence.
	 */
	@Test
	public void markTargetedBeforeExecution()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		TrialInstance drawn = generator.draw("safety", stream);
		DrawStream.State before = stream.state();

		TrialInstance targeted = generator.markTargeted(drawn);

		requireThat(targeted.targeted(), "targeted").isTrue();
		requireThat(targeted.isRepresentative(), "representative").isFalse();
		requireThat(targeted, "targeted").isEqualTo(new TrialInstance(drawn.cellId(), drawn.categoryId(), drawn.testId(),
			drawn.choices(), drawn.turns(), drawn.expectedBehaviour(), drawn.generator(), drawn.streamKind(),
			drawn.streamPosition(), true));
		requireThat(stream.state(), "stream.state()").isEqualTo(before);
		requireThat(drawn.isRepresentative(), "drawnRepresentative").isTrue();
	}

	/**
	 * Verifies that drawing from the formal stream before it is opened is refused and leaves the stream untouched.
	 */
	@Test
	public void formalDrawsRequireOpenStream()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream formal = DrawStream.create(DrawStream.Kind.FORMAL, SEED);
		boolean refused = false;
		try
		{
			generator.draw("formatting", formal);
		}
		catch (IllegalStateException _)
		{
			refused = true;
		}
		requireThat(refused, "refused").isTrue();
		requireThat(formal.state().position(), "position").isEqualTo(0L);

		formal.open();
		TrialInstance instance = generator.draw("formatting", formal);

		requireThat(instance.streamKind(), "streamKind").isEqualTo(DrawStream.Kind.FORMAL);
		requireThat(instance.isRepresentative(), "representative").isTrue();
	}

	/**
	 * Verifies that a draw from a cell that the contract does not define is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*unknown.*")
	public void drawRejectsUnknownCell()
	{
		// Requirement: R-C4
		new Generator(ContractFixtures.contract()).draw("unknown",
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED));
	}

	/**
	 * Verifies that an instance that a definition does not permit is refused, whatever the way in which it departs
	 * from the contract.
	 *
	 * @return the description of the departure, and the instance that departs
	 */
	@DataProvider(name = "outOfContractInstances")
	public Object[][] outOfContractInstances()
	{
		Generator generator = new Generator(ContractFixtures.contract());
		TrialInstance base = styledInstance(generator);
		List<VariationChoice> choices = base.choices();
		VariationChoice tone = choices.get(0);
		VariationChoice audience = choices.get(1);
		List<VariationChoice> unlisted = List.of(new VariationChoice("tone", "sarcastic", 0.5), audience);
		List<VariationChoice> reweighted = List.of(new VariationChoice("tone", tone.label(), 0.99), audience);
		return new Object[][]
			{
				{"unknown category", with(base, "nowhere", base.testId(), choices, base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"unknown test", with(base, base.categoryId(), "no-such-test", choices, base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"test of another category", with(base, "safety", base.testId(), choices, base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"category other than the cell's", new TrialInstance(base.cellId(), "safety", base.testId(), choices,
					base.turns(), base.expectedBehaviour(), base.generator(), base.streamKind(), base.streamPosition(),
					base.targeted())},
				{"missing dimension", with(base, base.categoryId(), base.testId(), List.of(tone), base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"extra dimension", with(base, base.categoryId(), base.testId(),
					List.of(tone, audience, new VariationChoice("mood", "calm", 1.0)), base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"dimensions out of order", with(base, base.categoryId(), base.testId(), List.of(audience, tone),
					base.turns(), base.expectedBehaviour(), base.generator())},
				{"unpermitted value", with(base, base.categoryId(), base.testId(), unlisted, base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"value with a different probability", with(base, base.categoryId(), base.testId(), reweighted,
					base.turns(), base.expectedBehaviour(), base.generator())},
				{"turns that do not match the values", with(base, base.categoryId(), base.testId(), choices,
					List.of("Summarize the notes.", "Shorten it."), base.expectedBehaviour(), base.generator())},
				{"extra turn", with(base, base.categoryId(), base.testId(), choices,
					List.of(base.turns().get(0), base.turns().get(1), "One more thing."), base.expectedBehaviour(),
					base.generator())},
				{"changed expected behaviour", with(base, base.categoryId(), base.testId(), choices, base.turns(),
					"Anything is fine.", base.generator())},
				{"another generator", with(base, base.categoryId(), base.testId(), choices, base.turns(),
					base.expectedBehaviour(), "other-generator/9")}
			};
	}

	/**
	 * Copies an instance with every field except the stream fields replaced.
	 *
	 * @param base the instance that supplies the stream fields
	 * @param categoryId the category
	 * @param testId the test
	 * @param choices the choices
	 * @param turns the turns
	 * @param expectedBehaviour the expected behaviour
	 * @param generator the generator identity
	 * @return the new instance
	 */
	private static TrialInstance with(TrialInstance base, String categoryId, String testId,
		List<VariationChoice> choices, List<String> turns, String expectedBehaviour, String generator)
	{
		return new TrialInstance(categoryId, categoryId, testId, choices, turns, expectedBehaviour, generator,
			base.streamKind(), base.streamPosition(), base.targeted());
	}

	/**
	 * Verifies that the generator emits only instances its test definition permits: validation rejects each
	 * departure from the definition.
	 *
	 * @param description the way in which the instance departs from the contract
	 * @param instance the instance that departs
	 */
	@Test(dataProvider = "outOfContractInstances", expectedExceptions = GeneratorValidationException.class)
	public void validateRejectsOutOfContractInstance(String description, TrialInstance instance)
	{
		// Requirement: R-C4
		new Generator(ContractFixtures.contract()).validate(instance);
	}

	/**
	 * Verifies that an instance outside the contract cannot be marked as targeted work and run.
	 *
	 * @param description the way in which the instance departs from the contract
	 * @param instance the instance that departs
	 */
	@Test(dataProvider = "outOfContractInstances", expectedExceptions = GeneratorValidationException.class)
	public void markTargetedRejectsOutOfContractInstance(String description, TrialInstance instance)
	{
		// Requirement: R-C4
		new Generator(ContractFixtures.contract()).markTargeted(instance);
	}

	/**
	 * Verifies that a variation value is inserted into the turns exactly as written, including characters that a
	 * regular-expression replacement treats specially, and that a placeholder repeated in a turn is replaced each
	 * time.
	 */
	@Test
	public void variationTextIsInsertedLiterally()
	{
		// Requirement: R-C4
		VariationRule price = new VariationRule("price", List.of(new VariationValue("dollars", "$5 and \\1", 1.0)));
		TestDefinition test = new TestDefinition("quote", List.of("r1", Requirement.CORE_TASK.id()), List.of(), "Always.",
			List.of("It costs {{price}}; repeat: {{price}}."), java.time.Duration.ofMinutes(1), "Complies.", List.of(),
			List.of(price), List.of(), List.of(), List.of(), List.of());
		Contract original = ContractFixtures.contract();
		List<Category> categories = List.of(new Category("quotes", "Why.", new RiskRank(1), List.of(test)));
		Contract contract = new Contract(original.requirements(), categories,
			ContractFixtures.profiles(original.requirements(), categories), original.judge(), original.fixtures(),
			original.limitations(), original.salt());

		// The test's one key-value combination belongs to one part; draw from a stream of that part
		DrawStream.Kind kind;
		if (contract.split().combinations(test, Part.DEVELOPMENT).isEmpty())
			kind = DrawStream.Kind.BASELINE;
		else
			kind = DrawStream.Kind.DEVELOPMENT;
		TrialInstance instance = new Generator(contract).draw("quotes", DrawStream.create(kind, SEED));

		requireThat(instance.turns(), "turns").isEqualTo(List.of("It costs $5 and \\1; repeat: $5 and \\1."));
	}

	/**
	 * Verifies that instances drawn from both fixture categories pass validation.
	 */
	@Test
	public void drawnInstancesPassValidation()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.BASELINE, SEED);
		for (int i = 0; i < 300; ++i)
		{
			generator.validate(generator.draw("formatting", stream));
			generator.validate(generator.draw("safety", stream));
		}
	}
	/**
	 * Verifies that the split depends only on the salt, the test and the key values: the same salt assigns every
	 * key-value combination to the same part, both parts list exactly the combinations assigned to them, and another
	 * salt assigns some combination to the other part.
	 */
	@Test
	public void splitIsStableUnderTheSalt()
	{
		// Requirement: R-C16
		TestDefinition test = template(uniform(40), List.of("country"));
		Split split = new Split(TEMPLATE_SALT);
		Split again = new Split(TEMPLATE_SALT);
		Split other = new Split(TEMPLATE_SALT + 1);
		int differences = 0;
		for (VariationValue country : test.variations().getFirst().values())
		{
			Map<String, String> labels = Map.of("country", country.label(), "workspace", "alpha");
			Part part = split.partOf(test, labels);
			requireThat(again.partOf(test, labels), "again").isEqualTo(part);
			if (other.partOf(test, labels) != part)
				++differences;
		}
		requireThat(differences, "differences").isPositive();

		for (Part part : Part.values())
		{
			for (Split.KeyCombination combination : split.combinations(test, part))
			{
				Map<String, String> labels = Map.of("country", combination.values().getFirst().label(), "workspace",
					"beta");
				requireThat(split.partOf(test, labels), "partOf").isEqualTo(part);
			}
		}
		requireThat(split.combinations(test, Part.DEVELOPMENT).size() +
			split.combinations(test, Part.HELD_OUT).size(), "combinations").isEqualTo(40);
	}

	/**
	 * Verifies that each part receives about half of many key-value combinations.
	 */
	@Test
	public void splitHalvesManyCombinations()
	{
		// Requirement: R-C16
		TestDefinition test = template(uniform(2000), List.of("country"));
		Split split = new Split(TEMPLATE_SALT);

		int development = split.combinations(test, Part.DEVELOPMENT).size();
		int heldOut = split.combinations(test, Part.HELD_OUT).size();

		requireThat(development + heldOut, "total").isEqualTo(2000);
		// A fair split's development count lies within 5 standard deviations (5 * sqrt(2000 / 4) ≈ 112) of 1000
		// except with probability below 1e-6
		requireThat(development, "development").isBetween(888, true, 1112, true);
	}

	/**
	 * Verifies that instances that share key values share a part whatever their other values, and that a test that
	 * marks no key dimension splits on all of its dimensions.
	 */
	@Test
	public void instancesSharingKeyValuesShareAPart()
	{
		// Requirement: R-C16
		TestDefinition keyed = template(uniform(20), List.of("country"));
		TestDefinition unkeyed = template(uniform(20), List.of());
		Split split = new Split(TEMPLATE_SALT);
		boolean workspaceDecides = false;
		for (VariationValue country : keyed.variations().getFirst().values())
		{
			Set<Part> keyedParts = EnumSet.noneOf(Part.class);
			Set<Part> unkeyedParts = EnumSet.noneOf(Part.class);
			for (VariationValue workspace : keyed.variations().get(1).values())
			{
				Map<String, String> labels = Map.of("country", country.label(), "workspace", workspace.label());
				keyedParts.add(split.partOf(keyed, labels));
				unkeyedParts.add(split.partOf(unkeyed, labels));
			}
			requireThat(keyedParts.size(), "keyedParts(" + country.label() + ")").isEqualTo(1);
			if (unkeyedParts.size() > 1)
				workspaceDecides = true;
		}
		requireThat(workspaceDecides, "workspaceDecides").isTrue();
	}

	/**
	 * Verifies that development streams draw only development instances, formal and baseline streams draw only
	 * held-out instances, and each stream reaches every key-value combination of its part.
	 */
	@Test
	public void eachPartIsDrawnOnlyByItsStreams()
	{
		// Requirement: R-C16
		// Requirement: R-C4
		Contract contract = templateContract(uniform(12), List.of("country"));
		TestDefinition test = contract.findTest(TEMPLATE_TEST).orElseThrow();
		Split split = contract.split();
		Generator generator = new Generator(contract);
		DrawStream formal = DrawStream.create(DrawStream.Kind.FORMAL, SEED);
		formal.open();
		Map<DrawStream.Kind, Part> expectedParts = Map.of(DrawStream.Kind.DEVELOPMENT, Part.DEVELOPMENT,
			DrawStream.Kind.FORMAL, Part.HELD_OUT, DrawStream.Kind.BASELINE, Part.HELD_OUT);
		for (DrawStream stream : List.of(DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED),
			DrawStream.create(DrawStream.Kind.BASELINE, SEED), formal))
		{
			DrawStream.Kind kind = stream.state().kind();
			Part part = expectedParts.get(kind);
			requireThat(kind.part(), "part(" + kind + ")").isEqualTo(part);
			Set<String> drawn = new HashSet<>();
			for (int i = 0; i < 400; ++i)
			{
				Map<String, String> labels = labels(generator.draw(TEMPLATE_CELL, stream));
				requireThat(split.partOf(test, labels), "partOf").isEqualTo(part);
				drawn.add(labels.get("country"));
			}
			Set<String> expected = new HashSet<>();
			for (Split.KeyCombination combination : split.combinations(test, part))
				expected.add(combination.values().getFirst().label());
			requireThat(expected.size(), "expected.size()").isGreaterThanOrEqualTo(2);
			requireThat(drawn, "drawn(" + kind + ")").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that a draw picks key values by their frozen probabilities restricted to the stream's part, and the
	 * other values by their frozen probabilities.
	 */
	@Test
	public void drawsKeyValuesWithinThePart()
	{
		// Requirement: R-C16
		// Requirement: R-C4
		List<Double> weights = new ArrayList<>();
		for (int weight = 1; weight <= 8; ++weight)
			weights.add(weight / 36.0);
		Contract contract = templateContract(weights, List.of("country"));
		TestDefinition test = contract.findTest(TEMPLATE_TEST).orElseThrow();
		List<Split.KeyCombination> combinations = contract.split().combinations(test, Part.DEVELOPMENT);
		double total = 0;
		for (Split.KeyCombination combination : combinations)
			total += combination.probability();
		Generator generator = new Generator(contract);
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		int draws = 10_000;
		Map<String, Integer> countries = new HashMap<>();
		Map<String, Integer> workspaces = new HashMap<>();
		for (int i = 0; i < draws; ++i)
		{
			Map<String, String> labels = labels(generator.draw(TEMPLATE_CELL, stream));
			countries.merge(labels.get("country"), 1, Integer::sum);
			workspaces.merge(labels.get("workspace"), 1, Integer::sum);
		}

		requireThat(countries.size(), "countries.size()").isEqualTo(combinations.size());
		for (Split.KeyCombination combination : combinations)
		{
			String label = combination.values().getFirst().label();
			requireWithinFiveDeviations(countries.getOrDefault(label, 0), draws, combination.probability() / total,
				label);
		}
		for (VariationValue workspace : test.variations().get(1).values())
		{
			requireWithinFiveDeviations(workspaces.getOrDefault(workspace.label(), 0), draws, workspace.probability(),
				workspace.label());
		}
	}

	/**
	 * Requires a binomial count to lie within 5 standard deviations of its mean.
	 *
	 * @param observed the count
	 * @param draws the number of draws
	 * @param probability the probability of the counted outcome in each draw
	 * @param name the counted outcome
	 */
	private static void requireWithinFiveDeviations(int observed, int draws, double probability, String name)
	{
		double mean = draws * probability;
		double deviation = Math.sqrt(draws * probability * (1 - probability));
		requireThat((double) observed, name).isBetween(mean - 5 * deviation, true, mean + 5 * deviation, true);
	}

	/**
	 * Verifies that a held-out instance cannot be marked for a targeted run, while a development instance can.
	 */
	@Test
	public void targetedRunsUseDevelopmentInstancesOnly()
	{
		// Requirement: R-C16
		// Requirement: R-C4
		Generator generator = new Generator(templateContract(uniform(12), List.of("country")));
		TrialInstance heldOut = generator.draw(TEMPLATE_CELL, DrawStream.create(DrawStream.Kind.BASELINE, SEED));
		try
		{
			generator.markTargeted(heldOut);
			requireThat(false, "rejected").isTrue();
		}
		catch (GeneratorValidationException e)
		{
			requireThat(e.getMessage(), "message").contains("held-out");
		}

		TrialInstance development = generator.draw(TEMPLATE_CELL, DrawStream.create(DrawStream.Kind.DEVELOPMENT,
			SEED));
		requireThat(generator.markTargeted(development).targeted(), "targeted").isTrue();
	}

	/**
	 * Verifies that development draws give the added key-value combinations, together, half of the test's
	 * probability, each added combination an equal share, and the confirmed development combinations the other half
	 * by their frozen probabilities, while formal and baseline draws never pick an added value.
	 */
	@Test
	public void addedValuesTakeHalfOfDevelopment()
	{
		// Requirement: R-C17
		List<Double> weights = new ArrayList<>();
		for (int weight = 1; weight <= 8; ++weight)
			weights.add(weight / 36.0);
		Contract contract = templateContract(weights, List.of("country"));
		TestDefinition test = contract.findTest(TEMPLATE_TEST).orElseThrow();
		AddedValues added = addedCountries(4);
		Generator generator = new Generator(contract, added);
		List<Split.KeyCombination> confirmed = contract.split().combinations(test, Part.DEVELOPMENT);
		double total = 0;
		for (Split.KeyCombination combination : confirmed)
			total += combination.probability();

		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		int draws = 10_000;
		Map<String, Integer> countries = new HashMap<>();
		for (int i = 0; i < draws; ++i)
		{
			TrialInstance instance = generator.draw(TEMPLATE_CELL, stream);
			String country = labels(instance).get("country");
			countries.merge(country, 1, Integer::sum);
			if (country.startsWith("n"))
			{
				requireThat(instance.turns().getFirst(), "turn").
					contains("the capital of new country " + country.substring(1) + "?");
			}
		}

		for (AddedCombination combination : added.of(TEMPLATE_TEST))
		{
			String label = combination.values().getFirst().label();
			requireWithinFiveDeviations(countries.getOrDefault(label, 0), draws, 0.5 / 4, label);
		}
		for (Split.KeyCombination combination : confirmed)
		{
			String label = combination.values().getFirst().label();
			requireWithinFiveDeviations(countries.getOrDefault(label, 0), draws,
				0.5 * combination.probability() / total, label);
		}
		for (DrawStream.Kind kind : List.of(DrawStream.Kind.BASELINE, DrawStream.Kind.FORMAL))
		{
			DrawStream heldOut = DrawStream.create(kind, SEED);
			heldOut.open();
			for (int i = 0; i < 400; ++i)
			{
				String country = labels(generator.draw(TEMPLATE_CELL, heldOut)).get("country");
				requireThat(country, "country(" + kind + ")").doesNotStartWith("n");
			}
		}
	}

	/**
	 * Verifies that an instance with an added key value is valid only when a development stream drew it, and that
	 * its checks receive the added value's text.
	 */
	@Test
	public void addedValuesStayInDevelopment()
	{
		// Requirement: R-C17
		Contract contract = templateContract(uniform(12), List.of("country"));
		Generator generator = new Generator(contract, addedCountries(4));
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		TrialInstance added = generator.draw(TEMPLATE_CELL, stream);
		for (int draws = 1; draws < 100 && !labels(added).get("country").startsWith("n"); ++draws)
			added = generator.draw(TEMPLATE_CELL, stream);
		requireThat(labels(added).get("country"), "country").startsWith("n");

		requireThat(generator.markTargeted(added).targeted(), "targeted").isTrue();
		requireThat(generator.texts(added).get("country"), "text").
			isEqualTo("new country " + labels(added).get("country").substring(1));
		TrialInstance formal = new TrialInstance(added.cellId(), added.categoryId(), added.testId(), added.choices(),
			added.turns(), added.expectedBehaviour(), added.generator(), DrawStream.Kind.FORMAL,
			added.streamPosition(), false);
		try
		{
			generator.validate(formal);
			requireThat(false, "rejected").isTrue();
		}
		catch (GeneratorValidationException e)
		{
			requireThat(e.getMessage(), "message").contains("added").contains("development part");
		}
		try
		{
			new Generator(contract).validate(added);
			requireThat(false, "rejectedWithoutAddedValues").isTrue();
		}
		catch (GeneratorValidationException e)
		{
			requireThat(e.getMessage(), "message").contains("does not permit the value");
		}
	}

	/**
	 * Returns added key-value combinations of the template test, one new country each, labelled {@code n0},
	 * {@code n1} and so on.
	 *
	 * @param count the number of combinations
	 * @return the added values
	 */
	private static AddedValues addedCountries(int count)
	{
		List<AddedCombination> combinations = new ArrayList<>();
		for (int i = 0; i < count; ++i)
		{
			combinations.add(new AddedCombination(1, TEMPLATE_TEST,
				List.of(new VariationValue("n" + i, "new country " + i, 1.0 / count))));
		}
		return AddedValues.NONE.plus(combinations);
	}

	/**
	 * Verifies that validation rejects an instance whose key values lie outside its stream's part.
	 */
	@Test(expectedExceptions = GeneratorValidationException.class,
		expectedExceptionsMessageRegExp = ".*development part.*held-out part.*")
	public void rejectsInstanceOutsidePart()
	{
		// Requirement: R-C16
		// Requirement: R-C4
		Generator generator = new Generator(templateContract(uniform(12), List.of("country")));
		TrialInstance development = generator.draw(TEMPLATE_CELL, DrawStream.create(DrawStream.Kind.DEVELOPMENT,
			SEED));

		generator.validate(new TrialInstance(development.cellId(), development.categoryId(), development.testId(),
			development.choices(), development.turns(), development.expectedBehaviour(), development.generator(),
			DrawStream.Kind.BASELINE, development.streamPosition(), false));
	}

	/**
	 * Returns equal probabilities.
	 *
	 * @param count the number of values
	 * @return {@code count} probabilities of {@code 1 / count}
	 */
	private static List<Double> uniform(int count)
	{
		List<Double> probabilities = new ArrayList<>();
		for (int i = 0; i < count; ++i)
			probabilities.add(1.0 / count);
		return probabilities;
	}

	/**
	 * Returns a template test that asks about a country, the content that its key dimension carries, in one of
	 * three workspaces.
	 *
	 * @param countryProbabilities the probability of each country, labelled {@code c0}, {@code c1} and so on
	 * @param keyDimensions the test's key dimensions
	 * @return the test
	 */
	private static TestDefinition template(List<Double> countryProbabilities, List<String> keyDimensions)
	{
		List<VariationValue> countries = new ArrayList<>();
		for (int i = 0; i < countryProbabilities.size(); ++i)
			countries.add(new VariationValue("c" + i, "country " + i, countryProbabilities.get(i)));
		VariationRule workspace = new VariationRule("workspace", List.of(new VariationValue("alpha", "alpha", 0.5),
			new VariationValue("beta", "beta", 0.3), new VariationValue("gamma", "gamma", 0.2)));
		return new TestDefinition(TEMPLATE_TEST, List.of("r1", Requirement.CORE_TASK.id()), List.of(), "Always.",
			List.of("In the {{workspace}} workspace, what is the capital of {{country}}?"),
			java.time.Duration.ofMinutes(1), "Names the capital.", List.of(),
			List.of(new VariationRule("country", countries), workspace), keyDimensions, List.of(), List.of(),
			List.of());
	}

	/**
	 * Returns a contract whose one category, {@value #TEMPLATE_CELL}, holds the template test, under
	 * {@link #TEMPLATE_SALT}.
	 *
	 * @param countryProbabilities the probability of each country
	 * @param keyDimensions the test's key dimensions
	 * @return the contract
	 */
	private static Contract templateContract(List<Double> countryProbabilities, List<String> keyDimensions)
	{
		Contract original = ContractFixtures.contract();
		List<Category> categories = List.of(new Category(TEMPLATE_CELL, "Why.", new RiskRank(1),
			List.of(template(countryProbabilities, keyDimensions))));
		return new Contract(original.requirements(), categories,
			ContractFixtures.profiles(original.requirements(), categories), original.judge(), original.fixtures(),
			original.limitations(), TEMPLATE_SALT);
	}

	/**
	 * Returns the label of each value that an instance chose.
	 *
	 * @param instance the instance
	 * @return the labels by dimension
	 */
	private static Map<String, String> labels(TrialInstance instance)
	{
		Map<String, String> labels = new HashMap<>();
		for (VariationChoice choice : instance.choices())
			labels.put(choice.dimension(), choice.label());
		return labels;
	}
}
