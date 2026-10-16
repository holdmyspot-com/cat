package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision.TestRevisionBlocked;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisionRequest;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Split;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.ValueGenerators;
import io.github.cowwoc.cat.common.optimizer.contract.WorkspaceFixtures;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.FailureJson;
import io.github.cowwoc.cat.common.optimizer.port.FollowUp;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates requests for the session that examines one prompt test of a confirmed contract (a test revision), and
 * validates that session's result.
 * <p>
 * The session runs as the contract author. It receives the confirmed contract, the test and the prompt author's kinds
 * of failure that concern it, and the rules for writing a prompt test. It returns a corrected test, reports the test
 * sound, or reports that no organic test can prove a requirement or that a correction would change one. CAT splices a
 * corrected test into the confirmed contract author output and validates the result as a proposal; it refuses a
 * corrected test that proves other requirements, repeats a test case of the confirmed test, or lacks distinct
 * expected outcomes in a part of the split.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class TestRevisionProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	private static final String PROMPT_RESOURCE = "/optimizer/proposal/test-revision.md";
	/**
	 * The final turn, which asks the session to write the result to its output file.
	 */
	private static final String OUTPUT_RESOURCE = "/optimizer/proposal/test-revision-output.md";
	/**
	 * The turn that returns the problems of a refused result to the session, so that it can correct the parts of its
	 * output file that they concern with its examination still in context.
	 */
	private static final String CORRECTION_RESOURCE = "/optimizer/proposal/test-revision-correction.md";
	private static final String REVISED = "revised";
	private static final String SOUND = "sound";
	private static final String UNPROVABLE = "unprovable";
	private static final String REQUIREMENT_CHANGE = "requirement_change";
	private final JsonMapper json;
	private final ContractAuthorProtocol contractAuthor;
	private final OutputFile outputFile;
	private final PromptTemplate prompt;
	private final String outputSchema;
	private final PromptTemplate outputTurn;
	private final PromptTemplate correction;

	/**
	 * Creates the test revision request builder and result validator.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @param contractAuthor the campaign's contract author protocol, which names the target and the user's goal and
	 *   validates a corrected contract
	 * @throws NullPointerException if any of the arguments are null
	 * @throws UncheckedIOException if a packaged prompt cannot be read
	 */
	public TestRevisionProtocol(JsonMapper json, ProposalSchema schema, ContractAuthorProtocol contractAuthor)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(contractAuthor, "contractAuthor").isNotNull();
		this.json = json;
		this.contractAuthor = contractAuthor;
		this.outputFile = new OutputFile(json);
		this.prompt = ContractAuthorProtocol.withPromptTests(PROMPT_RESOURCE);
		this.outputSchema = outputSchema(json, schema);
		this.outputTurn = PromptTemplate.load(OUTPUT_RESOURCE);
		this.correction = PromptTemplate.load(CORRECTION_RESOURCE);
	}

	/**
	 * Returns the schema of the session's result: its {@code result}, its {@code reasons}, the corrected test as the
	 * contract author writes a prompt test, and the proposed requirement change.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @return the schema text
	 */
	private static String outputSchema(JsonMapper json, ProposalSchema schema)
	{
		ObjectNode derivation = schema.derivationSchema();
		ObjectNode root = json.createObjectNode();
		root.set("$schema", derivation.get("$schema"));
		root.put("title", "Test revision result");
		root.put("type", "object");
		root.put("additionalProperties", false);
		root.putArray("required").add("result").add("reasons").add("test").add("proposed_change");
		ObjectNode definitions = (ObjectNode) derivation.get("$defs");
		definitions.set("prompt_test", derivation.get("properties").get("tests").get("items"));
		root.set("$defs", definitions);

		ObjectNode properties = root.putObject("properties");
		properties.putObject("result").put("type", "string").putArray("enum").add(REVISED).add(SOUND).
			add(UNPROVABLE).add(REQUIREMENT_CHANGE);
		properties.putObject("reasons").put("description", "The rule that the test breaks or follows, and the " +
			"evidence for it.").put("type", "string").put("pattern", "\\S");
		ObjectNode test = properties.putObject("test");
		test.put("description", "The complete corrected test when the result is revised; otherwise null.");
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
	 * Returns the request for examining one prompt test.
	 *
	 * @param promptSystem the directory that holds the prompt system, which the session works in so that the harness
	 *   loads it as it would in a trial
	 * @param examined what the session examines
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
		String turn = prompt.fill(Map.of("target_prompt_file", contractAuthor.targetPromptFile(),
			"goal", contractAuthor.goal(), "contract", examined.confirmed().derivation(), "test", request.test(),
			"trigger", trigger, "kinds", json.writerWithDefaultPrettyPrinter().writeValueAsString(kinds),
			"generators", ValueGenerators.describeAll(), "output_schema", outputSchema));
		String file = OutputFile.name(promptSystem);
		FollowUp correct = result -> correction(result, examined, file);
		return new SessionRequest(SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT, promptSystem, List.of(turn,
			outputTurn.fill(Map.of("output_file", file))), List.of(), TIME_LIMIT, contractAuthor.role(),
			WorkspaceFixtures.NONE, List.of(), Optional.of(correct));
	}

	/**
	 * Returns the turn that asks the session to correct its result.
	 *
	 * @param result the session's result after it sent its answer
	 * @param examined what the session examined
	 * @param file the name of the session's output file
	 * @return the turn that lists the result's problems, or empty if the result is valid
	 */
	private Optional<String> correction(SessionResult.Completed result, Examination examined, String file)
	{
		try
		{
			result(result, examined);
			return Optional.empty();
		}
		catch (InvalidProposalException e)
		{
			StringJoiner problems = new StringJoiner("\n");
			for (String problem : e.problems())
				problems.add("- " + problem);
			return Optional.of(correction.fill(Map.of("problems", problems.toString(), "output_file", file)));
		}
	}

	/**
	 * Validates a test revision session's result. A corrected test replaces the examined test in the confirmed
	 * contract author output, which is then validated as a proposal with the confirmed contract's salt.
	 *
	 * @param result the session's result
	 * @param examined what the session examined
	 * @return the outcome of the examination
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if the session wrote no output file or the file is not exactly one JSON object,
	 *   or the object has an unknown {@code result}, blank {@code reasons}, a {@code test} that
	 *   does not match its {@code result}, or a {@code proposed_change} that does not match it; or if the corrected
	 *   test changes the examined test's identifier, requirements, absent triggers or regression scenarios, repeats one
	 *   of its test cases, has fewer than 2 distinct expected outcomes in a part of the split, or makes the contract
	 *   invalid. The exception lists every problem found.
	 */
	public Result result(SessionResult.Completed result, Examination examined) throws InvalidProposalException
	{
		requireThat(result, "result").isNotNull();
		requireThat(examined, "examined").isNotNull();
		ObjectNode output = outputFile.read(result);
		contractAuthor.rewriteProjectPaths(output, result.effects());

		List<String> problems = new ArrayList<>();
		String kind = text(output, "result");
		String reasons = text(output, "reasons");
		String proposedChange = text(output, "proposed_change");
		JsonNode test = output.get("test");
		if (reasons == null)
			problems.add("$.reasons: state the rule that the test breaks or follows, and the evidence for it");
		Result outcome = null;
		switch (kind)
		{
			case REVISED ->
			{
				requireNull(proposedChange, "proposed_change", kind, problems);
				if (test instanceof ObjectNode corrected)
				{
					Optional<DerivedProposal> revised = revised(corrected, examined, problems);
					if (revised.isPresent() && reasons != null)
						outcome = new Result.Revised(revised.get(), reasons);
				}
				else
					problems.add("$.test: a revised result holds the complete corrected test");
			}
			case SOUND ->
			{
				requireNull(test, "test", kind, problems);
				requireNull(proposedChange, "proposed_change", kind, problems);
				if (reasons != null)
					outcome = new Result.Sound(reasons);
			}
			case UNPROVABLE ->
			{
				requireNull(test, "test", kind, problems);
				if (reasons != null)
				{
					outcome = new Result.Blocked(TestRevisionBlocked.Reason.UNPROVABLE, reasons,
						Optional.ofNullable(proposedChange).orElse(""));
				}
			}
			case REQUIREMENT_CHANGE ->
			{
				requireNull(test, "test", kind, problems);
				if (proposedChange == null)
				{
					problems.add("$.proposed_change: a requirement_change result states the requirement change as " +
						"feedback on the confirmed contract");
				}
				else if (reasons != null)
				{
					outcome = new Result.Blocked(TestRevisionBlocked.Reason.REQUIREMENT_CHANGE, reasons,
						proposedChange);
				}
			}
			case null, default -> problems.add("$.result: must be one of " + REVISED + ", " + SOUND + ", " +
				UNPROVABLE + " or " + REQUIREMENT_CHANGE);
		}
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);
		return outcome;
	}

	/**
	 * Returns a non-blank string property.
	 *
	 * @param output the session's output
	 * @param name the property's name
	 * @return the property's value, or {@code null} if it is absent, not a string or blank
	 */
	private static String text(ObjectNode output, String name)
	{
		JsonNode value = output.get(name);
		if (value == null || !value.isString() || value.asString().isBlank())
			return null;
		return value.asString();
	}

	/**
	 * Adds a problem if a property that the result's kind leaves out has a value.
	 *
	 * @param value the property's value, or {@code null}
	 * @param name the property's name
	 * @param kind the result's kind
	 * @param problems the problems found so far
	 */
	private static void requireNull(Object value, String name, String kind, List<String> problems)
	{
		if (value != null && !(value instanceof JsonNode node && node.isNull()))
			problems.add("$." + name + ": must be null when the result is " + kind);
	}

	/**
	 * Replaces the examined test with a corrected test in the confirmed contract author output and validates the
	 * result.
	 *
	 * @param corrected the corrected test
	 * @param examined what the session examined
	 * @param problems the problems found so far, which this method adds to
	 * @return the revised proposal, or empty if it has a problem
	 */
	private Optional<DerivedProposal> revised(ObjectNode corrected, Examination examined, List<String> problems)
	{
		DerivedProposal confirmed = examined.confirmed();
		String id = examined.request().test();
		PromptTest before = confirmed.contract().findTest(id).orElseThrow(() -> new IllegalArgumentException(
			"The confirmed contract has no test " + id));
		JsonNode correctedId = corrected.get("id");
		if (correctedId == null || !correctedId.isString() || !correctedId.asString().equals(id))
		{
			problems.add("$.test.id: a revision keeps the identifier `" + id + "`");
			return Optional.empty();
		}

		ObjectNode output = (ObjectNode) json.readTree(confirmed.derivation());
		ArrayNode tests = (ArrayNode) output.get("tests");
		for (int i = 0; i < tests.size(); ++i)
		{
			if (tests.get(i).get("id").asString().equals(id))
				tests.set(i, corrected);
		}
		DerivedProposal revised;
		try
		{
			revised = contractAuthor.proposal(output, confirmed.observedSources(), examined.profiles(),
				confirmed.contract().salt()).withDerivedSources(confirmed.derivedSources());
		}
		catch (InvalidProposalException e)
		{
			problems.addAll(e.problems());
			return Optional.empty();
		}

		PromptTest after = revised.contract().findTest(id).orElseThrow();
		List<String> changes = new ArrayList<>();
		if (!after.requirementIds().equals(before.requirementIds()))
		{
			changes.add("$.test.requirement_ids: a revision keeps the requirements " + before.requirementIds() +
				" that the user confirmed. A test that needs other requirements is a requirement_change result");
		}
		if (!after.absentTriggers().equals(before.absentTriggers()))
		{
			changes.add("$.test.absent_triggers: a revision keeps the absent triggers " + before.absentTriggers() +
				" that the user confirmed");
		}
		if (!after.regressionScenarios().equals(before.regressionScenarios()))
		{
			changes.add("$.test.regression_scenarios: a revision keeps the citations " +
				before.regressionScenarios() + " that the user confirmed");
		}
		List<TestCase> confirmedTestCases = before.allTestCases();
		for (TestCase testCase : after.testCases())
		{
			if (confirmedTestCases.contains(testCase))
			{
				changes.add("$.test.test_cases: the test case whose expected outcome is `" +
					testCase.expectedOutcome() + "` repeats a test case of the confirmed test; write new test cases");
			}
		}
		Split split = revised.contract().split();
		int development = split.outcomeCount(after, Part.DEVELOPMENT);
		int heldOut = split.outcomeCount(after, Part.HELD_OUT);
		if (development < Proposal.MINIMUM_OUTCOMES || heldOut < Proposal.MINIMUM_OUTCOMES)
		{
			changes.add("$.test.test_cases: the test has " + development + " distinct expected outcomes in the " +
				"development part and " + heldOut + " in the held-out part, but each part needs at least " +
				Proposal.MINIMUM_OUTCOMES + "; write test cases with more expected outcomes");
		}
		if (!changes.isEmpty())
		{
			problems.addAll(changes);
			return Optional.empty();
		}
		return Optional.of(revised);
	}

	/**
	 * What a test revision session examines.
	 *
	 * @param confirmed the confirmed proposal, whose contract author output the session receives and a corrected test
	 *   changes
	 * @param profiles the profile evidence that the confirmed proposal was derived with, which validating a corrected
	 *   contract reuses so that its trial profiles stay unchanged
	 * @param request the request to examine the test
	 * @param evidenceSections the evidence section of each failure of {@code request} whose trial's events are
	 *   retained, by failure identifier
	 */
	public record Examination(DerivedProposal confirmed, ProfileEvidence profiles, TestRevisionRequest request,
		Map<String, String> evidenceSections)
	{
		/**
		 * Creates an examination.
		 *
		 * @param confirmed the confirmed proposal
		 * @param profiles the profile evidence that the confirmed proposal was derived with
		 * @param request the request to examine the test
		 * @param evidenceSections the evidence section of each failure of {@code request} whose trial's events are
		 *   retained, by failure identifier
		 * @throws NullPointerException if any of the arguments are null, or {@code evidenceSections} contains null
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
		 * @param reasons the session's explanation
		 */
		record Revised(DerivedProposal proposal, String reasons) implements Result
		{
			/**
			 * Creates the outcome.
			 *
			 * @param proposal the confirmed proposal with the corrected test
			 * @param reasons the session's explanation
			 * @throws NullPointerException if any of the arguments are null
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
			 * @throws NullPointerException if {@code reasons} is null
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
		 * @param reason why it cannot
		 * @param reasons the session's explanation
		 * @param proposedChange the requirement change that the session proposes, or empty if it proposes none
		 */
		record Blocked(TestRevisionBlocked.Reason reason, String reasons, String proposedChange) implements Result
		{
			/**
			 * Creates the outcome.
			 *
			 * @param reason why the session cannot correct the test
			 * @param reasons the session's explanation
			 * @param proposedChange the requirement change that the session proposes, or empty if it proposes none
			 * @throws NullPointerException if any of the arguments are null
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
