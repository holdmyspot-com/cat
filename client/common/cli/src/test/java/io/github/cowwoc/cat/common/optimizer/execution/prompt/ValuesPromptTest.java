package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.FailureSummary;
import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.AddedValues;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link ValuesPrompt}.
 */
public final class ValuesPromptTest
{
	private static final Path DIRECTORY = Path.of("/runs/values");
	private static final String TEST = "delete-directory";
	private static final List<FailureSummary> FAILURES = List.of(
		new FailureSummary("safety", TEST, List.of("r2")),
		new FailureSummary("safety", TEST, List.of("r2", "core-task")));
	private final JsonMapper json = JsonMapper.builder().build();
	private final ValuesPrompt values = new ValuesPrompt(json, new ProposalSchema(json), DIRECTORY);

	/**
	 * Creates the test.
	 */
	public ValuesPromptTest()
	{
	}

	/**
	 * Verifies that the deriver receives each failed test with only its development key values and its earlier added
	 * values, its requirements and each failure's cell and failed requirements, and never the salt or a held-out
	 * value.
	 */
	@Test
	public void requestOmitsHeldOutValuesAndSalt()
	{
		// Requirement: R-C17
		// Requirement: R-C16
		Contract contract = ContractFixtures.contract();
		AddedValues earlier = AddedValues.NONE.plus(List.of(new AddedCombination(1, TEST,
			List.of(new VariationValue("srv", "srv", 1.0)))));

		SessionRequest request = values.request(contract, earlier, FAILURES);

		requireThat(request.role(), "role").isEqualTo(SessionRole.DERIVER);
		requireThat(request.source(), "source").isEqualTo(DIRECTORY);
		requireThat(request.profile(), "profile").isEqualTo(contract.judge().profile());
		String turn = request.turns().getFirst();
		requireThat(turn, "turn").doesNotContain("{{failed_tests}}").doesNotContain("{{output_schema}}").
			doesNotContain("\"salt\"").doesNotContain(String.valueOf(ContractFixtures.SALT));
		for (String heldOut : List.of("dist", "vendor", "coverage", "venv"))
			requireThat(turn, "turn").doesNotContain("\"" + heldOut + "\"");
		JsonNode failed = json.readTree(section(turn, "Each failed test, as JSON", "\n\n### How to write"));
		requireThat(failed.size(), "failed.size()").isEqualTo(1);
		JsonNode entry = failed.get(0);
		requireThat(entry.get("category").asString(), "category").isEqualTo("safety");
		JsonNode test = entry.get("test");
		requireThat(test.get("id").asString(), "test.id").isEqualTo(TEST);
		List<String> targets = new ArrayList<>();
		for (JsonNode value : test.get("variations").get(0).get("values"))
			targets.add(value.get("label").asString());
		requireThat(targets, "targets").isEqualTo(List.of("logs", "cache", "tmp", "build"));
		ObjectNode srv = json.createObjectNode();
		srv.putObject("target").put("label", "srv").put("text", "srv");
		requireThat(entry.get("added_key_combinations"), "added").isEqualTo(json.createArrayNode().add(srv));
		List<String> requirements = new ArrayList<>();
		for (JsonNode requirement : entry.get("requirements"))
			requirements.add(requirement.get("id").asString());
		requireThat(requirements, "requirements").isEqualTo(List.of("r2", "core-task"));
		ArrayNode expectedFailures = json.createArrayNode();
		expectedFailures.addObject().put("cell", "safety").putArray("failed_requirements").add("r2");
		expectedFailures.addObject().put("cell", "safety").putArray("failed_requirements").add("r2").add("core-task");
		requireThat(entry.get("failures"), "failures").isEqualTo(expectedFailures);
	}

	/**
	 * Verifies that new combinations become development values of their test, each value's probability its share of
	 * the combinations added with it.
	 */
	@Test
	public void acceptsNewCombinations() throws InvalidProposalException
	{
		// Requirement: R-C17
		List<AddedCombination> combinations = values.combinations(completed(response("srv", "opt", "data", "home")),
			ContractFixtures.contract(), AddedValues.NONE, 2, FAILURES);

		requireThat(combinations.size(), "combinations.size()").isEqualTo(4);
		for (AddedCombination combination : combinations)
		{
			requireThat(combination.attempt(), "attempt").isEqualTo(2);
			requireThat(combination.testId(), "testId").isEqualTo(TEST);
			requireThat(combination.values().size(), "values.size()").isEqualTo(1);
			requireThat(combination.values().getFirst().probability(), "probability").isEqualTo(0.25);
		}
		requireThat(combinations.getFirst().values().getFirst(), "first").
			isEqualTo(new VariationValue("srv", "the srv text", 0.25));
	}

	/**
	 * Verifies that a value equal to a held-out value of its dimension is rejected without naming the part that holds
	 * the value.
	 */
	@Test
	public void rejectsValueEqualToHeldOutValue()
	{
		// Requirement: R-C17
		// Requirement: R-C16
		requireRejected(response("srv", "opt", "data", "vendor"), AddedValues.NONE, "equals an existing value");
	}

	/**
	 * Verifies that a value whose text equals the text of an existing value, ignoring case and surrounding white
	 * space, is rejected.
	 */
	@Test
	public void rejectsValueWithAnExistingText()
	{
		// Requirement: R-C17
		String response = response("srv", "opt", "data", "home").replace("the home text", " Cache ");
		requireRejected(response, AddedValues.NONE, "equals an existing value");
	}

	/**
	 * Verifies that a value equal to an earlier added value of its dimension is rejected.
	 */
	@Test
	public void rejectsValueEqualToAnAddedValue()
	{
		// Requirement: R-C17
		AddedValues earlier = AddedValues.NONE.plus(List.of(new AddedCombination(1, TEST,
			List.of(new VariationValue("srv", "srv", 1.0)))));
		requireRejected(response("srv", "opt", "data", "home"), earlier, "equals an existing value");
	}

	/**
	 * Verifies that fewer than 4 combinations for a failed test are rejected.
	 */
	@Test
	public void rejectsFewerThanFourCombinations()
	{
		// Requirement: R-C17
		requireRejected(response("srv", "opt", "data"), AddedValues.NONE, "at least 4");
	}

	/**
	 * Verifies that a response that omits a failed test, names another test or gives a value for a dimension that is
	 * not a key dimension is rejected.
	 */
	@Test
	public void rejectsResponseOutsideTheFailedTests()
	{
		// Requirement: R-C17
		requireRejected("{\"tests\": []}", AddedValues.NONE, "delete-directory");
		requireRejected(response("srv", "opt", "data", "home").replace(TEST, "plain-list"), AddedValues.NONE,
			"plain-list");
		requireRejected(response("srv", "opt", "data", "home").replace("\"target\"", "\"tone\""), AddedValues.NONE,
			"tone");
		requireRejected("Here are the values.", AddedValues.NONE, "not exactly one JSON object");
	}

	/**
	 * Requires a response to be rejected.
	 *
	 * @param response the deriver's final response
	 * @param earlier the values added before
	 * @param problem text that the rejection contains
	 */
	private void requireRejected(String response, AddedValues earlier, String problem)
	{
		try
		{
			values.combinations(completed(response), ContractFixtures.contract(), earlier, 2, FAILURES);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			String message = String.join("\n", e.problems());
			requireThat(message, "message").contains(problem).doesNotContain("held-out").
				doesNotContain("held out").doesNotContain("development part");
		}
	}

	/**
	 * Returns a response that adds one target per label to the {@code delete-directory} test.
	 *
	 * @param labels the labels of the targets
	 * @return the response
	 */
	private String response(String... labels)
	{
		ObjectNode root = json.createObjectNode();
		ObjectNode test = root.putArray("tests").addObject();
		test.put("id", TEST);
		ArrayNode combinations = test.putArray("combinations");
		for (String label : labels)
			combinations.addArray().addObject().put("dimension", "target").put("label", label).
				put("text", "the " + label + " text");
		return json.writeValueAsString(root);
	}

	/**
	 * Returns a completed deriver session whose final response is given.
	 *
	 * @param response the final response
	 * @return the result
	 */
	private static SessionResult.Completed completed(String response)
	{
		return new SessionResult.Completed(Path.of("/runs/values/transcript.jsonl"), List.of(List.of(response)),
			DIRECTORY, "deriver-model", null, null, List.of(), "none", List.of());
	}

	/**
	 * Returns the JSON that follows the paragraph that contains a heading.
	 *
	 * @param text the prompt
	 * @param heading text in the paragraph before the section
	 * @param next the text that starts after the section
	 * @return the section
	 */
	private static String section(String text, String heading, String next)
	{
		int start = text.indexOf("\n\n", text.indexOf(heading));
		start = text.indexOf("\n\n", start + 2);
		int end = text.indexOf(next, start);
		return text.substring(start, end).strip();
	}
}
