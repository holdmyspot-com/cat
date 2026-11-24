package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.net.StandardProtocolFamily.UNIX;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.ScenarioFormatException;
import com.holdmyspot.cat.common.optimizer.contract.ValuesFile;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how {@link PromptTestFiles} reads the assertion examples of a prompt test.
 */
public final class PromptTestFilesTest
{
	/**
	 * A prompt test whose examples describe the answer to its single turn.
	 */
	private static final String SCENARIO = """
		## Variables

		- **day**: The day in the question

		## Scenario

		When does River arrive on {{day}}?

		## Assertions

		### arrival

		- **kind**: guard
		- **description**: The reply states {{expected_outcome}}.
		""";

	/**
	 * Verifies that reading a prompt test preserves its example files and empty directories in path order.
	 *
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void readsExampleFilesAndDirectories() throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = promptTest(jvm.temporaryDirectory(), cleanup);
			PromptTestFiles earlier = PromptTestFiles.readScenario(directory, directory, directory);
			requireThat(earlier.exampleSources(), "absentExampleSources").isEmpty();
			requireThat(earlier.exampleDirectories(), "absentExampleDirectories").isEmpty();

			Path examples = directory.resolve(PromptTestFiles.EXAMPLES);
			Path pass = Files.createDirectories(examples.resolve("arrival/pass_same_day"));
			Path fail = Files.createDirectories(examples.resolve("arrival/fail_wrong_day"));
			Files.createDirectory(fail.resolve("project"));
			Path passReplies = Files.writeString(pass.resolve("replies.json"), "[[\"River arrives Monday.\"]]");
			Path failReplies = Files.writeString(fail.resolve("replies.json"), "[[\"River arrives Tuesday.\"]]");
			JsonMapper json = JsonMapper.builder().build();
			try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve(ValuesFile.NAME)))
			{
				ValuesFile.write(json, List.of(), writer);
			}

			PromptTestFiles files = PromptTestFiles.read(json, directory, directory, directory);
			requireThat(files.exampleSources(), "exampleSources").isEqualTo(Map.of(
				"arrival/pass_same_day/replies.json", passReplies, "arrival/fail_wrong_day/replies.json", failReplies));
			requireThat(List.copyOf(files.exampleSources().keySet()), "examplePaths").isEqualTo(
				List.of("arrival/fail_wrong_day/replies.json", "arrival/pass_same_day/replies.json"));
			requireThat(List.copyOf(files.exampleDirectories()), "exampleDirectories").isEqualTo(List.of(
				"arrival", "arrival/fail_wrong_day", "arrival/fail_wrong_day/project", "arrival/pass_same_day"));
		}
	}

	/**
	 * Verifies that an examples directory reached through a symbolic link is refused even if its target is valid.
	 *
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the linked examples directory is refused
	 */
	@Test(expectedExceptions = ScenarioFormatException.class, expectedExceptionsMessageRegExp =
		".*examples.*directory.*")
	public void refusesLinkedExamplesRoot() throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = promptTest(jvm.temporaryDirectory(), cleanup);
			Files.createDirectory(directory.resolve("replies"));
			Files.createSymbolicLink(directory.resolve("examples"), Path.of("replies"));

			PromptTestFiles.readScenario(directory, directory, directory);
		}
	}

	/**
	 * Verifies that a regular file cannot stand in for an examples directory.
	 *
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the file at the examples directory's path is refused
	 */
	@Test(expectedExceptions = ScenarioFormatException.class, expectedExceptionsMessageRegExp =
		".*examples.*directory.*")
	public void refusesFileAtExamplesRoot() throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = promptTest(jvm.temporaryDirectory(), cleanup);
			Files.createFile(directory.resolve("examples"));

			PromptTestFiles.readScenario(directory, directory, directory);
		}
	}

	/**
	 * Verifies that a linked example file is refused even if it points to a regular file in the same example.
	 *
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the linked example file is refused
	 */
	@Test(expectedExceptions = ScenarioFormatException.class, expectedExceptionsMessageRegExp =
		".*examples/arrival/pass_same_day/replies[.]json.*regular file.*")
	public void refusesLinkedExampleFile() throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = promptTest(jvm.temporaryDirectory(), cleanup);
			Path example = Files.createDirectories(directory.resolve("examples/arrival/pass_same_day"));
			Files.createFile(example.resolve("original"));
			Files.createSymbolicLink(example.resolve("replies.json"), Path.of("original"));

			PromptTestFiles.readScenario(directory, directory, directory);
		}
	}

	/**
	 * Verifies that an example containing a special file is refused before its files can be used.
	 *
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws InterruptedException    if interrupted while creating the pipe
	 * @throws ScenarioFormatException if the special file is refused
	 */
	@Test(expectedExceptions = ScenarioFormatException.class, expectedExceptionsMessageRegExp =
		".*examples/special.*regular file.*")
	public void refusesSpecialExampleFile() throws IOException, InterruptedException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = promptTest(jvm.temporaryDirectory(), cleanup);
			Path examples = Files.createDirectory(directory.resolve("examples"));
			Path special = examples.resolve("special");
			// Windows has no named pipe entries in its filesystem.
			if (Platform.current().isWindows())
			{
				try (ServerSocketChannel server = ServerSocketChannel.open(UNIX))
				{
					server.bind(UnixDomainSocketAddress.of(special));
					PromptTestFiles.readScenario(directory, directory, directory);
					return;
				}
			}
			try (Process mkfifo = new ProcessBuilder("mkfifo", special.toString()).
				directory(directory.toFile()).start())
			{
				requireThat(mkfifo.waitFor(), "mkfifo").isEqualTo(0);
			}
			PromptTestFiles.readScenario(directory, directory, directory);
		}
	}

	/**
	 * Creates a prompt test directory with its scenario and no other files.
	 *
	 * @param temporaryDirectory the directory that holds the test's temporary inputs
	 * @param cleanup            removes the prompt test directory
	 * @return the prompt test directory
	 * @throws IOException if the scenario cannot be written
	 */
	private static Path promptTest(Path temporaryDirectory, TemporaryDirectories cleanup) throws IOException
	{
		Path directory = cleanup.add(Files.createTempDirectory(temporaryDirectory, "p"));
		Files.writeString(directory.resolve(PromptTestFiles.SCENARIO), SCENARIO);
		return directory;
	}
}
