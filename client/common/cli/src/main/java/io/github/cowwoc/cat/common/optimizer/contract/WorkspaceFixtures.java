package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fixtures that one trial's workspace receives on top of its staged source: each fixture file of the trial's
 * prompt test with the test case's values in its path and content.
 *
 * @param fixtures        each fixture by its path relative to the workspace root, with {@code /} separators and the
 *                        placeholders of the path replaced
 * @param values          the test case's value of each variable, by name, which replace the placeholders of the
 *                        contents
 * @param expectedOutcome the test case's expected outcome, or an empty string if there are no fixtures
 */
public record WorkspaceFixtures(Map<String, FixtureFile> fixtures, Map<String, TestValue> values,
	String expectedOutcome)
{
	/**
	 * No fixtures.
	 */
	public static final WorkspaceFixtures NONE = new WorkspaceFixtures(Map.of(), Map.of(), "");

	/**
	 * Creates the fixtures of a trial.
	 *
	 * @param fixtures        each fixture by its path relative to the workspace root, with {@code /} separators and the
	 *                        placeholders of the path replaced
	 * @param values          the test case's value of each variable, by name
	 * @param expectedOutcome the test case's expected outcome, or an empty string if there are no fixtures
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code expectedOutcome} is blank while there are fixtures
	 */
	public WorkspaceFixtures
	{
		requireThat(fixtures, "fixtures").isNotNull();
		requireThat(values, "values").isNotNull();
		requireThat(expectedOutcome, "expectedOutcome").isNotNull();
		if (!fixtures.isEmpty())
			requireThat(expectedOutcome, "expectedOutcome").isNotBlank();
		fixtures = Collections.unmodifiableMap(new LinkedHashMap<>(fixtures));
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/**
	 * Returns the fixtures of a trial of a test case.
	 *
	 * @param test     the prompt test
	 * @param testCase a test case of {@code test}
	 * @return the trial's fixtures
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the test case does not fit the test's fixtures
	 */
	public static WorkspaceFixtures of(PromptTest test, TestCase testCase)
	{
		requireThat(test, "test").isNotNull();
		Map<String, FixtureFile> targets = test.fixtureTargets(testCase);
		if (targets.isEmpty())
			return NONE;
		return new WorkspaceFixtures(targets, testCase.values(), testCase.expectedOutcome());
	}

	/**
	 * Indicates whether the trial receives no fixtures.
	 *
	 * @return {@code true} if there are no fixtures
	 */
	public boolean isEmpty()
	{
		return fixtures.isEmpty();
	}

	/**
	 * Writes every fixture below a directory, creating the directories that it needs. Nothing is replaced: a path that
	 * exists already is refused.
	 *
	 * @param directory the directory that stands for the workspace root
	 * @throws NullPointerException       if {@code directory} is null
	 * @throws FileAlreadyExistsException if a fixture's path exists already
	 * @throws IOException                if a file cannot be written
	 */
	public void write(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		for (Map.Entry<String, FixtureFile> entry : fixtures.entrySet())
		{
			Path target = directory.resolve(entry.getKey());
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
				throw new FileAlreadyExistsException(target.toString());
			Files.createDirectories(target.getParent());
			Fixtures.writeText(entry.getValue().content(), target, values, expectedOutcome);
		}
	}
}
