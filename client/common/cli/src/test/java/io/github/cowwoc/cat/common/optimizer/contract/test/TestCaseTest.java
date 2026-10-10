package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.LogLines;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.Scenario;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;
import io.github.cowwoc.cat.common.optimizer.contract.ValueGenerators;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;

import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Tests test cases: generated values and their validation against a prompt test.
 */
public final class TestCaseTest
{
	/**
	 * A generated value of 50 log lines.
	 */
	private static final TestValue.Generated LOGS = new TestValue.Generated("log_lines", 1, 5, Map.of("count", 50L));

	/**
	 * Creates the test fixture.
	 */
	public TestCaseTest()
	{
	}

	/**
	 * Returns a prompt test whose turn names {@code notes}, with a fixture {@code docs/{{notes}}} whose content names
	 * {@code before}, and a binary fixture.
	 *
	 * @param extraFixtures more fixtures
	 * @return the prompt test's files
	 */
	private static PromptTestFiles promptTest(PromptTestFiles.Fixture... extraFixtures)
	{
		Scenario scenario = new Scenario(List.of(new VariableDeclaration("notes", "The notes file name"),
			new VariableDeclaration("before", "Log text before the entry")),
			List.of("When was it published? See `docs/{{notes}}`."),
			List.of(new Assertion("answer_given", AssertionKind.GUARD, null, "The reply states {{expected_outcome}}.")));
		List<PromptTestFiles.Fixture> fixtures = new ArrayList<>();
		fixtures.add(new PromptTestFiles.Fixture("docs/{{notes}}", true, Set.of("before")));
		fixtures.add(new PromptTestFiles.Fixture("logo.png", false, Set.of()));
		fixtures.addAll(List.of(extraFixtures));
		return new PromptTestFiles(Path.of("/project/tests"), Path.of("/project/tests/case"), scenario, fixtures,
			List.of());
	}

	/**
	 * Returns a test case.
	 *
	 * @param notes  the value of {@code notes}
	 * @param before the value of {@code before}
	 * @return the test case
	 */
	private static TestCase testCase(TestValue notes, TestValue before)
	{
		Map<String, TestValue> values = new LinkedHashMap<>();
		values.put("notes", notes);
		values.put("before", before);
		return new TestCase(values, "2025-03-04");
	}

	/**
	 * Verifies that CAT gives two identical generator calls different seeds at the generator's current version.
	 */
	@Test
	public void identicalCallsGetDifferentSeeds()
	{
		// Requirement: R-C21
		Set<Long> taken = new HashSet<>();
		SplittableRandom random = new SplittableRandom(3);
		TestValue.Generated first = ValueGenerators.create("log_lines", Map.of("count", 100L), random, taken);
		TestValue.Generated second = ValueGenerators.create("log_lines", Map.of("count", 100L), random, taken);
		requireThat(first.version(), "first.version()").isEqualTo(LogLines.INSTANCE.currentVersion());
		requireThat(first.parameters(), "first.parameters()").isEqualTo(Map.of("count", 100L));
		requireThat(first.seed(), "first.seed()").isNotEqualTo(second.seed());
		requireThat(taken, "taken").isEqualTo(Set.of(first.seed(), second.seed()));
	}

	/**
	 * Verifies that a seed that another value already has is never reused, even when the random source repeats it.
	 */
	@Test
	public void takenSeedIsSkipped()
	{
		// Requirement: R-C21
		long repeated = new SplittableRandom(8).nextLong();
		Set<Long> taken = new HashSet<>(Set.of(repeated));
		TestValue.Generated value = ValueGenerators.create("log_lines", Map.of("count", 1L), new SplittableRandom(8),
			taken);
		requireThat(value.seed(), "seed").isNotEqualTo(repeated);
	}

	/**
	 * Verifies that an unknown generator and an unknown version are refused.
	 */
	@Test
	public void refusesUnknownGenerator()
	{
		// Requirement: R-C21
		try
		{
			ValueGenerators.create("lorem", Map.of("count", 1L), new SplittableRandom(1), new HashSet<>());
			throw new AssertionError("An unknown generator was accepted");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("lorem");
		}
		try
		{
			new TestValue.Generated("log_lines", 9, 1, Map.of("count", 1L));
			throw new AssertionError("An unknown version was accepted");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("version 9");
		}
	}

	/**
	 * Verifies that a test case with text values and a generated value used only in fixture contents is valid.
	 */
	@Test
	public void acceptsGeneratedValueInFixtureContents()
	{
		// Requirement: R-C21
		requireThat(promptTest().problems(testCase(new TestValue.Text("notes.md"), LOGS)), "problems").isEmpty();
	}

	/**
	 * Verifies that a test case must give exactly one value per declared variable.
	 */
	@Test
	public void refusesMissingOrUnknownValue()
	{
		// Requirement: R-C21
		PromptTestFiles files = promptTest();
		List<String> missing = files.problems(new TestCase(Map.of("notes", new TestValue.Text("a.md")), "x"));
		requireThat(missing.toString(), "missing").contains("before");
		Map<String, TestValue> values = new LinkedHashMap<>();
		values.put("notes", new TestValue.Text("a.md"));
		values.put("before", LOGS);
		values.put("after", new TestValue.Text("text"));
		requireThat(files.problems(new TestCase(values, "x")).toString(), "unknown").contains("after");
	}

	/**
	 * Verifies that a generated value is refused in a turn, in a fixture's name and where no fixture's content uses
	 * it.
	 */
	@Test
	public void refusesGeneratedValueOutsideContents()
	{
		// Requirement: R-C21
		PromptTestFiles files = promptTest();
		requireThat(files.problems(testCase(LOGS, LOGS)).toString(), "turnAndName").contains("notes");

		PromptTestFiles unused = new PromptTestFiles(files.root(), files.directory(), files.scenario(),
			List.of(new PromptTestFiles.Fixture("docs/{{notes}}", true, Set.of())), List.of());
		requireThat(unused.problems(testCase(new TestValue.Text("a.md"), LOGS)).toString(), "unused").
			contains("before");
	}

	/**
	 * Verifies that a value used in a fixture's name must be one valid file name segment.
	 */
	@Test
	public void refusesInvalidFileNameValue()
	{
		// Requirement: R-C21
		PromptTestFiles files = promptTest();
		List<String> names = List.of("", ".", "..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b",
			"a\tb");
		for (int i = 0; i < names.size(); ++i)
		{
			requireThat(files.problems(testCase(new TestValue.Text(names.get(i)), LOGS)), "problems(" + i + ")").
				isNotEmpty();
		}
	}

	/**
	 * Verifies that two fixtures that a test case's values give the same path, or a file and a directory of the same
	 * path, are refused.
	 */
	@Test
	public void refusesFixtureCollision()
	{
		// Requirement: R-C21
		PromptTestFiles same = promptTest(new PromptTestFiles.Fixture("docs/notes.md", true, Set.of()));
		requireThat(same.problems(testCase(new TestValue.Text("notes.md"), LOGS)).toString(), "same").
			contains("docs/notes.md");
		PromptTestFiles nested = promptTest(new PromptTestFiles.Fixture("docs/notes.md/inner.txt", true, Set.of()));
		requireThat(nested.problems(testCase(new TestValue.Text("notes.md"), LOGS)).toString(), "nested").
			contains("docs/notes.md");
		requireThat(same.problems(testCase(new TestValue.Text("other.md"), LOGS)), "distinct").isEmpty();
	}

	/**
	 * Verifies that each fixture's workspace path replaces its placeholders.
	 */
	@Test
	public void targetsReplaceNamePlaceholders()
	{
		// Requirement: R-C21
		PromptTestFiles files = promptTest();
		Map<PromptTestFiles.Fixture, String> targets = files.targets(testCase(new TestValue.Text("v2.md"), LOGS));
		requireThat(targets, "targets").isEqualTo(Map.of(files.fixtures().get(0), "docs/v2.md",
			files.fixtures().get(1), "logo.png"));
	}
}
