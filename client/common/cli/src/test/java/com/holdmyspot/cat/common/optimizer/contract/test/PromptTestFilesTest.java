package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.net.StandardProtocolFamily.UNIX;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.ExamplesFile;
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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests reading prompt test scenarios and their example files.
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
	 * The mapper that writes the fixtures' {@value ExamplesFile#NAME} files.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Verifies that the recorded draft reader ignores the retired subsection and leaves the values file to its
	 * caller, while the authored reader refuses the same scenario with a conversion instruction.
	 *
	 * @throws IOException             if the draft cannot be written or read
	 * @throws ScenarioFormatException if the recorded draft is refused
	 */
	@Test
	public void recordsAndAuthoredFilesParseSeparately() throws IOException, ScenarioFormatException
	{
		// Requirement: R-L17
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = jvm.temporaryDirectory();
			Path draft = cleanup.add(Files.createTempDirectory(project, "recorded-draft"));
			Path directory = promptTest(draft, cleanup);
			Files.writeString(directory.resolve(PromptTestFiles.SCENARIO), SCENARIO + """

				### expected_outcome
				- **tool**: `exact-text {{expected_outcome}}`
				""");
			Files.writeString(directory.resolve(ValuesFile.NAME), """
				{"format_version": 1, "test_cases": [{"values": {"day": "Monday"}, "expected_outcome": "noon"}]}
				""");

			PromptTestFiles files = PromptTestFiles.readRecordedScenario(project, draft, draft, directory);
			requireThat(files.scenario().assertions().stream().map(Assertion::id).toList(), "assertionIds").
				isEqualTo(List.of("arrival"));
			requireThat(files.testCases(), "testCases").isEmpty();
			ScenarioFormatException refused = expectThrows(ScenarioFormatException.class,
				() -> PromptTestFiles.readScenario(project, draft, draft, directory));
			requireThat(refused.getMessage(), "refusal").contains("expected_outcome").contains("names a requirement");
		}
	}

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
	 * Verifies that a prompt test without {@value ExamplesFile#NAME} reads its example files and directories as the
	 * directory holds them, with or without a values file.
	 *
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void readsExamplesAsBeforeWithoutExamplesFile() throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = exampleTest(jvm.temporaryDirectory(), cleanup);
			Path examples = directory.resolve(PromptTestFiles.EXAMPLES);
			Files.createDirectories(examples.resolve("arrival/fail_wrong_day/project"));
			Map<String, Path> sources = Map.of("arrival/pass_same_day/replies.json",
				examples.resolve("arrival/pass_same_day/replies.json"));
			List<String> directories = List.of("arrival", "arrival/fail_wrong_day", "arrival/fail_wrong_day/project",
				"arrival/pass_same_day");

			PromptTestFiles withoutValues = PromptTestFiles.read(JSON, directory, directory, directory);
			requireThat(withoutValues.exampleSources(), "withoutValues.exampleSources()").isEqualTo(sources);
			requireThat(List.copyOf(withoutValues.exampleDirectories()), "withoutValues.exampleDirectories()").
				isEqualTo(directories);

			try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve(ValuesFile.NAME)))
			{
				ValuesFile.write(JSON, List.of(), writer);
			}
			PromptTestFiles withValues = PromptTestFiles.read(JSON, directory, directory, directory);
			requireThat(withValues.exampleSources(), "withValues.exampleSources()").isEqualTo(sources);
			requireThat(List.copyOf(withValues.exampleDirectories()), "withValues.exampleDirectories()").
				isEqualTo(directories);
		}
	}

	/**
	 * Verifies that each directory that {@value ExamplesFile#NAME} lists joins the example directories with its
	 * parents, whether a checkout left it out, left out the whole examples directory, or kept it empty.
	 *
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void readsListedEmptyDirectories() throws IOException, ScenarioFormatException
	{
		// Requirement: R-L14
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = exampleTest(jvm.temporaryDirectory(), cleanup);
			Path examples = directory.resolve(PromptTestFiles.EXAMPLES);
			Files.createDirectory(examples.resolve("arrival/pass_late"));
			writeExamplesFile(directory, listing("arrival/fail_wrong_day/project", "arrival/pass_late",
				"arrival/pass_same_day/initial"));

			PromptTestFiles files = PromptTestFiles.read(JSON, directory, directory, directory);
			requireThat(files.exampleSources(), "exampleSources").isEqualTo(Map.of("arrival/pass_same_day/replies.json",
				examples.resolve("arrival/pass_same_day/replies.json")));
			requireThat(List.copyOf(files.exampleDirectories()), "exampleDirectories").isEqualTo(List.of("arrival",
				"arrival/fail_wrong_day", "arrival/fail_wrong_day/project", "arrival/pass_late", "arrival/pass_same_day",
				"arrival/pass_same_day/initial"));

			Path bare = promptTest(jvm.temporaryDirectory(), cleanup);
			writeExamplesFile(bare, listing("arrival/fail_wrong_day"));
			PromptTestFiles bareFiles = PromptTestFiles.read(JSON, bare, bare, bare);
			requireThat(bareFiles.exampleSources(), "bareFiles.exampleSources()").isEmpty();
			requireThat(List.copyOf(bareFiles.exampleDirectories()), "bareFiles.exampleDirectories()").
				isEqualTo(List.of("arrival", "arrival/fail_wrong_day"));
		}
	}

	/**
	 * Returns {@value ExamplesFile#NAME} contents that do not follow the file's format.
	 *
	 * @return the contents, each in an array
	 */
	@DataProvider
	public Object[][] malformedExamplesFiles()
	{
		return new Object[][]
			{
				// Not exactly one JSON object
				{"{"},
				{"[]"},
				{"{\"format_version\": 1, \"empty_directories\": []} {}"},
				// A duplicate, unknown or missing field
				{"{\"format_version\": 1, \"format_version\": 1, \"empty_directories\": []}"},
				{"{\"format_version\": 1, \"empty_directories\": [], \"directories\": []}"},
				{"{\"format_version\": 1}"},
				// A wrong version or value type
				{"{\"format_version\": 2, \"empty_directories\": []}"},
				{"{\"format_version\": \"1\", \"empty_directories\": []}"},
				{"{\"format_version\": 1, \"empty_directories\": \"arrival/pass_late\"}"},
				{"{\"format_version\": 1, \"empty_directories\": [1]}"},
				// The same directory twice
				{"{\"format_version\": 1, \"empty_directories\": [\"arrival/pass_late\", \"arrival/pass_late\"]}"}
			};
	}

	/**
	 * Verifies that an {@value ExamplesFile#NAME} that does not follow the file's format is refused by name.
	 *
	 * @param content the file's content
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the file is refused
	 */
	@Test(dataProvider = "malformedExamplesFiles", expectedExceptions = ScenarioFormatException.class,
		expectedExceptionsMessageRegExp = "examples[.]json: .*")
	public void refusesMalformedExamplesFile(String content) throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = exampleTest(jvm.temporaryDirectory(), cleanup);
			writeExamplesFile(directory, content);

			PromptTestFiles.read(JSON, directory, directory, directory);
		}
	}

	/**
	 * Verifies that an {@value ExamplesFile#NAME} reached through a symbolic link is refused even if its target is
	 * valid.
	 *
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the linked file is refused
	 */
	@Test(expectedExceptions = ScenarioFormatException.class, expectedExceptionsMessageRegExp =
		"examples[.]json: .*regular file.*")
	public void refusesLinkedExamplesFile() throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = exampleTest(jvm.temporaryDirectory(), cleanup);
			Files.writeString(directory.resolve("listing.json"), listing("arrival/pass_late"));
			Files.createSymbolicLink(directory.resolve(ExamplesFile.NAME), Path.of("listing.json"));

			PromptTestFiles.read(JSON, directory, directory, directory);
		}
	}

	/**
	 * Returns listed directories that do not name a directory inside the examples directory.
	 *
	 * @return the directories, each in an array
	 */
	@DataProvider
	public Object[][] outsideDirectories()
	{
		return new Object[][]
			{
				// A path that climbs out of the examples directory
				{"../outside"},
				{"arrival/../../outside"},
				// A path that is absolute or empty
				{"/arrival/pass_late"},
				{""},
				// A path with an empty, current-directory or non-portable name
				{"arrival//pass_late"},
				{"./arrival/pass_late"},
				{"arrival\\pass_late"},
				{"C:/arrival"}
			};
	}

	/**
	 * Verifies that a listed directory that does not lie inside the examples directory is refused, naming
	 * {@value ExamplesFile#NAME}.
	 *
	 * @param listed the listed directory
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the listed directory is refused
	 */
	@Test(dataProvider = "outsideDirectories", expectedExceptions = ScenarioFormatException.class,
		expectedExceptionsMessageRegExp = "examples[.]json: .*inside examples/.*")
	public void refusesDirectoryOutsideExamples(String listed) throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = exampleTest(jvm.temporaryDirectory(), cleanup);
			writeExamplesFile(directory, listing(listed));

			PromptTestFiles.read(JSON, directory, directory, directory);
		}
	}

	/**
	 * Returns listed directories that name an example file or lie below one.
	 *
	 * @return the directories, each in an array
	 */
	@DataProvider
	public Object[][] fileDirectories()
	{
		return new Object[][]
			{
				// The file itself
				{"arrival/pass_same_day/replies.json"},
				// A directory below the file
				{"arrival/pass_same_day/replies.json/initial"}
			};
	}

	/**
	 * Verifies that a listed directory whose path names an example file is refused, naming {@value ExamplesFile#NAME}.
	 *
	 * @param listed the listed directory
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the listed directory is refused
	 */
	@Test(dataProvider = "fileDirectories", expectedExceptions = ScenarioFormatException.class,
		expectedExceptionsMessageRegExp =
			"examples[.]json: .*names the file examples/arrival/pass_same_day/replies[.]json.*")
	public void refusesDirectoryNamingFile(String listed) throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = exampleTest(jvm.temporaryDirectory(), cleanup);
			writeExamplesFile(directory, listing(listed));

			PromptTestFiles.read(JSON, directory, directory, directory);
		}
	}

	/**
	 * Returns listings that name a directory that holds an entry.
	 *
	 * @return the listed directories, each in an array
	 */
	@DataProvider
	public Object[][] nonEmptyDirectories()
	{
		return new Object[][]
			{
				// A directory that holds an example file
				{List.of("arrival/pass_same_day")},
				// A directory that holds only directories
				{List.of("arrival")},
				// A listed directory below another
				{List.of("arrival/fail_wrong_day", "arrival/fail_wrong_day/project")}
			};
	}

	/**
	 * Verifies that a listed directory that holds an entry is refused, naming {@value ExamplesFile#NAME}.
	 *
	 * @param listed the listed directories
	 * @throws IOException             if the fixture cannot be written or read
	 * @throws ScenarioFormatException if the listed directory is refused
	 */
	@Test(dataProvider = "nonEmptyDirectories", expectedExceptions = ScenarioFormatException.class,
		expectedExceptionsMessageRegExp = "examples[.]json: .*holds .*")
	public void refusesNonEmptyDirectory(List<String> listed) throws IOException, ScenarioFormatException
	{
		// Requirement: R-L15
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = exampleTest(jvm.temporaryDirectory(), cleanup);
			writeExamplesFile(directory, listing(listed.toArray(String[]::new)));

			PromptTestFiles.read(JSON, directory, directory, directory);
		}
	}

	/**
	 * Creates a prompt test directory whose only example file is
	 * {@code examples/arrival/pass_same_day/replies.json}.
	 *
	 * @param temporaryDirectory the directory that holds the test's temporary inputs
	 * @param cleanup            removes the prompt test directory
	 * @return the prompt test directory
	 * @throws IOException if a file cannot be written
	 */
	private static Path exampleTest(Path temporaryDirectory, TemporaryDirectories cleanup) throws IOException
	{
		Path directory = promptTest(temporaryDirectory, cleanup);
		Path example = Files.createDirectories(directory.resolve("examples/arrival/pass_same_day"));
		Files.writeString(example.resolve("replies.json"), "[[\"River arrives Monday.\"]]");
		return directory;
	}

	/**
	 * Returns the content of an {@value ExamplesFile#NAME} that lists directories.
	 *
	 * @param directories the listed directories
	 * @return the file's content
	 */
	private static String listing(String... directories)
	{
		return JSON.writeValueAsString(Map.of("format_version", 1, "empty_directories", List.of(directories)));
	}

	/**
	 * Writes a prompt test's {@value ExamplesFile#NAME}.
	 *
	 * @param directory the prompt test directory
	 * @param content   the file's content
	 * @throws IOException if the file cannot be written
	 */
	private static void writeExamplesFile(Path directory, String content) throws IOException
	{
		Files.writeString(directory.resolve(ExamplesFile.NAME), content);
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
