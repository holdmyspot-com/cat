package com.holdmyspot.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorKind;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisionRequest;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.proposal.ContractAuthorProtocol;
import com.holdmyspot.cat.common.optimizer.proposal.ContractContext;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.optimizer.proposal.TestRevisionProtocol;
import com.holdmyspot.cat.common.optimizer.proposal.TestRevisionProtocol.Examination;
import com.holdmyspot.cat.common.optimizer.proposal.TestRevisionProtocol.Result;
import com.holdmyspot.cat.common.optimizer.proposal.test.ContractToolSessions.Report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests a test revision session's files, the validation of its result when the session ends, and
 * {@code contract check} on that result.
 */
public final class TestRevisionTest
{
	/**
	 * The profile evidence of the sessions.
	 */
	private static final ProfileEvidence PROFILES = new ProfileEvidence(ContractFixtures.ONE_PROFILE,
		ContractFixtures.ranking(ContractFixtures.ONE_PROFILE), Map.of());
	/**
	 * The directory of the examined prompt test, relative to the draft directory.
	 */
	private static final String FORMATTING = ".cat/tests/CLAUDE/formatting";
	/**
	 * Holds the projects of the sessions that the tests create.
	 */
	private final TemporaryDirectories directories = new TemporaryDirectories();
	private final JsonMapper json = JsonMapper.builder().build();
	private final ContractAuthorProtocol contractAuthor = ContractToolSessions.contractAuthor(json,
		_ -> OptionalLong.empty());
	private final TestRevisionProtocol protocol = new TestRevisionProtocol(json, new ProposalSchema(json),
		contractAuthor);

	/**
	 * Creates the test.
	 */
	public TestRevisionTest()
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
	 * Returns an examination of the prompt test {@code formatting} of a confirmed proposal of
	 * {@code derivation.json}, whose draft directory lies in a session's project.
	 *
	 * @return the examination
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	private Examination examination() throws IOException, InvalidProposalException
	{
		Path project = directories.add(Files.createTempDirectory("contract-author-"));
		ContractToolSessions.write(json, contractAuthor, PROFILES, ContractToolSessions.derivation(json, _ ->
		{
		}), project);
		DerivedProposal confirmed = contractAuthor.proposal(ContractToolSessions.completed(project,
			directories.add(Files.createTempDirectory("contract-author-initial-"))),
			ContractToolSessions.PROMPT_SYSTEM, PROFILES, ContractFixtures.SALT);
		PromptAuthorKind kind = new PromptAuthorKind("tone_named_in_turn", List.of("f1"), FailureKind.TEST_DEFECT,
			List.of(), "The first turn names the tone that the assertion expects.");
		return new Examination(confirmed, PROFILES, new TestRevisionRequest("formatting",
			TestRevisionRequest.Trigger.TEST_DEFECT, List.of(kind), Map.of()), Map.of());
	}

	/**
	 * Writes a test revision session's project: the files that CAT writes when the session starts, then the
	 * session's result file and the corrected test's files.
	 *
	 * @param examined what the session examines
	 * @param output   the session's result, whose corrected test holds its scenario inline
	 * @return the session's project
	 * @throws IOException if a file cannot be written
	 */
	private Path revisionProject(Examination examined, String output) throws IOException
	{
		Path project = directories.add(Files.createTempDirectory("test-revision-"));
		protocol.request(ContractToolSessions.PROMPT_SYSTEM, examined).files().write(project);
		ContractContext context = ContractContext.read(json, contextFile(project));
		String result = DraftFixtures.writeRevision(json, output, project.resolve(context.draft()),
			Path.of("CLAUDE.md"), Map.of());
		Files.writeString(project.resolve(context.proposalFile()), result, UTF_8);
		return project;
	}

	/**
	 * Returns the context file of a session's project.
	 *
	 * @param project the session's project
	 * @return the context file
	 */
	private static Path contextFile(Path project)
	{
		return project.resolve(ContractContext.name(ContractToolSessions.PROMPT_SYSTEM));
	}

	/**
	 * Returns a test revision's result that rewrites the prompt test {@code formatting} with new turns and new test
	 * cases, whose boundary class is their tone.
	 *
	 * @param change edits the corrected test
	 * @return the result as JSON text
	 */
	private String revisedFormatting(Consumer<ObjectNode> change)
	{
		ObjectNode test = (ObjectNode) json.readTree(ContractToolSessions.derivation(json, _ ->
		{
		})).get("tests").get(0);
		test.putArray("turns").add("Summarize the meeting notes in {{tone}} style.").
			add("Now shorten it for {{audience}} reader.");
		ArrayNode testCases = test.putArray("test_cases");
		for (String tone : List.of("an upbeat", "a somber", "a neutral", "a playful", "a stern", "a warm"))
		{
			for (String audience : List.of("a manager", "an intern"))
			{
				ObjectNode testCase = testCases.addObject();
				ArrayNode values = testCase.putArray("values");
				values.addObject().put("variable", "tone").put("text", tone);
				values.addObject().put("variable", "audience").put("text", audience);
				testCase.put("expected_outcome", tone + " summary shortened for " + audience + " reader");
				testCase.put("boundary_class", tone.substring(tone.indexOf(' ') + 1) + "_tone");
			}
		}
		change.accept(test);
		ObjectNode output = json.createObjectNode();
		output.put("result", "revised");
		output.put("reasons", "The old turns named the tone that the assertion expects.");
		output.set("test", test);
		output.putNull("proposed_change");
		return json.writeValueAsString(output);
	}

	/**
	 * Returns the regular files below a directory.
	 *
	 * @param directory the directory
	 * @return the files, relative to {@code directory}, sorted
	 * @throws IOException if the directory cannot be read
	 */
	private static Set<String> files(Path directory) throws IOException
	{
		Set<String> files = new TreeSet<>();
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.filter(Files::isRegularFile).toList())
				files.add(directory.relativize(path).toString().replace('\\', '/'));
		}
		return files;
	}

	/**
	 * Returns the report that {@code contract check} gives for a list of problems.
	 *
	 * @param problems the problems
	 * @return the report
	 */
	private static String problemReport(List<String> problems)
	{
		StringJoiner report = new StringJoiner(System.lineSeparator(), "", System.lineSeparator());
		report.add("`contract check` found " + problems.size() + " problem(s):");
		for (String problem : problems)
			report.add("- " + problem);
		return report.toString();
	}

	/**
	 * Verifies that a test revision session's draft directory holds only the examined prompt test's files, that its
	 * context file names the examined test, and that its turn names the draft directory, the result file, the
	 * launcher and the context file.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	@Test
	public void draftHoldsExaminedTest() throws IOException, InvalidProposalException
	{
		// Requirement: R-C26
		Examination examined = examination();
		SessionRequest request = protocol.request(ContractToolSessions.PROMPT_SYSTEM, examined);
		Path project = directories.add(Files.createTempDirectory("test-revision-"));
		request.files().write(project);

		ContractContext context = ContractContext.read(json, contextFile(project));
		requireThat(files(project.resolve(context.draft())), "draft").isEqualTo(new TreeSet<>(List.of(
			FORMATTING + "/scenario.md", FORMATTING + "/values.json")));
		requireThat(context.revision().orElseThrow().test(), "test").isEqualTo("formatting");
		requireThat(request.turns().getFirst(), "turn").contains(context.draft()).contains(context.proposalFile()).
			contains(ContractToolSessions.LAUNCHER.toString()).contains(contextFile(project).getFileName().toString());
	}

	/**
	 * Verifies that a test revision writes new examples without seeing the previous examples.
	 *
	 * @throws IOException if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	@Test
	public void draftOmitsPreviousExamples() throws IOException, InvalidProposalException
	{
		// Requirement: R-L16
		Examination examined = examination();
		Path example = Path.of(FORMATTING).resolve("examples/keeps_requested_tone/pass_terse_tone/replies.json");
		Path previous = examined.confirmed().draft().resolve(example);
		Files.createDirectories(previous.getParent());
		Files.writeString(previous, "[[\"Original reply.\"]]", UTF_8);
		SessionRequest request = protocol.request(ContractToolSessions.PROMPT_SYSTEM, examined);
		Path project = directories.add(Files.createTempDirectory("test-revision-examples-"));
		request.files().write(project);

		ContractContext context = ContractContext.read(json, contextFile(project));

		requireThat(Files.exists(project.resolve(context.draft()).resolve(example)), "previousExample").isFalse();
		requireThat(Files.readString(previous), "retainedExample").contains("Original reply.");
	}

	/**
	 * Verifies that the contract author's brief and the test revision's brief state both evidence scopes, the
	 * {@code actions} fallback for an assertion that needs both or whose scope is unclear, and the duty to split an
	 * assertion about an action between a built-in tool and a narrow judged assertion.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	@Test
	public void briefsStateEvidenceScopes() throws IOException, InvalidProposalException
	{
		// Requirement: R-L19
		// Requirement: R-L20
		String contractAuthorBrief = contractAuthor.request(ContractToolSessions.PROMPT_SYSTEM, PROFILES, List.of(),
			List.of(), ContractFixtures.SALT).turns().getFirst();
		String revisionBrief = protocol.request(ContractToolSessions.PROMPT_SYSTEM, examination()).turns().getFirst();
		for (String brief : List.of(contractAuthorBrief, revisionBrief))
		{
			requireThat(brief, "brief").contains("- `evidence_scope`").
				contains("`response`: the assertion is about what the agent says").
				contains("`actions`: the assertion is about what the agent does").
				contains("An assertion that needs both, or whose scope is unclear, is `actions`.").
				contains("a built-in tool decides whether the action happened").
				contains("a narrow judged assertion decides only the part that needs meaning").
				contains("names that part");
		}
	}

	/**
	 * Verifies that {@code contract check} reports, in a test revision session, the problems that CAT reports when
	 * the session ends for a corrected test that proves other requirements and repeats a confirmed test case.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	@Test
	public void checkMatchesSessionEndProblems() throws IOException, InvalidProposalException
	{
		// Requirement: R-C27
		Examination examined = examination();
		Path project = revisionProject(examined, revisedFormatting(test ->
		{
			test.putArray("requirement_ids").add("r2");
			((ObjectNode) test.get("assertions").get(0)).put("requirement_id", "r2");
			ObjectNode reused = ((ArrayNode) test.get("test_cases")).addObject();
			ArrayNode values = reused.putArray("values");
			values.addObject().put("variable", "tone").put("text", "a formal");
			values.addObject().put("variable", "audience").put("text", "an expert");
			reused.put("expected_outcome", "a formal summary shortened for an expert reader");
			reused.put("boundary_class", "upbeat_tone");
		}));

		Report report = ContractToolSessions.check(json, contextFile(project));

		List<String> problems;
		try
		{
			protocol.result(ContractToolSessions.completed(project,
				directories.add(Files.createTempDirectory("test-revision-initial-"))),
				ContractToolSessions.PROMPT_SYSTEM, examined);
			throw new AssertionError("The session-end validation accepted the result");
		}
		catch (InvalidProposalException e)
		{
			problems = e.problems();
		}
		requireThat(String.join("\n", problems), "problems").contains("r1").
			contains("a formal summary shortened for an expert reader");
		requireThat(report.output(), "output").isEqualTo(problemReport(problems));
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
	}

	/**
	 * Verifies that the test cases that a test revision adds or changes are checked for boundary classes as a
	 * proposal's are: a changed test case without a class is refused both when the session ends and by
	 * {@code contract check}, with the same problem, which names no part.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	@Test
	public void refusesRevisedCaseWithoutClass() throws IOException, InvalidProposalException
	{
		// Requirement: R-L11
		Examination examined = examination();
		Path project = revisionProject(examined, revisedFormatting(test ->
			((ObjectNode) test.get("test_cases").get(3)).remove("boundary_class")));

		Report report = ContractToolSessions.check(json, contextFile(project));

		List<String> problems = sessionEndProblems(project, examined);
		requireThat(String.join("\n", problems), "problems").contains(FORMATTING + "/values.json:").
			contains(": test case 4: the test case has no boundary_class").doesNotContain("development").
			doesNotContain("held-out").doesNotContain(" part");
		requireThat(report.output(), "output").isEqualTo(problemReport(problems));
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
	}

	/**
	 * Verifies that a test revision that adds a test case of a new boundary class with one distinct expected outcome
	 * is refused both when the session ends and by {@code contract check}, with the same problem, which names the
	 * class and no part.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	@Test
	public void refusesRevisionAddingSingleCaseClass() throws IOException, InvalidProposalException
	{
		// Requirement: R-L11
		Examination examined = examination();
		Path project = revisionProject(examined, revisedFormatting(test ->
		{
			ObjectNode added = ((ArrayNode) test.get("test_cases")).addObject();
			ArrayNode values = added.putArray("values");
			values.addObject().put("variable", "tone").put("text", "a sarcastic");
			values.addObject().put("variable", "audience").put("text", "a manager");
			added.put("expected_outcome", "a sarcastic summary shortened for a manager reader");
			added.put("boundary_class", "sarcastic_tone");
		}));

		Report report = ContractToolSessions.check(json, contextFile(project));

		List<String> problems = sessionEndProblems(project, examined);
		requireThat(problems, "problems").size().isEqualTo(1);
		requireThat(problems.getFirst(), "problem").contains(FORMATTING + "/values.json: the boundary class " +
			"`sarcastic_tone` has a single case").doesNotContain("development").doesNotContain("held-out").
			doesNotContain(" part");
		requireThat(report.output(), "output").isEqualTo(problemReport(problems));
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
	}

	/**
	 * Verifies that a corrected test with a judged assertion that declares no evidence scope is refused both when the
	 * session ends and by {@code contract check}, with the same problem, which names the scenario, the assertion and
	 * the key.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	@Test
	public void refusesRevisedAssertionWithoutScope() throws IOException, InvalidProposalException
	{
		// Requirement: R-C26
		// Requirement: R-L19
		Examination examined = examination();
		Path project = revisionProject(examined, revisedFormatting(test ->
			((ObjectNode) test.get("assertions").get(0)).putNull("evidence_scope")));

		Report report = ContractToolSessions.check(json, contextFile(project));

		List<String> problems = sessionEndProblems(project, examined);
		requireThat(problems, "problems").size().isEqualTo(1);
		requireThat(problems.getFirst(), "problem").contains(FORMATTING + "/scenario.md").
			contains("uses_requested_tone").contains("evidence_scope");
		requireThat(report.output(), "output").isEqualTo(problemReport(problems));
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
	}

	/**
	 * Returns the problems that the session-end validation of a test revision session reports.
	 *
	 * @param project  the session's project
	 * @param examined what the session examines
	 * @return the problems
	 * @throws IOException if a file cannot be read or written
	 */
	private List<String> sessionEndProblems(Path project, Examination examined) throws IOException
	{
		try
		{
			protocol.result(ContractToolSessions.completed(project,
				directories.add(Files.createTempDirectory("test-revision-initial-"))),
				ContractToolSessions.PROMPT_SYSTEM, examined);
			throw new AssertionError("The session-end validation accepted the result");
		}
		catch (InvalidProposalException e)
		{
			return e.problems();
		}
	}

	/**
	 * Verifies that a corrected test that changes a file outside its directory is refused both when the session ends
	 * and by {@code contract check}, with the same problem.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the confirmed proposal is refused
	 */
	@Test
	public void refusesChangesOutsideTest() throws IOException, InvalidProposalException
	{
		// Requirement: R-C27
		Examination examined = examination();
		Path project = revisionProject(examined, revisedFormatting(_ ->
		{
		}));
		ContractContext context = ContractContext.read(json, contextFile(project));
		Files.writeString(project.resolve(context.draft()).resolve(".cat/tests/CLAUDE/notes.md"), "Notes\n", UTF_8);

		Report report = ContractToolSessions.check(json, contextFile(project));

		List<String> problems;
		try
		{
			protocol.result(ContractToolSessions.completed(project,
				directories.add(Files.createTempDirectory("test-revision-initial-"))),
				ContractToolSessions.PROMPT_SYSTEM, examined);
			throw new AssertionError("The session-end validation accepted the result");
		}
		catch (InvalidProposalException e)
		{
			problems = e.problems();
		}
		requireThat(problems, "problems").size().isEqualTo(1);
		requireThat(problems.getFirst(), "problem").contains(".cat/tests/CLAUDE/notes.md");
		requireThat(report.output(), "output").isEqualTo(problemReport(problems));
		requireThat(report.exitCode(), "exitCode").isEqualTo(1);
	}

	/**
	 * Verifies that a corrected test is read from the session's draft directory: {@code contract check} counts its
	 * distinct expected outcomes, and the session-end validation returns a proposal whose new draft directory holds
	 * every confirmed prompt test with the corrected test's files, leaving the confirmed draft directory unchanged.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if a proposal is refused
	 */
	@Test
	public void readsRevisedTestFromDraft() throws IOException, InvalidProposalException
	{
		// Requirement: R-C26
		Examination examined = examination();
		Path confirmedDraft = examined.confirmed().draft();
		String confirmedScenario = Files.readString(confirmedDraft.resolve(FORMATTING).resolve("scenario.md"), UTF_8);
		Path project = revisionProject(examined, revisedFormatting(_ ->
		{
		}));

		Report report = ContractToolSessions.check(json, contextFile(project));
		requireThat(report.output(), "output").contains("`contract check` found no problem.").
			contains("- `formatting` (`" + FORMATTING + "`): 12").contains("- `plain_list` (`").
			doesNotContain("held-out").doesNotContain("development");
		requireThat(report.exitCode(), "exitCode").isEqualTo(0);

		Result result = protocol.result(ContractToolSessions.completed(project,
			directories.add(Files.createTempDirectory("test-revision-initial-"))),
			ContractToolSessions.PROMPT_SYSTEM, examined);
		Result.Revised revised = (Result.Revised) result;
		requireThat(revised.proposal().contract().findTest("formatting").orElseThrow().turns().getFirst(), "turn").
			isEqualTo("Summarize the meeting notes in {{tone}} style.");
		Path draft = revised.proposal().draft();
		requireThat(draft, "draft").isNotEqualTo(confirmedDraft);
		String examples = FORMATTING + "/examples/";
		Set<String> expectedFiles = new TreeSet<>(files(confirmedDraft));
		expectedFiles.removeIf(path -> path.startsWith(examples));
		String assertionExamples = examples + "uses_requested_tone/";
		expectedFiles.add(assertionExamples + "fail_omits_answer/replies.json");
		for (String boundary : List.of("neutral_tone", "playful_tone", "somber_tone", "stern_tone", "upbeat_tone",
			"warm_tone"))
		{
			expectedFiles.add(assertionExamples + "pass_" + boundary + "/replies.json");
		}
		requireThat(files(draft), "files").isEqualTo(expectedFiles);
		String retainedExample = "uses_requested_tone/pass_neutral_tone/replies.json";
		Path revisedExample = draft.resolve(FORMATTING).resolve("examples").resolve(retainedExample);
		requireThat(revised.proposal().contract().findTest("formatting").orElseThrow().draft().orElseThrow().
			exampleFiles().get(retainedExample).hash(), "exampleDigest").
			isEqualTo(Sha256.of(Files.readAllBytes(revisedExample)));
		requireThat(Files.exists(confirmedDraft.resolve(FORMATTING).
			resolve("examples/uses_requested_tone/pass_formal_tone/replies.json")), "originalExample").isTrue();
		requireThat(Files.readString(draft.resolve(FORMATTING).resolve("scenario.md"), UTF_8), "revisedScenario").
			contains("Summarize the meeting notes");
		requireThat(Files.readString(confirmedDraft.resolve(FORMATTING).resolve("scenario.md"), UTF_8),
			"confirmedScenario").isEqualTo(confirmedScenario);
	}

	/**
	 * Verifies that the session-end validation reads the draft directory that the session received and restores its
	 * links from the declarations that the runner returns beside the project, although the project as the session
	 * started, which the runner returns as the initial project, already holds that draft directory.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if a proposal is refused
	 */
	@Test
	public void readsDraftThatSessionReceived() throws IOException, InvalidProposalException
	{
		// Requirement: R-C26
		Examination examined = examination();
		SessionRequest request = protocol.request(ContractToolSessions.PROMPT_SYSTEM, examined);
		Path session = directories.add(Files.createTempDirectory("test-revision-session-"));
		Path project = Files.createDirectory(session.resolve("project"));
		request.files().write(project);
		// The runner copies the project, with the files that CAT writes, before the session starts
		Path initialProject = Files.createDirectory(session.resolve("initial-project"));
		request.files().write(initialProject);
		ContractContext context = ContractContext.read(json, contextFile(project));
		// The runner returns the draft directory's link declarations beside the project; this draft has no links
		Files.createDirectories(session.resolve("project-link-declarations").resolve(context.draft()));
		String output = DraftFixtures.writeRevision(json, revisedFormatting(_ ->
		{
		}), project.resolve(context.draft()), Path.of("CLAUDE.md"), Map.of());
		Files.writeString(project.resolve(context.proposalFile()), output, UTF_8);

		Result result = protocol.result(ContractToolSessions.completed(project, initialProject),
			ContractToolSessions.PROMPT_SYSTEM, examined);
		requireThat(result, "result").isInstanceOf(Result.Revised.class);
		requireThat(((Result.Revised) result).proposal().contract().findTest("formatting").orElseThrow().turns().
			getFirst(), "turn").isEqualTo("Summarize the meeting notes in {{tone}} style.");
	}

	/**
	 * Verifies that a result that finds the test sound passes {@code contract check} without counts, since it
	 * changes no prompt test, and that the session-end validation returns it as sound.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if a proposal is refused
	 */
	@Test
	public void soundResultChangesNoTest() throws IOException, InvalidProposalException
	{
		// Requirement: R-C27
		Examination examined = examination();
		ObjectNode output = json.createObjectNode();
		output.put("result", "sound");
		output.put("reasons", "The turns are organic and prove r1.");
		output.putNull("test");
		output.putNull("proposed_change");
		Path project = revisionProject(examined, json.writeValueAsString(output));

		Report report = ContractToolSessions.check(json, contextFile(project));
		requireThat(report.output(), "output").isEqualTo("`contract check` found no problem." +
			System.lineSeparator());
		requireThat(report.exitCode(), "exitCode").isEqualTo(0);
		requireThat(protocol.result(ContractToolSessions.completed(project,
			directories.add(Files.createTempDirectory("test-revision-initial-"))),
			ContractToolSessions.PROMPT_SYSTEM, examined), "result").
			isEqualTo(new Result.Sound("The turns are organic and prove r1."));
	}
}
