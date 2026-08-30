package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.classifiedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.unresolvedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cowwoc.cat.common.evaluation.CapabilityDiagnostic;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link PromptEvaluation}'s capability-profile diagnostics.
 */
public final class PromptEvaluationCapabilityDiagnosticTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationCapabilityDiagnosticTest()
	{
	}

	/**
	 * Verifies a capability diagnostic changes only the executor profile and records three non-certifying trials.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsExecutorOnlyCapabilityDiagnostic() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path certification = writeSuite(root);
			Path unresolvedSourceRun = unresolvedFailure(root, certification, "requested-change");
			Path diagnostic = root.resolve("diagnostic.json");
			PromptEvaluation.run(new String[]{"create-direct-diagnostic", unresolvedSourceRun.toString(),
				"requested-change", "1", diagnostic.toString()});
			Path sourceRun = classifiedFailure(root.resolve("localized"), certification, "requested-change", "application");
			PromptEvaluation.run(new String[]{"bind-plan", sourceRun.toString(),
				writeStaticExecutionPlan(root.resolve("source-plan")).toString()});
			recordRepeatedCapabilityEvidence(sourceRun, "requested-change", 3);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path plan = writeStaticExecutionPlan(root.resolve("capability-plan"));
			ObjectNode planContents = PromptEvaluation.object(PromptEvaluation.read(plan), "execution plan");
			ObjectNode identity = (ObjectNode) planContents.path("identity");
			identity.put("executor_model", "model-b");
			identity.put("executor_effort", "high");
			identity.put("validator_model", "model-a");
			PromptEvaluation.write(plan, planContents);
			Path run = root.resolve(".cat/work/temp/capability/run.json");
			PromptEvaluation.run(new String[]{"begin", diagnostic.toString(), prompt.toString(), "fixture", "model-b",
				context.toString(), run.toString()});
			CapabilityDiagnostic diagnosticOwner = PromptEvaluation.capabilityDiagnostic(scope.json());
			diagnosticOwner.start(run, new CapabilityDiagnostic.Profile("default-model-a-medium"),
				new CapabilityDiagnostic.Profile("default-model-b-high"));
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "passed");
			Path workspace = root.resolve(".cat/work/temp/capability/workspace");
			ObjectNode result = diagnosticOwner.run(run, workspace, plan, 3, evaluationRunner);

			ObjectNode diagnosticResult = result;
			requireThat(diagnosticResult.path("classification").asString(), "classification").
				isEqualTo("non_certifying_profile_comparison");
			requireThat(diagnosticResult.path("completed_trials").asInt(), "completedTrials").isEqualTo(3);
			requireThat(diagnosticResult.path("passed_trials").asInt(), "passedTrials").isEqualTo(3);
			requireThat(diagnosticResult.path("infrastructure_failed").asBoolean(), "infrastructureFailed").isFalse();
			String executionPlanText = Files.readString(plan);
			requireThat(executionPlanText, "executionPlan").contains("\"executor_model\" : \"model-b\"").
				contains("\"validator_model\" : \"model-a\"");
			String report = output(() -> PromptEvaluation.run(new String[]{"report", run.toString()}));
			requireThat(report, "report").contains("\"target_executor_profile\"").
				doesNotContain("\"compliance_rate\"");
			String performanceReport = output(() -> PromptEvaluation.run(new String[]{"report",
				run.toString(), "--performance"}));
			requireThat(performanceReport, "performanceReport").contains("\"performance\"").
				contains("\"queue_millis\"").contains("\"startup_millis\"").contains("\"execution_millis\"").
				contains("\"validation_millis\"").contains("\"merge_millis\"").contains("\"critical_path\"");
			boolean unchangedProfileRejected = false;
			try
			{
				Path unchangedRun = root.resolve(".cat/work/temp/unchanged-profile/run.json");
				PromptEvaluation.run(new String[]{"begin", diagnostic.toString(), prompt.toString(), "fixture", "model-a",
					context.toString(), unchangedRun.toString()});
				diagnosticOwner.start(unchangedRun, new CapabilityDiagnostic.Profile("default-model-a-medium"),
					new CapabilityDiagnostic.Profile("default-model-a-medium"));
			}
			catch (IOException e)
			{
				unchangedProfileRejected = e.getMessage().contains("must change the executor model or effort");
			}
			requireThat(unchangedProfileRejected, "unchangedProfileRejected").isTrue();
		}
	}

	/**
	 * Records the retained recurrence evidence required before a fixture may start a profile comparison.
	 *
	 * @param sourceRun the latest localized target-profile failure
	 * @param caseId the repeated testcase
	 * @param observations the number of fresh matching target-profile checks
	 * @throws IOException if fixture evidence cannot be written
	 */
	private static void recordRepeatedCapabilityEvidence(Path sourceRun, String caseId, int observations)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			ObjectNode run = (ObjectNode) scope.json().readTree(sourceRun.toFile());
			Path statePath = sourceRun.resolveSibling("capability-state.json");
			ObjectNode state = scope.json().createObjectNode();
			ObjectNode signature = state.putArray("failure_signatures").addObject();
			signature.put("key", caseId + "/result/fixture");
			ObjectNode latestCase = signature.putObject("latest_case");
			latestCase.put("case_id", caseId);
			latestCase.put("assertion", "result");
			latestCase.put("boundary", "fixture");
			signature.put("last_observed_check", sourceRun.toString());
			signature.put("last_observed_candidate_sha256", run.path("assembled_prompt_route_sha256").asString());
			ArrayNode candidateRevisions = signature.putArray("observed_candidate_revisions");
			for (int index = 1; index <= observations; ++index)
				candidateRevisions.add("candidate-revision-" + index);
			signature.put("observations", observations);
			signature.put("consecutive_observations", observations);
			ArrayNode checks = state.putArray("checks");
			for (int index = 1; index <= observations; ++index)
			{
				ObjectNode check = checks.addObject();
				check.put("run", sourceRun.resolveSibling("check-" + index + ".json").toString());
				check.put("assembled_prompt_route_sha256", "candidate-revision-" + index);
				check.put("status", "failed");
			}
			Files.writeString(statePath, scope.json().writeValueAsString(state));
			run.put("failure_signature_state", statePath.toString());
			Files.writeString(sourceRun, scope.json().writeValueAsString(run));
		}
	}
}
