package com.holdmyspot.cat.common.optimizer.execution.prompt.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorApproach;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCases;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief.EndedAttempt;
import com.holdmyspot.cat.common.optimizer.execution.prompt.PromptAuthorPrompt;
import com.holdmyspot.cat.common.optimizer.execution.prompt.ValuesPrompt;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests what the sessions that shape a candidate read of a confirmed contract whose test cases carry boundary classes
 * and of a formal failure on a held-out test case.
 */
public final class DevelopmentContractTest
{
	private static final String CLASS = "arrives_exactly_at_midnight";
	private static final String EXAMPLE_REPLY = "[[\"A private example reply.\"]]";
	private static final Path DIRECTORY = Path.of("/results/values").toAbsolutePath();
	private static final Path COPY = Path.of("/results/prompt-author-3/project").toAbsolutePath();
	private final JsonMapper json = JsonMapper.builder().build();
	private final ProposalSchema schema = new ProposalSchema(json);

	/**
	 * Creates the test.
	 */
	public DevelopmentContractTest()
	{
	}

	/**
	 * Returns the fixture contract whose {@code safety} test has, besides its tagged deletion test cases, test cases of
	 * the class {@value #CLASS} that name a place, a departure and a duration.
	 *
	 * @return the contract
	 */
	private static Contract withArrivals()
	{
		Contract contract = ContractFixtures.contract();
		List<PromptTest> tests = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			if (!test.id().equals("safety"))
			{
				tests.add(test);
				continue;
			}
			List<TestCase> testCases = new ArrayList<>();
			for (TestCase testCase : test.testCases())
				testCases.add(testCase.withBoundaryClass("deletes_one_directory"));
			for (int i = 1; i <= 6; ++i)
			{
				testCases.add(ContractFixtures.testCase("River " + i + ". It arrives at midnight.", CLASS,
					Map.of("target", "Pine" + i + " departing at noon for 12 hours")));
			}
			tests.add(new PromptTest(test.id(), test.riskRank(), test.requirementIds(), test.absentTriggers(),
				test.applicability(), test.turns(), test.timeLimit(), test.expectedBehaviour(),
				test.uncoveredInteractions(), test.variables(), test.committedTestCases(), testCases, test.assertions(),
				test.regressionScenarios(), List.of(), Optional.of(new PromptTest.Draft(Path.of("safety"),
					"Scenario from the confirmed draft.", Map.of(),
					Map.of("asks_before_deleting/pass_" + CLASS + "/replies.json",
						new FileContent(EXAMPLE_REPLY.getBytes(StandardCharsets.UTF_8))), Set.of()))));
		}
		return new Contract(contract.requirements(), tests, ContractFixtures.profiles(contract.requirements(), tests),
			contract.judge(), contract.limitations(), contract.minimumPassRate(), contract.salt());
	}

	/**
	 * Verifies that after a formal failure on a held-out test case of a class, neither the contract author nor the
	 * prompt author reads any value, expected outcome or turn of a held-out test case, while both read the class.
	 */
	@Test
	public void sessionsReadClassWithoutValues()
	{
		// Requirement: R-L12
		// Requirement: R-L16
		Contract contract = withArrivals();
		PromptTest test = contract.findTest("safety").orElseThrow();
		List<TestCase> heldOut = contract.split().testCases(test, Part.HELD_OUT);
		requireThat(heldOut.stream().filter(testCase -> testCase.boundaryClass().equals(CLASS)).count(),
			"heldOutArrivals").isGreaterThan(0L);
		TestCase failedCase = heldOut.stream().filter(testCase -> testCase.boundaryClass().equals(CLASS)).
			findFirst().orElseThrow();
		DrawnTestCase drawn = new DrawnTestCase("safety", "safety", failedCase,
			List.of("Delete the " + failedCase.describedValues().get("target") + " directory."),
			"The assistant asks before deleting anything.", "generator-1", DrawStream.Kind.FORMAL, 3, false);
		Failure failure = new Failure(drawn, "The held-out rationale.",
			List.of(new FailedAssertion("asks_before_deleting", "deleted_without_asking")), null, List.of(),
			List.of(List.of("The held-out reply.")), Builder.FILES);
		FailureGroup group = FailureGroup.of(List.of(failure)).getFirst();

		SessionRequest valuesRequest = new ValuesPrompt(json, schema, DIRECTORY, new Random(11)).
			request(contract, AddedTestCases.NONE, 1, group);
		PromptAuthorBrief brief = new PromptAuthorBrief("safety", PromptAuthorApproach.CORRECT_INSTRUCTION, contract,
			List.of(), Map.of(), Map.of(), List.of(new EndedAttempt(1, Outcome.BELOW_MINIMUM, Map.of(),
			List.of(failure.summary()), List.of(), List.of())), Map.of(), "", List.of(), List.of(), List.of());
		SessionRequest authorRequest = new PromptAuthorPrompt(json, schema, Set.of(Path.of("CLAUDE.md")), Set.of()).
			request(brief, COPY);

		JsonNode failureGroup = json.readTree(section(valuesRequest.turns().getFirst(),
			"The failure group, as JSON", 2, "\n\n### How to write"));
		requireThat(failureGroup.get("boundary_class").asString(), "failureGroup.boundary_class").isEqualTo(CLASS);
		JsonNode ended = json.readTree(section(authorRequest.turns().getFirst(), "or cite them:", 1,
			"\n\nThe table of earlier"));
		requireThat(ended.get(0).get("failures").get(0).get("boundary_class").asString(), "failure.boundary_class").
			isEqualTo(CLASS);
		for (SessionRequest request : List.of(valuesRequest, authorRequest))
		{
			String turn = request.turns().getFirst();
			requireThat(turn, "turn").contains(CLASS);
			String exampleContent = Base64.getEncoder().encodeToString(EXAMPLE_REPLY.getBytes(StandardCharsets.UTF_8));
			requireThat(turn, "turn").doesNotContain("example_files").doesNotContain(exampleContent);
			for (TestCase testCase : heldOut)
			{
				requireThat(turn, "turn").doesNotContain(testCase.expectedOutcome());
				for (String value : testCase.describedValues().values())
					requireThat(turn, "turn").doesNotContain("\"" + value + "\"");
			}
			requireThat(turn, "turn").doesNotContain("The held-out rationale.").doesNotContain("The held-out reply.");
		}
	}

	/**
	 * Returns the JSON that a prompt gives after a heading.
	 *
	 * @param text the prompt
	 * @param heading the text that starts the section's introduction
	 * @param paragraphs the number of paragraph breaks between the heading and the JSON
	 * @param next the text that starts after the JSON
	 * @return the JSON
	 */
	private static String section(String text, String heading, int paragraphs, String next)
	{
		int start = text.indexOf(heading);
		for (int i = 0; i < paragraphs; ++i)
			start = text.indexOf("\n\n", start + 2);
		int end = text.indexOf(next, start);
		return text.substring(start, end).strip();
	}
}
