package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link Generator}.
 */
public final class GeneratorTest
{
	private static final long SEED = 7_001L;
	/**
	 * The chi-square value that a correct sampler exceeds with probability 0.001, for 1 degree of freedom.
	 */
	private static final double CHI_SQUARE_1_DF = 10.828;
	/**
	 * The chi-square value that a correct sampler exceeds with probability 0.001, for 2 degrees of freedom.
	 */
	private static final double CHI_SQUARE_2_DF = 13.816;

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
	 * Computes the chi-square statistic of observed counts against expected probabilities.
	 *
	 * @param observed the count of each label
	 * @param probabilities the expected probability of each label
	 * @param total the number of observations
	 * @return the statistic
	 */
	private static double chiSquare(Map<String, Integer> observed, Map<String, Double> probabilities, int total)
	{
		double statistic = 0;
		for (Map.Entry<String, Double> entry : probabilities.entrySet())
		{
			double expected = total * entry.getValue();
			double difference = observed.getOrDefault(entry.getKey(), 0) - expected;
			statistic += difference * difference / expected;
		}
		return statistic;
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
	 * Verifies that a draw advances the stream by the one value that picks the test plus one value for each
	 * dimension of the picked test, so a persisted position identifies the next draw.
	 */
	@Test
	public void drawAdvancesStreamByItsOwnValues()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		long expectedPosition = 0;
		for (int i = 0; i < 30; ++i)
		{
			TrialInstance instance = generator.draw("formatting", stream);
			requireThat(instance.streamPosition(), "streamPosition").isEqualTo(expectedPosition);
			expectedPosition += 1 + instance.choices().size();
			requireThat(stream.state().position(), "position").isEqualTo(expectedPosition);
		}
	}

	/**
	 * Verifies that each variation value is sampled at its frozen probability. The seed is fixed, so the outcome is
	 * deterministic. The check uses chi-square tests at the 0.001 level, which a correct sampler fails once in a
	 * thousand seeds.
	 */
	@Test
	public void samplesVariationsAtFrozenProbabilities()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		Map<String, Integer> tones = new HashMap<>();
		Map<String, Integer> audiences = new HashMap<>();
		int styled = 0;
		for (int i = 0; i < 40_000; ++i)
		{
			TrialInstance instance = generator.draw("formatting", stream);
			if (!instance.testId().equals("styled-summary"))
				continue;
			++styled;
			tones.merge(instance.choices().get(0).label(), 1, Integer::sum);
			audiences.merge(instance.choices().get(1).label(), 1, Integer::sum);
		}

		double toneStatistic = chiSquare(tones, Map.of("formal", 0.5, "casual", 0.3, "terse", 0.2), styled);
		double audienceStatistic = chiSquare(audiences, Map.of("expert", 0.7, "novice", 0.3), styled);

		requireThat(toneStatistic, "toneStatistic").isLessThan(CHI_SQUARE_2_DF);
		requireThat(audienceStatistic, "audienceStatistic").isLessThan(CHI_SQUARE_1_DF);
	}

	/**
	 * Verifies that the sampler is sensitive: counts drawn at the wrong probabilities fail the same chi-square
	 * check, so the check in {@link #samplesVariationsAtFrozenProbabilities()} can detect a faulty generator.
	 */
	@Test
	public void chiSquareCheckDetectsWrongProbabilities()
	{
		// Requirement: R-C4
		Map<String, Integer> observed = Map.of("formal", 5_000, "casual", 3_000, "terse", 2_000);

		double statistic = chiSquare(observed, Map.of("formal", 0.45, "casual", 0.30, "terse", 0.25), 10_000);

		requireThat(statistic, "statistic").isGreaterThan(CHI_SQUARE_2_DF);
	}

	/**
	 * Verifies that a repeated draw of the same concrete instance is legitimate: a test without variation yields
	 * identical instances, and a stream resumed at an earlier position yields the instance again. Neither is
	 * rejected.
	 */
	@Test
	public void repeatedInstancesAreAllowed()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		List<TrialInstance> plainDraws = new ArrayList<>();
		for (int i = 0; i < 200; ++i)
		{
			TrialInstance instance = generator.draw("formatting", stream);
			if (instance.testId().equals("plain-list"))
				plainDraws.add(instance);
		}
		requireThat(plainDraws.size(), "plainDraws.size()").isGreaterThan(10);
		List<List<String>> turnSequences = plainDraws.stream().map(TrialInstance::turns).distinct().toList();
		requireThat(turnSequences, "turnSequences").isEqualTo(List.of(List.of("List three colors.")));

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
		requireThat(targeted, "targeted").isEqualTo(new TrialInstance(drawn.categoryId(), drawn.testId(),
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
	 * Verifies that a draw from a category that the contract does not define is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*unknown.*")
	public void drawRejectsUnknownCategory()
	{
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
		return new TrialInstance(categoryId, testId, choices, turns, expectedBehaviour, generator, base.streamKind(),
			base.streamPosition(), base.targeted());
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
		TestDefinition test = new TestDefinition("quote", List.of("r1"), "Always.",
			List.of("It costs {{price}}; repeat: {{price}}."), java.time.Duration.ofMinutes(1), "Complies.", List.of(),
			List.of(price));
		Contract original = ContractFixtures.contract();
		Contract contract = new Contract(original.requirements(),
			List.of(new Category("quotes", "Why.", new RiskRank(1), List.of(test))), original.judge(),
			original.fixtures(), original.limitations());

		TrialInstance instance = new Generator(contract).draw("quotes",
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED));

		requireThat(instance.turns(), "turns").isEqualTo(List.of("It costs $5 and \\1; repeat: $5 and \\1."));
	}

	/**
	 * Verifies that every drawn instance passes validation, so the generator never emits an out-of-contract
	 * instance of its own.
	 */
	@Test
	public void drawnInstancesAreInsideContract()
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
}
