package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.ArtifactRetention;

import io.github.cowwoc.cat.common.evaluation.FileStore;
import io.github.cowwoc.cat.common.evaluation.RunnerArtifactStore;
import io.github.cowwoc.cat.common.evaluation.SuiteStore;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.attemptLedger;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.ensureRunnerRuntimeManifest;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.record;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeInvocationLocalIdentityPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import io.github.cowwoc.cat.common.evaluation.EvaluationWorkspaceLifecycle;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link ArtifactRetention}.
 */
public final class ArtifactRetentionTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public ArtifactRetentionTest()
	{
	}

	/**
	 * Releases the test-owned mapper scope.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies an evaluation-created record marker identifies exactly the directory that owns its terminal evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void resolvesMarkerOwnedEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluation-artifact-retention"));
			Path temporary = root.resolve(".cat/work/temp");
			Path recordDirectory = temporary.resolve("evaluation");
			Files.createDirectories(recordDirectory);
			Path run = recordDirectory.resolve("run.json");
			Files.writeString(run, "{}");

			ArtifactRetention.markRecord(recordDirectory, run);

			requireThat(ArtifactRetention.ownedEvidence(temporary, run), "ownedEvidence").isEqualTo(
				recordDirectory);
		}
	}

	/**
	 * Verifies a completed correction replaces the retained failure as the workflow's current evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void linksRetainedFailureToCorrection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-journal"));
			Path failedJournal = Files.createDirectories(root.resolve("failed")).resolve("workflow-journal.json");
			Files.writeString(failedJournal, """
				{
				  "purpose": "prompt-evaluation",
				  "terminal_decision": "FAIL",
				  "failure_class": "prompt-or-certification",
				  "status": "current"
				}
				""");
			Path correctionJournal = Files.createDirectories(root.resolve("correction")).resolve("workflow-journal.json");
			Files.writeString(correctionJournal, """
				{
				  "purpose": "prompt-evaluation",
				  "terminal_decision": "PASS",
				  "failure_class": "none",
				  "status": "current"
				}
				""");

			PromptEvaluation.run(new String[]{"link-correction", failedJournal.toString(), correctionJournal.toString()});

			String failedContents = Files.readString(failedJournal);
			requireThat(failedContents, "failedContents").contains("\"status\" : \"superseded\"").
				contains(correctionJournal.toAbsolutePath().normalize().toString());
		}
	}

	/**
	 * Verifies an invocation remains until every record it contains completes its own retained-evidence handoff.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsInvocationForSiblingRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("sibling-prompt-evaluation-handoff-"));
			Path workRoot = Files.createDirectory(parent.resolve("work"));
			Path temporaryRoot = Files.createDirectory(workRoot.resolve("temp"));
			Path invocation = InvocationDirectory.create(temporaryRoot, "prompt-optimizer");
			Path record = invocation.resolve("evaluation");
			Path workspace = record.resolve("workspace");
			Path run = createTerminalFailure(parent, workspace, record.resolve("run.json"));
			Files.writeString(record.resolve(ArtifactRetention.RECORD_MARKER), run + "\n");
			Path sibling = Files.createDirectories(invocation.resolve("sibling"));
			Files.writeString(sibling.resolve(ArtifactRetention.RECORD_MARKER),
				sibling.resolve("run.json") + "\n");

			JsonNode finalization = PromptEvaluation.finalizeEvaluation(temporaryRoot, workRoot, run, workspace,
				"retain", workRoot.resolve("retained/evaluation"));
			JsonNode handoff = finalization.path("handoff");

			requireThat(handoff.path("invocation_cleanup").path("status").asString(), "invocationCleanup.status").
				isEqualTo("retained_for_sibling_records");
			requireThat(Files.isDirectory(invocation), "invocation").isTrue();
		}
	}

	/**
	 * Verifies terminal handoff retains referenced evidence and removes an invocation that has no sibling record.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsNestedEvaluatorHandoff() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("nested-prompt-evaluation-handoff-"));
			Path workRoot = Files.createDirectory(parent.resolve("work"));
			Path temporaryRoot = Files.createDirectory(workRoot.resolve("temp"));
			Path invocation = InvocationDirectory.create(temporaryRoot, "prompt-optimizer");
			Path record = invocation.resolve("evaluation");
			Path workspace = record.resolve("workspace");
			Path run = createTerminalFailure(parent, workspace, record.resolve("run.json"));
			Path raw = workspace.resolve("trial-1/requested-change-raw.txt");
			write(workspace, "trial-1/requested-change/executor.json", """
				{"retained_project":"%s","raw_result":"%s"}
				""".formatted(workspace.resolve("trial-1/requested-change"), raw));
			write(workspace, "trial-1/requested-change/validator-prompt.md", "Inspect " + raw + "\n");
			Files.writeString(record.resolve(ArtifactRetention.RECORD_MARKER), run + "\n");

			Path durable = workRoot.resolve("retained/prompt-evaluation");
			JsonNode handoff = PromptEvaluation.handoff(scope.json(), run, workspace, durable, temporaryRoot, workRoot);

			Path retainedRun = Path.of(handoff.path("run").stringValue());
			requireThat(retainedRun.startsWith(durable), "retainedRun.startsWithDurable").isTrue();
			requireThat(Files.readString(durable.resolve(ArtifactRetention.RECORD_MARKER)).strip(),
				"recordMarker").isEqualTo(retainedRun.toString());
			String retained = Files.readString(retainedRun.resolveSibling(retainedRun.getFileName() + ".attempts.jsonl"));
			Path evidenceStore = workRoot.resolve("prompt-evaluation-evidence-artifacts");
			requireThat(retained, "retainedAttemptLedger").contains(evidenceStore.toString()).
				doesNotContain(durable.resolve("evaluation/trial-1/requested-change-raw.txt").toString());
			requireThat(Files.exists(durable.resolve("evaluation/trial-1/requested-change-raw.txt")), "copiedRawResult").
				isFalse();
			requireThat(Files.exists(durable.resolve("evaluation")), "terminalWorkspace").isFalse();
			JsonNode status = PromptEvaluation.status(retainedRun, durable, false);
			requireThat(status.path("evaluation_state").asString(), "status.evaluationState").isEqualTo("terminal");
			requireThat(status.path("next_action").asString(), "status.nextAction").isEqualTo(
				"report_retained_evidence");
			requireThat(handoff.path("invocation_cleanup").path("status").asString(), "invocationCleanup.status").
				isEqualTo("deleted");
			requireThat(Files.exists(invocation), "outerInvocationExists").isFalse();
		}
	}

	/**
	 * Creates the terminal evaluator record whose retained-evidence handoff these tests exercise.
	 *
	 * @param root the isolated fixture root
	 * @param workspace the evaluator workspace
	 * @param run the evaluator record path
	 * @return the terminal evaluator record
	 * @throws IOException if fixture I/O fails
	 */
	private static Path createTerminalFailure(Path root, Path workspace, Path run) throws IOException
	{
		Path suite = writeSuite(root);
		Path prompt = write(root, "prompt.md", "Prompt artifact\n");
		Path context = write(root, "context.md", "Validator contract\n");
		PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
			context.toString(), run.toString()});
		PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"});
		for (String caseId : new String[]{"requested-change", "unrelated-request", "edge-condition"})
		{
			Path raw = write(workspace, "trial-1/" + caseId + "-raw.txt", "raw");
			Path verdict = write(workspace, "trial-1/" + caseId + "-verdict.txt", "failed");
			PromptEvaluation.run(new String[]{"record", run.toString(), caseId, "1", "failed", raw.toString(),
				verdict.toString()});
		}
		PromptEvaluation.run(new String[]{"decide", run.toString()});
		Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run + "\n");
		return run;
	}

	/**
	 * Verifies artifact collection preserves every evaluation reference and removes only unreferenced copies beyond its
	 * retention budget.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void collectsOnlyUnreferencedRunnerArtifacts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path firstSource = Files.createDirectories(root.resolve("first-runner"));
			write(firstSource, "client/runner.txt", "first artifact\n");
			ensureRunnerRuntimeManifest(firstSource);
			Path secondSource = Files.createDirectories(root.resolve("second-runner"));
			write(secondSource, "client/runner.txt", "second artifact\n");
			ensureRunnerRuntimeManifest(secondSource);

			Path first = RunnerArtifactStore.retain(root, firstSource);
			Path second = RunnerArtifactStore.retain(root, secondSource);
			RunnerArtifactStore.Collection dryRun = RunnerArtifactStore.collect(root, Set.of(first), 0,
				true);

			requireThat(dryRun.collected(), "dryRun.collected()").containsExactly(List.of(second));
			assertTrue(Files.isDirectory(second));

			RunnerArtifactStore.Collection collected = RunnerArtifactStore.collect(root, Set.of(first), 0,
				false);
			requireThat(collected.collected(), "collected.collected()").containsExactly(List.of(second));
			assertTrue(Files.isDirectory(first));
			assertFalse(Files.exists(second));
		}
	}

	/**
	 * Verifies a generated runner-runtime change receives a separate immutable copy.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsChangedGeneratedRunnerRuntime() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path source = Files.createDirectories(root.resolve("runner"));
			write(source, "client/lib/modules", "first runtime\n");
			ensureRunnerRuntimeManifest(source);
			Path first = RunnerArtifactStore.retain(root, source);

			Files.writeString(source.resolve("client/lib/modules"), "second runtime\n");
			Files.delete(source.resolve("client/runner-runtime.manifest"));
			ensureRunnerRuntimeManifest(source);
			Path second = RunnerArtifactStore.retain(root, source);

			requireThat(second, "second").isNotEqualTo(first);
			requireThat(Files.readString(second.resolve("client/lib/modules")), "retainedRuntime").isEqualTo(
				"second runtime\n");
		}
	}

	/**
	 * Verifies collection derives protected runner artifacts from retained evaluation records instead of trusting a
	 * caller to reconstruct those references.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void collectsReferencedArtifacts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path temporary = root.resolve(".cat/work/temp");
			Path firstSource = Files.createDirectories(root.resolve("first-runner"));
			write(firstSource, "client/runner.txt", "first artifact\n");
			ensureRunnerRuntimeManifest(firstSource);
			Path secondSource = Files.createDirectories(root.resolve("second-runner"));
			write(secondSource, "client/runner.txt", "second artifact\n");
			ensureRunnerRuntimeManifest(secondSource);
			Path first = RunnerArtifactStore.retain(temporary, firstSource);
			Path second = RunnerArtifactStore.retain(temporary, secondSource);
			Path record = Files.createDirectories(temporary.resolve("retained-evaluation"));
			Path plan = write(record, "execution-plan.json", """
				{"identity":{"runner_artifact":"%s"}}
				""".formatted(first));
			Files.writeString(record.resolve(".cat-prompt-evaluation-record"), record.resolve("run.json") + "\n");
			Path staleRecord = Files.createDirectories(temporary.resolve("interrupted-cleanup"));
			Files.writeString(staleRecord.resolve(".cat-prompt-evaluation-record"),
				staleRecord.resolve("missing-run.json") + "\n");
			write(record, "run.json", """
				{"execution_plan":"%s"}
				""".formatted(plan));

			RunnerArtifactStore.Collection dryRun = ArtifactRetention.collectRunnerArtifacts(scope.json(),
				temporary, 0, true);
			requireThat(dryRun.collected(), "dryRun.collected()").containsExactly(List.of(second));

			RunnerArtifactStore.Collection collected = ArtifactRetention.collectRunnerArtifacts(scope.json(),
				temporary, 0, false);
			requireThat(collected.collected(), "collected.collected()").containsExactly(List.of(second));
			assertTrue(Files.isDirectory(first));
			assertFalse(Files.exists(second));
		}
	}

	/**
	 * Verifies shared prompt and context copies remain while a marked evaluation record references them.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void collectsOnlyUnreferencedInputArtifacts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path temporary = root.resolve(".cat/work/temp");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator context\n");
			Path stale = write(root, "stale.md", "Stale input\n");
			Path retainedPrompt = FileStore.retain(temporary, prompt);
			Path retainedContext = FileStore.retain(temporary, context);
			Path unreferenced = FileStore.retain(temporary, stale);
			Path record = Files.createDirectories(temporary.resolve("retained-evaluation"));
			Path run = write(record, "run.json", """
				{"prompt_artifact":"%s","execution_context":"%s"}
				""".formatted(retainedPrompt, retainedContext));
			Files.writeString(record.resolve(ArtifactRetention.RECORD_MARKER), run + "\n");

			FileStore.Collection dryRun = ArtifactRetention.collectInputArtifacts(scope.json(),
				temporary, 0, true);
			requireThat(dryRun.collected(), "dryRun.collected()").containsExactly(List.of(unreferenced));
			assertTrue(Files.isRegularFile(unreferenced));

			FileStore.Collection collected = ArtifactRetention.collectInputArtifacts(scope.json(),
				temporary, 0, false);
			requireThat(collected.collected(), "collected.collected()").containsExactly(List.of(unreferenced));
			assertTrue(Files.isRegularFile(retainedPrompt));
			assertTrue(Files.isRegularFile(retainedContext));
			assertFalse(Files.exists(unreferenced));
		}
	}

	/**
	 * Verifies a terminal attempt ledger protects its retained results while collection removes an unreferenced copy.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void collectsUnreferencedExecutionEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path workRoot = root.resolve(".cat/work");
			Path raw = write(root, "raw.json", "Executor result\n");
			Path verdict = write(root, "verdict.json", "Validator result\n");
			Path stale = write(root, "stale.json", "Unreferenced result\n");
			Path retainedRaw = FileStore.retainExecutionEvidence(workRoot, raw);
			Path retainedVerdict = FileStore.retainExecutionEvidence(workRoot, verdict);
			Path unreferenced = FileStore.retainExecutionEvidence(workRoot, stale);
			Path record = Files.createDirectories(workRoot.resolve("retained-evaluation"));
			Path ledger = write(record, "run.json.attempts.jsonl", """
				{"raw_result":"%s","validator_result":"%s"}
				""".formatted(retainedRaw, retainedVerdict));
			Path run = write(record, "run.json", """
				{"attempt_ledger":"%s"}
				""".formatted(ledger));
			Files.writeString(record.resolve(ArtifactRetention.RECORD_MARKER), run + "\n");

			FileStore.Collection dryRun =
				ArtifactRetention.collectExecutionEvidenceArtifacts(scope.json(), workRoot, 0, true);
			requireThat(dryRun.collected(), "dryRun.collected()").containsExactly(List.of(unreferenced));
			assertTrue(Files.isRegularFile(unreferenced));

			FileStore.Collection collected =
				ArtifactRetention.collectExecutionEvidenceArtifacts(scope.json(), workRoot, 0, false);
			requireThat(collected.collected(), "collected.collected()").containsExactly(List.of(unreferenced));
			assertTrue(Files.isRegularFile(retainedRaw));
			assertTrue(Files.isRegularFile(retainedVerdict));
			assertFalse(Files.exists(unreferenced));
		}
	}

	/**
	 * Verifies collection never trusts an input-artifact path that a retained record could not have received from its
	 * immutable store.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*not an immutable input artifact.*")
	public void rejectsExternalInputArtifactReference() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path temporary = root.resolve(".cat/work/temp");
			Path source = write(root, "prompt.md", "Prompt artifact\n");
			FileStore.retain(temporary, source);

			FileStore.collect(temporary, Set.of(source), 0, true);
		}
	}

	/**
	 * Verifies shared suite copies remain while a marked evaluation record references their manifest.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void collectsOnlyUnreferencedSuiteArtifacts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path temporary = root.resolve(".cat/work/temp");
			write(root, "retained-suite/suite.json", "{\"suite\":\"retained\"}\n");
			write(root, "stale-suite/suite.json", "{\"suite\":\"stale\"}\n");
			Path retainedSource = root.resolve("retained-suite");
			Path staleSource = root.resolve("stale-suite");
			Path retainedSuite = SuiteStore.retain(temporary, retainedSource);
			Path unreferencedSuite = SuiteStore.retain(temporary, staleSource);
			Path record = Files.createDirectories(temporary.resolve("retained-evaluation"));
			Path run = write(record, "run.json", """
				{"suite":"%s"}
				""".formatted(retainedSuite));
			Files.writeString(record.resolve(ArtifactRetention.RECORD_MARKER), run + "\n");

			SuiteStore.Collection dryRun = ArtifactRetention.collectSuiteArtifacts(scope.json(),
				temporary, 0, true);
			requireThat(dryRun.collected(), "dryRun.collected()").containsExactly(List.of(unreferencedSuite));
			assertTrue(Files.isDirectory(unreferencedSuite));

			SuiteStore.Collection collected = ArtifactRetention.collectSuiteArtifacts(scope.json(),
				temporary, 0, false);
			requireThat(collected.collected(), "collected.collected()").containsExactly(
				List.of(unreferencedSuite));
			assertTrue(Files.isDirectory(retainedSuite));
			assertFalse(Files.exists(unreferencedSuite));
		}
	}

	/**
	 * Verifies collection never trusts a suite-manifest path that is outside its immutable suite store.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*not an immutable suite manifest.*")
	public void rejectsExternalSuiteReference() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path temporary = root.resolve(".cat/work/temp");
			write(root, "suite/suite.json", "{\"suite\":\"source\"}\n");
			Path source = root.resolve("suite");
			SuiteStore.retain(temporary, source);

			SuiteStore.collect(temporary, Set.of(source), 0, true);
		}
	}

	/**
	 * Verifies repeated terminal evaluations remain finalizable after their live execution plans change.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(invocationCount = 8)
	public void finalizesAfterPlanChanges() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path temporary = root.resolve(".cat/work/temp");
			Path evidence = InvocationDirectory.create(temporary, "prompt-evaluation-finalization");
			Path run = evidence.resolve("run.json");
			Path plan = writeInvocationLocalIdentityPlan(root.resolve("execution-plan"));
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "fixture", "model-a",
				context.toString(), run.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", run.toString(), plan.toString()});
			for (String caseId : List.of("requested-change", "unrelated-request", "edge-condition"))
				record(run, caseId, 1, "failed");
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			requireThat(decision, "decision").contains("\"overall_decision\":\"FAIL\"");
			Path workspace = evidence.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(EvaluationWorkspaceLifecycle.EVIDENCE_MARKER), run.toAbsolutePath() +
				System.lineSeparator());
			requireThat(run, "run").isRegularFile();
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode record = (ObjectNode) scope.json().readTree(run.toFile());
				ObjectNode state = scope.json().createObjectNode();
				state.put("schema_version", 2);
				state.put("suite_sha256", record.path("suite_sha256").asString());
				state.put("evaluation_epoch_sha256", record.path("evaluation_epoch_sha256").asString());
				state.putArray("promotions");
				state.putArray("failure_signatures");
				state.putArray("formal_failures").addObject().put("run", run.toString()).
					put("observed_at_epoch_millis", 1);
				record.put("failure_signature_state", candidateState.toString());
				Files.writeString(candidateState, scope.json().writeValueAsString(state));
				Files.writeString(run, scope.json().writeValueAsString(record));
			}
			Files.writeString(plan, "rebuilt execution plan\n");
			ObjectNode finalization = PromptEvaluation.finalizeEvaluation(temporary, temporary.getParent(), run,
				workspace, "delete", null);
			requireThat(finalization.path("deleted_bytes").asLong(), "deletedBytes").isGreaterThan(0L);
			requireThat(Files.exists(run), "deletedRun").isFalse();
			requireThat(Files.exists(workspace), "deletedWorkspace").isFalse();
			Path tombstone = Path.of(finalization.path("tombstone").asString());
			requireThat(tombstone, "tombstone").isRegularFile();
			String status = output(() -> PromptEvaluation.run(new String[]{"goal-status", candidateState.toString()}));
			requireThat(status, "status").contains(tombstone.toString());
		}
	}

	/**
	 * Verifies a later decision rejects a retained raw result whose contents changed after recording.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Raw result changed.*")
	public void rejectsChangedRawResult() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			for (String caseId : new String[]{"requested-change", "unrelated-request", "edge-condition"})
				record(run, caseId, 1, "failed");
			Files.writeString(root.resolve("requested-change-1-raw.json"), "modified raw result\n");
			PromptEvaluation.run(new String[]{"decide", run.toString()});
		}
	}

	/**
	 * Verifies terminal evaluator handoff rewrites retained artifact paths instead of leaving the record at the moved
	 * temporary location.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rewritesPathsDuringTerminalHandoff() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("prompt-evaluation-handoff-"));
			Path workRoot = Files.createDirectory(parent.resolve("work"));
			Path temporaryRoot = Files.createDirectory(workRoot.resolve("temp"));
			Path workspace = InvocationDirectory.create(temporaryRoot, "prompt-evaluation");
			Path suite = writeSuite(parent);
			Path prompt = write(parent, "prompt.md", "Prompt artifact\n");
			Path context = write(parent, "context.md", "Validator contract\n");
			Path run = workspace.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"});
			for (String caseId : new String[]{"requested-change", "unrelated-request", "edge-condition"})
			{
				Path raw = write(workspace, "trial-1/" + caseId + "-raw.txt", "raw");
				Path verdict = write(workspace, "trial-1/" + caseId + "-verdict.txt", "failed");
				PromptEvaluation.run(new String[]{"record", run.toString(), caseId, "1", "failed", raw.toString(),
					verdict.toString()});
			}
			Path deterministic = write(workspace, "trial-1/requested-change-deterministic.json", "{\"status\":\"passed\"}");
			Path ledger = attemptLedger(run);
			try (ToolScope scope = new ToolScope())
			{
				List<String> entries = Files.readAllLines(ledger);
				ObjectNode firstAttempt = (ObjectNode) scope.json().readTree(entries.getFirst());
				firstAttempt.put("deterministic_result", deterministic.toString());
				firstAttempt.put("deterministic_result_sha256", HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(deterministic))));
				entries.set(0, scope.json().writeValueAsString(firstAttempt));
				Files.writeString(ledger, String.join(System.lineSeparator(), entries) + System.lineSeparator());
			}
			PromptEvaluation.run(new String[]{"decide", run.toString()});
			Path candidateState = write(parent, "candidate-state.json", """
				{"formal_failures":[{"run":"%s","observed_at_epoch_millis":1}]}
				""".formatted(run));
			Path sandboxPolicy = write(workspace, "sandbox-policy.json", "sandbox policy\n");
			Path networkPolicy = write(workspace, "network-policy.json", "network policy\n");
			Path cachePolicy = write(workspace, "cache-policy.json", "cache policy\n");
			Path executionPlan = write(workspace, "execution-plan.json", """
				{"identity":{"sandbox_policy":"%s","network_policy":"%s","cache_policy":"%s"}}
				""".formatted(sandboxPolicy, networkPolicy, cachePolicy));
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode record = (ObjectNode) scope.json().readTree(Files.readString(run));
				record.put("assembled_prompt_route_sha256", "prompt-route-hash");
				record.put("retained_workspace", workspace.toString());
				record.put("execution_plan", executionPlan.toString());
				record.put("execution_plan_sha256", HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executionPlan))));
				record.put("failure_signature_state", candidateState.toString());
				record.put("failure_signature_state_sha256", "before-handoff");
				record.putObject("execution_identity").put("runner_runtime_sha256", "runner-runtime-hash");
				Files.writeString(run, scope.json().writeValueAsString(record));
			}
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run + "\n");
			Files.createFile(workspace.resolve(".cat-prompt-evaluation-active"));
			Path transientRuntime = write(workspace,
				"trial-1/requested-change/executor-project/runner-runs/codex/run/events.jsonl", "native events\n");

			Path durable = workRoot.resolve("retained/prompt-evaluation");
			ObjectNode handoff = PromptEvaluation.handoff(scope.json(), run, workspace, durable, temporaryRoot, workRoot);

			Path retainedRun = Path.of(handoff.path("run").stringValue());
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode retainedRecord = (ObjectNode) scope.json().readTree(Files.readString(retainedRun));
				requireThat(retainedRecord.path("retained_workspace").asString(), "retainedWorkspace").isEqualTo(
					durable.toString());
			}
			String retained = Files.readString(attemptLedger(retainedRun));
			String retainedPlan = Files.readString(durable.resolve("execution-plan.json"));
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode retainedRecord = (ObjectNode) scope.json().readTree(Files.readString(retainedRun));
				requireThat(retainedRecord.path("execution_plan_sha256").asString(), "retainedPlanFingerprint").
					isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
						Files.readAllBytes(durable.resolve("execution-plan.json")))));
			}
			requireThat(retainedPlan, "retainedExecutionPlan").contains(durable.resolve("sandbox-policy.json").toString()).
				contains(durable.resolve("network-policy.json").toString()).
				contains(durable.resolve("cache-policy.json").toString()).doesNotContain(workspace.toString());
			Path evidenceStore = workRoot.resolve("prompt-evaluation-evidence-artifacts");
			requireThat(retained, "retainedAttemptLedger").contains(evidenceStore.toString()).
				doesNotContain(durable.resolve("trial-1/requested-change-raw.txt").toString());
			requireThat(Files.exists(durable.resolve("trial-1/requested-change-raw.txt")), "copiedRawResult").isFalse();
			requireThat(Files.exists(durable.resolve("trial-1/requested-change-deterministic.json")),
				"copiedDeterministicResult").isFalse();
			requireThat(Files.exists(durable.resolve(workspace.relativize(transientRuntime))),
				"transientRuntime").isFalse();
			ObjectNode compaction = (ObjectNode) handoff.path("evidence_compaction");
			requireThat(compaction.path("retained_artifacts").size(), "retainedArtifacts").isEqualTo(3);
			requireThat(compaction.path("removed_runtime_paths").size(), "removedRuntimePaths").isEqualTo(1);
			requireThat(compaction.path("deleted_bytes").asLong(), "deletedBytes").isGreaterThan(0L);
			String classification = output(() -> PromptEvaluation.run(new String[]{"classify-failure",
				retainedRun.toString(), "requested-change", "1"}));
			requireThat(classification, "classification").contains("\"failure_class\":\"unresolved\"").
				contains("\"evidence\":\"" + evidenceStore);
			requireThat(Files.readString(durable.resolve(".cat-prompt-evaluation-evidence")).strip(), "evidencePointer").
				isEqualTo(retainedRun.toString());
			requireThat(Files.readString(candidateState), "candidateState").contains(retainedRun.toString()).
				doesNotContain(run.toString());
			Path journal = Path.of(handoff.path("journal").stringValue());
			requireThat(journal, "journal").isRegularFile();
			String journalContents = Files.readString(journal);
			requireThat(journalContents, "journalContents").contains("\"purpose\" : \"prompt-evaluation\"").
				contains("\"terminal_decision\" : \"FAIL\"").
				contains("\"failure_class\" : \"prompt-or-certification\"").
				contains("\"execution_context_sha256\" : ").
				contains("\"runner_runtime_sha256\" : \"runner-runtime-hash\"").
				contains("\"assembled_prompt_route_sha256\" : \"prompt-route-hash\"").
				contains("\"evidence_compaction\" : {").
				contains(retainedRun.toString());
		}
	}
}
