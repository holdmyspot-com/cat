package io.github.cowwoc.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.proposal.ContractAuthorProtocol;
import io.github.cowwoc.cat.common.optimizer.proposal.ContractTools;
import io.github.cowwoc.cat.common.optimizer.proposal.DraftDirectory;
import io.github.cowwoc.cat.common.optimizer.proposal.ProfileEvidence;
import io.github.cowwoc.cat.common.optimizer.proposal.test.ContractToolSessions.Report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@code contract workspace}, {@link ContractTools#workspace}.
 */
public final class ContractWorkspaceTest
{
	/**
	 * The profile evidence of the sessions, whose inventory holds two profiles.
	 */
	private static final ProfileEvidence PROFILES = new ProfileEvidence(ContractFixtures.TWO_PROFILES,
		ContractFixtures.ranking(ContractFixtures.TWO_PROFILES), Map.of());
	/**
	 * The directory of the {@code safety} prompt test, relative to the draft directory.
	 */
	private static final Path SAFETY = Path.of(".cat/tests/CLAUDE/safety");
	/**
	 * The fixture of the {@code safety} prompt test that names the directory that the user asks to delete.
	 */
	private static final String README = "Delete {{target}} only after asking.\nKeep the rest.\n";
	/**
	 * Holds the projects of the sessions that the tests create.
	 */
	private final TemporaryDirectories directories = new TemporaryDirectories();
	private final JsonMapper json = JsonMapper.builder().build();
	/**
	 * A contract author whose harness keeps 40 bytes of a tool output whole for the profile {@code big/high} and does
	 * not state its limit for {@code small/high}.
	 */
	private final ContractAuthorProtocol contractAuthor = ContractToolSessions.contractAuthor(json,
		ContractWorkspaceTest::limit);

	/**
	 * Creates the test.
	 */
	public ContractWorkspaceTest()
	{
	}

	/**
	 * Returns the tool-output limit of a profile.
	 *
	 * @param profile a profile of the inventory
	 * @return 40 bytes for {@code big/high}, otherwise empty
	 */
	private static OptionalLong limit(TrialProfile profile)
	{
		if (profile.equals(ContractFixtures.BIG))
			return OptionalLong.of(40);
		return OptionalLong.empty();
	}

	/**
	 * Removes the directories of the sessions that the tests created.
	 *
	 * @throws IOException if a directory cannot be removed
	 */
	@AfterClass
	public void removeDirectories() throws IOException
	{
		directories.close();
	}

	/**
	 * Returns a session's project whose {@code safety} prompt test has two workspace files, one of whose path and content
	 * name the directory that the user asks to delete.
	 *
	 * @param change edits the contract author output further
	 * @return the session's context file
	 * @throws IOException if a file cannot be written
	 */
	private Path project(Consumer<ObjectNode> change) throws IOException
	{
		Path project = directories.add(Files.createTempDirectory("contract-workspace-"));
		return ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal ->
			{
				ArrayNode workspaceFiles = ((ObjectNode) proposal.get("tests").get(2)).putArray("workspace_files");
				workspaceFiles.addObject().put("path", "{{target}}/README.md").put("content", README);
				workspaceFiles.addObject().put("path", "notes.txt").put("content", "ok");
				change.accept(proposal);
			}), project);
	}

	/**
	 * Returns the content of every file below a directory.
	 *
	 * @param directory the directory
	 * @return the content of each file, by its path relative to {@code directory}
	 * @throws IOException if a file cannot be read
	 */
	private static Map<String, String> files(Path directory) throws IOException
	{
		Map<String, String> files = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.filter(Files::isRegularFile).toList())
				files.put(directory.relativize(path).toString(), Files.readString(path, UTF_8));
		}
		return files;
	}

	/**
	 * Verifies that {@code contract workspace} writes one test case's workspace files, with its values in their paths
	 * and content, into the named directory and nowhere else, and reports each file's size and line count and how its
	 * size compares with the tool-output limit of each trial profile of the test, naming the profiles whose limit the
	 * harness does not state.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void reportsSizesAgainstLimits() throws IOException
	{
		// Requirement: R-C27
		Path contextFile = project(_ ->
		{
		});
		Path project = contextFile.getParent();
		Map<String, String> before = files(project);
		Path output = project.resolve("sized");

		Report report = ContractToolSessions.workspace(json, contextFile, SAFETY, 2, output);

		String readme = README.replace("{{target}}", "cache");
		int readmeBytes = readme.getBytes(UTF_8).length;
		String newline = System.lineSeparator();
		requireThat(report.output(), "output").isEqualTo("Wrote the workspace files of test case 2 of " +
			"`.cat/tests/CLAUDE/safety` to `" + output + "`." + newline + newline +
			"Tool-output limit of each trial profile of the test:" + newline +
			"- 40 bytes: `big/high`" + newline +
			"- not known, since the harness does not state it: `small/high`" + newline + newline +
			"Files:" + newline +
			"- `cache/README.md`: " + readmeBytes + " bytes, 2 line(s); " + (readmeBytes - 40) + " bytes over the " +
			"40-byte limit" + newline +
			"- `notes.txt`: 2 bytes, 1 line(s); 38 bytes under the 40-byte limit" + newline);
		requireThat(report.exitCode(), "exitCode").isEqualTo(0);
		requireThat(files(output), "written").isEqualTo(Map.of(Path.of("cache/README.md").toString(), readme,
			"notes.txt", "ok"));
		Map<String, String> after = files(project);
		after.keySet().removeIf(path -> Path.of(path).startsWith("sized"));
		requireThat(after, "after").isEqualTo(before);
	}

	/**
	 * Verifies that {@code contract workspace} writes nothing and exits with a nonzero status when the output directory
	 * exists or lies in the draft directory, when the test directory names no prompt test of the draft directory, and
	 * when the test has no test case with the given number.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void refusesInvalidArguments() throws IOException
	{
		// Requirement: R-C27
		Path contextFile = project(_ ->
		{
		});
		Path project = contextFile.getParent();
		Files.createDirectory(project.resolve("taken"));
		Map<String, String> before = files(project);

		Report existing = ContractToolSessions.workspace(json, contextFile, SAFETY, 1, project.resolve("taken"));
		requireThat(existing.output(), "existing").contains("must be a new directory outside the draft directory");
		Path inDraft = project.resolve(DraftDirectory.NAME).resolve("sized");
		Report drafted = ContractToolSessions.workspace(json, contextFile, SAFETY, 1, inDraft);
		requireThat(drafted.output(), "drafted").contains("must be a new directory outside the draft directory");
		Path output = project.resolve("sized");
		Report escaping = ContractToolSessions.workspace(json, contextFile, Path.of("../safety"), 1, output);
		requireThat(escaping.output(), "escaping").contains("names no prompt test in the draft directory");
		Report noTest = ContractToolSessions.workspace(json, contextFile, Path.of(".cat/tests/CLAUDE"), 1, output);
		requireThat(noTest.output(), "noTest").contains("names no prompt test in the draft directory");
		Report noTestCase = ContractToolSessions.workspace(json, contextFile, SAFETY, 9, output);
		requireThat(noTestCase.output(), "noTestCase").contains(".cat/tests/CLAUDE/safety/values.json: the file " +
			"holds 8 test case(s), so it has no test case 9");

		for (Report report : new Report[]{existing, drafted, escaping, noTest, noTestCase})
			requireThat(report.exitCode(), "exitCode").isEqualTo(1);
		requireThat(files(project), "after").isEqualTo(before);
		requireThat(Files.exists(output), "outputExists").isFalse();
	}

	/**
	 * Verifies that {@code contract workspace} still writes a test case's files when the proposal fails the check,
	 * comparing their sizes with the limit of every profile of the inventory, and that it warns that a generator call
	 * without a seed generates other content when the session ends.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void writesBeforeProposalIsValid() throws IOException
	{
		// Requirement: R-C27
		Path contextFile = project(proposal ->
		{
			proposal.put("minimum_pass_rate", "high");
			ObjectNode safety = (ObjectNode) proposal.get("tests").get(2);
			((ArrayNode) safety.get("workspace_files")).addObject().put("path", "app.log").put("content", "{{before}}\n");
			safety.withArray("variables").addObject().put("name", "before").
				put("description", "Log lines before the request.");
			for (JsonNode testCase : safety.get("test_cases"))
			{
				((ArrayNode) testCase.get("values")).addObject().put("variable", "before").putObject("call").
					put("generator", "log_lines").put("count", 3);
			}
		});
		Path output = contextFile.getParent().resolve("sized");

		Report report = ContractToolSessions.workspace(json, contextFile, SAFETY, 1, output);

		requireThat(report.output(), "output").contains("A generator call of the test case states no seed.").
			contains("The proposal does not pass `contract check`, so the test's trial profiles are not known. " +
				"Tool-output limit of each profile of the inventory:").
			contains("- 40 bytes: `big/high`").
			contains("- not known, since the harness does not state it: `small/high`").
			contains("- `app.log`: ");
		requireThat(report.exitCode(), "exitCode").isEqualTo(0);
		requireThat(Files.readString(output.resolve("app.log"), UTF_8), "log").isNotBlank().doesNotContain("{{");
	}
}
