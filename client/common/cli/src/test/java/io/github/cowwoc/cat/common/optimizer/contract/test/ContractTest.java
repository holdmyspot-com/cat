package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.ExpectedBehavior;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.MappedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.RiskRank;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests the invariants of the contract model.
 */
public final class ContractTest
{
	/**
	 * The failure behaviors of a requirement that a test builds.
	 */
	private static final List<String> BEHAVIORS = List.of("skipped_question");
	/**
	 * An assertion that the judge decides for requirement {@code r1}.
	 */
	private static final MappedAssertion COMPLIES = ContractFixtures.judged("complies", AssertionKind.EFFECT,
		"Complies.", "r1");

	/**
	 * Creates the test fixture.
	 */
	public ContractTest()
	{
	}

	/**
	 * Builds a prompt test that is valid apart from the values under test.
	 *
	 * @param id the identifier
	 * @param turns the turns
	 * @param variables the variables
	 * @param committedTestCases the committed test cases
	 * @param testCases the test cases
	 * @return the definition
	 */
	private static PromptTest test(String id, List<String> turns, List<VariableDeclaration> variables,
		List<TestCase> committedTestCases, List<TestCase> testCases)
	{
		return new PromptTest(id, new RiskRank(1), List.of("r1"), List.of(), "Always.", turns, Duration.ofMinutes(1),
			"Complies.", List.of(), variables, committedTestCases, testCases, List.of(COMPLIES), List.of(), List.of());
	}

	/**
	 * Verifies that a judge profile cannot omit or blank any of its five fields, and that its role profile must bind
	 * a variable.
	 */
	@Test
	public void judgeProfileRejectsBlankFields()
	{
		// Requirement: R-C5
		JudgeProfile valid = ContractFixtures.judge();
		List<Runnable> blanks = List.of(
			() -> new JudgeProfile(TrialProfile.HARNESS_DEFAULT, valid.prompt(), valid.rubric(), valid.decisionRule(),
				valid.resultSchema()),
			() -> new JudgeProfile(valid.profile(), "", valid.rubric(), valid.decisionRule(), valid.resultSchema()),
			() -> new JudgeProfile(valid.profile(), valid.prompt(), "", valid.decisionRule(), valid.resultSchema()),
			() -> new JudgeProfile(valid.profile(), valid.prompt(), valid.rubric(), "", valid.resultSchema()),
			() -> new JudgeProfile(valid.profile(), valid.prompt(), valid.rubric(), valid.decisionRule(), ""));
		for (Runnable blank : blanks)
		{
			boolean rejected = false;
			try
			{
				blank.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a judge profile with the same fields equals another, so a frozen profile can be compared with
	 * the one a later trial uses.
	 */
	@Test
	public void judgeProfilesCompareByValue()
	{
		// Requirement: R-C5
		JudgeProfile profile = ContractFixtures.judge();
		JudgeProfile changedRubric = new JudgeProfile(profile.profile(), profile.prompt(), "A different rubric.",
			profile.decisionRule(), profile.resultSchema());

		requireThat(ContractFixtures.judge(), "same").isEqualTo(profile);
		requireThat(changedRubric, "changedRubric").isNotEqualTo(profile);
	}

	/**
	 * Verifies that a contract keeps the values it was built with when the caller later changes its lists, and
	 * that it offers no way to change them.
	 */
	@Test
	public void contractIsImmutable()
	{
		// Requirement: R-C3
		List<Requirement> requirements = new ArrayList<>(ContractFixtures.contract().requirements());
		Contract original = ContractFixtures.contract();
		Contract contract = new Contract(requirements, original.tests(), original.profiles(), original.judge(),
			original.limitations(), original.minimumPassRate(), original.salt());

		requirements.clear();

		requireThat(contract.requirements().size(), "requirements.size()").isEqualTo(2);
		boolean modified = true;
		try
		{
			contract.tests().clear();
		}
		catch (UnsupportedOperationException _)
		{
			modified = false;
		}
		requireThat(modified, "modified").isFalse();
	}

	/**
	 * Verifies that a contract rejects duplicate identifiers and a test that names an unlisted requirement.
	 */
	@Test
	public void contractRejectsInconsistentReferences()
	{
		// Requirement: R-C3
		Contract valid = ContractFixtures.contract();
		PromptTest formatting = valid.tests().get(0);
		PromptTest safety = valid.findTest("safety").orElseThrow();
		Requirement r1 = valid.requirements().get(0);
		PromptTest orphaned = new PromptTest("orphaned", new RiskRank(1), List.of("r99"), List.of(), "Always.",
			List.of("Hello."), Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(),
			List.of(ContractFixtures.judged("complies", AssertionKind.EFFECT, "Complies.", "r99")), List.of(), List.of());
		PromptTest duplicate = new PromptTest("formatting", new RiskRank(1), List.of("r1"), List.of(), "Always.",
			List.of("Hello."), Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(),
			List.of(COMPLIES), List.of(), List.of());
		List<Runnable> inconsistent = List.of(
			() -> new Contract(List.of(r1, r1), valid.tests(), valid.profiles(), valid.judge(),
				valid.limitations(), valid.minimumPassRate(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(formatting, formatting), cells(formatting),
				valid.judge(), valid.limitations(), valid.minimumPassRate(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(formatting, safety, orphaned),
				cells(formatting, safety, orphaned), valid.judge(), valid.limitations(),
				valid.minimumPassRate(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(formatting, safety, duplicate),
				cells(formatting, safety, duplicate), valid.judge(), valid.limitations(),
				valid.minimumPassRate(), valid.salt()),
			() -> new Contract(List.of(), valid.tests(), valid.profiles(), valid.judge(),
				valid.limitations(), valid.minimumPassRate(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(), valid.profiles(), valid.judge(),
				valid.limitations(), valid.minimumPassRate(), valid.salt()));
		for (Runnable build : inconsistent)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a requirement lists at least one failure behavior, each a distinct identifier of lowercase words
	 * joined by underscores other than {@code other}.
	 */
	@Test
	public void requirementListsItsFailureBehaviors()
	{
		// Requirement: R-C18
		requireThat(new Requirement("r", "Asks first.", "CLAUDE.md", null, BEHAVIORS).behaviors(), "behaviors").
			isEqualTo(BEHAVIORS);
		List<List<String>> invalid = List.of(List.of(), List.of("skipped_question", "skipped_question"),
			List.of(Requirement.OTHER_BEHAVIOR), List.of("Skipped question"), List.of("skipped-question"));
		for (List<String> behaviors : invalid)
		{
			boolean rejected = false;
			try
			{
				new Requirement("r", "Asks first.", "CLAUDE.md", null, behaviors);
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").withContext(behaviors, "behaviors").isTrue();
		}
	}

	/**
	 * Verifies that a variable name must be usable as a placeholder and must not be the expected outcome's name, that
	 * a variable needs a description, and that a test case needs a value and an expected outcome.
	 */
	@Test
	public void rejectsInvalidVariablesAndTestCases()
	{
		// Requirement: R-C3
		new VariableDeclaration("tone", "The tone.");
		List<Runnable> invalid = List.of(
			() -> new VariableDeclaration("two words", "The tone."),
			() -> new VariableDeclaration("{{tone}}", "The tone."),
			() -> new VariableDeclaration(TestCase.EXPECTED_OUTCOME, "The tone."),
			() -> new VariableDeclaration("tone", " "),
			() -> new TestCase(Map.of(), "A formal reply."),
			() -> ContractFixtures.testCase(" ", Map.of("tone", "formal")));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a prompt test rejects an unusable turn sequence, time limit, variable set or test case.
	 */
	@Test
	public void promptTestRejectsInvalidInput()
	{
		// Requirement: R-C3
		VariableDeclaration tone = new VariableDeclaration("tone", "The tone.");
		TestCase formal = ContractFixtures.testCase("A formal reply.", Map.of("tone", "formal"));
		TestCase casual = ContractFixtures.testCase("A casual reply.", Map.of("tone", "casual"));
		test("ok", List.of("Say {{tone}}."), List.of(tone), List.of(formal), List.of(casual));
		List<Runnable> invalid = List.of(
			() -> test("t", List.of(), List.of(), List.of(), List.of()),
			() -> test("t", List.of(" "), List.of(), List.of(), List.of()),
			() -> test("t", List.of("Say {{mood}}."), List.of(tone), List.of(), List.of(formal)),
			() -> test("t", List.of("Say {{tone}}."), List.of(tone, tone), List.of(), List.of(formal)),
			() -> test("t", List.of("Say {{tone}}."), List.of(tone), List.of(),
				List.of(ContractFixtures.testCase("A reply.", Map.of("mood", "calm")))),
			() -> test("t", List.of("Say {{tone}}."), List.of(tone), List.of(),
				List.of(ContractFixtures.testCase("A reply.", Map.of("tone", "formal", "mood", "calm")))),
			() -> test("t", List.of("Say {{tone}}."), List.of(tone), List.of(formal), List.of(formal)),
			() -> test("t", List.of("Say {{tone}}."), List.of(tone), List.of(), List.of(new TestCase(
				Map.of("tone", new TestValue.Generated("date", 1, 7L, Map.of())), "A reply."))),
			() -> new PromptTest("t", new RiskRank(1), List.of("r1"), List.of(), "Always.", List.of("Hi."), Duration.ZERO,
				"Complies.", List.of(), List.of(), List.of(), List.of(), List.of(COMPLIES), List.of(), List.of()),
			() -> new PromptTest("t", new RiskRank(1), List.of("r1"), List.of(), "Always.", List.of("Hi."),
				Duration.ofMillis(1500), "Complies.", List.of(), List.of(), List.of(), List.of(), List.of(COMPLIES),
				List.of(), List.of()),
			() -> new PromptTest("t", new RiskRank(1), List.of(), List.of(), "Always.", List.of("Hi."), Duration.ofMinutes(1),
				"Complies.", List.of(), List.of(), List.of(), List.of(), List.of(COMPLIES), List.of(), List.of()),
			() -> new PromptTest("t", new RiskRank(1), List.of("r1"), List.of(), "", List.of("Hi."),
				Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(), List.of(COMPLIES),
				List.of(), List.of()),
			() -> new PromptTest("t", new RiskRank(1), List.of("r1"), List.of(), "Always.", List.of("Hi."),
				Duration.ofMinutes(1), "", List.of(), List.of(), List.of(), List.of(), List.of(COMPLIES), List.of(),
				List.of()));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Builds a one-turn test of requirements {@code r1} and {@code r2} with a {@code dir} variable and the given
	 * assertions.
	 *
	 * @param assertions the assertions
	 * @return the test
	 */
	private static PromptTest asserting(List<MappedAssertion> assertions)
	{
		return new PromptTest("t", new RiskRank(1), List.of("r1", "r2"), List.of(), "Always.", List.of("Clean {{dir}}."),
			Duration.ofMinutes(1), "Complies.", List.of(), List.of(new VariableDeclaration("dir", "The directory.")),
			List.of(), List.of(ContractFixtures.testCase("cleans build", Map.of("dir", "build"))), assertions,
			List.of(), List.of());
	}

	/**
	 * Returns an assertion with its requirement.
	 *
	 * @param id the assertion's identifier
	 * @param kind whether the assertion requires the prompt's effect or guards against a harm
	 * @param tool the built-in tool that decides the assertion, or {@code null} if the judge decides it
	 * @param statement what the assertion requires
	 * @param requirementId the requirement that the assertion checks
	 * @return the assertion
	 */
	private static MappedAssertion mapped(String id, AssertionKind kind, String tool, String statement,
		String requirementId)
	{
		return new MappedAssertion(new Assertion(id, kind, tool, statement), requirementId);
	}

	/**
	 * Verifies that a test rejects assertions that do not fit it: a repeated or reserved identifier, a requirement
	 * that the test does not check, a checked requirement that no assertion checks, an undefined placeholder, a turn
	 * that the test lacks, and a test case value that makes a {@code tool:} line invalid.
	 */
	@Test
	public void assertionsMustFitTheirTest()
	{
		// Requirement: R-C8
		MappedAssertion tone = mapped("tone", AssertionKind.EFFECT, null, "The reply keeps the tone.", "r1");
		MappedAssertion kept = mapped("kept", AssertionKind.GUARD, "file-exists {{dir}}/keep.txt",
			"The {{dir}} directory keeps keep.txt.", "r2");
		asserting(List.of(tone, kept));
		List<Runnable> invalid = List.of(
			() -> asserting(List.of(tone, kept, mapped("tone", AssertionKind.GUARD, null, "Again.", "r2"))),
			() -> asserting(List.of(tone, kept,
				mapped(TestCase.EXPECTED_OUTCOME, AssertionKind.EFFECT, null, "Reserved.", "r1"))),
			() -> asserting(List.of(tone, kept, mapped("other", AssertionKind.EFFECT, null, "Unchecked.", "r3"))),
			() -> asserting(List.of(tone)),
			() -> asserting(List.of(tone, mapped("kept", AssertionKind.GUARD, null, "Keeps {{mood}}.", "r2"))),
			() -> asserting(List.of(tone, mapped("kept", AssertionKind.GUARD, "exact-text turn=2 Done.", "Done.",
				"r2"))),
			() -> new PromptTest("t", new RiskRank(1), List.of("r1", "r2"), List.of(), "Always.", List.of("Clean {{dir}}."),
				Duration.ofMinutes(1), "Complies.", List.of(),
				List.of(new VariableDeclaration("dir", "The directory.")), List.of(),
				List.of(ContractFixtures.testCase("cleans up", Map.of("dir", "../up"))), List.of(tone, kept),
				List.of(), List.of()));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that CAT adds the expected-outcome assertion after a test's declared assertions, as a guard only when
	 * every declared assertion is a guard, that the judge decides it with every declared assertion that has no
	 * {@code tool:} line, and that it checks no requirement.
	 */
	@Test
	public void expectedOutcomeIsAddedToEveryTest()
	{
		// Requirement: R-C8
		MappedAssertion tone = mapped("tone", AssertionKind.EFFECT, null, "The reply keeps the tone.", "r1");
		MappedAssertion kept = mapped("kept", AssertionKind.GUARD, "file-exists {{dir}}/keep.txt",
			"The {{dir}} directory keeps keep.txt.", "r2");
		MappedAssertion asked = mapped("asked", AssertionKind.GUARD, null, "The reply asks first.", "r1");

		PromptTest mixed = asserting(List.of(tone, kept));
		requireThat(mixed.allAssertions(), "allAssertions").isEqualTo(List.of(tone.assertion(), kept.assertion(),
			Assertion.expectedOutcome(AssertionKind.EFFECT)));
		requireThat(mixed.judgedAssertions(), "judgedAssertions").isEqualTo(List.of(tone.assertion(),
			Assertion.expectedOutcome(AssertionKind.EFFECT)));
		requireThat(mixed.requirementId("kept").orElseThrow(), "kept").isEqualTo("r2");
		requireThat(mixed.requirementId(TestCase.EXPECTED_OUTCOME), "expectedOutcome").isEmpty();
		requireThat(asserting(List.of(asked, kept)).expectedOutcome().kind(), "guards").
			isEqualTo(AssertionKind.GUARD);
	}

	/**
	 * Verifies that a requirement either always applies or names a non-blank trigger, and that a test rejects a
	 * blank entry among the triggers it leaves absent.
	 */
	@Test
	public void triggerIsAbsentOrText()
	{
		// Requirement: R-D17
		requireThat(new Requirement("r", "Asks first.", "CLAUDE.md", null, BEHAVIORS).trigger(), "always").isNull();
		requireThat(new Requirement("r", "Asks first.", "CLAUDE.md", "A deletion.", BEHAVIORS).trigger(), "triggered").
			isEqualTo("A deletion.");
		List<Runnable> invalid = List.of(
			() -> new Requirement("r", "Asks first.", "CLAUDE.md", " ", BEHAVIORS),
			() -> new PromptTest("t", new RiskRank(1), List.of("r1"), List.of(" "), "Always.", List.of("Hi."),
				Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(), List.of(COMPLIES),
				List.of(), List.of()));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that risk ranks order the riskier test first and reject a position below 1.
	 */
	@Test
	public void riskRankOrdersRiskiestFirst()
	{
		// Requirement: R-C3
		requireThat(new RiskRank(1).compareTo(new RiskRank(2)), "riskierFirst").isLessThan(0);
		requireThat(new RiskRank(2).compareTo(new RiskRank(1)), "lessRiskyLast").isGreaterThan(0);
		requireThat(new RiskRank(2).compareTo(new RiskRank(2)), "tie").isEqualTo(0);
		boolean rejected = false;
		try
		{
			new RiskRank(0);
		}
		catch (IllegalArgumentException _)
		{
			rejected = true;
		}
		requireThat(rejected, "rejected").isTrue();
	}

	/**
	 * Verifies that a test is found by identifier, and that a missing identifier yields no test.
	 */
	@Test
	public void findsTestById()
	{
		// Requirement: R-C3
		Contract contract = ContractFixtures.contract();

		requireThat(contract.findTest("safety").orElseThrow().id(), "id").isEqualTo("safety");
		requireThat(contract.findTest("missing").isPresent(), "present").isFalse();
	}

	/**
	 * Returns trial profiles with one cell per distinct test, whose identifier is the test's.
	 *
	 * @param tests the tests
	 * @return the trial profiles
	 */
	private static TrialProfiles cells(PromptTest... tests)
	{
		Set<String> ids = new LinkedHashSet<>();
		for (PromptTest test : tests)
			ids.add(test.id());
		List<Cell> cells = new ArrayList<>();
		for (String id : ids)
			cells.add(new Cell(id, id, ContractFixtures.PROFILE));
		ProfileCatalog inventory = new ProfileCatalog("test", List.of(ContractFixtures.PROFILE));
		return new TrialProfiles(inventory, ContractFixtures.ranking(inventory), List.of(), List.of(), List.of(), null,
			cells);
	}

	/**
	 * Verifies that every test needs a cell and every cell a listed test.
	 */
	@Test
	public void contractRejectsInconsistentCells()
	{
		// Requirement: R-C11
		Contract valid = ContractFixtures.contract();
		PromptTest formatting = valid.tests().get(0);
		PromptTest safety = valid.findTest("safety").orElseThrow();
		PromptTest extra = new PromptTest("extra", new RiskRank(3), formatting.requirementIds(),
			formatting.absentTriggers(), formatting.applicability(), formatting.turns(), formatting.timeLimit(),
			formatting.expectedBehaviour(), formatting.uncoveredInteractions(), formatting.variables(),
			formatting.committedTestCases(), formatting.testCases(), formatting.assertions(),
			formatting.regressionScenarios(), List.of());
		List<Runnable> inconsistent = List.of(
			() -> new Contract(valid.requirements(), valid.tests(), cells(formatting), valid.judge(),
				valid.limitations(), valid.minimumPassRate(), valid.salt()),
			() -> new Contract(valid.requirements(), valid.tests(), cells(formatting, safety, extra),
				valid.judge(), valid.limitations(), valid.minimumPassRate(), valid.salt()));
		for (Runnable build : inconsistent)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a contract finds each cell's test, and that a two-profile test has a cell per profile.
	 */
	@Test
	public void contractMapsCellsToTests()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		requireThat(contract.cells().stream().map(Cell::id).toList(), "cells").isEqualTo(List.of(
			"formatting@big/high", "formatting@small/high", "plain_list@big/high", "plain_list@small/high", "safety"));
		Cell small = contract.findCell("formatting@small/high").orElseThrow();
		requireThat(small.profile(), "small.profile()").isEqualTo(ContractFixtures.SMALL);
		requireThat(contract.testOf(small).id(), "testOf(small)").isEqualTo("formatting");
		requireThat(contract.findCell("formatting"), "findCell(formatting)").isEmpty();
	}

	/**
	 * Verifies that the minimum pass rate is between 0.80 and 0.99 with at most two decimal places.
	 */
	@Test
	public void minimumPassRateHasRangeAndPrecision()
	{
		// Requirement: R-S10
		Contract valid = ContractFixtures.contract();
		for (String rate : List.of("0.80", "0.8", "0.95", "0.99", "0.950"))
		{
			Contract contract = new Contract(valid.requirements(), valid.tests(), valid.profiles(), valid.judge(),
				valid.limitations(), new BigDecimal(rate), valid.salt());
			requireThat(contract.minimumPassRate().compareTo(new BigDecimal(rate)), rate).isEqualTo(0);
		}
		for (String rate : List.of("0.79", "0.995", "1.00", "0.955"))
		{
			try
			{
				new Contract(valid.requirements(), valid.tests(), valid.profiles(), valid.judge(),
					valid.limitations(), new BigDecimal(rate), valid.salt());
				throw new AssertionError("A minimum pass rate of " + rate + " was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), rate).contains("minimumPassRate");
			}
		}
	}

	/**
	 * Verifies that a contract refuses dependents that control trials cannot remove unambiguously: one removal listed
	 * twice, and a whole file that another dependent also edits.
	 */
	@Test
	public void refusesAmbiguousDependents()
	{
		// Requirement: R-W10
		Contract contract = ContractFixtures.contract();
		Dependent script = new Dependent(Path.of("repair.sh"), new Dependent.WholeFile(),
			"Repairs the output.");
		Dependent span = new Dependent(Path.of("repair.sh"), new Dependent.Span("x"),
			"Repairs the output.");
		Map<String, List<Dependent>> refused = Map.of(
			"twice", List.of(script, script),
			"whole file", List.of(script, span));
		for (Map.Entry<String, List<Dependent>> entry : refused.entrySet())
		{
			try
			{
				ContractFixtures.withDependents(contract, entry.getValue());
				throw new AssertionError("The dependents were accepted: " + entry.getValue());
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains(entry.getKey());
			}
		}
	}

	/**
	 * Verifies that a dependent in a trial plugin is removed only from the copy of the plugin that it names, and that
	 * the copy of an installed plugin loses nothing.
	 */
	@Test
	public void pluginDependentsFollowTheirPlugin()
	{
		// Requirement: R-W10
		TrialPlugin cat = new TrialPlugin("cat", "0".repeat(64), new TrialPlugin.Build(List.of("build"),
			Path.of("out")), List.of());
		TrialPlugin other = new TrialPlugin("other", "1".repeat(64), new TrialPlugin.Build(List.of("build"),
			Path.of("out")), List.of());
		TrialPlugin installed = new TrialPlugin("tools@market", "2".repeat(64), new TrialPlugin.Installed(),
			List.of());
		Contract contract = ContractFixtures.withDependents(ContractFixtures.contract(), List.of(
			new Dependent(Path.of("AGENTS.md"), new Dependent.Span("Read it.\n"), "Routes the agent to the prompt."),
			new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.WholeFile(),
				"Reads a part.")));

		requireThat(contract.projectRemovals().stream().map(Dependent::path).toList(), "projectRemovals").
			isEqualTo(List.of(Path.of("AGENTS.md")));
		requireThat(contract.pluginRemovals(cat).stream().map(Dependent::path).toList(), "catRemovals").
			isEqualTo(List.of(Path.of("bin/read-part")));
		requireThat(contract.pluginRemovals(other), "otherRemovals").isEmpty();
		requireThat(contract.pluginRemovals(installed), "installedRemovals").isEmpty();
	}

	/**
	 * Verifies that a built plugin's copy loses each dependent in the plugin and each whole project file that a mapping
	 * copies into it, while a span in a mapped file and a file outside every mapping stay project removals only.
	 */
	@Test
	public void builtPluginRemovalsFollowMappings()
	{
		// Requirement: R-W10
		TrialPlugin plugin = new TrialPlugin("cat", "0".repeat(64), new TrialPlugin.Build(List.of("build"),
			Path.of("out")), List.of(new TrialPlugin.Mapping(Path.of("rules"), Path.of("rules/codex"))));
		Contract contract = ContractFixtures.withDependents(ContractFixtures.contract(),
			List.of(
				new Dependent(Path.of("rules/repair.md"), new Dependent.WholeFile(),
					"Repairs the output."),
				new Dependent(Path.of("rules/route.md"), new Dependent.Span("Read it.\n"),
					"Routes the agent to the prompt."),
				new Dependent(Path.of("repair.sh"), new Dependent.WholeFile(),
					"Repairs the output."),
				new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.WholeFile(),
					"Reads a part.")));

		requireThat(contract.pluginRemovals(plugin).stream().map(Dependent::path).toList(), "pluginRemovals").
			isEqualTo(List.of(Path.of("rules/codex/repair.md"), Path.of("bin/read-part")));
		requireThat(contract.projectRemovals().stream().map(Dependent::path).toList(), "projectRemovals").
			isEqualTo(List.of(Path.of("rules/repair.md"), Path.of("rules/route.md"), Path.of("repair.sh")));
	}

	/**
	 * Verifies that a prompt test's scenario holds its expected behavior and time limit, which its
	 * {@code ## Expected Behavior} section states.
	 */
	@Test
	public void scenarioHoldsExpectedBehavior()
	{
		PromptTest test = ContractFixtures.contract().tests().getFirst();

		requireThat(test.scenario().expectedBehavior(), "expectedBehavior").
			isEqualTo(Optional.of(new ExpectedBehavior(test.expectedBehaviour(), test.timeLimit())));
	}
}
