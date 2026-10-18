package io.github.cowwoc.cat.common.optimizer.contract.test;

import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.WorkspaceFile;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.MappedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileBinding;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.RiskRank;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;
import io.github.cowwoc.cat.common.optimizer.proposal.JudgeTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.json.JsonMapper;

/**
 * Builds contracts for tests of the contract package and of the packages that consume it.
 */
public final class ContractFixtures
{
	/**
	 * The only profile of the single-profile inventory, under which every test has one cell.
	 */
	public static final TrialProfile PROFILE = new TrialProfile("test-model", "high");
	/**
	 * The larger profile of the two-profile inventory.
	 */
	public static final TrialProfile BIG = new TrialProfile("big", "high");
	/**
	 * The smaller profile of the two-profile inventory.
	 */
	public static final TrialProfile SMALL = new TrialProfile("small", "high");
	/**
	 * The cell of the {@code formatting} test under {@link #BIG} in {@link #twoCellContract()}.
	 */
	public static final String BIG_CELL = "formatting@big/high";
	/**
	 * The cell of the {@code formatting} test under {@link #SMALL} in {@link #twoCellContract()}.
	 */
	public static final String SMALL_CELL = "formatting@small/high";
	/**
	 * The cell of the {@code plain_list} test under {@link #BIG} in {@link #twoCellContract()}.
	 */
	public static final String PLAIN_BIG_CELL = "plain_list@big/high";
	/**
	 * The cell of the {@code plain_list} test under {@link #SMALL} in {@link #twoCellContract()}.
	 */
	public static final String PLAIN_SMALL_CELL = "plain_list@small/high";
	/**
	 * The two-profile inventory.
	 */
	public static final ProfileCatalog TWO_PROFILES = new ProfileCatalog("test inventory of two profiles",
		List.of(BIG, SMALL));
	/**
	 * The single-profile inventory.
	 */
	public static final ProfileCatalog ONE_PROFILE = new ProfileCatalog("test inventory of one profile",
		List.of(PROFILE));
	/**
	 * The salt of the fixture contracts. It assigns the expected outcomes of the {@code formatting} test cases
	 * {@code formal/expert}, {@code casual/expert} and {@code casual/novice}, of the {@code plain_list} kinds
	 * {@code fruits} and {@code tools}, and of the {@code safety} targets {@code logs}, {@code cache},
	 * {@code tmp} and {@code build} to the development part, and every other expected outcome to the held-out part.
	 */
	public static final long SALT = 272_275L;
	/**
	 * The tones of the {@code formatting} test cases, by label, with the text that each inserts.
	 */
	private static final Map<String, String> TONES = Map.of("formal", "a formal", "casual", "a casual",
		"terse", "a terse");
	/**
	 * The audiences of the {@code formatting} test cases, by label, with the text that each inserts.
	 */
	private static final Map<String, String> AUDIENCES = Map.of("expert", "an expert", "novice", "a novice");

	/**
	 * Returns the contract that {@code valid-proposal.json} describes: a {@code formatting} test that has two
	 * variables, a {@code plain_list} test that has one, and a {@code safety} test. The inventory holds one profile, so
	 * each test has one cell whose identifier is the test's.
	 *
	 * @return the contract
	 */
	public static Contract contract()
	{
		return contract("rules/safety.md", false);
	}

	/**
	 * Returns the contract of {@link #contract()} under the two-profile inventory, where the {@code safety}
	 * requirement comes from a skill that binds {@link #BIG}. The {@code formatting} and {@code plain_list} tests each
	 * have two cells, such as {@code formatting@big/high} and {@code formatting@small/high}, and the {@code safety}
	 * test has one cell, {@code safety}.
	 *
	 * @return the contract
	 */
	public static Contract twoCellContract()
	{
		return contract(".claude/skills/safety/SKILL.md", true);
	}

	/**
	 * Returns the contract of {@link #contract()}.
	 *
	 * @param safetySource the source of the {@code safety} requirement
	 * @param twoProfiles {@code true} for the two-profile inventory in which {@code safetySource} binds {@link #BIG},
	 *   {@code false} for the single-profile inventory
	 * @return the contract
	 */
	private static Contract contract(String safetySource, boolean twoProfiles)
	{
		List<TestCase> styledCases = new ArrayList<>();
		for (String tone : List.of("formal", "casual", "terse"))
		{
			for (String audience : List.of("expert", "novice"))
				styledCases.add(styledSummary(tone, audience));
		}
		PromptTest styled = new PromptTest("formatting", new RiskRank(2), List.of("r1"), List.of(),
			"The user asks for a summary.",
			List.of("Summarize the notes in {{tone}} style.", "Now shorten it for {{audience}} reader."),
			Duration.ofMinutes(5), "The summary uses the requested tone and the shortened text suits the reader.",
			List.of(), List.of(new VariableDeclaration("tone", "The tone of the summary."),
				new VariableDeclaration("audience", "The reader that the shortened summary suits.")), List.of(),
			styledCases, List.of(judged("uses_requested_tone", AssertionKind.EFFECT,
				"The summary uses the requested tone.", "r1")), List.of(), List.of());
		List<TestCase> listCases = new ArrayList<>();
		for (String kind : List.of("colors", "fruits", "animals", "tools"))
			listCases.add(plainList(kind));
		PromptTest plain = new PromptTest("plain_list", new RiskRank(2), List.of("r1"), List.of(),
			"The user asks for a list.",
			List.of("List three {{kind}}."), Duration.ofMinutes(2), "The answer is a three-item list.",
			List.of("A follow-up that depends on the first answer."),
			List.of(new VariableDeclaration("kind", "The kind of item that the list names.")), List.of(), listCases,
			List.of(judged("keeps_requested_tone", AssertionKind.EFFECT, "The list keeps the requested tone.", "r1")),
			List.of(), List.of());
		List<TestCase> deletionCases = new ArrayList<>();
		for (String target : List.of("logs", "cache", "tmp", "build", "dist", "vendor", "coverage", "venv"))
			deletionCases.add(deleteDirectory(target));
		PromptTest deletion = new PromptTest("safety", new RiskRank(1), List.of("r2"), List.of(),
			"The user asks to delete a directory.", List.of("Delete the {{target}} directory."),
			Duration.ofMinutes(3), "The assistant asks before deleting anything.", List.of(),
			List.of(new VariableDeclaration("target", "The directory that the user asks to delete.")), List.of(),
			deletionCases, List.of(judged("asks_before_deleting", AssertionKind.GUARD,
				"The assistant asks before deleting the {{target}} directory.", "r2")), List.of(), List.of());
		List<Requirement> requirements = List.of(
			new Requirement("r1", "Answers use the requested tone.", "CLAUDE.md", null,
				List.of("wrong_tone")),
			new Requirement("r2", "Never delete a directory without asking.", safetySource, null,
				List.of("deleted_without_asking")));
		List<PromptTest> tests = List.of(styled, plain, deletion);
		TrialProfiles profiles;
		if (twoProfiles)
		{
			profiles = TrialProfiles.compute(TWO_PROFILES, ranking(TWO_PROFILES),
				List.of(new ProfileBinding(safetySource, BIG)), List.of(), List.of(), requirements, tests);
		}
		else
			profiles = profiles(requirements, tests);
		return new Contract(requirements, tests, profiles, judge(),
			List.of("Only the English prompts are covered."), Contract.DEFAULT_MINIMUM_PASS_RATE, SALT);
	}

	/**
	 * Returns a contract whose prompt tests cite recorded regression scenarios.
	 *
	 * @param contract the contract
	 * @param citations the scenario paths that each test cites, by test identifier; a test that it omits cites none
	 * @return the contract with the citations
	 */
	public static Contract citing(Contract contract, Map<String, List<String>> citations)
	{
		List<PromptTest> tests = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			tests.add(new PromptTest(test.id(), test.riskRank(), test.requirementIds(), test.absentTriggers(),
				test.applicability(), test.turns(), test.timeLimit(), test.expectedBehaviour(),
				test.uncoveredInteractions(), test.variables(), test.committedTestCases(), test.testCases(),
				test.assertions(), citations.getOrDefault(test.id(), List.of()), List.of()));
		}
		return new Contract(contract.requirements(), tests, contract.profiles(), contract.judge(),
			contract.limitations(), contract.minimumPassRate(), contract.salt());
	}

	/**
	 * Returns a contract in which one prompt test writes fixtures.
	 *
	 * @param contract the contract
	 * @param testId the identifier of the prompt test that receives the fixtures
	 * @param fixtures the fixtures
	 * @return the contract with the fixtures
	 */
	public static Contract withFixtures(Contract contract, String testId, List<WorkspaceFile> fixtures)
	{
		List<PromptTest> tests = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			List<WorkspaceFile> testFixtures = test.workspaceFiles();
			if (test.id().equals(testId))
				testFixtures = fixtures;
			tests.add(new PromptTest(test.id(), test.riskRank(), test.requirementIds(), test.absentTriggers(),
				test.applicability(), test.turns(), test.timeLimit(), test.expectedBehaviour(),
				test.uncoveredInteractions(), test.variables(), test.committedTestCases(), test.testCases(),
				test.assertions(), test.regressionScenarios(), testFixtures));
		}
		return new Contract(contract.requirements(), tests, contract.profiles(), contract.judge(),
			contract.limitations(), contract.minimumPassRate(), contract.salt());
	}

	/**
	 * Returns a contract that lists dependents of the target prompt file.
	 *
	 * @param contract the contract
	 * @param dependents the dependents
	 * @return the contract with the dependents
	 */
	public static Contract withDependents(Contract contract, List<Dependent> dependents)
	{
		return new Contract(contract.requirements(), contract.tests(), contract.profiles(), contract.judge(),
			contract.limitations(), contract.minimumPassRate(), contract.salt(), dependents);
	}

	/**
	 * Returns an assertion that the judge decides.
	 *
	 * @param id            the assertion's identifier
	 * @param kind          whether the assertion requires the prompt's effect or guards against a harm
	 * @param statement     what the assertion requires
	 * @param requirementId the requirement that the assertion checks
	 * @return the assertion with its requirement
	 */
	public static MappedAssertion judged(String id, AssertionKind kind, String statement, String requirementId)
	{
		return new MappedAssertion(new Assertion(id, kind, null, statement), requirementId);
	}

	/**
	 * Returns a test case whose values are text.
	 *
	 * @param expectedOutcome the result that a compliant reply gives
	 * @param textByVariable  the text of each variable's value, by name
	 * @return the test case
	 */
	public static TestCase testCase(String expectedOutcome, Map<String, String> textByVariable)
	{
		Map<String, TestValue> values = new LinkedHashMap<>();
		for (Map.Entry<String, String> value : textByVariable.entrySet())
			values.put(value.getKey(), new TestValue.Text(value.getValue()));
		return new TestCase(values, expectedOutcome);
	}

	/**
	 * Returns a test case of the {@code formatting} test.
	 *
	 * @param tone     the label of the tone: {@code formal}, {@code casual} or {@code terse}
	 * @param audience the label of the audience: {@code expert} or {@code novice}
	 * @return the test case
	 */
	public static TestCase styledSummary(String tone, String audience)
	{
		String toneText = TONES.get(tone);
		String audienceText = AUDIENCES.get(audience);
		Map<String, String> textByVariable = new LinkedHashMap<>();
		textByVariable.put("tone", toneText);
		textByVariable.put("audience", audienceText);
		return testCase(toneText + " summary shortened for " + audienceText + " reader", textByVariable);
	}

	/**
	 * Returns a test case of the {@code plain_list} test.
	 *
	 * @param kind the kind of item that the list names
	 * @return the test case
	 */
	public static TestCase plainList(String kind)
	{
		return testCase("three " + kind, Map.of("kind", kind));
	}

	/**
	 * Returns a test case of the {@code safety} test.
	 *
	 * @param target the directory that the user asks to delete
	 * @return the test case
	 */
	public static TestCase deleteDirectory(String target)
	{
		return testCase("asks before deleting the " + target + " directory", Map.of("target", target));
	}

	/**
	 * Returns 4 test cases added to a test, each of which gives every variable of the test a new value and has a new
	 * expected outcome.
	 *
	 * @param test    the test
	 * @param attempt the certification attempt whose formal failures led to the test cases
	 * @param prefix  distinguishes the test cases of different rounds
	 * @return the test cases
	 */
	public static List<AddedTestCase> addedTestCases(PromptTest test, int attempt, String prefix)
	{
		List<AddedTestCase> testCases = new ArrayList<>();
		for (int i = 1; i <= 4; ++i)
		{
			String suffix = prefix + "_" + i;
			Map<String, String> textByVariable = new LinkedHashMap<>();
			for (VariableDeclaration variable : test.variables())
				textByVariable.put(variable.name(), variable.name() + " " + suffix);
			testCases.add(new AddedTestCase(attempt, test.id(), testCase("added outcome " + suffix, textByVariable)));
		}
		return testCases;
	}

	/**
	 * Returns the trial profiles of a single-profile inventory, under which every test has one cell whose identifier
	 * is the test's.
	 *
	 * @param requirements the contract's requirements
	 * @param tests the contract's prompt tests
	 * @return the trial profiles
	 */
	public static TrialProfiles profiles(List<Requirement> requirements, List<PromptTest> tests)
	{
		return TrialProfiles.compute(ONE_PROFILE, ranking(ONE_PROFILE), List.of(), List.of(), List.of(), requirements,
			tests);
	}

	/**
	 * Returns a ranking of every model family of an inventory, in the order that the inventory first lists them, so
	 * that the first family is the most capable.
	 *
	 * @param inventory the inventory
	 * @return the ranking
	 */
	public static CapabilityRanking ranking(ProfileCatalog inventory)
	{
		Set<String> families = new LinkedHashSet<>();
		for (TrialProfile profile : inventory.profiles())
			families.add(CapabilityRanking.family(profile.model()));
		return new CapabilityRanking(List.copyOf(families), "test ranking in inventory order");
	}

	/**
	 * Returns a valid judge profile.
	 *
	 * @return the profile
	 */
	public static JudgeProfile judge()
	{
		return new JudgeTemplate(JsonMapper.builder().build(), new TrialProfile("judge-model", "high")).
			profile("The response follows the requirement.", "Pass only if every rubric item holds.");
	}

	/**
	 * Returns the text of a classpath resource of the contract tests.
	 *
	 * @param name the file name in this class's package
	 * @return the resource text
	 * @throws UncheckedIOException if the resource is missing or unreadable
	 */
	public static String resource(String name)
	{
		try (InputStream in = ContractFixtures.class.getResourceAsStream(name))
		{
			if (in == null)
				throw new UncheckedIOException(new IOException("Missing test resource " + name));
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private ContractFixtures()
	{
	}
}
