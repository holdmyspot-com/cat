package com.holdmyspot.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
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
	 * expected outcomes, counting outcomes that differ only in case and whitespace once, without saying how they would
	 * be split between the parts.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void countsOutcomesWithoutParts() throws IOException
	{
		// Requirement: R-C27
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
			"- `plain_list` (`.cat/tests/CLAUDE/plain_list`): 3" + newline +
			"- `safety` (`.cat/tests/CLAUDE/safety`): 8" + newline);
		requireThat(report.exitCode(), "exitCode").isEqualTo(0);
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
