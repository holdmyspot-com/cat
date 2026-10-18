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
 * The test workspace of one trial: the files that the trial's workspace receives on top of its staged source, each
 * workspace file or link of the trial's prompt test with the test case's values in its path, content and link target.
 *
 * @param files           each workspace file by its path relative to the workspace root, with {@code /} separators and
 *                        the placeholders of the path replaced
 * @param values          the test case's value of each variable, by name, which replace the placeholders of the
 *                        contents and link targets
 * @param expectedOutcome the test case's expected outcome, or an empty string if there are no files
 */
public record TestWorkspace(Map<String, WorkspaceFile> files, Map<String, TestValue> values,
	String expectedOutcome) implements WorkspaceFiles
{
	/**
	 * No files.
	 */
	public static final TestWorkspace NONE = new TestWorkspace(Map.of(), Map.of(), "");

	/**
	 * Creates the test workspace of a trial.
	 *
	 * @param files           each workspace file by its path relative to the workspace root, with {@code /} separators
	 *                        and the placeholders of the path replaced
	 * @param values          the test case's value of each variable, by name
	 * @param expectedOutcome the test case's expected outcome, or an empty string if there are no files
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code expectedOutcome} is blank while there are files
	 */
	public TestWorkspace
	{
		requireThat(files, "files").isNotNull();
		requireThat(values, "values").isNotNull();
		requireThat(expectedOutcome, "expectedOutcome").isNotNull();
		if (!files.isEmpty())
			requireThat(expectedOutcome, "expectedOutcome").isNotBlank();
		files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/**
	 * Returns the test workspace of a trial of a test case.
	 *
	 * @param test     the prompt test
	 * @param testCase a test case of {@code test}
	 * @return the trial's test workspace
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the test case does not fit the test's workspace files
	 */
	public static TestWorkspace of(PromptTest test, TestCase testCase)
	{
		requireThat(test, "test").isNotNull();
		Map<String, WorkspaceFile> targets = test.workspaceTargets(testCase);
		if (targets.isEmpty())
			return NONE;
		return new TestWorkspace(targets, testCase.values(), testCase.expectedOutcome());
	}

	@Override
	public boolean isEmpty()
	{
		return files.isEmpty();
	}

	@Override
	public void write(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		for (Map.Entry<String, WorkspaceFile> entry : files.entrySet())
		{
			Path target = directory.resolve(entry.getKey());
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
				throw new FileAlreadyExistsException(target.toString());
			Files.createDirectories(target.getParent());
			WorkspaceFile file = entry.getValue();
			if (file.link())
				TestWorkspaces.writeLink(file.content(), target, values, expectedOutcome);
			else
				TestWorkspaces.writeText(file.content(), target, values, expectedOutcome);
		}
	}
}
