package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.CandidateState;

import static io.github.cowwoc.cat.common.PromptEvaluationCandidateFixtures.staticFormalFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFailingFormalBuildPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFailingFormalRunner;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeLifecycleSoakPrecondition;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.Test;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests durable candidate-state updates after formal evaluation.
 */
public final class PromptEvaluationCandidateStateTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationCandidateStateTest()
	{
	}

	/**
	 * Verifies a failed formal certification adds its failed assertion to the promoted candidate's ledger.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsFormalFailureBeforeLaterPromotion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticFormalFailure(requests, request);
			ObjectNode check = PromptEvaluation.regressionCheck(temporary, candidateState, suite, prompt, "fixture", context,
				runner, buildPlan, temporary.resolve("check"), 1, evaluationRunner);
			requireThat(check.path("regression_check").path("status").asString(), "regressionCheckStatus").
				isEqualTo("passed");
			requireThat(check.path("promotion").toString(), "promotion").contains("check_run");
			writeLifecycleSoakPrecondition(temporary, buildPlan, runner);

			ObjectNode result = PromptEvaluation.certifyPromoted(temporary, candidateState, suite, prompt, "fixture", context,
				runner, buildPlan, temporary.resolve("formal"), 1, evaluationRunner);

			requireThat(result.path("decision").path("overall_decision").asString(), "formalDecision").isEqualTo("FAIL");
			String state = Files.readString(candidateState);
			requireThat(state, "candidateState").contains("\"formal_failures\"").
				contains("\"last_observed_formal_run\"").contains("\"case_id\" : \"requested-change\"").
				contains("\"assembled_prompt_route_sha256\"").contains("\"lineage\"").
				contains("\"last_observed_formal_run\"");
			ObjectNode retry = PromptEvaluation.advance(temporary, candidateState, suite, prompt, "fixture", context,
				runner, buildPlan, temporary.resolve("retry"), 1, evaluationRunner);
			requireThat(retry.path("transition").asString(), "retryTransition").isEqualTo("regression-check");
			requireThat(retry.path("result").path("regression_check").path("status").asString(), "retryStatus").
				isEqualTo("failed");
		}
	}

	/**
	 * Verifies the controller repeats the required regression check when a promoted check's execution conditions drift
	 * before certification can begin.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rechecksAfterPromotionInputDrift() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode first = PromptEvaluation.advance(temporary, candidateState, suite, prompt, "fixture", context,
				runner, buildPlan, temporary.resolve("check"), 1, evaluationRunner);
			requireThat(first.path("transition").asString(), "checkTransition").isEqualTo("regression-check");
			ObjectNode promotedState = (ObjectNode) scope.json().readTree(candidateState.toFile());
			requireThat(promotedState.has("current_promotion"), "currentPromotion").isTrue();
			Path executionPlan = Path.of(first.path("result").path("execution_plan").asString());
			ObjectNode plan = (ObjectNode) scope.json().readTree(executionPlan.toFile());
			Path sandboxPolicy = Path.of(plan.path("identity").path("sandbox_policy").asString());
			Files.writeString(sandboxPolicy, "changed policy\n");

			ObjectNode retry = PromptEvaluation.advance(temporary, candidateState, suite, prompt, "fixture", context,
				runner, buildPlan, temporary.resolve("retry"), 1, evaluationRunner);

			requireThat(retry.path("transition").asString(), "retryTransition").isEqualTo("regression-check");
			ObjectNode state = (ObjectNode) scope.json().readTree(candidateState.toFile());
			requireThat(state.path("invalidated_promotions").size(), "invalidationCount").isEqualTo(1);
			requireThat(state.path("invalidated_promotions").get(0).path("reason").asString(), "invalidationReason").
				contains("Execution conditions changed after the evaluation began");
		}
	}

	/**
	 * Verifies only a matching terminal PASS certification clears previously observed failure obligations.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void clearsSignaturesAfterCertification() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path candidateState = root.resolve("candidate-state.json");
			Path formalRun = write(root, "formal.json", """
				{
				  "suite_sha256":"suite-hash",
				  "evaluation_epoch_sha256":"epoch-hash",
				  "assembled_prompt_route_sha256":"route-hash",
				  "candidate_identity_sha256":"candidate-hash",
				  "decision":{"overall_decision":"PASS"}
				}
				""");
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode state = scope.json().createObjectNode();
				state.put("suite_sha256", "suite-hash");
				state.put("evaluation_epoch_sha256", "epoch-hash");
				ObjectNode promotion = state.putObject("current_promotion");
				promotion.put("candidate_identity_sha256", "candidate-hash");
				promotion.put("evaluation_epoch_sha256", "epoch-hash");
				ObjectNode signature = state.withArray("failure_signatures").addObject();
				signature.put("key", "requested-change/result/fixture");
				signature.putObject("latest_case").put("case_id", "requested-change").put("assertion", "result").
					put("boundary", "fixture");
				ObjectNode obligation = state.putObject("lineage").putArray("obligations").addObject();
				obligation.put("key", "requested-change/result/fixture");
				obligation.put("status", "covered");
				obligation.putObject("case").put("case_id", "requested-change").put("assertion", "result").
					put("boundary", "fixture");
				Files.writeString(candidateState, scope.json().writeValueAsString(state));
			}

			ObjectNode result = CandidateState.clearFailureSignaturesAfterCertification(formalRun,
				candidateState);

			requireThat(result.path("failure_signatures").get(0).path("cleared_by_certification").path("run").asString(),
				"clearanceRun").isEqualTo(formalRun.toString());
			requireThat(result.path("lineage").path("obligations").get(0).path("status").asString(),
				"lineageStatus").isEqualTo("cleared");
			requireThat(Files.readString(candidateState), "candidateState").contains("\"cleared_by_certification\"").
				contains("\"candidate_identity_sha256\"");
		}
	}
}
