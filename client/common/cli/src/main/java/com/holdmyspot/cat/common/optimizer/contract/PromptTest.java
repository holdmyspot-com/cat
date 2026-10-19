package com.holdmyspot.cat.common.optimizer.contract;

import com.holdmyspot.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * One kind of trial that checks one or more requirements through its assertions. A trial passes when it passes every
 * assertion: CAT decides an assertion that has a {@code tool} with its built-in tool, and a judge decides the
 * others. CAT adds the assertion {@value TestCase#EXPECTED_OUTCOME}, which the judge decides, to every test.
 *
 * @param id the identifier that is unique within the contract
 * @param riskRank the rank that breaks allocation ties
 * @param requirementIds the identifiers of the requirements that this test checks
 * @param absentTriggers the identifiers of the requirements whose trigger this test leaves absent, so that a
 *   compliant response does not take the action that they oblige
 * @param applicability the conditions under which the checked requirements apply to a trial
 * @param turns the complete prewritten user turns, which may contain {@code {{variable}}} placeholders
 * @param timeLimit the positive whole-second duration a trial may run before it is judged on the behaviour it has
 *   shown
 * @param expectedBehaviour what a compliant response does
 * @param uncoveredInteractions the material interactions that this test cannot cover
 * @param variables the variables that every test case gives a value
 * @param committedTestCases the test cases that an earlier campaign committed with the prompt; they belong to the
 *   development part
 * @param testCases the test cases that the contract author wrote for this campaign
 * @param assertions the assertions that the contract author declared, each with the requirement that it checks
 * @param regressionScenarios the recorded scenario paths covered by this test; empty when none are covered
 * @param workspaceFiles the files that each trial's workspace receives, with placeholders replaced in their paths and
 *   contents
 * @param draft the test's directory in the contract author's draft directory and the {@value PromptTestFiles#SCENARIO}
 *   that it holds, which delivery writes; empty for a test that the contract author wrote into the proposal itself
 */
public record PromptTest(String id, RiskRank riskRank, List<String> requirementIds, List<String> absentTriggers,
	String applicability, List<String> turns, Duration timeLimit, String expectedBehaviour,
	List<String> uncoveredInteractions, List<VariableDeclaration> variables, List<TestCase> committedTestCases,
	List<TestCase> testCases, List<MappedAssertion> assertions, List<String> regressionScenarios,
	List<WorkspaceFile> workspaceFiles, Optional<PromptTest.Draft> draft)
{
	/**
	 * The behavior through which a reply fails the assertion {@value TestCase#EXPECTED_OUTCOME}.
	 */
	public static final String UNEXPECTED_OUTCOME = "unexpected_outcome";

	/**
	 * A prompt test's directory in the contract author's draft directory.
	 *
	 * @param directory   the directory, relative to the draft directory, which is also its path relative to the
	 *                    project
	 * @param scenario    the content of the {@value PromptTestFiles#SCENARIO} that it holds
	 * @param copiedLinks the target of each link of its {@value PromptTestFiles#WORKSPACE} directory whose target lies
	 *                    outside that directory, so that trials receive a copy of the target, by the link's path
	 *                    relative to {@value PromptTestFiles#WORKSPACE}; each target is relative to the link's
	 *                    directory, and paths and targets have {@code /} separators
	 */
	public record Draft(Path directory, String scenario, Map<String, String> copiedLinks)
	{
		/**
		 * Creates a draft directory of a prompt test.
		 *
		 * @param directory   the directory, relative to the draft directory, which is also its path relative to the
		 *                    project
		 * @param scenario    the content of the {@value PromptTestFiles#SCENARIO} that it holds
		 * @param copiedLinks the target of each link of its {@value PromptTestFiles#WORKSPACE} directory whose target
		 *                    lies outside that directory, by the link's path relative to
		 *                    {@value PromptTestFiles#WORKSPACE}
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code directory} is absolute, empty or not normalized or
		 *                                  leaves the draft directory, or {@code scenario} is blank
		 */
		public Draft
		{
			requireThat(directory, "directory").isNotNull();
			requireThat(directory.isAbsolute(), "directory.isAbsolute()").isFalse();
			requireThat(directory.toString(), "directory").isNotEmpty();
			requireThat(directory.normalize(), "directory.normalize()").isEqualTo(directory, "directory");
			requireThat(directory.startsWith(".."), "directory.startsWith(\"..\")").isFalse();
			requireThat(scenario, "scenario").isNotBlank();
			copiedLinks = Collections.unmodifiableMap(new TreeMap<>(copiedLinks));
		}

		/**
		 * Creates a draft directory of a prompt test whose {@value PromptTestFiles#WORKSPACE} directory has no link
		 * whose target lies outside it.
		 *
		 * @param directory the directory, relative to the draft directory, which is also its path relative to the
		 *                  project
		 * @param scenario  the content of the {@value PromptTestFiles#SCENARIO} that it holds
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code directory} is absolute, empty or not normalized or
		 *                                  leaves the draft directory, or {@code scenario} is blank
		 */
		public Draft(Path directory, String scenario)
		{
			this(directory, scenario, Map.of());
		}
	}

	/**
	 * Creates a prompt test.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param riskRank the rank that breaks allocation ties
	 * @param requirementIds the identifiers of the requirements that this test checks
	 * @param absentTriggers the identifiers of the requirements whose trigger this test leaves absent, so that a
	 *   compliant response does not take the action that they oblige
	 * @param applicability the conditions under which the checked requirements apply to a trial
	 * @param turns the complete prewritten user turns, which may contain {@code {{variable}}} placeholders
	 * @param timeLimit the positive whole-second duration a trial may run before it is judged on the behaviour it has
	 *   shown
	 * @param expectedBehaviour what a compliant response does
	 * @param uncoveredInteractions the material interactions that this test cannot cover
	 * @param variables the variables that every test case gives a value
	 * @param committedTestCases the test cases that an earlier campaign committed with the prompt; they belong to the
	 *   development part
	 * @param testCases the test cases that the contract author wrote for this campaign
	 * @param assertions the assertions that the contract author declared, each with the requirement that it checks
	 * @param regressionScenarios the recorded scenario paths covered by this test; empty when none are covered
	 * @param workspaceFiles the files that each trial's workspace receives, with placeholders replaced in their paths and
	 *   contents
	 * @param draft the test's directory in the contract author's draft directory and the
	 *   {@value PromptTestFiles#SCENARIO} that it holds, which delivery writes; empty for a test that the contract
	 *   author wrote into the proposal itself
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code id}, a requirement identifier or an absent trigger is not lowercase
	 *   words joined by underscores, a text is blank, a list that must not be empty is empty, {@code timeLimit} is not
	 *   positive or includes fractional seconds, two variables share a name, a turn names a placeholder that no variable
	 *   defines, a test case does not give a value to exactly the declared variables, a test case's values do not fit the
	 *   workspace files (a generated value outside workspace file contents or in none, a file-name value that is not one
	 *   valid segment, or two workspace files landing on one path), a test case appears twice, two workspace files share
	 *   a path, two assertions share an identifier, an assertion has the reserved identifier
	 *   {@value TestCase#EXPECTED_OUTCOME}, an assertion checks a requirement that the test does not check, a requirement
	 *   has no assertion, an assertion's statement or {@code tool} names a placeholder that is neither a variable nor
	 *   {@value TestCase#EXPECTED_OUTCOME}, a {@code tool} examines a turn that the test does not have, or a test case's
	 *   values make a {@code tool} invalid
	 */
	public PromptTest
	{
		requireThat(id, "id").matches(Identifier.SNAKE_CASE);
		requireThat(riskRank, "riskRank").isNotNull();
		requireThat(requirementIds, "requirementIds").isNotEmpty();
		requireThat(absentTriggers, "absentTriggers").isNotNull();
		requireThat(applicability, "applicability").isNotBlank();
		requireThat(turns, "turns").isNotEmpty();
		requireThat(timeLimit, "timeLimit").isGreaterThan(Duration.ZERO);
		requireThat(timeLimit.getNano(), "timeLimit.getNano()").isEqualTo(0);
		requireThat(expectedBehaviour, "expectedBehaviour").isNotBlank();
		requireThat(uncoveredInteractions, "uncoveredInteractions").isNotNull();
		requireThat(variables, "variables").isNotNull();
		requireThat(committedTestCases, "committedTestCases").isNotNull();
		requireThat(testCases, "testCases").isNotNull();
		requireThat(assertions, "assertions").isNotNull();
		requireThat(regressionScenarios, "regressionScenarios").isNotNull();
		requireThat(workspaceFiles, "workspaceFiles").isNotNull();
		requireThat(draft, "draft").isNotNull();
		requirementIds = List.copyOf(requirementIds);
		absentTriggers = List.copyOf(absentTriggers);
		turns = List.copyOf(turns);
		uncoveredInteractions = List.copyOf(uncoveredInteractions);
		variables = List.copyOf(variables);
		committedTestCases = List.copyOf(committedTestCases);
		testCases = List.copyOf(testCases);
		assertions = List.copyOf(assertions);
		regressionScenarios = List.copyOf(regressionScenarios);
		workspaceFiles = List.copyOf(workspaceFiles);
		for (String requirementId : requirementIds)
			requireThat(requirementId, "requirementId").matches(Identifier.SNAKE_CASE);
		for (String absentTrigger : absentTriggers)
			requireThat(absentTrigger, "absentTrigger").matches(Identifier.SNAKE_CASE);
		for (String turn : turns)
			requireThat(turn, "turn").isNotBlank();
		for (String interaction : uncoveredInteractions)
			requireThat(interaction, "uncoveredInteraction").isNotBlank();
		for (String scenario : regressionScenarios)
			requireThat(scenario, "regressionScenario").isNotBlank();

		Set<String> names = new LinkedHashSet<>();
		for (VariableDeclaration variable : variables)
		{
			if (!names.add(variable.name()))
				throw new IllegalArgumentException("Test '" + id + "' defines the variable '" + variable.name() +
					"' twice");
		}
		for (String turn : turns)
		{
			for (String name : Placeholders.names(turn))
			{
				if (!names.contains(name))
				{
					throw new IllegalArgumentException("Test '" + id + "' has a turn with the placeholder {{" + name +
						"}}, but the test defines no such variable. Defined variables: " + names);
				}
			}
		}

		Set<String> workspaceFilePaths = new HashSet<>();
		for (WorkspaceFile workspaceFile : workspaceFiles)
		{
			if (!workspaceFilePaths.add(workspaceFile.path()))
			{
				throw new IllegalArgumentException("Test '" + id + "' lists the workspace file '" + workspaceFile.path() +
					"' twice");
			}
		}
		Set<String> inText = namedInText(turns, assertions);
		List<PromptTestFiles.WorkspaceEntry> described = describe(workspaceFiles, names);
		List<TestCase> allTestCases = concat(committedTestCases, testCases);
		Set<TestCase> seen = new HashSet<>();
		for (TestCase testCase : allTestCases)
		{
			requireValid(id, names, testCase);
			List<String> problems = PromptTestFiles.problems(names, inText, described, testCase);
			if (!problems.isEmpty())
			{
				throw new IllegalArgumentException("A test case of test '" + id + "' does not fit the test: " +
					String.join("; ", problems));
			}
			if (!seen.add(testCase))
				throw new IllegalArgumentException("Test '" + id + "' lists the test case " + testCase + " twice");
		}

		Set<String> assertionIds = new HashSet<>();
		Set<String> mapped = new HashSet<>();
		for (MappedAssertion mappedAssertion : assertions)
		{
			Assertion assertion = mappedAssertion.assertion();
			if (assertion.id().equals(TestCase.EXPECTED_OUTCOME))
			{
				throw new IllegalArgumentException("Test '" + id + "' declares the assertion '" +
					TestCase.EXPECTED_OUTCOME + "', but CAT reserves that identifier for the assertion it adds");
			}
			if (!assertionIds.add(assertion.id()))
			{
				throw new IllegalArgumentException("Test '" + id + "' declares the assertion '" + assertion.id() +
					"' twice");
			}
			String requirementId = mappedAssertion.requirementId();
			if (!requirementIds.contains(requirementId))
			{
				throw new IllegalArgumentException("The assertion '" + assertion.id() + "' of test '" + id +
					"' checks the requirement '" + requirementId + "', which the test does not check. Checked " +
					"requirements: " + requirementIds);
			}
			mapped.add(requirementId);
			requireValid(id, names, turns.size(), allTestCases, assertion);
		}
		for (String requirementId : requirementIds)
		{
			if (!mapped.contains(requirementId))
			{
				throw new IllegalArgumentException("Test '" + id + "' checks the requirement '" + requirementId +
					"', but none of its assertions checks it");
			}
		}
	}

	/**
	 * Creates a prompt test that the contract author wrote into the proposal itself, without a draft directory.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param riskRank the rank that breaks allocation ties
	 * @param requirementIds the identifiers of the requirements that this test checks
	 * @param absentTriggers the identifiers of the requirements whose trigger this test leaves absent
	 * @param applicability the conditions under which the checked requirements apply to a trial
	 * @param turns the complete prewritten user turns, which may contain {@code {{variable}}} placeholders
	 * @param timeLimit the positive whole-second duration a trial may run before it is judged on the behaviour it has
	 *   shown
	 * @param expectedBehaviour what a compliant response does
	 * @param uncoveredInteractions the material interactions that this test cannot cover
	 * @param variables the variables that every test case gives a value
	 * @param committedTestCases the test cases that an earlier campaign committed with the prompt
	 * @param testCases the test cases that the contract author wrote for this campaign
	 * @param assertions the assertions that the contract author declared, each with the requirement that it checks
	 * @param regressionScenarios the recorded scenario paths covered by this test; empty when none are covered
	 * @param workspaceFiles the files that each trial's workspace receives
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the canonical constructor rejects the arguments
	 */
	public PromptTest(String id, RiskRank riskRank, List<String> requirementIds, List<String> absentTriggers,
		String applicability, List<String> turns, Duration timeLimit, String expectedBehaviour,
		List<String> uncoveredInteractions, List<VariableDeclaration> variables, List<TestCase> committedTestCases,
		List<TestCase> testCases, List<MappedAssertion> assertions, List<String> regressionScenarios,
		List<WorkspaceFile> workspaceFiles)
	{
		this(id, riskRank, requirementIds, absentTriggers, applicability, turns, timeLimit, expectedBehaviour,
			uncoveredInteractions, variables, committedTestCases, testCases, assertions, regressionScenarios, workspaceFiles,
			Optional.empty());
	}

	/**
	 * Returns two lists joined.
	 *
	 * @param first the first list
	 * @param second the second list
	 * @return the elements of {@code first}, then those of {@code second}
	 */
	private static List<TestCase> concat(List<TestCase> first, List<TestCase> second)
	{
		List<TestCase> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	/**
	 * Rejects a test case that does not give a value to exactly the declared variables.
	 *
	 * @param id the test's identifier
	 * @param names the names of the declared variables
	 * @param testCase the test case
	 * @throws IllegalArgumentException if the test case does not fit the test
	 */
	private static void requireValid(String id, Set<String> names, TestCase testCase)
	{
		if (!testCase.values().keySet().equals(names))
		{
			throw new IllegalArgumentException("A test case of test '" + id + "' gives values to " +
				testCase.values().keySet() + ", but the test declares the variables " + names);
		}
	}

	/**
	 * Returns the variables that turns, assertion statements and {@code tool} values name.
	 *
	 * @param turns the turns
	 * @param assertions the declared assertions
	 * @return the names, without {@value TestCase#EXPECTED_OUTCOME}
	 */
	private static Set<String> namedInText(List<String> turns, List<MappedAssertion> assertions)
	{
		Set<String> named = new LinkedHashSet<>();
		for (String turn : turns)
			named.addAll(Placeholders.scenarioNames(turn));
		for (MappedAssertion mapped : assertions)
		{
			Assertion assertion = mapped.assertion();
			named.addAll(Placeholders.scenarioNames(assertion.statement()));
			if (assertion.tool() != null)
				named.addAll(Placeholders.scenarioNames(assertion.tool()));
		}
		named.remove(TestCase.EXPECTED_OUTCOME);
		return named;
	}

	/**
	 * Describes workspace files as the workspace file rules read them.
	 *
	 * @param workspaceFiles the workspace files
	 * @param names the names of the declared variables
	 * @return one workspace file per file or link, naming the declared variables that a file's content names
	 */
	private static List<PromptTestFiles.WorkspaceEntry> describe(List<WorkspaceFile> workspaceFiles, Set<String> names)
	{
		List<PromptTestFiles.WorkspaceEntry> described = new ArrayList<>(workspaceFiles.size());
		for (WorkspaceFile workspaceFile : workspaceFiles)
		{
			if (workspaceFile.link())
			{
				described.add(new PromptTestFiles.WorkspaceEntry(workspaceFile.path(), false, Set.of(),
					Optional.of(workspaceFile.content())));
				continue;
			}
			Set<String> contentNames = new HashSet<>(Placeholders.scenarioNames(workspaceFile.content()));
			contentNames.retainAll(names);
			described.add(new PromptTestFiles.WorkspaceEntry(workspaceFile.path(), true, contentNames));
		}
		return described;
	}

	/**
	 * Returns where each workspace file lands in a trial's workspace for a test case.
	 *
	 * @param testCase a test case of this test
	 * @return each workspace file by its path relative to the workspace root, with {@code /} separators, in the order
	 *   of the workspace files
	 * @throws NullPointerException if {@code testCase} is null
	 * @throws IllegalArgumentException if the test case does not fit this test's workspace files
	 */
	public Map<String, WorkspaceFile> workspaceTargets(TestCase testCase)
	{
		requireThat(testCase, "testCase").isNotNull();
		Set<String> names = new LinkedHashSet<>();
		for (VariableDeclaration variable : variables)
			names.add(variable.name());
		List<PromptTestFiles.WorkspaceEntry> described = describe(workspaceFiles, names);
		List<String> problems = PromptTestFiles.problems(names, namedInText(turns, assertions), described, testCase);
		if (!problems.isEmpty())
		{
			throw new IllegalArgumentException("The test case does not fit test '" + id + "': " +
				String.join("; ", problems));
		}
		Map<PromptTestFiles.WorkspaceEntry, String> targets = PromptTestFiles.substitute(described, testCase);
		Map<String, WorkspaceFile> byTarget = new LinkedHashMap<>();
		for (int i = 0; i < workspaceFiles.size(); ++i)
			byTarget.put(targets.get(described.get(i)), workspaceFiles.get(i));
		return byTarget;
	}

	/**
	 * Rejects an assertion that names a placeholder or a turn that its test does not have, or whose
	 * {@code tool} a test case's values make invalid.
	 *
	 * @param id the test's identifier
	 * @param names the names of the test's variables
	 * @param turnCount the number of turns that the test has
	 * @param testCases every test case of the test
	 * @param assertion the assertion
	 * @throws IllegalArgumentException if the assertion does not fit the test
	 */
	private static void requireValid(String id, Set<String> names, int turnCount, List<TestCase> testCases,
		Assertion assertion)
	{
		Set<String> named = new LinkedHashSet<>(Placeholders.scenarioNames(assertion.statement()));
		Optional<BuiltInTool> builtInTool = assertion.builtInTool();
		builtInTool.ifPresent(builtIn -> named.addAll(builtIn.placeholders()));
		for (String name : named)
		{
			if (!name.equals(TestCase.EXPECTED_OUTCOME) && !names.contains(name))
			{
				throw new IllegalArgumentException("The assertion '" + assertion.id() + "' of test '" + id +
					"' has the placeholder {{" + name + "}}, but the test defines no such variable. Defined " +
					"variables: " + names);
			}
		}
		if (builtInTool.isEmpty())
			return;
		BuiltInTool builtIn = builtInTool.get();
		Integer turn = builtIn.turn();
		if (turn != null && turn > turnCount)
		{
			throw new IllegalArgumentException("The assertion '" + assertion.id() + "' of test '" + id +
				"' examines turn " + turn + ", but the test has " + turnCount + " turns");
		}
		for (TestCase testCase : testCases)
		{
			try
			{
				builtIn.validate(textByName(testCase));
			}
			catch (IllegalArgumentException e)
			{
				throw new IllegalArgumentException("A test case of test '" + id + "' makes the tool of the " +
					"assertion '" + assertion.id() + "' invalid: " + e.getMessage(), e);
			}
		}
	}

	/**
	 * Returns every test case of the confirmed contract: the committed ones, then the new ones.
	 *
	 * @return the test cases
	 */
	public List<TestCase> allTestCases()
	{
		return concat(committedTestCases, testCases);
	}

	/**
	 * Returns this test as a {@value PromptTestFiles#SCENARIO} describes it: its variables, its turns, its expected
	 * behavior with its time limit, and the assertions that the contract author declared, without the one that CAT
	 * adds.
	 *
	 * @return the scenario
	 * @throws IllegalArgumentException if the test declares no variable, which a scenario requires, or its expected
	 *   behavior names a placeholder that no variable defines
	 */
	public Scenario scenario()
	{
		List<Assertion> declared = new ArrayList<>(assertions.size());
		for (MappedAssertion mapped : assertions)
			declared.add(mapped.assertion());
		return new Scenario(variables, turns, Optional.of(new ExpectedBehavior(expectedBehaviour, timeLimit)),
			declared);
	}

	/**
	 * Returns the assertion that CAT adds: a guard when every declared assertion is a guard, otherwise an effect.
	 *
	 * @return the assertion {@value TestCase#EXPECTED_OUTCOME}
	 */
	public Assertion expectedOutcome()
	{
		AssertionKind kind = AssertionKind.GUARD;
		for (MappedAssertion mapped : assertions)
		{
			if (mapped.assertion().kind() != AssertionKind.GUARD)
			{
				kind = AssertionKind.EFFECT;
				break;
			}
		}
		return Assertion.expectedOutcome(kind);
	}

	/**
	 * Returns every assertion that a trial must pass: the declared ones, then {@value TestCase#EXPECTED_OUTCOME}.
	 *
	 * @return the assertions
	 */
	public List<Assertion> allAssertions()
	{
		List<Assertion> all = new ArrayList<>();
		for (MappedAssertion mapped : assertions)
			all.add(mapped.assertion());
		all.add(expectedOutcome());
		return all;
	}

	/**
	 * Returns the assertions that the judge decides: those without a {@code tool}, then
	 * {@value TestCase#EXPECTED_OUTCOME}.
	 *
	 * @return the assertions
	 */
	public List<Assertion> judgedAssertions()
	{
		return allAssertions().stream().filter(assertion -> assertion.tool() == null).toList();
	}

	/**
	 * Returns the requirement that an assertion checks.
	 *
	 * @param assertionId the assertion's identifier
	 * @return the requirement's identifier, or empty for {@value TestCase#EXPECTED_OUTCOME}
	 * @throws NullPointerException if {@code assertionId} is null
	 * @throws IllegalArgumentException if the test has no such assertion
	 */
	public Optional<String> requirementId(String assertionId)
	{
		requireThat(assertionId, "assertionId").isNotNull();
		if (assertionId.equals(TestCase.EXPECTED_OUTCOME))
			return Optional.empty();
		for (MappedAssertion mapped : assertions)
		{
			if (mapped.assertion().id().equals(assertionId))
				return Optional.of(mapped.requirementId());
		}
		throw new IllegalArgumentException("Test '" + id + "' has no assertion '" + assertionId + "'");
	}

	/**
	 * Returns the text of each text value of a test case. Generated values are left out: they appear only in workspace
	 * file contents, never in turns, assertions or file names.
	 *
	 * @param testCase the test case
	 * @return the text of each text value, by variable, in the test case's order
	 * @throws NullPointerException if {@code testCase} is null
	 */
	public static Map<String, String> textByVariable(TestCase testCase)
	{
		requireThat(testCase, "testCase").isNotNull();
		Map<String, String> textByVariable = new LinkedHashMap<>();
		for (Map.Entry<String, TestValue> value : testCase.values().entrySet())
		{
			if (value.getValue() instanceof TestValue.Text text)
				textByVariable.put(value.getKey(), text.text());
		}
		return textByVariable;
	}

	/**
	 * Returns the text of each placeholder that an assertion of a test case may name: each variable's value and the
	 * expected outcome. Generated values are left out.
	 *
	 * @param testCase the test case
	 * @return the text of each placeholder, by name
	 * @throws NullPointerException if {@code testCase} is null
	 */
	public static Map<String, String> textByName(TestCase testCase)
	{
		Map<String, String> textByName = textByVariable(testCase);
		textByName.put(TestCase.EXPECTED_OUTCOME, testCase.expectedOutcome());
		return textByName;
	}

	/**
	 * Replaces every placeholder in the turns with a test case's values.
	 *
	 * @param testCase the test case
	 * @return the complete turn sequence of the test case
	 * @throws NullPointerException if {@code testCase} is null
	 */
	List<String> expandTurns(TestCase testCase)
	{
		Map<String, String> textByVariable = textByVariable(testCase);
		return turns.stream().map(turn -> Placeholders.fill(turn, textByVariable)).toList();
	}

	/**
	 * Runs the built-in tool of every assertion that has a {@code tool} on a trial's record.
	 *
	 * @param testCase the test case that the trial ran
	 * @param record the trial's record
	 * @return one result per built-in tool, in assertion order
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the record cannot be read
	 * @throws UndecidableToolException if the record lacks what a built-in tool observes
	 */
	public List<BuiltInToolResult> decide(TestCase testCase, TrialRecord record)
		throws IOException, UndecidableToolException
	{
		requireThat(record, "record").isNotNull();
		Map<String, String> textByName = textByName(testCase);
		List<BuiltInToolResult> results = new ArrayList<>();
		for (MappedAssertion mapped : assertions)
		{
			Assertion assertion = mapped.assertion();
			Optional<BuiltInTool> builtInTool = assertion.builtInTool();
			if (builtInTool.isEmpty())
				continue;
			BuiltInTool.Decision decision = builtInTool.get().decide(record, textByName);
			results.add(new BuiltInToolResult(assertion, decision.passed(), decision.observed()));
		}
		return results;
	}
}
