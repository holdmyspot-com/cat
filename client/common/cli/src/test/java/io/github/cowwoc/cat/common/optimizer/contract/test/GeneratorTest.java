package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCases;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.GeneratorValidationException;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.RiskRank;
import io.github.cowwoc.cat.common.optimizer.contract.Split;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
	 * The salt of the capital-question contracts.
	 */
	private static final long CAPITAL_SALT = 42L;
	/**
	 * The test of the capital-question contracts.
	 */
	private static final String CAPITAL_TEST = "capital";
	/**
	 * The cell of the capital-question contracts, named by its test because the test has one profile.
	 */
	private static final String CAPITAL_CELL = CAPITAL_TEST;

	/**
	 * Creates the test fixture.
	 */
	public GeneratorTest()
	{
	}

	/**
	 * Draws until the generator picks the test that has two variables.
	 *
	 * @param generator the generator to draw from
	 * @return a test case of {@code formatting}
	 */
	private static DrawnTestCase styledTestCase(Generator generator)
	{
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		for (int i = 0; i < 100; ++i)
		{
			DrawnTestCase testCase = generator.draw("formatting", stream);
			if (testCase.testId().equals("formatting"))
				return testCase;
		}
		throw new AssertionError("No formatting test case in 100 draws");
	}

	/**
	 * Verifies that a drawn test case names its test, replaces every placeholder, keeps the expected behaviour and
	 * records the generator, stream and position.
	 */
	@Test
	public void drawRecordsConcreteTestCase()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawnTestCase testCase = styledTestCase(generator);

		DrawnTestCase replayed = generator.draw("formatting",
			DrawStream.resume(new DrawStream.State(DrawStream.Kind.DEVELOPMENT, SEED, testCase.streamPosition(), true)));

		requireThat(replayed, "replayed").isEqualTo(testCase);
		requireThat(testCase.generator(), "generator").isEqualTo(Generator.IDENTITY);
		requireThat(testCase.streamKind(), "streamKind").isEqualTo(DrawStream.Kind.DEVELOPMENT);
		requireThat(testCase.testId(), "testId").isEqualTo("formatting");
		requireThat(List.copyOf(testCase.testCase().values().keySet()), "variables").
			isEqualTo(List.of("tone", "audience"));
		for (String turn : testCase.turns())
			requireThat(turn, "turn").doesNotContain("{{");
		requireThat(testCase.turns().size(), "turns.size()").isEqualTo(2);
		requireThat(testCase.expectedBehaviour(), "expectedBehaviour").isEqualTo(
			"The summary uses the requested tone and the shortened text suits the reader.");
		requireThat(testCase.isRepresentative(), "representative").isTrue();
	}

	/**
	 * Verifies that a draw advances the stream by the one value that picks the test case, so a persisted position
	 * identifies the next draw.
	 */
	@Test
	public void drawAdvancesStreamByItsOwnValues()
	{
		// Requirement: R-C4
		Generator generator = new Generator(capitalContract(12));
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		long expectedPosition = 0;
		for (int i = 0; i < 30; ++i)
		{
			DrawnTestCase testCase = generator.draw(CAPITAL_CELL, stream);
			requireThat(testCase.streamPosition(), "streamPosition").isEqualTo(expectedPosition);
			++expectedPosition;
			requireThat(stream.state().position(), "position").isEqualTo(expectedPosition);
		}
	}

	/**
	 * Verifies that drawing the same test case again within a part is legitimate: a part with few test cases draws a
	 * test case more than once, and a stream resumed at an earlier position draws the same test case again. Neither is
	 * rejected; each repeated draw is another trial of that test case.
	 */
	@Test
	public void repeatedDrawsAreAllowed()
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
	 * Verifies that marking a test case as targeted keeps its identity and stream position, never draws, and
	 * removes it from representative evidence.
	 */
	@Test
	public void markTargetedBeforeExecution()
	{
		// Requirement: R-C4
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		DrawnTestCase drawn = generator.draw("safety", stream);
		DrawStream.State before = stream.state();

		DrawnTestCase targeted = generator.markTargeted(drawn);

		requireThat(targeted.targeted(), "targeted").isTrue();
		requireThat(targeted.isRepresentative(), "representative").isFalse();
		requireThat(targeted, "targeted").isEqualTo(new DrawnTestCase(drawn.cellId(), drawn.testId(),
			drawn.testCase(), drawn.turns(), drawn.expectedBehaviour(), drawn.generator(), drawn.streamKind(),
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
		DrawnTestCase testCase = generator.draw("formatting", formal);

		requireThat(testCase.streamKind(), "streamKind").isEqualTo(DrawStream.Kind.FORMAL);
		requireThat(testCase.isRepresentative(), "representative").isTrue();
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
	 * Verifies that a test case that its prompt test does not permit is refused, whatever the way in which it departs
	 * from the contract.
	 *
	 * @return the description of the departure, and the test case that departs
	 */
	@DataProvider(name = "outOfContractTestCases")
	public Object[][] outOfContractTestCases()
	{
		Generator generator = new Generator(ContractFixtures.contract());
		DrawnTestCase base = styledTestCase(generator);
		TestCase testCase = base.testCase();
		Map<String, String> values = PromptTest.textByVariable(testCase);
		String outcome = testCase.expectedOutcome();
		Map<String, String> toneOnly = Map.of("tone", values.get("tone"));
		Map<String, String> extra = new LinkedHashMap<>(values);
		extra.put("mood", "calm");
		Map<String, String> unlisted = new LinkedHashMap<>(values);
		unlisted.put("tone", "a sarcastic");
		return new Object[][]
			{
				{"unknown cell", with(base, "nowhere", base.testId(), testCase, base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"unknown test", with(base, base.cellId(), "no-such-test", testCase, base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"cell of another test", with(base, "safety", base.testId(), testCase, base.turns(),
					base.expectedBehaviour(), base.generator())},
				{"test other than the cell's", new DrawnTestCase(base.cellId(), "safety", testCase,
					base.turns(), base.expectedBehaviour(), base.generator(), base.streamKind(), base.streamPosition(),
					base.targeted())},
				{"missing variable", with(base, base.cellId(), base.testId(),
					ContractFixtures.testCase(outcome, toneOnly), base.turns(), base.expectedBehaviour(), base.generator())},
				{"extra variable", with(base, base.cellId(), base.testId(), ContractFixtures.testCase(outcome, extra),
					base.turns(), base.expectedBehaviour(), base.generator())},
				{"unpermitted value", with(base, base.cellId(), base.testId(),
					ContractFixtures.testCase(outcome, unlisted), base.turns(), base.expectedBehaviour(), base.generator())},
				{"another expected outcome", with(base, base.cellId(), base.testId(),
					ContractFixtures.testCase("any summary", values), base.turns(), base.expectedBehaviour(),
					base.generator())},
				{"turns that do not match the values", with(base, base.cellId(), base.testId(), testCase,
					List.of("Summarize the notes.", "Shorten it."), base.expectedBehaviour(), base.generator())},
				{"extra turn", with(base, base.cellId(), base.testId(), testCase,
					List.of(base.turns().get(0), base.turns().get(1), "One more thing."), base.expectedBehaviour(),
					base.generator())},
				{"changed expected behaviour", with(base, base.cellId(), base.testId(), testCase, base.turns(),
					"Anything is fine.", base.generator())},
				{"another generator", with(base, base.cellId(), base.testId(), testCase, base.turns(),
					base.expectedBehaviour(), "other-generator/9")}
			};
	}

	/**
	 * Copies a test case with every field except the stream fields replaced.
	 *
	 * @param base the test case that supplies the stream fields
	 * @param cellId the cell
	 * @param testId the test
	 * @param testCase the values and expected outcome
	 * @param turns the turns
	 * @param expectedBehaviour the expected behaviour
	 * @param generator the generator identity
	 * @return the new test case
	 */
	private static DrawnTestCase with(DrawnTestCase base, String cellId, String testId, TestCase testCase,
		List<String> turns, String expectedBehaviour, String generator)
	{
		return new DrawnTestCase(cellId, testId, testCase, turns, expectedBehaviour, generator,
			base.streamKind(), base.streamPosition(), base.targeted());
	}

	/**
	 * Verifies that the generator emits only test cases its prompt test permits: validation rejects each
	 * departure from the definition.
	 *
	 * @param description the way in which the test case departs from the contract
	 * @param testCase the test case that departs
	 */
	@Test(dataProvider = "outOfContractTestCases", expectedExceptions = GeneratorValidationException.class)
	public void validateRejectsOutOfContractTestCase(String description, DrawnTestCase testCase)
	{
		// Requirement: R-C4
		new Generator(ContractFixtures.contract()).validate(testCase);
	}

	/**
	 * Verifies that a test case outside the contract cannot be marked as targeted work and run.
	 *
	 * @param description the way in which the test case departs from the contract
	 * @param testCase the test case that departs
	 */
	@Test(dataProvider = "outOfContractTestCases", expectedExceptions = GeneratorValidationException.class)
	public void markTargetedRejectsOutOfContractTestCase(String description, DrawnTestCase testCase)
	{
		// Requirement: R-C4
		new Generator(ContractFixtures.contract()).markTargeted(testCase);
	}

	/**
	 * Verifies that a variable value is inserted into the turns exactly as written, including characters that a
	 * regular-expression replacement treats specially, and that a placeholder repeated in a turn is replaced each
	 * time.
	 */
	@Test
	public void variableTextIsInsertedLiterally()
	{
		// Requirement: R-C4
		PromptTest test = new PromptTest("quote", new RiskRank(1), List.of("r1"), List.of(), "Always.",
			List.of("It costs {{price}}; repeat: {{price}}."), java.time.Duration.ofMinutes(1), "Complies.", List.of(),
			List.of(new VariableDeclaration("price", "The price that the turn quotes.")), List.of(),
			List.of(ContractFixtures.testCase("$5 and \\1", Map.of("price", "$5 and \\1"))),
			List.of(ContractFixtures.judged("complies", AssertionKind.EFFECT, "Complies.", "r1")), List.of(), List.of());
		Contract original = ContractFixtures.contract();
		List<PromptTest> tests = List.of(test);
		Contract contract = new Contract(checked(original, tests), tests,
			ContractFixtures.profiles(checked(original, tests), tests), original.judge(),
			original.limitations(), original.minimumPassRate(), original.salt());

		// The test's one expected outcome belongs to one part; draw from a stream of that part
		DrawStream.Kind kind;
		if (contract.split().testCases(test, Part.DEVELOPMENT).isEmpty())
			kind = DrawStream.Kind.BASELINE;
		else
			kind = DrawStream.Kind.DEVELOPMENT;
		DrawnTestCase testCase = new Generator(contract).draw("quote", DrawStream.create(kind, SEED));

		requireThat(testCase.turns(), "turns").isEqualTo(List.of("It costs $5 and \\1; repeat: $5 and \\1."));
	}

	/**
	 * Verifies that test cases drawn from both fixture tests pass validation.
	 */
	@Test
	public void drawnTestCasesPassValidation()
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
	 * Verifies that the split depends only on the salt and the expected outcome: the same salt assigns every test
	 * case to the same part, both parts list exactly the test cases assigned to them, and another salt assigns some
	 * test case to the other part.
	 */
	@Test
	public void splitIsStableUnderTheSalt()
	{
		// Requirement: R-C16
		PromptTest test = capitalTest(40, List.of("alpha"));
		Split split = new Split(CAPITAL_SALT);
		Split again = new Split(CAPITAL_SALT);
		Split other = new Split(CAPITAL_SALT + 1);
		int differences = 0;
		for (TestCase testCase : test.testCases())
		{
			Part part = split.partOf(test, testCase);
			requireThat(again.partOf(test, testCase), "again").isEqualTo(part);
			if (other.partOf(test, testCase) != part)
				++differences;
		}
		requireThat(differences, "differences").isPositive();

		for (Part part : Part.values())
		{
			for (TestCase testCase : split.testCases(test, part))
				requireThat(split.partOf(test, testCase), "partOf").isEqualTo(part);
		}
		requireThat(split.testCases(test, Part.DEVELOPMENT).size() + split.testCases(test, Part.HELD_OUT).size(),
			"testCases").isEqualTo(40);
	}

	/**
	 * Verifies that test cases that share an expected outcome share a part whatever their other values, and that
	 * expected outcomes that differ only in case and spacing count as the same outcome.
	 */
	@Test
	public void sharedOutcomesShareAPart()
	{
		// Requirement: R-C16
		PromptTest test = capitalTest(20, List.of("alpha", "beta", "gamma"));
		Split split = new Split(CAPITAL_SALT);
		Map<String, Set<Part>> partsByOutcome = new HashMap<>();
		for (TestCase testCase : test.testCases())
		{
			partsByOutcome.computeIfAbsent(Split.normalize(testCase.expectedOutcome()), _ -> EnumSet.noneOf(Part.class)).
				add(split.partOf(test, testCase));
		}
		requireThat(partsByOutcome.size(), "outcomes").isEqualTo(20);
		for (Map.Entry<String, Set<Part>> entry : partsByOutcome.entrySet())
			requireThat(entry.getValue().size(), "parts").withContext(entry.getKey(), "outcome").isEqualTo(1);
		requireThat(split.outcomeCount(test, Part.DEVELOPMENT) + split.outcomeCount(test, Part.HELD_OUT),
			"outcomeCount").isEqualTo(20);
	}

	/**
	 * Verifies that development streams draw only development test cases, formal and baseline streams draw only
	 * held-out test cases, and each stream reaches every test case of its part.
	 */
	@Test
	public void eachPartIsDrawnOnlyByItsStreams()
	{
		// Requirement: R-C16
		// Requirement: R-C4
		Contract contract = capitalContract(12);
		PromptTest test = contract.findTest(CAPITAL_TEST).orElseThrow();
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
			Set<TestCase> drawn = new HashSet<>();
			for (int i = 0; i < 400; ++i)
			{
				TestCase testCase = generator.draw(CAPITAL_CELL, stream).testCase();
				requireThat(split.partOf(test, testCase), "partOf").isEqualTo(part);
				drawn.add(testCase);
			}
			Set<TestCase> expected = new HashSet<>(split.testCases(test, part));
			requireThat(expected.size(), "expected.size()").isGreaterThanOrEqualTo(2);
			requireThat(drawn, "drawn(" + kind + ")").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that a draw picks each test case of the stream's part with equal probability.
	 */
	@Test
	public void drawsTestCasesUniformlyWithinThePart()
	{
		// Requirement: R-C16
		// Requirement: R-C4
		Contract contract = capitalContract(16);
		PromptTest test = contract.findTest(CAPITAL_TEST).orElseThrow();
		List<TestCase> development = contract.split().testCases(test, Part.DEVELOPMENT);
		Generator generator = new Generator(contract);
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		int draws = 4000;
		Map<TestCase, Integer> counts = new HashMap<>();
		for (int i = 0; i < draws; ++i)
			counts.merge(generator.draw(CAPITAL_CELL, stream).testCase(), 1, Integer::sum);

		requireThat(counts.size(), "counts.size()").isEqualTo(development.size());
		for (TestCase testCase : development)
		{
			requireWithinFiveDeviations(counts.getOrDefault(testCase, 0), draws, 1.0 / development.size(),
				testCase.expectedOutcome());
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
		requireThat((double) observed, "observed").withContext(name, "outcome").
			isBetween(mean - 5 * deviation, true, mean + 5 * deviation, true);
	}

	/**
	 * Verifies that a held-out test case cannot be marked for a targeted run, while a development test case can.
	 */
	@Test
	public void targetedRunsUseDevelopmentTestCasesOnly()
	{
		// Requirement: R-C16
		// Requirement: R-C4
		Generator generator = new Generator(capitalContract(12));
		DrawnTestCase heldOut = generator.draw(CAPITAL_CELL, DrawStream.create(DrawStream.Kind.BASELINE, SEED));
		try
		{
			generator.markTargeted(heldOut);
			requireThat(false, "rejected").isTrue();
		}
		catch (GeneratorValidationException e)
		{
			requireThat(e.getMessage(), "message").contains("held-out");
		}

		DrawnTestCase development = generator.draw(CAPITAL_CELL, DrawStream.create(DrawStream.Kind.DEVELOPMENT,
			SEED));
		requireThat(generator.markTargeted(development).targeted(), "targeted").isTrue();
	}

	/**
	 * Verifies that development draws give the added test cases, together, half of the test's draws, each added test
	 * case an equal share, and the confirmed development test cases the other half in equal shares, while formal and
	 * baseline draws never pick an added test case.
	 */
	@Test
	public void addedTestCasesTakeHalfOfDevelopment()
	{
		// Requirement: R-C17
		Contract contract = capitalContract(16);
		PromptTest test = contract.findTest(CAPITAL_TEST).orElseThrow();
		AddedTestCases added = addedCountries(4);
		Generator generator = new Generator(contract, added);
		List<TestCase> confirmed = contract.split().testCases(test, Part.DEVELOPMENT);

		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		int draws = 4000;
		Map<TestCase, Integer> counts = new HashMap<>();
		for (int i = 0; i < draws; ++i)
		{
			DrawnTestCase testCase = generator.draw(CAPITAL_CELL, stream);
			counts.merge(testCase.testCase(), 1, Integer::sum);
		}

		for (AddedTestCase testCase : added.of(CAPITAL_TEST))
		{
			requireWithinFiveDeviations(counts.getOrDefault(testCase.testCase(), 0), draws, 0.5 / 4,
				testCase.testCase().expectedOutcome());
		}
		for (TestCase testCase : confirmed)
		{
			requireWithinFiveDeviations(counts.getOrDefault(testCase, 0), draws, 0.5 / confirmed.size(),
				testCase.expectedOutcome());
		}
		Set<TestCase> addedCases = new HashSet<>();
		for (AddedTestCase testCase : added.testCases())
			addedCases.add(testCase.testCase());
		for (DrawStream.Kind kind : List.of(DrawStream.Kind.BASELINE, DrawStream.Kind.FORMAL))
		{
			DrawStream heldOut = DrawStream.create(kind, SEED);
			heldOut.open();
			for (int i = 0; i < 400; ++i)
			{
				TestCase testCase = generator.draw(CAPITAL_CELL, heldOut).testCase();
				requireThat(addedCases.contains(testCase), "added(" + kind + ")").isFalse();
			}
		}
	}

	/**
	 * Verifies that an added test case is valid only when a development stream drew it.
	 */
	@Test
	public void addedTestCasesStayInDevelopment()
	{
		// Requirement: R-C17
		Contract contract = capitalContract(12);
		AddedTestCases addedCases = addedCountries(4);
		Generator generator = new Generator(contract, addedCases);
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, SEED);
		DrawnTestCase added = generator.drawAdded(CAPITAL_CELL, CAPITAL_TEST, addedCases.testCases(), stream);
		requireThat(added.turns().getFirst(), "turn").contains("new country");

		requireThat(generator.markTargeted(added).targeted(), "targeted").isTrue();
		DrawnTestCase formal = new DrawnTestCase(added.cellId(), added.testId(), added.testCase(),
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
			requireThat(false, "rejectedWithoutAddedTestCases").isTrue();
		}
		catch (GeneratorValidationException e)
		{
			requireThat(e.getMessage(), "message").contains("neither a test case");
		}
	}

	/**
	 * Returns added test cases of the capital-question test, one new country each.
	 *
	 * @param count the number of test cases
	 * @return the added test cases
	 */
	private static AddedTestCases addedCountries(int count)
	{
		List<AddedTestCase> testCases = new ArrayList<>();
		for (int i = 0; i < count; ++i)
		{
			testCases.add(new AddedTestCase(1, CAPITAL_TEST, ContractFixtures.testCase("new capital " + i,
				Map.of("country", "new country " + i, "workspace", "alpha"))));
		}
		return AddedTestCases.NONE.plus(testCases);
	}

	/**
	 * Verifies that validation rejects a test case whose expected outcome lies outside its stream's part.
	 */
	@Test(expectedExceptions = GeneratorValidationException.class,
		expectedExceptionsMessageRegExp = ".*development part.*held-out part.*")
	public void rejectsTestCaseOutsidePart()
	{
		// Requirement: R-C16
		// Requirement: R-C4
		Generator generator = new Generator(capitalContract(12));
		DrawnTestCase development = generator.draw(CAPITAL_CELL, DrawStream.create(DrawStream.Kind.DEVELOPMENT,
			SEED));

		generator.validate(new DrawnTestCase(development.cellId(), development.testId(),
			development.testCase(), development.turns(), development.expectedBehaviour(), development.generator(),
			DrawStream.Kind.BASELINE, development.streamPosition(), false));
	}

	/**
	 * Returns a prompt test that asks about a country, whose capital is the expected outcome, in a workspace. Each
	 * test case pairs a country with a workspace; the workspace does not change the expected outcome.
	 *
	 * @param countries  the number of countries, which the test cases name {@code country 0}, {@code country 1} and so
	 *                   on
	 * @param workspaces the workspaces that each country is asked in
	 * @return the test
	 */
	private static PromptTest capitalTest(int countries, List<String> workspaces)
	{
		List<TestCase> testCases = new ArrayList<>();
		for (int i = 0; i < countries; ++i)
		{
			for (String workspace : workspaces)
			{
				Map<String, String> values = new LinkedHashMap<>();
				values.put("country", "country " + i);
				values.put("workspace", workspace);
				// Spelling the outcome differently in each workspace does not change the outcome
				String outcome = "Capital " + i;
				if (workspace.equals("beta"))
					outcome = "  capital\t" + i;
				testCases.add(ContractFixtures.testCase(outcome, values));
			}
		}
		return new PromptTest(CAPITAL_TEST, new RiskRank(1), List.of("r1"), List.of(), "Always.",
			List.of("In the {{workspace}} workspace, what is the capital of {{country}}?"),
			java.time.Duration.ofMinutes(1), "Names the capital.", List.of(),
			List.of(new VariableDeclaration("country", "The country whose capital the turn asks for."),
				new VariableDeclaration("workspace", "The workspace that the question is asked in.")), List.of(),
			testCases, List.of(ContractFixtures.judged("names_capital", AssertionKind.EFFECT, "Names the capital.", "r1")),
			List.of(), List.of());
	}

	/**
	 * Returns a contract whose one test, {@value #CAPITAL_TEST}, is the capital-question test asked in the
	 * {@code alpha} workspace, under {@link #CAPITAL_SALT}.
	 *
	 * @param countries the number of countries
	 * @return the contract
	 */
	private static Contract capitalContract(int countries)
	{
		Contract original = ContractFixtures.contract();
		List<PromptTest> tests = List.of(capitalTest(countries, List.of("alpha")));
		return new Contract(checked(original, tests), tests,
			ContractFixtures.profiles(checked(original, tests), tests), original.judge(),
			original.limitations(), original.minimumPassRate(), CAPITAL_SALT);
	}

	/**
	 * Returns the requirements of a contract that the given tests check.
	 *
	 * @param original the contract
	 * @param tests the tests
	 * @return the requirements that some test lists in its requirement ids
	 */
	private static List<Requirement> checked(Contract original, List<PromptTest> tests)
	{
		Set<String> ids = new HashSet<>();
		for (PromptTest test : tests)
			ids.addAll(test.requirementIds());
		return original.requirements().stream().filter(requirement -> ids.contains(requirement.id())).toList();
	}
}
