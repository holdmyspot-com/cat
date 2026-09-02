package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.runner.PromptInput;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;
import io.github.cowwoc.cat.common.optimization.development.FormalPromptTestScheduler;
import io.github.cowwoc.cat.common.optimization.development.PromptTestGenerationPolicy;
import io.github.cowwoc.cat.common.optimization.development.PromptTestPoolPartition;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests one scheduled formal trial through the typed harness and evaluator boundaries.
 */
public final class FormalTrialOperationTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public FormalTrialOperationTest()
	{
	}

	/**
	 * Closes the JSON scope after tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies one selected family executes through the Luna/medium harness profile, records its evaluator outcome, and
	 * reuses the completed operation without another model or evaluator invocation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void executesAndReusesOneFormalTrial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-trial-operation-"));
			Path policyPath = Files.writeString(root.resolve("policy.json"),
				FamilySequentialPolicyDocumentTest.policyJson(""));
			FamilySequentialPolicyDocument.Loaded policy = FamilySequentialPolicyDocument.read(scope.json(), policyPath);
			Path allowances = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(scope.json(), allowances, "lineage-a", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(scope.json(), allowances,
				"candidate-a", Instant.parse("2026-09-01T11:00:00Z"));
			Path trials = root.resolve("trials.json");
			FormalTrialLedger.initialize(scope.json(), trials, new FormalTrialLedger.Identity("candidate-a",
				fingerprint('f'), fingerprint('a'), fingerprint('b'), fingerprint('c'), policy.fingerprint(),
				assignment.entryFingerprint()), List.of("family-a"));
			Path evidence = Files.createDirectory(root.resolve("evidence"));
			FormalPromptTestScheduler.ScheduledPromptTest promptTest = scheduledPromptTest(7);
			HarnessEvaluationRequest harnessRequest = new HarnessEvaluationRequest(root.resolve("codex-runner"),
				root.resolve("artifact"), root.resolve("project"),
				new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM),
				PromptInput.forPrompt(scope.json(), promptTest.promptTest().renderedPrompt()),
				root.resolve("transcript.jsonl"), root.resolve("runner-receipt.json"), false, List.of(evidence));
			FormalTrialOperation.Request request = new FormalTrialOperation.Request("formal-operation-a", promptTest,
				harnessRequest);
			AtomicInteger runnerInvocations = new AtomicInteger();
			AtomicInteger evaluatorInvocations = new AtomicInteger();
			Clock firstClock = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);
			FormalTrialOperation.Result first = FormalTrialOperation.execute(scope.json(), firstClock, allowances, trials,
				policy, request, ignored ->
				{
					runnerInvocations.incrementAndGet();
					Path retainedProject = Files.createDirectory(root.resolve("retained-project"));
					Path rawResult = Files.writeString(root.resolve("raw-result.jsonl"), "completed\n");
					return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(retainedProject, rawResult), 10, 20);
				}, execution ->
				{
					evaluatorInvocations.incrementAndGet();
					return new FormalTrialOperation.Evaluation(FormalTrialLedger.Verdict.PASS, fingerprint('e'));
				});
			Clock retryClock = Clock.fixed(Instant.parse("2026-09-01T13:00:00Z"), ZoneOffset.UTC);
			FormalTrialOperation.Result retry = FormalTrialOperation.execute(scope.json(), retryClock, allowances, trials,
				policy, request, ignored ->
				{
					runnerInvocations.incrementAndGet();
					throw new AssertionError("Retained operation invoked the runner again");
				}, execution ->
				{
					evaluatorInvocations.incrementAndGet();
					throw new AssertionError("Retained operation invoked the evaluator again");
				});

			requireThat(runnerInvocations.get(), "runnerInvocations").isEqualTo(1);
			requireThat(evaluatorInvocations.get(), "evaluatorInvocations").isEqualTo(1);
			requireThat(retry.retainedOutcome(), "retainedOutcome").isEqualTo(first.retainedOutcome());
			requireThat(retry.decision().action(), "decision").isEqualTo(
				FamilySequentialDecision.Action.SAMPLE_FAMILY);
			requireThat(FormalTrialLedger.read(scope.json(), trials).entryCount(), "entryCount").isEqualTo(1);
		}
	}

	/**
	 * Verifies a harness request cannot substitute another prompt for the scheduler-selected formal promptTest.
	 *
	 * @throws IOException for the expected trusted-input rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Formal trial request changed the trusted prompt input.*")
	public void rejectsChangedFormalPrompt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-trial-input-"));
			Path policyPath = Files.writeString(root.resolve("policy.json"),
				FamilySequentialPolicyDocumentTest.policyJson(""));
			FamilySequentialPolicyDocument.Loaded policy = FamilySequentialPolicyDocument.read(scope.json(), policyPath);
			Path allowances = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(scope.json(), allowances, "lineage-a", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(scope.json(), allowances,
				"candidate-a", Instant.parse("2026-09-01T11:00:00Z"));
			Path trials = root.resolve("trials.json");
			FormalTrialLedger.initialize(scope.json(), trials, new FormalTrialLedger.Identity("candidate-a",
				fingerprint('f'), fingerprint('a'), fingerprint('b'), fingerprint('c'), policy.fingerprint(),
				assignment.entryFingerprint()), List.of("family-a"));
			FormalPromptTestScheduler.ScheduledPromptTest promptTest = scheduledPromptTest(7);
			HarnessEvaluationRequest harnessRequest = new HarnessEvaluationRequest(root.resolve("codex-runner"),
				root.resolve("artifact"), root.resolve("project"),
				new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM),
				PromptInput.forPrompt(scope.json(), "Different task"), root.resolve("transcript.jsonl"),
				root.resolve("runner-receipt.json"), false, List.of());
			FormalTrialOperation.Request request = new FormalTrialOperation.Request("formal-operation-a", promptTest,
				harnessRequest);

			FormalTrialOperation.execute(scope.json(), Clock.systemUTC(), allowances, trials, policy, request,
				ignored ->
				{
					throw new AssertionError("Changed formal prompt reached the runner");
				}, ignored -> new FormalTrialOperation.Evaluation(FormalTrialLedger.Verdict.PASS, fingerprint('e')));
		}
	}

	/**
	 * Creates one formal draw with its exact cross-pool identity.
	 *
	 * @param seed the immutable draw seed
	 * @return the scheduled promptTest
	 */
	private static FormalPromptTestScheduler.ScheduledPromptTest scheduledPromptTest(long seed)
	{
		PromptTestGenerationPolicy policy = new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.FORMAL, "family-a",
			"distribution-a", "uniform-v1", "generator-a", "formal-a", "Complete the task in {{tone}} style.",
			Map.of("tone", List.of("plain", "direct", "brief")), Set.of(), 10);
		PromptTestGenerationPolicy.PromptTest promptTest = policy.generate(seed);
		PromptTestPoolPartition.Identity identity = new PromptTestPoolPartition.Identity(
			PromptTestPoolPartition.Pool.FORMAL, promptTest.instanceIdentifier(),
			promptTest.seedNamespace() + ":FORMAL:" + Long.toUnsignedString(seed), promptTest.contentFingerprint(),
			"formal-run-" + Long.toUnsignedString(seed));
		return new FormalPromptTestScheduler.ScheduledPromptTest(promptTest, identity);
	}

	/**
	 * Creates a lowercase SHA-256 fixture value.
	 *
	 * @param value the repeated hexadecimal character
	 * @return the 64-character fingerprint
	 */
	private static String fingerprint(char value)
	{
		return Character.toString(value).repeat(64);
	}
}
