package com.holdmyspot.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.cli.Commands;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.proposal.ContractAuthorProtocol;
import com.holdmyspot.cat.common.optimizer.proposal.ContractContext;
import com.holdmyspot.cat.common.optimizer.proposal.ContractTools;
import com.holdmyspot.cat.common.optimizer.proposal.DraftDirectory;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.optimizer.proposal.ProposalCheck.RecordedTest;
import com.holdmyspot.cat.common.optimizer.proposal.test.ContractToolSessions.Report;
import com.holdmyspot.cat.common.test.RootedPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.StringJoiner;

import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@code contract check}, {@link ContractTools#check}.
 */
public final class ContractCheckTest
{
	/**
	 * The profile evidence of the sessions.
	 */
	private static final ProfileEvidence PROFILES = new ProfileEvidence(ContractFixtures.ONE_PROFILE,
		ContractFixtures.ranking(ContractFixtures.ONE_PROFILE), Map.of());
	/**
	 * Holds the projects of the sessions that the tests create.
	 */
	private final TemporaryDirectories directories = new TemporaryDirectories();
	private final JsonMapper json = JsonMapper.builder().build();
	private final ContractAuthorProtocol contractAuthor = ContractToolSessions.contractAuthor(json,
		_ -> OptionalLong.empty());

	/**
	 * Creates the test.
	 */
	public ContractCheckTest()
	{
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
	 * Verifies that the author's local check identifies the missing examples before the proposal is submitted.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void reportsMissingAssertionExamples() throws IOException
	{
		// Requirement: R-L15
		Path project = directories.add(Files.createTempDirectory("contract-check-examples-"));
		Path context = ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal ->
			{
				ObjectNode assertion = (ObjectNode) proposal.at("/tests/1/assertions/0");
				assertion.put("tool", "pattern \"{{expected_outcome}}\"");
			}), project);
		Path example = project.resolve(DraftDirectory.name(ContractToolSessions.PROMPT_SYSTEM)).
			resolve(".cat/tests/CLAUDE/plain_list/examples/keeps_requested_tone/pass_item_kind");
		Files.delete(example.resolve("replies.json"));
		Files.delete(example);

		Report report = ContractToolSessions.check(json, context);

		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
		requireThat(report.output(), "output").contains("plain_list").contains("pass_").
			doesNotContain("held-out").doesNotContain("development");
	}

	/**
	 * Verifies that {@code contract check} reports every problem that CAT's validation reports when the session ends,
	 * at once and in the same words, a problem of the proposal file with its JSON path and a problem of the draft
	 * directory with its file, and exits with a nonzero status.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void reportsSessionEndProblems() throws IOException
	{
		// Requirement: R-C27
		Path project = directories.add(Files.createTempDirectory("contract-check-"));
		Path contextFile = ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal ->
			{
				proposal.put("minimum_pass_rate", "high");
				((ObjectNode) proposal.get("tests").get(0)).remove("expected_behavior");
			}), project);
		Files.writeString(project.resolve(DraftDirectory.NAME).resolve(".cat/tests/CLAUDE/plain_list/values.json"),
			"{", UTF_8);

		Report report = ContractToolSessions.check(json, contextFile);

		List<String> problems;
		try
		{
			contractAuthor.proposal(ContractToolSessions.completed(project,
				directories.add(Files.createTempDirectory("contract-check-initial-"))),
				ContractToolSessions.PROMPT_SYSTEM, PROFILES, ContractFixtures.SALT);
			throw new AssertionError("The session-end validation accepted the proposal");
		}
		catch (InvalidProposalException e)
		{
			problems = e.problems();
		}
		requireThat(problems.size(), "problems.size()").isGreaterThanOrEqualTo(3);
		requireThat(String.join("\n", problems), "problems").contains("minimum_pass_rate").
			contains("formatting/scenario.md").contains("plain_list/values.json");
		StringJoiner expected = new StringJoiner(System.lineSeparator(), "", System.lineSeparator());
		expected.add("`contract check` found " + problems.size() + " problem(s):");
		for (String problem : problems)
			expected.add("- " + problem);
		requireThat(report.output(), "output").isEqualTo(expected.toString());
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
	}

	/**
	 * Verifies that {@code contract check} runs each check program twice on each of its assertion's examples and
	 * reports a program that fails a passing example in the words of the session-end validation, naming the test, the
	 * assertion, the example and both outputs, but no part.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void reportsContradictedCheckProgram() throws IOException
	{
		// Requirement: R-C27
		// Requirement: R-L15
		Path project = directories.add(Files.createTempDirectory("contract-check-program-"));
		Path contextFile = writeCheckProgramDraft(project);
		ScriptedCheckPrograms checkPrograms = new ScriptedCheckPrograms(Map.of(
			"A passing reply.", ScriptedCheckPrograms.agreeing(CheckProgram.Outcome.FAIL, "the passing example"),
			"A failing reply.", ScriptedCheckPrograms.agreeing(CheckProgram.Outcome.FAIL, "the failing example")));

		Report report = ContractToolSessions.check(json, contextFile, checkPrograms);

		List<String> problems;
		try
		{
			ContractToolSessions.contractAuthor(json, _ -> OptionalLong.empty(), checkPrograms).
				proposal(ContractToolSessions.completed(project,
					directories.add(Files.createTempDirectory("contract-check-program-initial-"))),
					ContractToolSessions.PROMPT_SYSTEM, PROFILES, ContractFixtures.SALT);
			throw new AssertionError("The session-end validation accepted the proposal");
		}
		catch (InvalidProposalException e)
		{
			problems = e.problems();
		}
		requireThat(problems, "problems").size().isEqualTo(1);
		requireThat(problems.getFirst(), "problem").startsWith("Prompt test `plain_list`, assertion " +
			"`keeps_requested_tone`, example `pass_item_kind`: the check program failed this passing example.\n").
			contains("first run on the passing example").contains("second run on the passing example").
			doesNotContain("held-out").doesNotContain("development");
		StringJoiner expected = new StringJoiner(System.lineSeparator(), "", System.lineSeparator());
		expected.add("`contract check` found 1 problem(s):");
		expected.add("- " + problems.getFirst());
		requireThat(report.output(), "output").isEqualTo(expected.toString());
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
		requireThat(checkPrograms.calls().stream().map(ScriptedCheckPrograms.Call::runs).toList(), "runs").
			isEqualTo(List.of(2, 2, 2, 2));
	}

	/**
	 * Writes a contract author session whose draft gives the first assertion of {@code plain_list} a check program,
	 * with a passing example whose first reply is {@code A passing reply.} and a failing example whose first reply is
	 * {@code A failing reply.}.
	 *
	 * @param project the session's project directory
	 * @return the session's context file
	 * @throws IOException if a file cannot be written
	 */
	private Path writeCheckProgramDraft(Path project) throws IOException
	{
		Path contextFile = ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal -> ((ObjectNode) proposal.at("/tests/1/assertions/0")).
				put("tool", "check-program checks/Check.java").put("on_tool_failure", "fail")), project);
		Path test = project.resolve(DraftDirectory.name(ContractToolSessions.PROMPT_SYSTEM)).
			resolve(".cat/tests/CLAUDE/plain_list");
		Files.createDirectories(test.resolve("checks"));
		Files.writeString(test.resolve("checks/Check.java"), "class Check\n{\n}\n", UTF_8);
		Path examples = test.resolve("examples/keeps_requested_tone");
		Files.writeString(Files.createDirectories(examples.resolve("pass_item_kind")).resolve("replies.json"),
			"[[\"A passing reply.\"]]", UTF_8);
		Files.writeString(Files.createDirectories(examples.resolve("fail_omits_answer")).resolve("replies.json"),
			"[[\"A failing reply.\"]]", UTF_8);
		return contextFile;
	}

	/**
	 * Verifies that {@code contract check}, run where Bubblewrap cannot be found or on native Windows, refuses each
	 * example of a check program and names the missing sandbox, instead of accepting the examples unchecked.
	 *
	 * @param platform the platform that runs the contract tool
	 * @param missing  the reason that each example's problem ends with
	 * @throws IOException if a file cannot be read or written
	 */
	@Test(dataProvider = "hostsWithoutSandbox")
	public void refusesCheckProgramWithoutSandbox(Platform platform, String missing) throws IOException
	{
		// Requirement: R-C27
		// Requirement: R-L15
		Path project = directories.add(Files.createTempDirectory("contract-check-sandbox-"));
		Path contextFile = writeCheckProgramDraft(project);

		Report report = ContractToolSessions.check(json, contextFile,
			Commands.contractCheckPrograms(json, platform, Map.of()));

		StringJoiner expected = new StringJoiner(System.lineSeparator(), "", System.lineSeparator());
		expected.add("`contract check` found 2 problem(s):");
		for (String example : List.of("fail_omits_answer", "pass_item_kind"))
		{
			expected.add("- Prompt test `plain_list`, assertion `keeps_requested_tone`, example `" + example + "`: " +
				missing);
		}
		requireThat(report.output(), "output").isEqualTo(expected.toString());
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
	}

	/**
	 * Returns hosts that cannot run a check program, with the reason that the contract tool reports.
	 *
	 * @return the data rows
	 */
	@DataProvider
	public static Object[][] hostsWithoutSandbox()
	{
		return new Object[][]
		{
			{Platform.LINUX_X64, "Check programs require Bubblewrap (bwrap) at an absolute location on PATH."},
			{Platform.WINDOWS_X64, "Check programs require WSL2 on Windows."}
		};
	}

	/**
	 * Verifies that {@code contract check} refuses a judged assertion that declares no evidence scope, naming its
	 * scenario, the assertion and the key.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void refusesJudgedAssertionWithoutScope() throws IOException
	{
		// Requirement: R-C27
		// Requirement: R-L19
		Path project = directories.add(Files.createTempDirectory("contract-check-"));
		Path contextFile = ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal ->
				((ObjectNode) proposal.get("tests").get(0).get("assertions").get(0)).putNull("evidence_scope")),
			project);

		Report report = ContractToolSessions.check(json, contextFile);

		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
		requireThat(report.output(), "output").contains("`contract check` found 1 problem(s):").
			contains("formatting/scenario.md").contains("uses_requested_tone").contains("evidence_scope");
	}

	/**
	 * Verifies that {@code contract check} refuses a prompt test's directory that starts at a root, including one that
	 * names the draft directory's prompt test through its root alone.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void refusesRootedTestDirectory() throws IOException
	{
		List<String> texts = new ArrayList<>(RootedPaths.texts());
		texts.add(null);
		for (String text : texts)
		{
			Path project = directories.add(Files.createTempDirectory("contract-check-"));
			String directory = text;
			if (directory == null)
			{
				directory = RootedPaths.withoutDrive(project.resolve(DraftDirectory.NAME).
					resolve(".cat/tests/CLAUDE/formatting").toAbsolutePath());
			}
			String rooted = directory;
			Path contextFile = ContractToolSessions.write(json, contractAuthor, PROFILES,
				ContractToolSessions.derivation(json, proposal ->
					((ObjectNode) proposal.get("tests").get(0)).put("directory", rooted)), project);

			Report report = ContractToolSessions.check(json, contextFile);

			requireThat(report.output(), "output").contains("$.tests[0].directory: `" + rooted +
				"` is not a directory relative to the draft directory");
			requireThat(report.exitCode(), "exitCode").isEqualTo(1);
		}
	}

	/**
	 * Verifies that {@code contract check} accepts a valid proposal and reports each prompt test's number of distinct
	 * expected outcomes, and each boundary class's number of test cases and distinct expected outcomes, counting
	 * outcomes that differ only in case and whitespace once, without saying how they would be split between the parts.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void countsOutcomesWithoutParts() throws IOException
	{
		// Requirement: R-C27
		// Requirement: R-L11
		Path project = directories.add(Files.createTempDirectory("contract-check-"));
		Path contextFile = ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal ->
			{
				ArrayNode testCases = (ArrayNode) proposal.get("tests").get(1).get("test_cases");
				((ObjectNode) testCases.get(1)).put("expected_outcome", "Three  Colors");
			}), project);

		Report report = ContractToolSessions.check(json, contextFile);

		String newline = System.lineSeparator();
		requireThat(report.output(), "output").isEqualTo("`contract check` found no problem." + newline + newline +
			"Distinct expected outcomes of each prompt test's test cases:" + newline +
			"- `formatting` (`.cat/tests/CLAUDE/formatting`): 6" + newline +
			"  - Boundary class `formal_tone`: 2 test cases, 2 distinct expected outcomes" + newline +
			"  - Boundary class `casual_tone`: 2 test cases, 2 distinct expected outcomes" + newline +
			"  - Boundary class `terse_tone`: 2 test cases, 2 distinct expected outcomes" + newline +
			"- `plain_list` (`.cat/tests/CLAUDE/plain_list`): 3" + newline +
			"  - Boundary class `item_kind`: 4 test cases, 3 distinct expected outcomes" + newline +
			"- `safety` (`.cat/tests/CLAUDE/safety`): 8" + newline +
			"  - Boundary class `stored_data`: 4 test cases, 4 distinct expected outcomes" + newline +
			"  - Boundary class `generated_output`: 4 test cases, 4 distinct expected outcomes" + newline);
		requireThat(report.exitCode(), "exitCode").isEqualTo(0);
	}

	/**
	 * Verifies that {@code contract check} prints the refusal of a test case without a boundary class, of a class with
	 * a single case and of test cases that share an expected outcome but not a class, each without naming a part.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void printsBoundaryClassRefusalsWithoutParts() throws IOException
	{
		// Requirement: R-L11
		Path project = directories.add(Files.createTempDirectory("contract-check-"));
		Path contextFile = ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal ->
			{
				ArrayNode formatting = (ArrayNode) proposal.get("tests").get(0).get("test_cases");
				((ObjectNode) formatting.get(0)).remove("boundary_class");
				ArrayNode plainList = (ArrayNode) proposal.get("tests").get(1).get("test_cases");
				((ObjectNode) plainList.get(0)).put("boundary_class", "color_kind");
				ArrayNode safety = (ArrayNode) proposal.get("tests").get(2).get("test_cases");
				((ObjectNode) safety.get(2)).put("expected_outcome", "Asks before deleting the logs directory");
			}), project);

		Report report = ContractToolSessions.check(json, contextFile);

		requireThat(report.output(), "output").
			contains(".cat/tests/CLAUDE/formatting/values.json:").
			contains(": test case 1: the test case has no boundary_class").
			contains(".cat/tests/CLAUDE/plain_list/values.json: the boundary class `color_kind` has a single case").
			contains(".cat/tests/CLAUDE/safety/values.json: the test cases with the expected outcome " +
				"\"asks before deleting the logs directory\" have the boundary classes `stored_data` and " +
				"`generated_output`").
			doesNotContain("development").doesNotContain("held-out").doesNotContain(" part");
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
	}

	/**
	 * Verifies that {@code contract check} accepts a declared file that is a symbolic link to a file outside the
	 * project, and refuses a declared file that is missing, is the target prompt file, is a directory, lies below a
	 * symbolic link to a directory, or lies in Git metadata, naming each in the proposal's JSON path.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void checksReferencedFiles() throws IOException
	{
		Path outside = directories.add(Files.createTempDirectory("contract-check-outside-"));
		Path rules = Files.writeString(outside.resolve("reply-rules.md"), "reply rules", UTF_8);
		Path valid = ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal -> proposal.putArray("referenced_files").
				add("docs/reply-rules.md").add("docs/kit/guide.md")), referencingProject(outside, rules));
		Path invalid = ContractToolSessions.write(json, contractAuthor, PROFILES,
			ContractToolSessions.derivation(json, proposal -> proposal.putArray("referenced_files").
				add("docs/missing.md").add("CLAUDE.md").add("docs/kit").add("docs/linked/reply-rules.md").
				add(".git/config")), referencingProject(outside, rules));

		Report accepted = ContractToolSessions.check(json, valid);
		Report refused = ContractToolSessions.check(json, invalid);

		requireThat(accepted.output(), "accepted").contains("found no problem");
		requireThat(accepted.exitCode(), "acceptedExitCode").isEqualTo(0);
		requireThat(refused.exitCode(), "refusedExitCode").isEqualTo(1);
		requireThat(refused.output(), "refused").contains("found 5 problem(s)").
			contains("$.referenced_files[0]: `docs/missing.md` is not a file of the project").
			contains("$.referenced_files[1]: `CLAUDE.md` is the target prompt file").
			contains("$.referenced_files[2]: `docs/kit` is not a file of the project").
			contains("$.referenced_files[3]: `docs/linked/reply-rules.md` lies below a symbolic link").
			contains("$.referenced_files[4]: `.git/config` lies in Git metadata");
	}

	/**
	 * Creates a session's project whose prompt refers to a link to a file outside the project, to a file in a
	 * directory and to a link to a directory.
	 *
	 * @param outside the directory outside the project that holds {@code rules}
	 * @param rules   the file that {@code docs/reply-rules.md} links to
	 * @return the project
	 * @throws IOException if a file cannot be created
	 */
	private Path referencingProject(Path outside, Path rules) throws IOException
	{
		Path project = directories.add(Files.createTempDirectory("contract-check-"));
		Files.createDirectories(project.resolve("docs/kit"));
		Files.createSymbolicLink(project.resolve("docs/reply-rules.md"), rules);
		Files.createSymbolicLink(project.resolve("docs/linked"), outside);
		Files.writeString(project.resolve("CLAUDE.md"), "prompt", UTF_8);
		Files.writeString(project.resolve("docs/kit/guide.md"), "guide", UTF_8);
		return project;
	}

	/**
	 * Verifies that a session context refuses a target that starts at a root.
	 *
	 * @param text the target's text
	 */
	@Test(dataProvider = "rootedTexts", dataProviderClass = RootedPaths.class,
		expectedExceptions = IllegalArgumentException.class)
	public void refusesRootedContextTarget(String text)
	{
		new ContractContext(ContractContext.NAME, DraftDirectory.NAME, Path.of(text), true,
			ContractFixtures.PROFILE, PROFILES,
			Map.of(ContractFixtures.PROFILE, 40_000L), List.of(), Optional.empty());
	}

	/**
	 * Verifies that a recorded prompt test refuses a directory that starts at a root.
	 *
	 * @param text the directory's text
	 */
	@Test(dataProvider = "rootedTexts", dataProviderClass = RootedPaths.class,
		expectedExceptions = IllegalArgumentException.class)
	public void refusesRootedRecordedDirectory(String text)
	{
		new RecordedTest(".cat/tests/CLAUDE/formatting/scenario.md", Path.of(text), List.of());
	}
}
