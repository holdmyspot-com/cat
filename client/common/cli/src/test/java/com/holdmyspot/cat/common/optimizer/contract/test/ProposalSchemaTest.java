package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.ContractIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.ExpectedOutcomeTool;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link ProposalSchema}.
 */
public final class ProposalSchemaTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public ProposalSchemaTest()
	{
	}

	/**
	 * An evaluated contract reads identical test profiles with distinct evaluator-qualified addresses.
	 *
	 * @throws InvalidProposalException if the stored contract cannot be read
	 */
	@Test
	public void storedContractWithTwoEvaluators() throws InvalidProposalException
	{
		// Requirement: R-L8
		ProposalSchema schema = schema();
		Contract originalContract = ContractFixtures.contract();
		ObjectNode stored = (ObjectNode) json.readTree(schema.toJson(originalContract));
		ObjectNode original = (ObjectNode) stored.get("trial_profiles");
		ArrayNode evaluators = stored.putObject("trial_profiles").putArray("evaluators");
		for (Harness evaluator : Harness.values())
		{
			ObjectNode local = original.deepCopy();
			local.put("inventory_source", evaluator.commandName() + " profile fixture");
			((ObjectNode) local.get("ranking")).put("source", evaluator.commandName() + " ranking fixture");
			for (JsonNode element : local.get("cells"))
			{
				ObjectNode cell = (ObjectNode) element;
				cell.put("id", evaluator.commandName() + ":" + cell.get("id").asString());
			}
			evaluators.addObject().put("evaluator", evaluator.commandName()).set("profiles", local);
		}

		Contract loaded = schema.parse(json.writeValueAsString(stored));

		for (Harness evaluator : Harness.values())
		{
			String id = evaluator.commandName() + ":plain_list";
			Cell cell = loaded.findCell(id).orElseThrow();
			TrialProfiles local = loaded.profiles().forEvaluator(evaluator);
			requireThat(cell.evaluator(), "evaluator").isEqualTo(Optional.of(evaluator));
			requireThat(cell.profile(), "trialProfile").isEqualTo(originalContract.findCell("plain_list").
				orElseThrow().profile());
			requireThat(local.inventory().source(), "inventorySource").
				isEqualTo(evaluator.commandName() + " profile fixture");
			requireThat(local.ranking().source(), "rankingSource").isEqualTo(evaluator.commandName() + " ranking fixture");
			requireThat(local.cellsOf("plain_list"), "localCells").isEqualTo(List.of(cell));
		}
		requireThat(json.readTree(schema.toJson(loaded)), "roundTrip").isEqualTo(stored);
	}

	/**
	 * A shared campaign cannot read profile evidence from a bare singleton contract.
	 *
	 * @throws InvalidProposalException when the contract fails to supply each evaluator's profiles
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = ".*must record every evaluator's profiles.*")
	public void sharedScopeNeedsEvaluatorProfiles() throws InvalidProposalException
	{
		// Requirement: R-L8
		ProposalSchema schema = schema();
		schema.parseRecorded(schema.toJson(ContractFixtures.contract()), Set.of(Harness.CLAUDE, Harness.CODEX));
	}

	/**
	 * Assigns identical computed cells to two evaluators and keeps bare addresses for an individual evaluator.
	 */
	@Test
	public void computedProfilesQualifySharedCells()
	{
		// Requirement: R-L8
		TrialProfiles individual = ContractFixtures.contract().profiles();
		Cell raw = individual.findCell("plain_list").orElseThrow();
		TrialProfiles scoped = TrialProfiles.forEvaluators(Map.of(Harness.CLAUDE, individual, Harness.CODEX, individual));
		requireThat(scoped.cellsOf(raw.testId()), "sharedCells").isEqualTo(List.of(
			new Cell(Harness.CLAUDE.commandName() + ":" + raw.id(), raw.testId(), raw.profile(), Optional.of(Harness.CLAUDE)),
			new Cell(Harness.CODEX.commandName() + ":" + raw.id(), raw.testId(), raw.profile(), Optional.of(Harness.CODEX))));
		requireThat(String.join("\n", scoped.describe()), "description").
			contains("named `" + Harness.CODEX.commandName() + ":<test>`");
		TrialProfiles singleton = TrialProfiles.forEvaluators(Map.of(Harness.CLAUDE, individual));
		requireThat(singleton.cellsOf(raw.testId()), "singletonCell").isEqualTo(List.of(
			new Cell(raw.id(), raw.testId(), raw.profile(), Optional.of(Harness.CLAUDE))));
	}

	/**
	 * Verifies that an assertion with a built-in tool, and the requirement that it checks, survive writing the
	 * contract and reading it back, and that the assertion is part of the contract's identity.
	 *
	 * @throws InvalidProposalException if the stored contract is rejected
	 */
	@Test
	public void assertionsSurviveTheStoredContract() throws InvalidProposalException
	{
		// Requirement: R-C8
		// Requirement: R-C9
		ObjectNode stored = (ObjectNode) json.readTree(schema().toJson(ContractFixtures.contract()));
		ObjectNode assertion = (ObjectNode) json.readTree("""
			{"id":"avoids_disclaimer","kind":"guard","tool":"forbidden-text turn=1 \\"As an AI\\"",
			 "on_tool_failure":"judge","statement":"The list has no disclaimer.","requirement_id":"r1"}
			""");
		((ArrayNode) stored.get("tests").get(1).get("assertions")).add(assertion);

		Contract loaded = schema().parse(json.writeValueAsString(stored));

		List<MappedAssertion> assertions = loaded.findTest("plain_list").orElseThrow().assertions();
		requireThat(assertions.getLast(), "assertions.getLast()").isEqualTo(new MappedAssertion(new Assertion(
			"avoids_disclaimer", AssertionKind.GUARD, "forbidden-text turn=1 \"As an AI\"", OnToolFailure.JUDGE,
			"The list has no disclaimer."), "r1"));
		requireThat(json.readTree(schema().toJson(loaded)), "roundTrip").isEqualTo(stored);
		ProposalSchema schema = schema();
		requireThat(ContractIdentity.of(schema, loaded), "identity").
			isNotEqualTo(ContractIdentity.of(schema, ContractFixtures.contract()));
	}

	/**
	 * Verifies that the confirmed contract carries an example's exact file bytes and its explicit empty directory.
	 *
	 * @throws InvalidProposalException if the stored contract is rejected
	 */
	@Test
	public void storesAssertionExampleFiles() throws InvalidProposalException
	{
		// Requirement: R-L15
		ProposalSchema schema = schema();
		String path = "arrives/pass_same_day/initial/result.dat";
		ObjectNode stored = contractWithExample(path);

		Contract loaded = schema.parse(json.writeValueAsString(stored));

		PromptTest.Draft draft = loaded.tests().getFirst().draft().orElseThrow();
		requireThat(draft.exampleDirectories(), "exampleDirectories").isEqualTo(Set.of("arrives/pass_same_day/project"));
		FileContent evidence = draft.exampleFiles().get(path);
		byte[] bytes = evidence.bytes();
		requireThat(Arrays.equals(bytes, new byte[] {(byte) 0xff, 0}), "exampleBytesMatch").isTrue();
		bytes[0] = 0;
		requireThat(json.readTree(schema.toJson(loaded)), "roundTrip").isEqualTo(stored);

		ObjectNode file = (ObjectNode) stored.at("/tests/0/draft/example_files/0");
		file.put("content", Base64.getEncoder().encodeToString(bytes)).put("sha256", Sha256.of(bytes).hex());
		Contract changed = schema.parse(json.writeValueAsString(stored));
		requireThat(ContractIdentity.of(schema, changed), "changedIdentity").
			isNotEqualTo(ContractIdentity.of(schema, loaded));
	}

	/**
	 * Verifies that a draft created before contracts stored assertion examples keeps its original serialized form.
	 *
	 * @throws InvalidProposalException if the stored contract is rejected
	 */
	@Test
	public void preservesLegacyDraftFormat() throws InvalidProposalException
	{
		ProposalSchema schema = schema();
		ObjectNode stored = contractWithExample("arrives/pass_same_day/initial/result.dat");
		ProposalSchema.omitExamples((ObjectNode) stored.at("/tests/0"));

		Contract loaded = schema.parse(json.writeValueAsString(stored));

		PromptTest.Draft draft = loaded.tests().getFirst().draft().orElseThrow();
		requireThat(draft.exampleFiles(), "exampleFiles").isEmpty();
		requireThat(draft.exampleDirectories(), "exampleDirectories").isEmpty();
		requireThat(json.readTree(schema.toJson(loaded)), "roundTrip").isEqualTo(stored);
	}

	/**
	 * Verifies that the confirmed codec refuses example bytes whose digest differs from the stored digest.
	 *
	 * @throws InvalidProposalException if the codec detects the inconsistent digest
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = "(?s).*arrives/pass_same_day/initial/result.dat.*SHA-256.*")
	public void refusesChangedExampleContent() throws InvalidProposalException
	{
		ObjectNode stored = contractWithExample("arrives/pass_same_day/initial/result.dat");
		ObjectNode file = (ObjectNode) stored.at("/tests/0/draft/example_files/0");
		file.put("content", "AA==");

		schema().parse(json.writeValueAsString(stored));
	}

	/**
	 * Verifies that an example file cannot use a path outside its examples directory.
	 *
	 * @throws InvalidProposalException if the codec detects the path
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = "(?s).*example path.*[.][.]/outside[.]dat.*")
	public void refusesEscapingExampleFile() throws InvalidProposalException
	{
		ObjectNode stored = contractWithExample("../outside.dat");

		schema().parse(json.writeValueAsString(stored));
	}

	/**
	 * Verifies that a stored example file cannot occupy an example directory's path.
	 *
	 * @throws InvalidProposalException if the codec detects the collision
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = "(?s).*arrives/pass_same_day/project.*both a file and a directory.*")
	public void refusesExamplePathCollision() throws InvalidProposalException
	{
		ObjectNode stored = contractWithExample("arrives/pass_same_day/project");

		schema().parse(json.writeValueAsString(stored));
	}

	/**
	 * Verifies that an example cannot have a directory underneath a file.
	 *
	 * @throws InvalidProposalException if the codec detects the occupied parent directory
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = "(?s).*arrives/pass_same_day.*uses it as a directory.*")
	public void refusesExampleFileAsParent() throws InvalidProposalException
	{
		ObjectNode stored = contractWithExample("arrives/pass_same_day");

		schema().parse(json.writeValueAsString(stored));
	}

	/**
	 * Supplies stored example evidence with an explicit empty project and one file at the given path.
	 *
	 * @param path the file's path relative to the examples directory
	 * @return a stored contract with the example evidence
	 */
	private ObjectNode contractWithExample(String path)
	{
		ObjectNode stored = (ObjectNode) json.readTree(schema().toJson(ContractFixtures.contract()));
		ObjectNode draft = ((ObjectNode) stored.get("tests").get(0)).putObject("draft");
		draft.put("directory", ".cat/tests/guidance/formatting").put("scenario", "# Formatting test");
		draft.putArray("example_directories").add("arrives/pass_same_day/project");
		byte[] bytes = {(byte) 0xff, 0};
		draft.putArray("example_files").addObject().put("path", path).put("sha256", Sha256.of(bytes).hex()).
			put("content", Base64.getEncoder().encodeToString(bytes));
		return stored;
	}

	/**
	 * Verifies that each judged assertion's evidence scope and the judge's response prompt survive writing the contract
	 * and reading it back and are part of the contract's identity, that an assertion without a scope writes no property
	 * for it, and that a value other than {@code response} or {@code actions} is rejected at the assertion's location.
	 *
	 * @throws InvalidProposalException if the stored contract is rejected
	 */
	@Test
	public void evidenceScopesAreStored() throws InvalidProposalException
	{
		// Requirement: R-L19
		Contract plain = ContractFixtures.contract();
		ObjectNode plainStored = (ObjectNode) json.readTree(schema().toJson(plain));
		requireThat(plainStored.get("tests").get(0).get("assertions").get(0).has("evidence_scope"), "withoutScope").
			isFalse();

		Contract scoped = ContractFixtures.withEvidenceScope(plain, EvidenceScope.RESPONSE);
		ObjectNode stored = (ObjectNode) json.readTree(schema().toJson(scoped));
		requireThat(stored.get("tests").get(0).get("assertions").get(0).path("evidence_scope").asString(),
			"storedScope").isEqualTo("response");
		requireThat(stored.get("judge").path("response_prompt").asString(), "storedResponsePrompt").
			isEqualTo(scoped.judge().responsePrompt());

		Contract loaded = schema().parse(json.writeValueAsString(stored));
		requireThat(loaded.tests(), "tests").isEqualTo(scoped.tests());
		requireThat(loaded.judge(), "judge").isEqualTo(scoped.judge());
		ProposalSchema schema = schema();
		requireThat(ContractIdentity.of(schema, loaded), "identity").
			isNotEqualTo(ContractIdentity.of(schema, ContractFixtures.withEvidenceScope(plain,
				EvidenceScope.ACTIONS)));

		((ObjectNode) stored.get("tests").get(0).get("assertions").get(0)).put("evidence_scope", "both");
		requireThat(String.join(" | ", rejection(json.writeValueAsString(stored)).problems()), "invalidScope").
			contains("$.tests[0].assertions[0]").contains("evidence_scope");
	}

	/**
	 * Verifies that the built-in tool of the assertion that CAT adds survives writing the contract and reading it back,
	 * that it is part of the contract's identity, that a contract without it writes no property for it, and that a tool
	 * that names no built-in tool is rejected at the test's location.
	 *
	 * @throws InvalidProposalException if the stored contract is rejected
	 */
	@Test
	public void expectedOutcomeToolIsStored() throws InvalidProposalException
	{
		ObjectNode stored = (ObjectNode) json.readTree(schema().toJson(ContractFixtures.contract()));
		requireThat(stored.get("tests").get(1).has("expected_outcome_tool"), "withoutTool").isFalse();
		((ObjectNode) stored.get("tests").get(1)).putObject("expected_outcome_tool").
			put("tool", "pattern {{expected_outcome}}").put("on_tool_failure", "judge");

		Contract loaded = schema().parse(json.writeValueAsString(stored));

		requireThat(loaded.findTest("plain_list").orElseThrow().expectedOutcomeTool(), "tool").isEqualTo(Optional.of(
			new ExpectedOutcomeTool("pattern {{expected_outcome}}", OnToolFailure.JUDGE)));
		requireThat(json.readTree(schema().toJson(loaded)), "roundTrip").isEqualTo(stored);
		ProposalSchema schema = schema();
		requireThat(ContractIdentity.of(schema, loaded), "identity").
			isNotEqualTo(ContractIdentity.of(schema, ContractFixtures.contract()));

		((ObjectNode) stored.get("tests").get(1).get("expected_outcome_tool")).put("tool", "no-such-check x");
		requireThat(String.join(" | ", rejection(json.writeValueAsString(stored)).problems()), "unknownTool").
			contains("$.tests[1].expected_outcome_tool").contains("There is no built-in tool named no-such-check");
	}

	/**
	 * Verifies that the near misses of judged assertions survive writing the contract and reading it back, that they
	 * are part of the contract's identity, that a contract without them writes no property for them, and that a test
	 * accepts them only for the assertions that its judge decides, with two replies on each side.
	 *
	 * @throws InvalidProposalException if the stored contract is rejected
	 */
	@Test
	public void nearMissesAreStored() throws InvalidProposalException
	{
		Contract original = ContractFixtures.contract();
		Contract described = ContractFixtures.withNearMisses(original);
		requireThat(json.readTree(schema().toJson(original)).get("tests").get(1).has("near_misses"), "without").
			isFalse();
		ObjectNode stored = (ObjectNode) json.readTree(schema().toJson(described));
		ArrayNode nearMisses = (ArrayNode) stored.get("tests").get(1).get("near_misses");
		requireThat(nearMisses.size(), "described").isEqualTo(described.tests().get(1).judgedAssertions().size());
		requireThat(nearMisses.get(0).get("satisfying").size(), "satisfying").isEqualTo(2);
		requireThat(nearMisses.get(0).get("failing").size(), "failing").isEqualTo(2);

		Contract loaded = schema().parse(json.writeValueAsString(stored));

		requireThat(loaded, "loaded").isEqualTo(described);
		requireThat(json.readTree(schema().toJson(loaded)), "roundTrip").isEqualTo(stored);
		requireThat(loaded.tests().get(1).hasCompleteNearMisses(), "complete").isTrue();
		requireThat(original.tests().get(1).hasCompleteNearMisses(), "completeWithout").isFalse();
		ProposalSchema schema = schema();
		requireThat(ContractIdentity.of(schema, described), "identity").
			isNotEqualTo(ContractIdentity.of(schema, original));

		ObjectNode oneReply = stored.deepCopy();
		((ArrayNode) oneReply.get("tests").get(1).get("near_misses").get(0).get("satisfying")).remove(1);
		requireThat(String.join(" | ", rejection(json.writeValueAsString(oneReply)).problems()), "oneReply").
			contains("$.tests[1].near_misses[0]");

		ObjectNode threeReplies = stored.deepCopy();
		((ArrayNode) threeReplies.get("tests").get(1).get("near_misses").get(0).get("failing")).add("A third reply");
		requireThat(String.join(" | ", rejection(json.writeValueAsString(threeReplies)).problems()), "threeReplies").
			contains("$.tests[1].near_misses[0]");

		ObjectNode undecided = stored.deepCopy();
		((ObjectNode) undecided.get("tests").get(1).get("near_misses").get(0)).put("assertion_id", "no_such_assertion");
		requireThat(String.join(" | ", rejection(json.writeValueAsString(undecided)).problems()), "undecided").
			contains("$.tests[1]").contains("no_such_assertion");

		ObjectNode shared = stored.deepCopy();
		ObjectNode first = (ObjectNode) shared.get("tests").get(1).get("near_misses").get(0);
		((ArrayNode) first.get("failing")).set(0, first.get("satisfying").get(0));
		requireThat(String.join(" | ", rejection(json.writeValueAsString(shared)).problems()), "sharedReply").
			contains("$.tests[1].near_misses[0]");

		ObjectNode unknownName = stored.deepCopy();
		((ArrayNode) unknownName.get("tests").get(1).get("near_misses").get(0).get("failing")).set(0,
			json.getNodeFactory().stringNode("Mentions {{no_such_variable}}"));
		requireThat(String.join(" | ", rejection(json.writeValueAsString(unknownName)).problems()), "unknownName").
			contains("no_such_variable");
	}

	/**
	 * Verifies that an assertion whose {@code tool} line names no built-in tool, or gives a tool arguments that do
	 * not suit it, is rejected at the assertion's location.
	 */
	@Test
	public void rejectsAnInvalidToolLine()
	{
		// Requirement: R-C9
		String[] tool = {"tests", "1", "assertions", "0", "tool"};

		requireThat(String.join(" | ", rejection(text(tool, "no-such-check x")).problems()), "unknownCheck").
			contains("$.tests[1].assertions[0]").
			contains("There is no built-in tool named no-such-check");
		requireThat(String.join(" | ", rejection(text(tool, "forbidden-text")).problems()), "missingArgument").
			contains("$.tests[1].assertions[0]").
			contains("forbidden-text takes");
	}

	/**
	 * Verifies that an assertion that leaves a failure of its built-in tool to the judge but has no tool is rejected at
	 * the assertion's location.
	 */
	@Test
	public void rejectsJudgeWithoutTool()
	{
		// Requirement: R-C9
		String[] onToolFailure = {"tests", "1", "assertions", "0", "on_tool_failure"};

		requireThat(String.join(" | ", rejection(text(onToolFailure, "judge")).problems()), "problems").
			contains("$.tests[1].assertions[0]").
			contains("no tool");
	}

	/**
	 * Returns the schema reader under test.
	 *
	 * @return the reader
	 */
	private ProposalSchema schema()
	{
		return new ProposalSchema(json);
	}

	/**
	 * Returns the valid proposal with one value replaced.
	 *
	 * @param path the property names and array indexes that lead to the value's parent, ending with the property
	 *   name to replace
	 * @param replacement the new value, or null to remove the property
	 * @return the changed proposal text
	 */
	private String changed(String[] path, JsonNode replacement)
	{
		JsonNode root = json.readTree(ContractFixtures.resource("valid-proposal.json"));
		JsonNode parent = root;
		for (int i = 0; i < path.length - 1; ++i)
		{
			String step = path[i];
			if (parent instanceof ArrayNode)
				parent = parent.get(Integer.parseInt(step));
			else
				parent = parent.get(step);
		}
		String last = path[path.length - 1];
		if (parent instanceof ArrayNode array)
		{
			int index = Integer.parseInt(last);
			if (replacement == null)
				array.remove(index);
			else
				array.set(index, replacement);
		}
		else
		{
			ObjectNode object = (ObjectNode) parent;
			if (replacement == null)
				object.remove(last);
			else
				object.set(last, replacement);
		}
		return json.writeValueAsString(root);
	}

	/**
	 * Returns the valid proposal with a string replaced.
	 *
	 * @param path the location of the value
	 * @param value the new string
	 * @return the changed proposal text
	 */
	private String text(String[] path, String value)
	{
		return changed(path, json.getNodeFactory().stringNode(value));
	}

	/**
	 * Returns the valid proposal with a whole number replaced.
	 *
	 * @param path the location of the value
	 * @param value the new number
	 * @return the changed proposal text
	 */
	private String integer(String[] path, int value)
	{
		return changed(path, json.getNodeFactory().numberNode(value));
	}

	/**
	 * Returns the valid proposal with a number replaced.
	 *
	 * @param path the location of the value
	 * @param value the new number
	 * @return the changed proposal text
	 */
	private String number(String[] path, double value)
	{
		return changed(path, json.getNodeFactory().numberNode(value));
	}

	/**
	 * Parses a proposal that must be rejected.
	 *
	 * @param proposal the proposal text
	 * @return the rejection
	 * @throws AssertionError if the proposal is accepted
	 */
	private InvalidProposalException rejection(String proposal)
	{
		try
		{
			schema().parse(proposal);
		}
		catch (InvalidProposalException e)
		{
			return e;
		}
		throw new AssertionError("Proposal was accepted");
	}

	/**
	 * Verifies that the valid fixture proposal is accepted and becomes the contract that it describes, including every
	 * item that a proposal must list.
	 *
	 * @throws InvalidProposalException if the fixture proposal is rejected
	 */
	@Test
	public void parsesValidProposal() throws InvalidProposalException
	{
		// Requirement: R-E21
		// Requirement: R-C1
		Contract contract = schema().parse(ContractFixtures.resource("valid-proposal.json"));
		Contract fixture = ContractFixtures.contract();
		Contract expected = new Contract(fixture.requirements(), fixture.tests(), fixture.profiles(),
			frozenJudge(), fixture.limitations(), fixture.minimumPassRate(), fixture.salt());

		requireThat(contract, "contract").isEqualTo(expected);
		requireThat(contract.requirements().get(1).source(), "source").isEqualTo("rules/safety.md");
		PromptTest styled = contract.tests().get(0);
		requireThat(styled.timeLimit().toSeconds(), "timeLimitSeconds").isEqualTo(300L);
		requireThat(styled.turns().size(), "turns.size()").isEqualTo(2);
	}

	/**
	 * Verifies that writing the valid fixture contract and reading it back preserves its confirmed values.
	 *
	 * @throws InvalidProposalException if the written fixture contract is rejected
	 */
	@Test
	public void writtenContractParsesBackEqual() throws InvalidProposalException
	{
		// Requirement: R-C1
		Contract original = ContractFixtures.contract();

		Contract reread = schema().parse(schema().toJson(original));

		requireThat(reread, "reread").isEqualTo(original);
	}

	/**
	 * Verifies that a judged check definition depends only on the test, its own requirements and the frozen judge.
	 */
	@Test
	public void judgedDefinitionIgnoresUnrelatedRules()
	{
		// Requirement: R-L16
		Contract contract = ContractFixtures.contract();
		PromptTest test = contract.tests().getFirst();
		Map<String, Requirement> requirements = new HashMap<>();
		for (Requirement requirement : contract.requirements())
			requirements.put(requirement.id(), requirement);
		String definition = schema().judgedCheckDefinition(test, requirements, contract.judge());

		Requirement unrelated = new Requirement("later", "Record an unrelated action.", "rules/later.md", null,
			List.of("omitted"));
		requirements.put(unrelated.id(), unrelated);
		requireThat(schema().judgedCheckDefinition(test, requirements, contract.judge()), "unrelated").
			isEqualTo(definition);

		String checkedId = test.requirementIds().getFirst();
		Requirement checked = requirements.get(checkedId);
		requirements.put(checkedId, new Requirement(checkedId, "Follow an edited rule.", checked.source(),
			checked.trigger(), checked.behaviors()));
		requireThat(schema().judgedCheckDefinition(test, requirements, contract.judge()), "changed").
			isNotEqualTo(definition);
	}

	/**
	 * Verifies that every test lists its regression scenario citations explicitly, including an empty list.
	 */
	@Test
	public void rejectsMissingScenarioCitations()
	{
		InvalidProposalException e = rejection(changed(
			new String[]{"tests", "0", "regression_scenarios"}, null));

		requireThat(String.join(" | ", e.problems()), "problems").contains("regression_scenarios");
	}

	/**
	 * Verifies that a contract author's test may write workspace files and give a variable a generator call, which CAT
	 * completes with the generator's version and a distinct seed for each test case, and that the completed contract
	 * reads back unchanged. A call that the stored form holds without its seed is refused.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void readsWorkspaceFilesAndGeneratorCalls() throws InvalidProposalException
	{
		// Requirement: R-C21
		ObjectNode proposal = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		proposal.remove(List.of("salt", "trial_profiles"));
		for (JsonNode testNode : proposal.get("tests"))
			((ObjectNode) testNode).remove("committed_test_cases");
		ObjectNode test = (ObjectNode) proposal.get("tests").get(0);
		test.withArray("workspace_files").addObject().put("path", "notes/{{tone}}.md").
			put("content", "{{before}}\nWrite for {{audience}} readers.\n");
		test.withArray("variables").addObject().put("name", "before").
			put("description", "Log lines before the notes.");
		for (JsonNode testCase : test.get("test_cases"))
		{
			((ArrayNode) testCase.get("values")).addObject().put("variable", "before").putObject("call").
				put("generator", "log_lines").put("count", 3);
		}
		String output = json.writeValueAsString(proposal);

		Contract contract = schema().parse(output, ContractFixtures::profiles, ContractFixtures.SALT,
			new SplittableRandom(3), Map.of(), Map.of());

		PromptTest parsed = contract.tests().getFirst();
		requireThat(parsed.workspaceFiles(), "workspaceFiles").isEqualTo(List.of(new WorkspaceFile("notes/{{tone}}.md",
			"{{before}}\nWrite for {{audience}} readers.\n")));
		Set<Long> seeds = new HashSet<>();
		for (TestCase testCase : parsed.testCases())
		{
			TestValue.Generated before = (TestValue.Generated) testCase.values().get("before");
			requireThat(before.version(), "version").isEqualTo(1);
			requireThat(before.parameters(), "parameters").isEqualTo(Map.of("count", 3L));
			seeds.add(before.seed());
		}
		requireThat(seeds.size(), "distinctSeeds").isEqualTo(parsed.testCases().size());
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);

		ObjectNode stored = (ObjectNode) json.readTree(schema().toJson(contract));
		for (JsonNode value : stored.get("tests").get(0).get("test_cases").get(0).get("values"))
		{
			if (value.get("variable").asString().equals("before"))
				((ObjectNode) value.get("call")).remove("seed");
		}
		InvalidProposalException e = rejection(json.writeValueAsString(stored));
		requireThat(String.join(" | ", e.problems()), "problems").contains("seed");
	}

	/**
	 * Verifies that the stored contract keeps a recorded workspace file that is a link as a link, and reads it back.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void storesRecordedLinks() throws InvalidProposalException
	{
		ObjectNode proposal = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		proposal.remove(List.of("salt", "trial_profiles"));
		for (JsonNode testNode : proposal.get("tests"))
			((ObjectNode) testNode).remove("committed_test_cases");
		WorkspaceFile glossary = new WorkspaceFile("docs/glossary.md", "A glossary.\n",
			".cat/tests/CLAUDE/formatting/workspace/docs/glossary.md");
		WorkspaceFile link = new WorkspaceFile("glossary.md", "docs/glossary.md",
			".cat/tests/CLAUDE/formatting/workspace/glossary.md", true);
		Map<String, List<WorkspaceFile>> recordedByTest = Map.of("formatting", List.of(glossary, link));

		Contract contract = schema().parse(json.writeValueAsString(proposal), ContractFixtures::profiles,
			ContractFixtures.SALT, new SplittableRandom(3), Map.of(), recordedByTest);

		requireThat(contract.tests().getFirst().workspaceFiles(), "workspaceFiles").isEqualTo(List.of(glossary, link));
		JsonNode stored = json.readTree(schema().toJson(contract));
		requireThat(stored.get("tests").get(0).get("recorded_workspace_files").get(1), "link").isEqualTo(
			json.createObjectNode().put("path", link.path()).put("content", link.content()).
				put("source", link.source()).put("link", true));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
	}

	/**
	 * Verifies that CAT adds to a prompt test the workspace files of the recorded scenarios that it cites, before the
	 * workspace files that the contract author wrote, that the stored contract keeps each with the file that CAT copied
	 * it from, and that the contract author can neither write them nor write another workspace file at one's path.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void addsRecordedWorkspaceFiles() throws InvalidProposalException
	{
		ObjectNode proposal = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		proposal.remove(List.of("salt", "trial_profiles"));
		for (JsonNode testNode : proposal.get("tests"))
			((ObjectNode) testNode).remove("committed_test_cases");
		WorkspaceFile written = new WorkspaceFile("notes/{{tone}}.md", "Write for {{audience}} readers.\n");
		((ObjectNode) proposal.get("tests").get(0)).withArray("workspace_files").addObject().put("path", written.path()).
			put("content", written.content());
		WorkspaceFile recorded = new WorkspaceFile("docs/glossary.md", "A glossary.\n",
			"plugin/src/test/resources/fixtures/docs/glossary.md");
		Map<String, List<WorkspaceFile>> recordedByTest = Map.of("formatting", List.of(recorded));

		Contract contract = schema().parse(json.writeValueAsString(proposal), ContractFixtures::profiles,
			ContractFixtures.SALT, new SplittableRandom(3), Map.of(), recordedByTest);

		requireThat(contract.tests().getFirst().workspaceFiles(), "workspaceFiles").isEqualTo(List.of(recorded, written));
		requireThat(contract.tests().get(1).workspaceFiles(), "plain_list.workspaceFiles").isEmpty();
		JsonNode stored = json.readTree(schema().toJson(contract));
		JsonNode storedTest = stored.get("tests").get(0);
		requireThat(storedTest.get("recorded_workspace_files"), "recorded_workspace_files").isEqualTo(
			json.createArrayNode().add(json.createObjectNode().put("path", recorded.path()).
				put("content", recorded.content()).put("source", recorded.source())));
		requireThat(storedTest.get("workspace_files").size(), "workspaceFiles.size()").isEqualTo(1);
		requireThat(stored.get("tests").get(1).has("recorded_workspace_files"), "plain_list.hasRecordedWorkspaceFiles").
			isFalse();
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);

		ObjectNode copied = proposal.deepCopy();
		((ObjectNode) copied.get("tests").get(0)).set("recorded_workspace_files",
			storedTest.get("recorded_workspace_files"));
		requireThat(String.join(" | ", derivationRejection(copied, recordedByTest).problems()), "copied").
			contains("recorded_workspace_files");

		ObjectNode colliding = proposal.deepCopy();
		((ObjectNode) colliding.get("tests").get(0)).withArray("workspace_files").addObject().put("path", recorded.path()).
			put("content", "Another glossary.\n");
		requireThat(String.join(" | ", derivationRejection(colliding, recordedByTest).problems()), "colliding").
			contains("$.tests[0].workspace_files[1]").contains(recorded.source());
	}

	/**
	 * Parses contract author output that must be rejected.
	 *
	 * @param output   the contract author's output
	 * @param recorded the workspace files of the cited recorded scenarios, by prompt test identifier
	 * @return the rejection
	 * @throws AssertionError if the output is accepted
	 */
	private InvalidProposalException derivationRejection(ObjectNode output, Map<String, List<WorkspaceFile>> recorded)
	{
		try
		{
			schema().parse(json.writeValueAsString(output), ContractFixtures::profiles, ContractFixtures.SALT,
				new SplittableRandom(3), Map.of(), recorded);
		}
		catch (InvalidProposalException e)
		{
			return e;
		}
		throw new AssertionError("Output was accepted");
	}

	/**
	 * Verifies that reading and writing a proposal preserves every cited scenario, in its original order.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void preservesScenarioCitations() throws InvalidProposalException
	{
		ArrayNode citations = json.createArrayNode().add(".cat/tests/rule/reported.md").
			add(".cat/tests/rule/analogous.md");
		Contract contract = schema().parse(changed(
			new String[]{"tests", "0", "regression_scenarios"}, citations));

		JsonNode written = json.readTree(schema().toJson(contract));
		requireThat(written.get("tests").get(0).get("regression_scenarios"),
			"regressionScenarios").isEqualTo(citations);
	}

	/**
	 * Verifies that a proposal rejects a blank citation and a citation list represented by a string.
	 */
	@Test
	public void rejectsInvalidScenarioCitations()
	{
		String[] location = {"tests", "0", "regression_scenarios"};
		InvalidProposalException blank = rejection(changed(location, json.createArrayNode().add(" ")));
		InvalidProposalException scalar = rejection(text(location, ".cat/tests/rule/reported.md"));

		requireThat(String.join(" | ", blank.problems()), "blank").contains("regression_scenarios[0]");
		requireThat(String.join(" | ", scalar.problems()), "scalar").contains("regression_scenarios");
	}

	/**
	 * Verifies that the schema text names every top-level item that a proposal lists, so the contract author's prompt
	 * can state its output schema.
	 */
	@Test
	public void schemaIsJsonObjectForContractAuthor()
	{
		// Requirement: R-C1
		JsonNode schema = json.readTree(schema().schemaText());

		requireThat(schema.get("type").asString(), "type").isEqualTo("object");
		requireThat(schema.get("additionalProperties").asBoolean(), "additionalProperties").isFalse();
		Set<String> required = new HashSet<>();
		for (JsonNode name : schema.get("required"))
			required.add(name.asString());
		requireThat(required, "required").isEqualTo(
			Set.of("requirements", "tests", "trial_profiles", "judge", "limitations",
				"minimum_pass_rate", "salt"));
	}

	/**
	 * Returns the locations of items that a proposal must list, one per item.
	 *
	 * @return a description and the path whose removal must be rejected
	 */
	@DataProvider(name = "requiredItems")
	public Object[][] requiredItems()
	{
		return new Object[][]
			{
				{"requirements", new String[]{"requirements"}},
				{"requirement id", new String[]{"requirements", "0", "id"}},
				{"requirement description", new String[]{"requirements", "0", "description"}},
				{"requirement source", new String[]{"requirements", "0", "source"}},
				{"tests", new String[]{"tests"}},
				{"test id", new String[]{"tests", "0", "id"}},
				{"test risk rank", new String[]{"tests", "0", "risk_rank"}},
				{"test requirements", new String[]{"tests", "0", "requirement_ids"}},
				{"test applicability", new String[]{"tests", "0", "applicability"}},
				{"test turns", new String[]{"tests", "0", "turns"}},
				{"test time limit", new String[]{"tests", "0", "time_limit_seconds"}},
				{"test expected behavior", new String[]{"tests", "0", "expected_behavior"}},
				{"test uncovered interactions", new String[]{"tests", "0", "uncovered_interactions"}},
				{"test variables", new String[]{"tests", "0", "variables"}},
				{"test assertions", new String[]{"tests", "0", "assertions"}},
				{"assertion id", new String[]{"tests", "0", "assertions", "0", "id"}},
				{"assertion kind", new String[]{"tests", "0", "assertions", "0", "kind"}},
				{"assertion tool", new String[]{"tests", "0", "assertions", "0", "tool"}},
				{"assertion statement", new String[]{"tests", "0", "assertions", "0", "statement"}},
				{"assertion requirement", new String[]{"tests", "0", "assertions", "0",
					"requirement_id"}},
				{"test regression scenarios", new String[]{"tests", "0", "regression_scenarios"}},
				{"test committed test cases", new String[]{"tests", "0", "committed_test_cases"}},
				{"test test cases", new String[]{"tests", "0", "test_cases"}},
				{"variable name", new String[]{"tests", "0", "variables", "0", "name"}},
				{"variable description", new String[]{"tests", "0", "variables", "0", "description"}},
				{"test case values", new String[]{"tests", "0", "test_cases", "0", "values"}},
				{"test case value variable", new String[]{"tests", "0", "test_cases", "0", "values",
					"0", "variable"}},
				{"test case value text", new String[]{"tests", "0", "test_cases", "0", "values", "0",
					"text"}},
				{"test case expected outcome", new String[]{"tests", "0", "test_cases", "0",
					"expected_outcome"}},
				{"judge", new String[]{"judge"}},
				{"limitations", new String[]{"limitations"}},
				{"salt", new String[]{"salt"}}
			};
	}

	/**
	 * Verifies that a proposal that omits any item that a proposal must list is rejected and that the problem names
	 * the omitted location.
	 *
	 * @param description the omitted item
	 * @param path        the location of the omitted item
	 */
	@Test(dataProvider = "requiredItems")
	public void rejectsOmittedItem(String description, String[] path)
	{
		// Requirement: R-C1
		InvalidProposalException e = rejection(changed(path, null));

		String missing = path[path.length - 1];
		requireThat(String.join(" | ", e.problems()), "problems").contains(missing);
	}

	/**
	 * Verifies that each requirement states its trigger, or {@code null} when it always applies, that each test lists
	 * the triggers it leaves absent, and that a blank or non-text trigger is rejected.
	 */
	@Test
	public void requiresTriggerAndAbsentTriggers()
	{
		// Requirement: R-D17
		String[] trigger = {"requirements", "0", "trigger"};
		String[] absent = {"tests", "0", "absent_triggers"};

		requireThat(rejection(changed(trigger, null)).problems(), "missingTrigger").
			contains("$.requirements[0].trigger: required property is missing");
		requireThat(rejection(changed(absent, null)).problems(), "missingAbsentTriggers").
			contains("$.tests[0].absent_triggers: required property is missing");
		InvalidProposalException blankTrigger = rejection(text(trigger, " "));
		requireThat(blankTrigger.problems(), "blankTrigger").
			contains("$.requirements[0].trigger: must not be blank");
		requireThat(String.join(" | ", rejection(integer(trigger, 1)).problems()), "numericTrigger").
			contains("$.requirements[0].trigger");
		requireThat(String.join(" | ", rejection(changed(absent, json.createArrayNode().add(" "))).problems()),
			"blankAbsentTrigger").contains("$.tests[0].absent_triggers[0]");
	}

	/**
	 * Verifies that each requirement lists its failure behaviors as lowercase words joined by underscores, and that a
	 * stored contract keeps them.
	 *
	 * @throws InvalidProposalException if the contract is rejected
	 */
	@Test
	public void requiresFailureBehaviors() throws InvalidProposalException
	{
		// Requirement: R-C18
		String[] behaviors = {"requirements", "0", "behaviors"};

		requireThat(rejection(changed(behaviors, null)).problems(), "missingBehaviors").
			contains("$.requirements[0].behaviors: required property is missing");
		requireThat(String.join(" | ", rejection(changed(behaviors, json.createArrayNode())).problems()),
			"emptyBehaviors").contains("$.requirements[0].behaviors");
		requireThat(String.join(" | ", rejection(changed(behaviors, json.createArrayNode().add("Wrong tone"))).
			problems()), "spacedBehavior").contains("$.requirements[0].behaviors[0]");
		requireThat(String.join(" | ", rejection(changed(behaviors, json.createArrayNode().add("other"))).
			problems()), "otherBehavior").contains("'other'");

		Contract contract = ContractFixtures.contract();
		requireThat(contract.requirements().getFirst().behaviors(), "behaviors").isEqualTo(List.of("wrong_tone"));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
	}

	/**
	 * Verifies that a proposal spells each identifier that the contract author names, which are the requirement,
	 * behavior, test, test and variable identifiers, as lowercase words joined by underscores.
	 */
	@Test
	public void requiresSnakeCaseIdentifiers()
	{
		String[][] locations = {
			{"requirements", "0", "id"},
			{"requirements", "0", "behaviors", "0"},
			{"tests", "0", "id"},
			{"tests", "0", "id"},
			{"tests", "0", "requirement_ids", "0"},
			{"tests", "2", "variables", "0", "name"}};
		for (String[] location : locations)
		{
			StringBuilder path = new StringBuilder("$");
			for (String step : location)
			{
				if (step.chars().allMatch(Character::isDigit))
					path.append('[').append(step).append(']');
				else
					path.append('.').append(step);
			}
			String problems = String.join(" | ", rejection(changed(location,
				json.getNodeFactory().stringNode("kebab-case"))).problems());
			requireThat(problems, "problems").withContext(path, "path").contains(path + ":");
		}
	}

	/**
	 * Verifies that a stored contract keeps each requirement's trigger and each test's absent triggers.
	 *
	 * @throws InvalidProposalException if the contract is rejected
	 */
	@Test
	public void triggersRoundTripThroughContract() throws InvalidProposalException
	{
		// Requirement: R-D17
		ObjectNode root = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		((ObjectNode) root.get("requirements").get(1)).put("trigger", "The user asks to delete a directory.");
		((ObjectNode) root.get("tests").get(1)).putArray("absent_triggers").add("r2");

		Contract contract = schema().parse(json.writeValueAsString(root));

		requireThat(contract.requirements().get(1).trigger(), "trigger").
			isEqualTo("The user asks to delete a directory.");
		requireThat(contract.findTest("plain_list").orElseThrow().absentTriggers(), "absentTriggers").
			isEqualTo(List.of("r2"));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
	}

	/**
	 * Verifies that an aggregate count cannot stand in for the items: a count in place of a list and a count beside
	 * a list are both rejected.
	 */
	@Test
	public void rejectsAggregateSummaries()
	{
		// Requirement: R-C1
		InvalidProposalException countInsteadOfList = rejection(number(new String[]{"requirements"}, 12));
		InvalidProposalException countBesideList = rejection(number(new String[]{"requirement_count"}, 2));
		InvalidProposalException countInsideTest = rejection(number(new String[]{"tests", "0", "test_count"}, 2));

		requireThat(String.join(" | ", countInsteadOfList.problems()), "countInsteadOfList").contains("$.requirements");
		requireThat(String.join(" | ", countBesideList.problems()), "countBesideList").
			contains("requirement_count");
		requireThat(String.join(" | ", countInsideTest.problems()), "countInsideTest").
			contains("$.tests[0].test_count");
	}

	/**
	 * Verifies that an empty list in place of the items is rejected, because an empty proposal lists nothing.
	 */
	@Test
	public void rejectsEmptyItemLists()
	{
		// Requirement: R-C1
		ArrayNode empty = json.createArrayNode();
		String[][] locations =
			{
				new String[]{"requirements"},
				new String[]{"tests"},
				new String[]{"tests", "0", "turns"},
				new String[]{"tests", "0", "requirement_ids"},
				new String[]{"tests", "0", "test_cases", "0", "values"}
			};
		for (String[] location : locations)
			rejection(changed(location, empty));
		String problems = String.join(" | ",
			rejection(changed(new String[]{"tests", "0", "test_cases", "0", "values"}, empty)).problems());
		requireThat(problems, "problems").contains("$.tests[0].test_cases[0]").contains("values");
	}

	/**
	 * Verifies that rejection reports two independent violations together, so the contract author can correct both.
	 */
	@Test
	public void reportsIndependentViolations()
	{
		// Requirement: R-C1
		String proposal = text(new String[]{"requirements", "0", "source"}, "");
		proposal = proposal.replaceFirst("\"risk_rank\":2", "\"risk_rank\":0");

		InvalidProposalException e = rejection(proposal);

		String problems = String.join(" | ", e.problems());
		requireThat(problems, "problems").contains("$.requirements[0].source");
		requireThat(problems, "problems").contains("$.tests[0].risk_rank");
		requireThat(e.problems().size(), "problems.size()").isEqualTo(2);
	}

	/**
	 * Verifies that text that is not a JSON object is rejected with a problem, not an unchecked exception.
	 */
	@Test
	public void rejectsNonObjectInput()
	{
		// Requirement: R-C1
		for (String text : new String[]
			{
				"", "not json", "[]", "\"proposal\"", "{\"requirements\":"
			})
			requireThat(rejection(text).problems().size(), "problems.size()").isGreaterThan(0);
	}

	/**
	 * Verifies that a test case that omits a variable's value is rejected at the test's location.
	 */
	@Test
	public void rejectsTestCaseThatOmitsAVariable()
	{
		// Requirement: R-C1
		String[] values = {"tests", "0", "test_cases", "0", "values"};
		ArrayNode toneOnly = json.createArrayNode();
		toneOnly.addObject().put("variable", "tone").put("text", "a formal");

		InvalidProposalException e = rejection(changed(values, toneOnly));

		requireThat(String.join(" | ", e.problems()), "problems").contains("$.tests[0]: ").
			contains("audience");
	}

	/**
	 * Verifies that a test that names a requirement the proposal does not list is rejected.
	 */
	@Test
	public void rejectsUnknownRequirementReference()
	{
		// Requirement: R-C1
		String[] ids = {"tests", "0", "requirement_ids"};
		ArrayNode unknown = json.createArrayNode().add("r99");

		InvalidProposalException e = rejection(changed(ids, unknown));

		requireThat(String.join(" | ", e.problems()), "problems").contains("r99");
	}

	/**
	 * Verifies that a stored contract keeps its salt, its test cases and its committed test cases, and that a test case
	 * without an expected outcome is rejected.
	 *
	 * @throws InvalidProposalException if the fixture proposal is rejected
	 */
	@Test
	public void testCasesAndSaltRoundTrip() throws InvalidProposalException
	{
		// Requirement: R-C16
		String[] committed = {"tests", "1", "committed_test_cases"};
		ArrayNode pencils = json.createArrayNode();
		ObjectNode pencil = pencils.addObject();
		pencil.putArray("values").addObject().put("variable", "kind").put("text", "pencils");
		pencil.put("expected_outcome", "three pencils");
		Contract contract = schema().parse(changed(committed, pencils));
		requireThat(contract.salt(), "salt").isEqualTo(ContractFixtures.SALT);
		PromptTest styled = contract.tests().getFirst();
		requireThat(styled.testCases(), "styled.testCases()").
			isEqualTo(ContractFixtures.contract().tests().getFirst().testCases());
		PromptTest list = contract.tests().get(1);
		requireThat(list.committedTestCases(), "list.committedTestCases()").
			isEqualTo(List.of(ContractFixtures.plainList("pencils")));
		JsonNode written = json.readTree(schema().toJson(contract));
		requireThat(written.get("salt").asLong(), "written.salt").isEqualTo(ContractFixtures.SALT);
		JsonNode tests = written.get("tests");
		requireThat(tests.get(1).get("committed_test_cases"), "written.committed_test_cases").isEqualTo(pencils);
		requireThat(schema().parse(json.writeValueAsString(written)), "reparsed").isEqualTo(contract);

		String[] outcome = {"tests", "0", "test_cases", "0", "expected_outcome"};
		InvalidProposalException e = rejection(changed(outcome, json.getNodeFactory().stringNode(" ")));
		requireThat(String.join(" | ", e.problems()), "problems").contains("expected_outcome");
	}

	/**
	 * Verifies that a turn placeholder that no variable defines is rejected, because the generator could not
	 * replace it.
	 */
	@Test
	public void rejectsUndefinedPlaceholder()
	{
		// Requirement: R-C1
		String[] turns = {"tests", "1", "turns"};
		ArrayNode placeholder = json.createArrayNode().add("List three {{things}}.");

		InvalidProposalException e = rejection(changed(turns, placeholder));

		requireThat(String.join(" | ", e.problems()), "problems").contains("things");
	}

	/**
	 * Verifies that a test that cannot be written as a regression scenario, because its turn opens a code fence that
	 * would hide the sections after it, is rejected at the test's location.
	 */
	@Test
	public void rejectsTestThatCannotBeWrittenAsScenario()
	{
		// Requirement: R-W11
		String[] turns = {"tests", "1", "turns"};
		ArrayNode fence = json.createArrayNode().add("List three {{kind}}.\n```\nKeep it short.");

		InvalidProposalException e = rejection(changed(turns, fence));

		requireThat(String.join(" | ", e.problems()), "problems").contains("$.tests[1]").contains("scenario.md");
	}

	/**
	 * Verifies that a time limit that is not a positive whole number of seconds and an unknown rank are rejected.
	 */
	@Test
	public void rejectsInvalidScalars()
	{
		// Requirement: R-C1
		String[] timeLimit = {"tests", "0", "time_limit_seconds"};
		rejection(integer(timeLimit, 0));
		rejection(integer(timeLimit, -5));
		rejection(number(timeLimit, 1.5));
		rejection(text(timeLimit, "300"));
		String[] rank = {"tests", "0", "risk_rank"};
		rejection(integer(rank, 0));
		rejection(integer(rank, -1));
		rejection(number(rank, 1.5));
	}

	/**
	 * Verifies that a proposal that lists ignored files is rejected, because a trial receives a file outside the prompt
	 * system only through a link in its prompt test's workspace.
	 */
	@Test
	public void rejectsIgnoredFiles()
	{
		// Requirement: R-C1
		ObjectNode ignoredFiles = json.createObjectNode();
		ignoredFiles.putArray("paths").add(".env.example");

		InvalidProposalException e = rejection(changed(new String[]{"ignored_files"}, ignoredFiles));

		requireThat(e.problems(), "problems").contains("$.ignored_files: unexpected property");
	}

	/**
	 * Verifies that duplicate identifiers are rejected.
	 */
	@Test
	public void rejectsDuplicateIdentifiers()
	{
		// Requirement: R-C1
		ObjectNode duplicated = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		ArrayNode requirements = (ArrayNode) duplicated.get("requirements");
		requirements.add(requirements.get(0).deepCopy());
		String problems = String.join(" | ", rejection(json.writeValueAsString(duplicated)).problems());
		requireThat(problems, "problems").contains("r1").contains("listed twice");
		rejection(text(new String[]{"tests", "1", "id"}, "formatting"));
		rejection(text(new String[]{"tests", "2", "id"}, "plain_list"));
	}

	/**
	 * Verifies that a judge profile requires a model, prompt, rubric and decision rule as non-blank text and a result
	 * schema as a JSON object, and rejects unexpected properties.
	 */
	@Test
	public void judgeProfileRequiresModelAndInstructions()
	{
		// Requirement: R-C5
		for (String field : new String[]
			{
				"model", "prompt", "rubric", "decision_rule", "result_schema"
			})
		{
			InvalidProposalException omitted = rejection(changed(new String[]{"judge", field}, null));
			requireThat(String.join(" | ", omitted.problems()), "problems").contains("$.judge");
		}
		for (String field : new String[]
			{
				"model", "prompt", "rubric", "decision_rule"
			})
			rejection(text(new String[]{"judge", field}, "  "));
		rejection(text(new String[]{"judge", "result_schema"}, "{}"));
		rejection(changed(new String[]{"judge", "unexpected"}, json.getNodeFactory().stringNode("x")));
	}

	/**
	 * Verifies that the parsed judge profile preserves its model, effort and judging instructions, and keeps the
	 * result schema as JSON text.
	 *
	 * @throws InvalidProposalException if the fixture proposal is rejected
	 */
	@Test
	public void judgeProfileCarriesFrozenFields() throws InvalidProposalException
	{
		// Requirement: R-E21
		// Requirement: R-C5
		JudgeProfile judge = schema().parse(ContractFixtures.resource("valid-proposal.json")).judge();

		requireThat(judge.profile(), "profile").isEqualTo(new TrialProfile("judge-model", "high"));
		requireThat(judge.prompt(), "prompt").isEqualTo(frozenJudge().prompt());
		requireThat(judge.rubric(), "rubric").isEqualTo("The response follows the requirement.");
		requireThat(judge.decisionRule(), "decisionRule").isEqualTo("Pass only if every rubric item holds.");
		requireThat(json.readTree(judge.resultSchema()).get("required").get(0).asString(), "required").
			isEqualTo("assertions");
	}

	/**
	 * Returns the expected judge profile with the exact prompt frozen in the proposal fixture.
	 *
	 * @return the frozen profile
	 */
	private JudgeProfile frozenJudge()
	{
		JudgeProfile judge = ContractFixtures.judge();
		String prompt = json.readTree(ContractFixtures.resource("valid-proposal.json")).path("judge").
			path("prompt").asString();
		return new JudgeProfile(judge.profile(), prompt, judge.rubric(), judge.decisionRule(), judge.resultSchema());
	}

	/**
	 * Verifies that a proposal's minimum pass rate round-trips through the stored contract, and that one outside 0.80
	 * to 0.99 or with more than two decimal places is rejected.
	 *
	 * @throws InvalidProposalException if the fixture proposal is rejected
	 */
	@Test
	public void minimumPassRateRoundTripsWithinRange() throws InvalidProposalException
	{
		// Requirement: R-S10
		String[] path = {"minimum_pass_rate"};
		Contract contract = schema().parse(number(path, 0.9));
		requireThat(contract.minimumPassRate(), "parsed").isEqualTo(new BigDecimal("0.9"));
		JsonNode written = json.readTree(schema().toJson(contract));
		requireThat(written.get("minimum_pass_rate").decimalValue(), "written").
			isEqualTo(new BigDecimal("0.9"));

		rejection(number(path, 0.79));
		rejection(number(path, 0.995));
		InvalidProposalException e = rejection(number(path, 0.955));
		requireThat(String.join(" | ", e.problems()), "problems").contains("minimumPassRate");
	}

	/**
	 * Verifies that a proposal that names the plugin that trials install is rejected, because the prompt test
	 * configuration lists the trial plugins and a contract author cannot change them.
	 */
	// Requirement: R-W12
	@Test
	public void rejectsPluginInProposal()
	{
		InvalidProposalException e = rejection(changed(new String[]{"trial_plugins"}, json.readTree("""
			[{"name": "cat", "mappings": []}]
			""")));
		requireThat(String.join(" | ", e.problems()), "problems").contains("trial_plugins");
		requireThat(schema().derivationSchema().get("properties").has("trial_plugins"), "derivationHasPlugins").
			isFalse();
	}

	/**
	 * Verifies that each kind of dependent survives parsing and writing and changes the contract's identity, and that a
	 * contract without dependents writes none.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	// Requirement: R-W10
	@Test
	public void dependentsRoundTrip() throws InvalidProposalException
	{
		Contract contract = schema().parse(changed(new String[]{"dependents"}, json.readTree("""
			[
			  {"location": "project", "plugin": null, "path": "repair.sh", "removes": "file", "text": null,
			   "event": null, "command": null, "serves": "Repairs the output."},
			  {"location": "project", "plugin": null, "path": "AGENTS.md", "removes": "span",
			   "text": "Read prompt.md first.\\n", "event": null, "command": null,
			   "serves": "Routes the agent to the prompt."},
			  {"location": "project", "plugin": null, "path": ".codex/hooks.json", "removes": "hook_registration",
			   "text": null, "event": "PostToolUse", "command": "repair.sh", "serves": "Runs the repair script."},
			  {"location": "plugin", "plugin": "cat", "path": "bin/read-part", "removes": "file", "text": null,
			   "event": null, "command": null, "serves": "Reads a part."}
			]
			""")));
		Contract without = schema().parse(ContractFixtures.resource("valid-proposal.json"));

		requireThat(contract.dependents(), "dependents").isEqualTo(List.of(
			new Dependent(Path.of("repair.sh"), new Dependent.WholeFile(),
				"Repairs the output."),
			new Dependent(Path.of("AGENTS.md"), new Dependent.Span("Read prompt.md first.\n"),
				"Routes the agent to the prompt."),
			new Dependent(Path.of(".codex/hooks.json"),
				new Dependent.HookRegistration("PostToolUse", "repair.sh"), "Runs the repair script."),
			new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.WholeFile(),
				"Reads a part.")));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
		requireThat(json.readTree(schema().toJson(without)).has("dependents"), "withoutDependents").isFalse();
		requireThat(ContractIdentity.of(schema(), contract), "identity").
			isNotEqualTo(ContractIdentity.of(schema(), without));
	}

	/**
	 * A packaged dependent can name one evaluator, even when both evaluators' builds have the same plugin and path.
	 *
	 * @throws InvalidProposalException if the fixture cannot be parsed
	 */
	@Test
	public void scopedPluginDependentsRoundTrip() throws InvalidProposalException
	{
		Contract contract = schema().parse(changed(new String[]{"dependents"}, json.readTree("""
			[
			  {"location": "plugin", "plugin": "cat", "evaluator": "codex", "path": "bin/read-part",
			   "removes": "file", "text": null, "event": null, "command": null, "serves": "Reads a Codex part."},
			  {"location": "plugin", "plugin": "cat", "evaluator": "claude", "path": "bin/read-part",
			   "removes": "file", "text": null, "event": null, "command": null, "serves": "Reads a Claude part."},
			  {"location": "project", "plugin": null, "path": "bin/read-other", "removes": "file",
			   "text": null, "event": null, "command": null, "serves": "Reads a shared part."}
			]
			""")));
		TrialPlugin plugin = new TrialPlugin("cat", "1".repeat(64), new TrialPlugin.Build(List.of(), Path.of("out")),
			List.of(new TrialPlugin.Mapping(Path.of("bin"), Path.of("bin"))), null);
		Dependent codex = contract.dependents().get(0);
		Dependent claude = contract.dependents().get(1);
		Dependent project = contract.dependents().get(2);
		Dependent mapped = project.inPlugin("cat", project.path());
		requireThat(codex.evaluator(), "codexScope").isEqualTo(Optional.of(Harness.CODEX));
		requireThat(claude.evaluator(), "claudeScope").isEqualTo(Optional.of(Harness.CLAUDE));
		requireThat(contract.pluginRemovals(plugin, Harness.CODEX), "codexRemovals").isEqualTo(List.of(codex, mapped));
		requireThat(contract.pluginRemovals(plugin, Harness.CLAUDE), "claudeRemovals").isEqualTo(List.of(claude, mapped));
		requireThat(contract.pluginRemovals(plugin), "unqualifiedRemovals").isEqualTo(List.of(mapped));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
	}

	/**
	 * Verifies that a span without its text and a hook registration without its command are rejected, because control
	 * trials could not tell what to remove.
	 */
	// Requirement: R-W10
	@Test
	public void rejectsIncompleteDependents()
	{
		InvalidProposalException span = rejection(changed(new String[]{"dependents"}, json.readTree("""
			[{"location": "project", "plugin": null, "path": "AGENTS.md", "removes": "span", "text": null,
			  "event": null, "command": null, "serves": "Routes the agent to the prompt."}]
			""")));
		InvalidProposalException hook = rejection(changed(new String[]{"dependents"}, json.readTree("""
			[{"location": "project", "plugin": null, "path": "hooks.json", "removes": "hook_registration",
			  "text": null, "event": "PostToolUse", "command": null, "serves": "Runs the repair script."}]
			""")));

		requireThat(String.join(" | ", span.problems()), "spanProblems").contains("gives text");
		requireThat(String.join(" | ", hook.problems()), "hookProblems").contains("gives command");
	}

	/**
	 * Verifies that the declared files survive parsing and writing, change the contract's identity, and are omitted
	 * from a contract that declares none.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void referencedFilesRoundTrip() throws InvalidProposalException
	{
		Contract contract = schema().parse(changed(new String[]{"referenced_files"}, json.readTree("""
			["docs/reply-rules.md", "docs/style/tone.md"]
			""")));
		Contract without = schema().parse(ContractFixtures.resource("valid-proposal.json"));

		requireThat(contract.referencedFiles(), "referencedFiles").
			isEqualTo(List.of(Path.of("docs/reply-rules.md"), Path.of("docs/style/tone.md")));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
		requireThat(json.readTree(schema().toJson(without)).has("referenced_files"), "withoutDeclared").isFalse();
		requireThat(ContractIdentity.of(schema(), contract), "identity").
			isNotEqualTo(ContractIdentity.of(schema(), without));
	}

	/**
	 * Verifies that a declared file that is absolute, leaves the project, is not normalized, is blank or is listed
	 * twice is rejected.
	 */
	@Test
	public void rejectsInvalidReferencedFiles()
	{
		Map<String, String> problemByFiles = Map.of(
			"[\"/etc/hosts\"]", "A referenced file must be a normalized path relative to the project",
			"[\"../outside.md\"]", "A referenced file must be a normalized path relative to the project",
			"[\"docs/../../outside.md\"]", "A referenced file must be a normalized path relative to the project",
			"[\"docs/./rules.md\"]", "A referenced file must be a normalized path relative to the project",
			"[\"rules.md\", \"rules.md\"]", "is listed twice",
			"[\" \"]", "referenced_files");
		for (Map.Entry<String, String> entry : problemByFiles.entrySet())
		{
			InvalidProposalException e = rejection(changed(new String[]{"referenced_files"},
				json.readTree(entry.getKey())));

			requireThat(String.join(" | ", e.problems()), "problems").contains(entry.getValue());
		}
	}

	/**
	 * Verifies that a proposal file that holds only the requirements, each prompt test's directory with the fields that
	 * belong to the contract, and the contract's other fields satisfies the proposal file's schema.
	 */
	// Requirement: R-C26
	@Test
	public void acceptsNarrowedProposalFile()
	{
		requireThat(schema().proposalFileViolations(proposalFile()), "violations").isEmpty();
	}

	/**
	 * Verifies that a proposal file that restates a prompt test's scenario, such as its turns or test cases, is refused
	 * at each restated key, because the prompt test lives in its directory in the draft.
	 */
	// Requirement: R-C26
	@Test
	public void refusesRestatedPromptTest()
	{
		ObjectNode file = proposalFile();
		ObjectNode test = (ObjectNode) file.get("tests").get(0);
		test.putArray("turns").add("Summarize the notes.");
		test.putArray("test_cases");

		requireThat(schema().proposalFileViolations(file), "violations").
			containsAll(List.of("$.tests[0].turns: unexpected property", "$.tests[0].test_cases: unexpected property"));
	}

	/**
	 * Verifies that a prompt test's directory in the draft directory and the {@code scenario.md} that it holds survive
	 * the stored contract, and that a prompt test without a draft directory writes none, so that a contract stored
	 * before draft directories keeps its identity.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void draftRoundTrips() throws InvalidProposalException
	{
		ObjectNode draft = json.createObjectNode();
		draft.put("directory", ".cat/tests/formatting/notes");
		draft.put("scenario", "# Formatting\n\nThe draft's scenario.\n");
		Contract contract = schema().parse(changed(new String[]{"tests", "0", "draft"}, draft));
		Contract without = schema().parse(ContractFixtures.resource("valid-proposal.json"));

		requireThat(json.readTree(schema().toJson(contract)).get("tests").get(0).get("draft"), "draft").
			isEqualTo(draft);
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
		requireThat(json.readTree(schema().toJson(without)).get("tests").get(0).has("draft"), "withoutDraft").
			isFalse();
	}

	/**
	 * Verifies that the links of a draft prompt test's workspace directory whose targets lie outside it survive the
	 * stored contract with their targets.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void draftKeepsCopiedLinks() throws InvalidProposalException
	{
		ObjectNode draft = json.createObjectNode();
		draft.put("directory", ".cat/tests/formatting/notes");
		draft.put("scenario", "# Formatting\n\nThe draft's scenario.\n");
		ArrayNode links = draft.putArray("copied_links");
		links.addObject().put("path", "AGENTS.md").put("target", "../../shared/AGENTS.md");
		links.addObject().put("path", "docs/guide").put("target", "../../../../docs/guide");
		Contract contract = schema().parse(changed(new String[]{"tests", "0", "draft"}, draft));

		requireThat(contract.tests().getFirst().draft().orElseThrow().copiedLinks(), "copiedLinks").isEqualTo(Map.of(
			"AGENTS.md", "../../shared/AGENTS.md",
			"docs/guide", "../../../../docs/guide"));
		requireThat(json.readTree(schema().toJson(contract)).get("tests").get(0).get("draft"), "draft").
			isEqualTo(draft);
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
	}

	/**
	 * Returns a valid proposal file: the valid proposal without the fields that CAT computes, with each prompt test
	 * narrowed to its directory and the fields that belong to the contract.
	 *
	 * @return the proposal file
	 */
	private ObjectNode proposalFile()
	{
		ObjectNode root = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		root.remove(List.of("trial_profiles", "salt"));
		ArrayNode tests = json.createArrayNode();
		for (JsonNode full : root.get("tests"))
		{
			ObjectNode test = tests.addObject();
			test.put("directory", ".cat/tests/summary/" + full.get("id").asString());
			for (String name : List.of("risk_rank", "requirement_ids", "absent_triggers", "applicability",
				"uncovered_interactions"))
			{
				test.set(name, full.get(name));
			}
			ArrayNode assertions = test.putArray("assertions");
			for (JsonNode assertion : full.get("assertions"))
			{
				assertions.addObject().put("id", assertion.get("id").asString()).
					put("requirement_id", assertion.get("requirement_id").asString());
			}
		}
		root.set("tests", tests);
		return root;
	}
}
