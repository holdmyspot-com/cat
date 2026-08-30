package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.CandidateState;
import io.github.cowwoc.cat.common.evaluation.RuntimeRevalidation;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeInvocationLocalIdentityPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.recordedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.evaluation.RunIntegrity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the evaluation identity and epoch contracts.
 */
public final class PromptEvaluationIdentityTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationIdentityTest()
	{
	}

	/**
	 * Verifies two fresh copies of the same runner and policy can continue one promoted evaluation epoch.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsCopiedArtifactsInOneEpoch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path check = root.resolve("check.json");
			Path certification = root.resolve("certification.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), check.toString()});
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), certification.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", check.toString(),
				writeInvocationLocalIdentityPlan(root.resolve("check-plan")).toString()});
			PromptEvaluation.run(new String[]{"bind-plan", certification.toString(),
				writeInvocationLocalIdentityPlan(root.resolve("certification-plan")).toString()});

			try (ToolScope scope = new ToolScope())
			{
				ObjectNode checkRecord = (ObjectNode) scope.json().readTree(Files.readString(check));
				ObjectNode certificationRecord = (ObjectNode) scope.json().readTree(Files.readString(certification));
				String checkEpoch = checkRecord.path("evaluation_epoch_sha256").asString();
				String certificationEpoch = certificationRecord.path("evaluation_epoch_sha256").asString();
				String checkIdentity = checkRecord.path("execution_identity_sha256").asString();
				String certificationIdentity = certificationRecord.path("execution_identity_sha256").asString();
				requireThat(certificationEpoch, "certificationEpoch").isEqualTo(checkEpoch);
				requireThat(certificationIdentity, "certificationIdentity").isEqualTo(checkIdentity);
			}
		}
	}

	/**
	 * Verifies a changed routed rule starts a new prompt candidate without falsely changing its runner environment.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void separatesRoutedRuleFromRunnerEpoch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path baseline = root.resolve("baseline.json");
			Path candidate = root.resolve("candidate.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), baseline.toString()});
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), candidate.toString()});
			Path baselinePlan = writeInvocationLocalIdentityPlan(root.resolve("baseline-plan"));
			Path candidatePlan = writeInvocationLocalIdentityPlan(root.resolve("candidate-plan"));
			Files.writeString(candidatePlan.getParent().resolve("runner-artifact/rules/common/rule.md"),
				"revised target rule guidance\n");
			PromptEvaluation.run(new String[]{"bind-plan", baseline.toString(), baselinePlan.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", candidate.toString(), candidatePlan.toString()});

			try (ToolScope scope = new ToolScope())
			{
				ObjectNode baselineRecord = (ObjectNode) scope.json().readTree(Files.readString(baseline));
				ObjectNode candidateRecord = (ObjectNode) scope.json().readTree(Files.readString(candidate));
				requireThat(candidateRecord.path("evaluation_epoch_sha256").asString(), "candidateEpoch").isEqualTo(
					baselineRecord.path("evaluation_epoch_sha256").asString());
				requireThat(candidateRecord.path("assembled_prompt_route_sha256").asString(), "candidateRoute").
					isNotEqualTo(baselineRecord.path("assembled_prompt_route_sha256").asString());
			}
		}
	}

	/**
	 * Verifies a changed policy does not join the epoch merely because the runner copy is otherwise the same.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void separatesChangedPolicies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path baseline = root.resolve("baseline.json");
			Path changed = root.resolve("changed.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), baseline.toString()});
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), changed.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", baseline.toString(),
				writeInvocationLocalIdentityPlan(root.resolve("baseline-plan"), "shared-writable-cache").toString()});
			PromptEvaluation.run(new String[]{"bind-plan", changed.toString(),
				writeInvocationLocalIdentityPlan(root.resolve("changed-plan"), "read-only-cache").toString()});

			try (ToolScope scope = new ToolScope())
			{
				String baselineEpoch = scope.json().readTree(Files.readString(baseline)).
					path("evaluation_epoch_sha256").asString();
				String changedEpoch = scope.json().readTree(Files.readString(changed)).
					path("evaluation_epoch_sha256").asString();
				requireThat(changedEpoch, "changedEpoch").isNotEqualTo(baselineEpoch);
			}
		}
	}

	/**
	 * Verifies a changed execution identity stays frozen until retained evaluator-defect evidence explicitly supersedes
	 * the current epoch.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void requiresDefectToChangeEpoch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path defectRun = recordedFailure(root, suite, "requested-change", "deterministic-tool", "failed");
			ObjectNode defect = (ObjectNode) scope.json().readTree(defectRun.toFile());
			defect.put("evaluation_epoch_sha256", "original-execution-epoch");
			Files.writeString(defectRun, scope.json().writeValueAsString(defect));
			Path state = root.resolve("candidate-state.json");
			ObjectNode candidateState = scope.json().createObjectNode();
			candidateState.put("evaluation_epoch_sha256", "original-execution-epoch");
			candidateState.putArray("failure_signatures");
			Files.writeString(state, scope.json().writeValueAsString(candidateState));
			Path proposedRun = root.resolve("proposed-run.json");
			ObjectNode proposed = defect.deepCopy();
			proposed.put("evaluation_epoch_sha256", "changed-execution-epoch");
			proposed.put("assembled_prompt_route_sha256", "candidate-route");
			Files.writeString(proposedRun, scope.json().writeValueAsString(proposed));

			boolean rejected = false;
			try
			{
				CandidateState.validateCandidateStateBeforeCheck(state, proposedRun);
			}
			catch (IOException e)
			{
				rejected = true;
				requireThat(e.getMessage(), "message").contains("execution epoch is frozen");
			}
			requireThat(rejected, "rejected").isTrue();

			ObjectNode authorization = RuntimeRevalidation.authorizeDefectSupersession(state, defectRun,
				"requested-change", 1);
			requireThat(authorization.path("classification").path("failure_class").asString(), "failureClass").isEqualTo(
				"deterministic-tool");
			CandidateState.validateCandidateStateBeforeCheck(state, proposedRun);
		}
	}

	/**
	 * Verifies a missing historical check receipt preserves the epoch-freeze diagnostic instead of leaking a file-read
	 * failure.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*execution epoch is frozen.*")
	public void rejectsEpochWithUnavailableCheck() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path state = root.resolve("candidate-state.json");
			ObjectNode candidateState = scope.json().createObjectNode();
			candidateState.put("evaluation_epoch_sha256", "original-execution-epoch");
			candidateState.put("latest_check", root.resolve("unavailable-check.json").toString());
			candidateState.putArray("failure_signatures");
			Files.writeString(state, scope.json().writeValueAsString(candidateState));

			Path proposedRun = recordedFailure(root, suite, "requested-change", "deterministic-tool", "failed");
			ObjectNode proposed = (ObjectNode) scope.json().readTree(proposedRun.toFile());
			proposed.put("evaluation_epoch_sha256", "changed-execution-epoch");
			Files.writeString(proposedRun, scope.json().writeValueAsString(proposed));

			CandidateState.validateCandidateStateBeforeCheck(state, proposedRun);
		}
	}

	/**
	 * Verifies a later batch rejects a changed runner artifact even when the command plan itself is unchanged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Runner runtime manifest does not match.*")
	public void rejectsChangedExecutionIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "passed");
			PromptEvaluation.runBatch(run, 1, root.resolve("first-batch"), plan, 1, evaluationRunner);
			Files.writeString(root.resolve("runner-artifact/client/runner.txt"), "changed runner artifact\n");

			PromptEvaluation.run(new String[]{"run-batch", run.toString(), "1", root.resolve("second-batch").toString(),
				plan.toString(), "--max-concurrency", "1"});
		}
	}

	/**
	 * Verifies a changed installed rule cannot run under a plan that was bound to its earlier prompt bundle.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Prompt route changed after the evaluation began.*")
	public void rejectsChangedRuleBeforeEvaluation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "passed");
			PromptEvaluation.runBatch(run, 1, root.resolve("first-batch"), plan, 1, evaluationRunner);
			RunIntegrity.verifyInputs((ObjectNode) PromptEvaluation.read(run), run);
			Files.writeString(root.resolve("runner-artifact/rules/common/rule.md"), "changed installed rule\n");

			PromptEvaluation.run(new String[]{"run-batch", run.toString(), "1", root.resolve("second-batch").toString(),
				plan.toString(), "--max-concurrency", "1"});
		}
	}
}
