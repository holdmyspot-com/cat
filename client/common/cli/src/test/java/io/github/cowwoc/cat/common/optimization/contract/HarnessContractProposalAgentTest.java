package io.github.cowwoc.cat.common.optimization.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
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
 * Tests one isolated harness-backed contract-proposal actor.
 */
public final class HarnessContractProposalAgentTest
{
	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public HarnessContractProposalAgentTest()
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
	 * Verifies the actor reads the draft as isolated evidence and returns one strict typed proposal.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void returnsStrictProposalFromReadOnlyDraft() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-proposal-agent-"));
			CertificationContract.Draft complete = CertificationContractTest.validDraft(SHA, false);
			CertificationContract.Draft inventory = inventoryWithMarker(complete, "private-marker-9821");
			List<String> trustedPrompts = new ArrayList<>();
			Path[] inputRoot = new Path[1];
			HarnessContractProposalAgent.RequestFactory factory = (profile, prompt, input, run) ->
			{
				trustedPrompts.add(prompt);
				inputRoot[0] = input;
				return request(profile, prompt, input, run);
			};
			HarnessEvaluationRunner runner = request -> evaluatorExecution(request,
				scope.json().writeValueAsString(complete));
			HarnessContractProposalAgent agent = new HarnessContractProposalAgent(scope.json(),
				HarnessContractProposalAgent.ActorRole.PRIMARY_REQUIREMENT_EXTRACTOR,
				new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM), root.resolve("operation"), factory,
				runner);

			CertificationContract.Draft result = agent.propose(inventory);

			requireThat(result, "result").isEqualTo(complete);
			requireThat(trustedPrompts, "trustedPrompts").size().isEqualTo(1);
			requireThat(trustedPrompts.getFirst(), "trustedPrompt").doesNotContain("private-marker-9821");
			requireThat(CertificationContractDocument.readDraft(scope.json(), inputRoot[0].resolve("draft.json")),
				"retainedInput").isEqualTo(inventory);
		}
	}

	/**
	 * Verifies unknown model-output fields are rejected at the strict draft boundary.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Invalid certification contract JSON.*unknown.*")
	public void rejectsProposalFormatDrift() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-proposal-agent-"));
			CertificationContract.Draft complete = CertificationContractTest.validDraft(SHA, false);
			String invalid = scope.json().writeValueAsString(complete).replaceFirst("\\{", "{\"unknown\":true,");
			HarnessContractProposalAgent agent = new HarnessContractProposalAgent(scope.json(),
				HarnessContractProposalAgent.ActorRole.PRIMARY_REQUIREMENT_EXTRACTOR,
				new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM), root.resolve("operation"),
				this::request, request -> evaluatorExecution(request, invalid));

			agent.propose(complete);
		}
	}

	/** Verifies unsupported optimizer profiles are rejected at construction. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported OPTIMIZER_EXECUTOR model profile.*")
	public void rejectsUnsupportedOptimizerProfile()
	{
		new HarnessContractProposalAgent(scope.json(), HarnessContractProposalAgent.ActorRole.PRIMARY_REQUIREMENT_EXTRACTOR,
			new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH), Path.of("operation"), this::request,
			request -> null);
	}

	/**
	 * Verifies the request factory cannot replace the actor's trusted instructions.
	 *
	 * @throws IOException for the expected request-binding rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*changed the trusted prompt input.*")
	public void rejectsChangedActorInput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("contract-proposal-input-"));
			CertificationContract.Draft draft = CertificationContractTest.validDraft(SHA, false);
			HarnessContractProposalAgent agent = new HarnessContractProposalAgent(scope.json(),
				HarnessContractProposalAgent.ActorRole.PRIMARY_REQUIREMENT_EXTRACTOR,
				new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM), root.resolve("operation"),
				(profile, prompt, input, run) -> request(profile, "Different task", input, run),
				request -> evaluatorExecution(request, scope.json().writeValueAsString(draft)));

			agent.propose(draft);
		}
	}

	/**
	 * Creates an inventory whose source contains a marker that must stay out of the trusted prompt.
	 *
	 * @param complete the complete fixture
	 * @param marker the private source marker
	 * @return the inventory
	 */
	private static CertificationContract.Draft inventoryWithMarker(CertificationContract.Draft complete, String marker)
	{
		CertificationContract.SourceSpan span = complete.sourceSpans().getFirst();
		CertificationContract.SourceSpan marked = new CertificationContract.SourceSpan(span.identifier(),
			span.artifactIdentifier(), span.startPosition(), span.endPosition(), marker);
		return new CertificationContract.Draft(complete.schemaVersion(), complete.artifacts(), List.of(marked),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), complete.promptTests());
	}

	/**
	 * Creates one actor harness request.
	 *
	 * @param profile the frozen actor profile
	 * @param prompt the trusted actor prompt
	 * @param input the read-only input root
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
	 * Creates one retained harness response.
	 *
	 * @param request the actor request
	 * @param response the actor response
	 * @return the retained execution
	 * @throws IOException if fixture I/O fails
	 */
	private HarnessEvaluationExecution evaluatorExecution(HarnessEvaluationRequest request, String response)
		throws IOException
	{
		ObjectNode event = scope.json().createObjectNode();
		event.put("type", "turn-completed");
		event.put("runId", "contract-actor");
		event.put("exitCode", 0);
		event.put("error", "");
		event.put("assistant_response", response);
		Files.createDirectories(request.transcript().getParent());
		Files.writeString(request.transcript(), scope.json().writeValueAsString(event) + "\n");
		return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(request.project(), request.transcript()),
			1, 2);
	}
}
