package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision.TestRevisionBlocked;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisionRequest;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.ValueGenerators;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.FailureJson;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.FollowUp;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates requests for the session that examines one prompt test of a confirmed contract (a test revision), and
 * validates that session's result.
 * <p>
 * The session runs as the contract author. It receives the confirmed proposal file, the test's files in its draft
 * directory, the prompt author's kinds of failure that concern the test, and the rules for writing a prompt test. It
 * corrects the test's files and returns the test's item of the proposal file, reports the test sound, or reports that
 * no organic test can prove a requirement or that a correction would change one. CAT validates a corrected test with
 * the other confirmed tests as a proposal; it refuses a corrected test that changes a file outside its directory,
 * proves other requirements, repeats a test case of the confirmed test, or lacks distinct expected outcomes in a part
 * of the split. A valid correction receives a new draft directory beside the confirmed one, which holds the confirmed
 * draft with the corrected test's files.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class TestRevisionProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	private static final String PROMPT_RESOURCE = "/optimizer/proposal/test-revision.md";
	/**
	 * The turn that returns the problems of a refused result to the session, so that it can correct the files that
	 * they concern with its examination still in context.
	 */
	private static final String CORRECTION_RESOURCE = "/optimizer/proposal/test-revision-correction.md";
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final ContractAuthorProtocol contractAuthor;
	private final OutputFile outputFile;
	private final PromptTemplate prompt;
	private final String outputSchema;
	private final PromptTemplate correction;

	/**
	 * Creates the test revision request builder and result validator.
	 *
	 * @param json           the operation-owned JSON mapper
	 * @param schema         the proposal schema
	 * @param contractAuthor the campaign's contract author protocol, which names the target and the user's goal and
	 *                       validates a corrected contract
	 * @throws NullPointerException if any of the arguments are null
	 * @throws UncheckedIOException if a packaged prompt cannot be read
	 */
	public TestRevisionProtocol(JsonMapper json, ProposalSchema schema, ContractAuthorProtocol contractAuthor)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(contractAuthor, "contractAuthor").isNotNull();
		this.json = json;
		this.schema = schema;
		this.contractAuthor = contractAuthor;
		this.outputFile = new OutputFile(json);
		this.prompt = ContractAuthorProtocol.withPromptTests(PROMPT_RESOURCE);
		this.outputSchema = outputSchema(json, schema);
		this.correction = PromptTemplate.load(CORRECTION_RESOURCE);
	}

	/**
	 * Returns the schema of the session's result: its {@code result}, its {@code reasons}, the corrected test's item
	 * as the proposal file holds it, and the proposed requirement change.
	 *
	 * @param json   the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @return the schema text
	 */
	private static String outputSchema(JsonMapper json, ProposalSchema schema)
	{
		ObjectNode proposalFile = schema.proposalFileSchema();
		ObjectNode root = json.createObjectNode();
		root.set("$schema", proposalFile.get("$schema"));
		root.put("title", "Test revision result");
		root.put("type", "object");
		root.put("additionalProperties", false);
		root.putArray("required").add("result").add("reasons").add("test").add("proposed_change");
		ObjectNode definitions = (ObjectNode) proposalFile.get("$defs");
		definitions.set("prompt_test", proposalFile.get("properties").get("tests").get("items"));
		root.set("$defs", definitions);

		ObjectNode properties = root.putObject("properties");
		properties.putObject("result").put("type", "string").putArray("enum").add(TestRevisionCheck.REVISED).
			add("sound").add("unprovable").add("requirement_change");
		properties.putObject("reasons").put("description", "The rule that the test breaks or follows, and the " +
			"evidence for it.").put("type", "string").put("pattern", "\\S");
		ObjectNode test = properties.putObject("test");
		test.put("description", "The corrected test's item, as the proposal file's tests hold it, when the result " +
			"is revised; otherwise null.");
		ArrayNode testTypes = test.putArray("oneOf");
		testTypes.addObject().put("$ref", "#/$defs/prompt_test");
		testTypes.addObject().put("type", "null");
		ObjectNode proposedChange = properties.putObject("proposed_change");
		proposedChange.put("description", "The requirement change that would let a test prove the claim, as " +
			"feedback on the confirmed contract. Required when the result is requirement_change, optional when it " +
			"is unprovable, and null otherwise.");
		ArrayNode changeTypes = proposedChange.putArray("oneOf");
		changeTypes.addObject().put("type", "string").put("pattern", "\\S");
		changeTypes.addObject().put("type", "null");
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
	}

	/**
	 * Returns the request for examining one prompt test. The session's draft directory starts with a copy of the
	 * test's files from the confirmed draft directory.
	 *
	 * @param promptSystem the directory that holds the prompt system, which the session works in so that the harness
	 *                     loads it as it would in a trial
	 * @param examined     what the session examines
	 * @return a request whose role is {@link SessionRole#CONTRACT_AUTHOR}, which returns the problems of a refused
	 *   result to the session once
	 * @throws NullPointerException if any of the arguments are null
	 */
	public SessionRequest request(Path promptSystem, Examination examined)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(examined, "examined").isNotNull();
		TestRevisionRequest request = examined.request();
		String trigger = switch (request.trigger())
		{
			case TEST_DEFECT -> "The prompt author classified the kinds of failure below as defects of the test.";
			case REVIEW_CHECKPOINT -> "A cell of the test reached its review checkpoint, and no test revision has " +
				"examined the test since it last changed, so examine it against every rule even if no failure below " +
				"points to a defect.";
		};
		ArrayNode kinds = json.createArrayNode();
		for (PromptAuthorKind kind : request.kinds())
		{
			ObjectNode node = kinds.addObject();
			node.put("name", kind.name());
			node.put("fault", kind.fault());
			ArrayNode failures = node.putArray("failures");
			for (String id : kind.failures())
			{
				Failure failure = request.failures().get(id);
				// The prompt author may cite a failure of another test's kind, which this request does not carry
				if (failure != null)
					failures.add(FailureJson.encode(json, failure, id, examined.evidenceSections().get(id)));
			}
		}

		DerivedProposal confirmed = examined.confirmed();
		Path directory = check(examined).directory();
		Path confirmedDraft = confirmed.draft();
		Map<Path, Sha256> sharedFiles;
		try
		{
			sharedFiles = DraftDirectory.sharedFiles(confirmedDraft, directory);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		ContractAuthorFiles files = contractAuthor.files(promptSystem, examined.profiles(),
			draft -> DraftDirectory.copyTest(confirmedDraft, directory, draft),
			Optional.of(revision(examined, sharedFiles)));
		String turn = prompt.fill(Map.ofEntries(Map.entry("target_prompt_file", contractAuthor.targetPromptFile()),
			Map.entry("goal", contractAuthor.goal()), Map.entry("contract", confirmed.derivation()),
			Map.entry("test", request.test()), Map.entry("examined_directory", PortablePaths.text(directory)),
			Map.entry("trigger", trigger),
			Map.entry("kinds", json.writerWithDefaultPrettyPrinter().writeValueAsString(kinds)),
			Map.entry("generators", ValueGenerators.describeAll()), Map.entry("output_schema", outputSchema),
			Map.entry("output_file", files.context().proposalFile()), Map.entry("draft_directory", files.draft()),
			Map.entry("launcher", contractAuthor.launcher()), Map.entry("context_file", files.contextFile())));
		FollowUp correct = result -> correction(result, examined, files);
		return new SessionRequest(SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT, promptSystem, List.of(turn),
			List.of(), TIME_LIMIT, contractAuthor.role(), files, List.of(), Optional.of(correct));
	}

	/**
	 * Returns the confirmed contract that a test revision session receives in its context file.
	 *
	 * @param examined    what the session examines
	 * @param sharedFiles the hash of each file that the session's draft directory receives outside the examined
	 *                    test's directory, by its path relative to the draft directory
	 * @return the confirmed proposal file, the confirmed prompt tests in the form that a completed proposal holds
	 *   them, and the files that the draft directory receives outside the examined test's directory
	 */
	private ContractContext.Revision revision(Examination examined, Map<Path, Sha256> sharedFiles)
	{
		DerivedProposal confirmed = examined.confirmed();
		return new ContractContext.Revision(examined.request().test(), confirmedProposalFile(confirmed),
			confirmedTests(confirmed), sharedFiles);
	}

	/**
	 * Returns a confirmed proposal's proposal file.
	 *
	 * @param confirmed the confirmed proposal
	 * @return the proposal file
	 */
	private ObjectNode confirmedProposalFile(DerivedProposal confirmed)
	{
		return (ObjectNode) json.readTree(confirmed.derivation());
	}

	/**
	 * Returns a confirmed proposal's prompt tests.
	 *
	 * @param confirmed the confirmed proposal
	 * @return the prompt tests, each in the form that a completed proposal holds it
	 */
	private ArrayNode confirmedTests(DerivedProposal confirmed)
	{
		return (ArrayNode) json.readTree(schema.toJson(confirmed.contract())).get("tests");
	}

	/**
	 * Returns the validator of a test revision's result.
	 *
	 * @param examined what the session examines
	 * @return the validator
	 */
	private TestRevisionCheck check(Examination examined)
	{
		DerivedProposal confirmed = examined.confirmed();
		return new TestRevisionCheck(contractAuthor.check(), confirmedProposalFile(confirmed),
			confirmedTests(confirmed), examined.request().test());
	}

	/**
	 * Returns the turn that asks the session to correct its result.
	 *
	 * @param result   the session's result after it sent its answer
	 * @param examined what the session examined
	 * @param files    the files that the session's project received
	 * @return the turn that lists the result's problems, or empty if the result is valid
	 */
	private Optional<String> correction(SessionResult.Completed result, Examination examined,
		ContractAuthorFiles files)
	{
		try
		{
			validate(result, examined);
			return Optional.empty();
		}
		catch (InvalidProposalException e)
		{
			StringJoiner problems = new StringJoiner("\n");
			for (String problem : e.problems())
				problems.add("- " + problem);
			return Optional.of(correction.fill(Map.of("problems", problems.toString(),
				"output_file", files.context().proposalFile(), "draft_directory", files.draft(),
				"launcher", contractAuthor.launcher(), "context_file", files.contextFile())));
		}
	}

	/**
	 * Validates a test revision session's result. A corrected test's item replaces the examined test's item in the
	 * confirmed proposal file, which is then validated with the corrected test's files and the other confirmed tests
	 * as a proposal with the confirmed contract's salt. A valid correction receives a new draft directory beside the
	 * confirmed one, which holds the confirmed draft with the corrected test's files and their generator calls'
	 * seeds.
	 *
	 * @param result   the session's result
	 * @param examined what the session examined
	 * @return the outcome of the examination; a corrected test's proposal has the new draft directory, each prompt
	 *   test's change from the recorded one and the confirmed proposal's derived sources
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidProposalException if the session wrote no output file or the file is not exactly one JSON object,
	 *                                  or the object has an unknown {@code result}, blank {@code reasons}, a
	 *                                  {@code test} that does not match its {@code result}, or a
	 *                                  {@code proposed_change} that does not match it; or if the corrected test
	 *                                  changes a file outside its directory, the examined test's directory,
	 *                                  requirements or absent triggers, repeats one of its test cases, has fewer than
	 *                                  2 distinct expected outcomes in a part of the split, or makes the contract
	 *                                  invalid. The exception lists every problem found.
	 * @throws UncheckedIOException     if a file cannot be read or written
	 */
	public Result result(SessionResult.Completed result, Examination examined) throws InvalidProposalException
	{
		Result outcome = validate(result, examined);
		if (!(outcome instanceof Result.Revised revised))
			return outcome;
		DerivedProposal confirmed = examined.confirmed();
		DerivedProposal proposal = revised.proposal();
		ProposalCheck check = contractAuthor.check();
		Path draft;
		try
		{
			draft = DraftDirectory.revise(confirmed.draft(), check(examined).directory(), proposal.draft());
			check.writeSeeds(proposal.contract(), draft);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		proposal = proposal.withDraft(draft, check.changes(proposal.contract(), draft,
			contractAuthor.scenarios().project())).withDerivedSources(confirmed.derivedSources());
		return new Result.Revised(proposal, revised.reasons());
	}

	/**
	 * Validates a test revision session's result without changing a file.
	 *
	 * @param result   the session's result
	 * @param examined what the session examined
	 * @return the outcome of the examination; a corrected test's proposal has the session's draft directory, which
	 *   holds only the corrected test
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidProposalException if the result is invalid
	 * @throws UncheckedIOException     if a file cannot be read
	 */
	private Result validate(SessionResult.Completed result, Examination examined) throws InvalidProposalException
	{
		requireThat(result, "result").isNotNull();
		requireThat(examined, "examined").isNotNull();
		ObjectNode output = outputFile.read(result);
		contractAuthor.rewriteProjectPaths(output, result.effects());
		DerivedProposal confirmed = examined.confirmed();
		TestRevisionCheck check = check(examined);
		Path effects = result.effects();
		String draftName = DraftDirectory.name(result.initialProject());
		Path draft = effects.resolve(draftName);
		Result outcome = check.validate(output, effects, draft, confirmed.observedSources(), examined.profiles(),
			confirmed.contract().salt(), true);
		if (!(outcome instanceof Result.Revised))
			return outcome;

		List<Path> changed;
		try
		{
			changed = DraftDirectory.changedOutside(draft, check.directory(),
				DraftDirectory.sharedFiles(confirmed.draft(), check.directory()));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		if (changed.isEmpty())
			return outcome;
		throw new InvalidProposalException(check.outsideProblems(draftName, changed));
	}

	/**
	 * What a test revision session examines.
	 *
	 * @param confirmed        the confirmed proposal, whose proposal file the session receives, whose draft directory
	 *                         holds the examined test's files, and which a corrected test changes
	 * @param profiles         the profile evidence that the confirmed proposal was derived with, which validating a
	 *                         corrected contract reuses so that its trial profiles stay unchanged
	 * @param request          the request to examine the test
	 * @param evidenceSections the evidence section of each failure of {@code request} whose trial's events are
	 *                         retained, by failure identifier
	 */
	public record Examination(DerivedProposal confirmed, ProfileEvidence profiles, TestRevisionRequest request,
		Map<String, String> evidenceSections)
	{
		/**
		 * Creates an examination.
		 *
		 * @param confirmed        the confirmed proposal
		 * @param profiles         the profile evidence that the confirmed proposal was derived with
		 * @param request          the request to examine the test
		 * @param evidenceSections the evidence section of each failure of {@code request} whose trial's events are
		 *                         retained, by failure identifier
		 * @throws NullPointerException     if any of the arguments are null, or {@code evidenceSections} contains null
		 * @throws IllegalArgumentException if the confirmed contract has no test that {@code request} names
		 */
		public Examination
		{
			requireThat(confirmed, "confirmed").isNotNull();
			requireThat(profiles, "profiles").isNotNull();
			requireThat(request, "request").isNotNull();
			requireThat(confirmed.contract().findTest(request.test()).isPresent(), "hasTest").isTrue();
			evidenceSections = Map.copyOf(evidenceSections);
		}
	}

	/**
	 * The outcome of a test revision.
	 */
	public sealed interface Result
	{
		/**
		 * The session corrected the test.
		 *
		 * @param proposal the confirmed proposal with the corrected test
		 * @param reasons  the session's explanation
		 */
		record Revised(DerivedProposal proposal, String reasons) implements Result
		{
			/**
			 * Creates the outcome.
			 *
			 * @param proposal the confirmed proposal with the corrected test
			 * @param reasons  the session's explanation
			 * @throws NullPointerException     if any of the arguments are null
			 * @throws IllegalArgumentException if {@code reasons} is blank
			 */
			public Revised
			{
				requireThat(proposal, "proposal").isNotNull();
				requireThat(reasons, "reasons").isNotBlank();
			}
		}

		/**
		 * The session found that the test follows every rule.
		 *
		 * @param reasons the session's explanation
		 */
		record Sound(String reasons) implements Result
		{
			/**
			 * Creates the outcome.
			 *
			 * @param reasons the session's explanation
			 * @throws NullPointerException     if {@code reasons} is null
			 * @throws IllegalArgumentException if {@code reasons} is blank
			 */
			public Sound
			{
				requireThat(reasons, "reasons").isNotBlank();
			}
		}

		/**
		 * The session found that it cannot correct the test within the confirmed requirements.
		 *
		 * @param reason         why it cannot
		 * @param reasons        the session's explanation
		 * @param proposedChange the requirement change that the session proposes, or empty if it proposes none
		 */
		record Blocked(TestRevisionBlocked.Reason reason, String reasons, String proposedChange) implements Result
		{
			/**
			 * Creates the outcome.
			 *
			 * @param reason         why the session cannot correct the test
			 * @param reasons        the session's explanation
			 * @param proposedChange the requirement change that the session proposes, or empty if it proposes none
			 * @throws NullPointerException     if any of the arguments are null
			 * @throws IllegalArgumentException if {@code reasons} is blank
			 */
			public Blocked
			{
				requireThat(reason, "reason").isNotNull();
				requireThat(reasons, "reasons").isNotBlank();
				requireThat(proposedChange, "proposedChange").isNotNull();
			}
		}
	}
}
