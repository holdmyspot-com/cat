package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.ArtifactRetention;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.attemptLedger;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

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
 * Tests {@link PromptEvaluation} non-certifying smoke lifecycle.
 */
public final class PromptEvaluationSmokeTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationSmokeTest()
	{
	}

	/**
	 * Verifies a public regression check rejects a missing smoke receipt.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*requires a retained smoke run.*")
	public void requiresSmokeReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = PromptEvaluationFixtures.writeFormalRunner(root, "passed");
			Path buildPlan = PromptEvaluationFixtures.writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");

			PromptEvaluation.regressionCheck(temporary, temporary.resolve("missing-smoke.json"),
				root.resolve(".cat/work/candidate-state.json"), suite, prompt, "fixture", context, runner, buildPlan,
				temporary.resolve("check"), 1);
		}
	}

	/**
	 * Verifies a smoke receipt cannot authorize a candidate with different recorded inputs.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*does not match the regression candidate: suite_sha256.*")
	public void rejectsMismatchedSmokeReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = PromptEvaluationFixtures.writeFormalRunner(root, "passed");
			Path buildPlan = PromptEvaluationFixtures.writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path smoke = write(root, "smoke-run.json", """
				{"mode":"smoke","smoke_result":{"case_id":"requested-change","trial":1},
				 "attempt_ledger":"%s","suite_sha256":"old-suite","prompt_sha256":"old-prompt",
				 "execution_context_sha256":"old-context","assembled_prompt_route_sha256":"old-route",
				 "candidate_identity_sha256":"old-candidate","execution_identity_sha256":"old-execution",
				 "harness":"fixture"}
				""".formatted(root.resolve("smoke-attempts.jsonl")));
			Files.writeString(root.resolve("smoke-attempts.jsonl"),
				"{\"case_id\":\"requested-change\",\"trial\":1,\"status\":\"passed\"}\n");

			PromptEvaluation.regressionCheck(temporary, smoke, root.resolve(".cat/work/candidate-state.json"), suite, prompt,
				"fixture", context, runner, buildPlan, temporary.resolve("check"), 1);
		}
	}

	/**
	 * Verifies a smoke run executes only its selected testcase and cannot be reused as certification evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsNonCertifyingSmokeTestcase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path temporaryRoot = Files.createDirectories(root.resolve(".cat/work/temp"));
			Path record = InvocationDirectory.create(temporaryRoot, "prompt-evaluation-smoke");
			Path run = record.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			Path workspace = record.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode result = PromptEvaluation.smoke(run, "requested-change", workspace, plan, 1, runner);

			requireThat(result.path("classification").asString(), "result").isEqualTo("non_certifying_smoke");
			requireThat(result.path("case_id").asString(), "result").isEqualTo("requested-change");
			String ledger = Files.readString(attemptLedger(run));
			requireThat(ledger, "ledger").contains("\"case_id\":\"requested-change\"").
				doesNotContain("\"case_id\":\"unrelated-request\"").doesNotContain("\"case_id\":\"edge-condition\"");
			String status = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(),
				workspace.toString(), "--summary"}));
			requireThat(status, "status").contains("\"evaluation_state\":\"terminal\"").
				contains("\"last_completed_stage\":\"smoke_completed\"").contains("\"next_action\":\"finalize_evidence\"");
			requireThat(Files.readString(workspace.resolve(".cat-prompt-evaluation-status.json")), "rawStatus").
				contains("\"phase\" : \"terminal\"").contains("\"active_workers\" : [ ]");
			String report = output(() -> PromptEvaluation.run(new String[]{"report", run.toString()}));
			requireThat(report, "report").contains("\"classification\":\"non_certifying_smoke\"").
				contains("\"case_id\":\"requested-change\"");
			try
			{
				PromptEvaluation.run(new String[]{"run-batch", run.toString(), "1", root.resolve("batch").toString(),
					plan.toString(), "--max-concurrency", "1"});
				throw new AssertionError("Expected smoke record to reject certification");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("non-certifying smoke");
			}
			Path workRoot = temporaryRoot.getParent();
			Path interruptedCleanup = Files.createDirectories(workRoot.resolve("interrupted-cleanup"));
			Files.writeString(interruptedCleanup.resolve(ArtifactRetention.RECORD_MARKER),
				interruptedCleanup.resolve("missing-run.json") + "\n");
			ObjectNode finalization = PromptEvaluation.finalizeEvaluation(temporaryRoot, workRoot, run, workspace,
				"retain", null);
			requireThat(finalization.path("retained_bytes").asLong(), "retainedBytes").isGreaterThan(0L);
			ObjectNode handoff = (ObjectNode) finalization.path("handoff");
			Path retained = Path.of(handoff.path("evidence_root").asString());
			requireThat(retained.getParent(), "retained.parent").isEqualTo(workRoot.resolve("prompt-evaluation-retained"));
			ObjectNode compaction = (ObjectNode) handoff.path("evidence_compaction");
			requireThat(compaction.path("workspace_cleanup").path("status").asString(), "workspaceCleanupStatus").
				isEqualTo("deleted");
			requireThat(Files.exists(retained.resolve("workspace")), "retainedWorkspace").isFalse();
			String retainedStatus = output(() -> PromptEvaluation.run(new String[]{"status",
				Path.of(handoff.path("run").asString()).toString(), "--summary"}));
			requireThat(retainedStatus, "retainedStatus").contains("\"evaluation_state\":\"terminal\"").
				contains("\"next_action\":\"report_retained_evidence\"");
			requireThat(Files.readString(Path.of(handoff.path("journal").asString())), "journal").
				contains("\"terminal_decision\" : \"SMOKE\"");
		}
	}
}
