package io.github.cowwoc.cat.common.optimization.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the bounded harness-backed semantic disagreement resolver.
 */
public final class HarnessContractResolverAgentTest
{
	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public HarnessContractResolverAgentTest()
	{
	}

	/**
	 * Closes the shared JSON scope.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies the resolver returns exact typed decisions without copying customer content into trusted instructions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void returnsStrictResolverDecisions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-resolver-agent-"));
			CertificationContract.Draft draft = CertificationContractTest.validDraft(SHA, false);
			List<ContractExtractionReducer.Disagreement> disagreements = disagreements(draft, "private-marker-417");
			List<String> prompts = new ArrayList<>();
			HarnessContractResolverAgent agent = new HarnessContractResolverAgent(scope.json(),
				new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM), root.resolve("operation"),
				(profile, prompt, input, run) ->
				{
					prompts.add(prompt);
					return request(profile, prompt, input, run);
				}, request -> evaluatorExecution(request,
					"{\"decisions\":[{\"disagreementIdentifier\":\"disagreement-1\"," +
						"\"action\":\"UNRESOLVED\",\"retainedRequirement\":null," +
						"\"rationale\":\"The source remains ambiguous.\"}]}"));

			List<ContractExtractionReducer.ResolverDecision> result = agent.resolve(disagreements, draft, 1);

			requireThat(result, "result").size().isEqualTo(1);
			requireThat(result.getFirst().action(), "action").isEqualTo(
				ContractExtractionReducer.ResolverAction.UNRESOLVED);
			requireThat(prompts.getFirst(), "trustedPrompt").doesNotContain("private-marker-417");
		}
	}

	/**
	 * Verifies the request factory cannot replace the resolver's trusted instructions.
	 *
	 * @throws IOException for the expected request-binding rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*changed the trusted prompt input.*")
	public void rejectsChangedResolverInput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-resolver-input-"));
			CertificationContract.Draft draft = CertificationContractTest.validDraft(SHA, false);
			HarnessContractResolverAgent agent = new HarnessContractResolverAgent(scope.json(),
				new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM), root.resolve("operation"),
				(profile, prompt, input, run) -> request(profile, "Different task", input, run),
				request -> evaluatorExecution(request, "{\"decisions\":[]}"));

			agent.resolve(disagreements(draft, "marker"), draft, 1);
		}
	}

	/** Verifies unsupported optimizer profiles are rejected at construction. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported OPTIMIZER_EXECUTOR model profile.*")
	public void rejectsUnsupportedOptimizerProfile()
	{
		new HarnessContractResolverAgent(scope.json(), new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH),
			Path.of("operation"), this::request, request -> null);
	}

	/**
	 * Creates one named semantic disagreement.
	 *
	 * @param draft the complete fixture
	 * @param marker a private source marker
	 * @return the disagreement list
	 */
	private static List<ContractExtractionReducer.Disagreement> disagreements(CertificationContract.Draft draft,
		String marker)
	{
		CertificationContract.Requirement first = draft.requirements().getFirst();
		CertificationContract.Requirement second = new CertificationContract.Requirement("req-2", marker,
			first.sourceSpanIdentifiers(), first.applicabilityRuleIdentifiers(), List.of());
		return List.of(new ContractExtractionReducer.Disagreement("disagreement-1", List.of(first, second),
			first.sourceSpanIdentifiers(), ContractExtractionReducer.ConflictCategory.SEMANTIC_INTERPRETATION, 0));
	}

	/**
	 * Creates one resolver harness request.
	 *
	 * @param profile the resolver profile
	 * @param prompt trusted resolver instructions
	 * @param input the read-only resolver input
	 * @param run the durable run root
	 * @return the typed request
	 * @throws IOException if fixture directories cannot be created
	 */
	private HarnessEvaluationRequest request(HarnessModelProfile profile, String prompt, Path input, Path run)
		throws IOException
	{
		Path project = Files.createDirectories(run.resolve("project"));
		ObjectNode turn = scope.json().createObjectNode();
		turn.put("type", "turn");
		turn.put("prompt", prompt);
		String bridgeInput = scope.json().writeValueAsString(turn) + "\n{\"type\":\"complete\"}\n";
		return new HarnessEvaluationRequest(run.resolve("runner"), run.resolve("artifact"), project, profile,
			bridgeInput, run.resolve("transcript.jsonl"), run.resolve("receipt.json"), false, List.of(input));
	}

	/**
	 * Creates one retained resolver response.
	 *
	 * @param request the resolver request
	 * @param response the resolver response
	 * @return the retained execution
	 * @throws IOException if fixture I/O fails
	 */
	private HarnessEvaluationExecution evaluatorExecution(HarnessEvaluationRequest request, String response)
		throws IOException
	{
		ObjectNode event = scope.json().createObjectNode();
		event.put("type", "turn-completed");
		event.put("runId", "contract-resolver");
		event.put("exitCode", 0);
		event.put("error", "");
		event.put("assistant_response", response);
		Files.createDirectories(request.transcript().getParent());
		Files.writeString(request.transcript(), scope.json().writeValueAsString(event) + "\n");
		return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(request.project(), request.transcript()),
			1, 2);
	}
}
