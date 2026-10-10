package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Copies a prompt test's fixtures into a trial's workspace for one test case.
 */
public final class Fixtures
{
	/**
	 * Prevents instantiation.
	 */
	private Fixtures()
	{
	}

	/**
	 * Copies every fixture to its path in a workspace. Placeholders are replaced in file names and in the content of
	 * files that are valid UTF-8; other files are copied unchanged. Nothing is written unless every fixture can be.
	 *
	 * @param files     the prompt test's files
	 * @param testCase  a valid test case of the prompt test
	 * @param workspace the trial's workspace root, which already holds the staged source
	 * @throws NullPointerException       if any of the arguments are null
	 * @throws IllegalArgumentException   if the prompt test rejects the test case
	 * @throws FileAlreadyExistsException if a fixture's path already exists in the workspace, or one of its parent
	 *                                    directories exists as another kind of file
	 * @throws IOException                if a file cannot be read or written
	 */
	public static void copy(PromptTestFiles files, TestCase testCase, Path workspace) throws IOException
	{
		requireThat(files, "files").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		Map<PromptTestFiles.Fixture, String> targets = files.targets(testCase);
		for (String target : targets.values())
		{
			if (Files.exists(workspace.resolve(target), LinkOption.NOFOLLOW_LINKS))
				throw new FileAlreadyExistsException(target, null, "A fixture lands at a path that the workspace holds");
			for (int slash = target.indexOf('/'); slash != -1; slash = target.indexOf('/', slash + 1))
			{
				String parent = target.substring(0, slash);
				Path path = workspace.resolve(parent);
				if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
				{
					throw new FileAlreadyExistsException(parent, null, "A fixture needs this path as a directory, but " +
						"the workspace holds a file there");
				}
			}
		}

		Path sources = files.directory().resolve(PromptTestFiles.FIXTURES);
		List<Path> created = new ArrayList<>();
		try
		{
			for (Map.Entry<PromptTestFiles.Fixture, String> entry : targets.entrySet())
			{
				PromptTestFiles.Fixture fixture = entry.getKey();
				Path source = sources.resolve(fixture.path());
				Path target = workspace.resolve(entry.getValue());
				createParents(workspace, target.getParent(), created);
				if (fixture.text())
					writeText(Files.readString(source), target, testCase.values(), testCase.expectedOutcome());
				else
					Files.copy(source, target);
				created.add(target);
			}
		}
		catch (IOException | RuntimeException e)
		{
			for (Path path : created.reversed())
			{
				try
				{
					Files.deleteIfExists(path);
				}
				catch (IOException suppressed)
				{
					e.addSuppressed(suppressed);
				}
			}
			throw e;
		}
	}

	/**
	 * Creates the missing directories of a path below the workspace.
	 *
	 * @param workspace the workspace root
	 * @param directory the directory to create
	 * @param created   receives each directory that this method creates, parents first
	 * @throws IOException if a directory cannot be created
	 */
	private static void createParents(Path workspace, Path directory, List<Path> created) throws IOException
	{
		if (directory.equals(workspace) || Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			return;
		createParents(workspace, directory.getParent(), created);
		Files.createDirectory(directory);
		created.add(directory);
	}

	/**
	 * Writes a new text file with its placeholders replaced: each declared variable's {@code {{name}}} by its value,
	 * expanding a generated value, and {@code {{expected_outcome}}} by the expected outcome. Other text in double
	 * braces stays as it is.
	 *
	 * @param content         the fixture's content
	 * @param target          the path to write, which must not exist
	 * @param values          the test case's value of each variable, by name
	 * @param expectedOutcome the test case's expected outcome
	 * @throws IOException if the file cannot be written
	 */
	static void writeText(String content, Path target, Map<String, TestValue> values, String expectedOutcome)
		throws IOException
	{
		try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
		{
			Matcher placeholder = Placeholders.SCENARIO_PLACEHOLDER.matcher(content);
			int end = 0;
			while (placeholder.find())
			{
				out.write(content, end, placeholder.start() - end);
				String name = placeholder.group(1);
				TestValue value = values.get(name);
				if (name.equals(TestCase.EXPECTED_OUTCOME))
					out.write(expectedOutcome);
				else if (value instanceof TestValue.Text text)
					out.write(text.text());
				else if (value instanceof TestValue.Generated generated)
					generated.write(out);
				else
					out.write(placeholder.group());
				end = placeholder.end();
			}
			out.write(content, end, content.length() - end);
		}
	}
}
