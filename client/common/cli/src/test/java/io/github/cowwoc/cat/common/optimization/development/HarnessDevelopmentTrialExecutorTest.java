package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.optimization.certification.FormalTrialLedger;
import io.github.cowwoc.cat.common.optimization.certification.FormalTrialOperation;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the harness-backed adapter for one development candidate trial.
 */
public final class HarnessDevelopmentTrialExecutorTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public HarnessDevelopmentTrialExecutorTest()
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
	 * Verifies the exact candidate is installed, evaluated, and retained without duplicate execution on retry.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void executesRetainedCandidateTrial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-trial-"));
			Path artifact = artifact(root);
			DevelopmentComparisonOperation.CandidateReference candidate = candidate(root);
			PromptTestGenerationPolicy.PromptTest promptTest = promptTestPolicy().generate(7);
			HarnessModelProfile profile = new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM);
			AtomicInteger runnerInvocations = new AtomicInteger();
			HarnessDevelopmentTrialExecutor executor = new HarnessDevelopmentTrialExecutor(scope.json(),
				root.resolve("work"), artifact, profile, (selectedProfile, retainedArtifact, selectedPromptTest, position,
					receiptRoot) ->
				{
					requireThat(Files.readString(retainedArtifact.resolve("rules/common/rule.md")), "installedCandidate").
						isEqualTo("Optimized rule");
					return request(selectedProfile, retainedArtifact, selectedPromptTest, receiptRoot);
				}, request ->
				{
					runnerInvocations.incrementAndGet();
					return execution(request);
				}, trial -> retained -> new FormalTrialOperation.Evaluation(FormalTrialLedger.Verdict.PASS,
					"b".repeat(64)));
			DevelopmentComparisonOperation.TrialRequest request =
				new DevelopmentComparisonOperation.TrialRequest("trial-1", candidate, promptTest, 1,
					root.resolve("receipt"));

			DevelopmentComparisonOperation.TrialOutcome first = executor.execute(request);
			DevelopmentComparisonOperation.TrialOutcome second = executor.execute(request);

			requireThat(first.passed(), "passed").isTrue();
			requireThat(first.evidenceFingerprint(), "evidenceFingerprint").matches("[0-9a-f]{64}");
			requireThat(second, "retainedOutcome").isEqualTo(first);
			requireThat(runnerInvocations.get(), "runnerInvocations").isEqualTo(1);
		}
	}

	/**
	 * Verifies a harness request factory cannot substitute a different task for the frozen promptTest.
	 *
	 * @throws IOException for the expected request-binding rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*changed the frozen prompt-test input.*")
	public void rejectsChangedFrozenPromptTest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-trial-input-"));
			Path artifact = artifact(root);
			DevelopmentComparisonOperation.CandidateReference candidate = candidate(root);
			PromptTestGenerationPolicy.PromptTest promptTest = promptTestPolicy().generate(7);
			HarnessModelProfile profile = new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM);
			HarnessDevelopmentTrialExecutor executor = new HarnessDevelopmentTrialExecutor(scope.json(),
				root.resolve("work"), artifact, profile,
				(selectedProfile, retainedArtifact, selectedPromptTest, position, receiptRoot) ->
					request(selectedProfile, retainedArtifact, changedPromptTest(selectedPromptTest), receiptRoot),
				this::execution, trial -> retained -> new FormalTrialOperation.Evaluation(
					FormalTrialLedger.Verdict.PASS, "b".repeat(64)));
			DevelopmentComparisonOperation.TrialRequest request = new DevelopmentComparisonOperation.TrialRequest(
				"trial-1", candidate, promptTest, 1, root.resolve("receipt"));

			executor.execute(request);
		}
	}

	/** Verifies unsupported optimizer profiles are rejected at construction. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported OPTIMIZER_EXECUTOR model profile.*")
	public void rejectsUnsupportedOptimizerProfile()
	{
		new HarnessDevelopmentTrialExecutor(scope.json(), Path.of("work"), Path.of("artifact"),
			new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH), null, null, null);
	}

	/**
	 * Creates a promptTest whose rendered prompt differs from the selected promptTest.
	 *
	 * @param selectedPromptTest the selected promptTest
	 * @return the changed promptTest
	 */
	private static PromptTestGenerationPolicy.PromptTest changedPromptTest(
		PromptTestGenerationPolicy.PromptTest selectedPromptTest)
	{
		return new PromptTestGenerationPolicy.PromptTest(selectedPromptTest.pool(), selectedPromptTest.familyIdentifier(),
			selectedPromptTest.distributionIdentifier(), selectedPromptTest.samplerIdentifier(),
			selectedPromptTest.generatorIdentifier(), selectedPromptTest.seedNamespace(),
			selectedPromptTest.instanceIdentifier(), selectedPromptTest.seed(), selectedPromptTest.selectedValues(),
			"Different task", "f".repeat(64));
	}

	/**
	 * Creates a minimal packaged runner artifact.
	 *
	 * @param root the fixture root
	 * @return the artifact
	 * @throws IOException if it cannot be written
	 */
	private static Path artifact(Path root) throws IOException
	{
		Path artifact = Files.createDirectories(root.resolve("artifact"));
		Path runner = artifact.resolve("client/bin/runner");
		Files.createDirectories(runner.getParent());
		Files.writeString(runner, "runner");
		Path rules = artifact.resolve("rules/common/rule.md");
		Files.createDirectories(rules.getParent());
		Files.writeString(rules, "Original rule");
		RunnerRuntimeManifest.write(artifact);
		return artifact;
	}

	/**
	 * Creates one immutable optimized candidate.
	 *
	 * @param root the fixture root
	 * @return the candidate reference
	 * @throws IOException if it cannot be materialized
	 */
	private DevelopmentComparisonOperation.CandidateReference candidate(Path root) throws IOException
	{
		Path source = Files.createDirectories(root.resolve("source/common"));
		Files.writeString(source.resolve("rule.md"), "Optimized rule");
		Path bundle = root.resolve("candidate");
		CandidateBundle.Candidate materialized = CandidateBundle.materialize(scope.json(), source.getParent(), bundle,
			"common/rule.md", List.of());
		return new DevelopmentComparisonOperation.CandidateReference("candidate", bundle, materialized.sha256());
	}

	/**
	 * Creates one finite development prompt-test policy.
	 *
	 * @return the policy
	 */
	private static PromptTestGenerationPolicy promptTestPolicy()
	{
		return new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.DEVELOPMENT, "clarity", "distribution-v1",
			"uniform-v1", "generator-v1", "development", "Explain in a {{tone}} tone.",
			Map.of("tone", List.of("direct", "plain", "brief")), Set.of(), 10);
	}

	/**
	 * Creates one typed harness request.
	 *
	 * @param profile the exact profile
	 * @param artifact the retained candidate artifact
	 * @param promptTest the promptTest
	 * @param receiptRoot the trial evidence root
	 * @return the request
	 * @throws IOException if the project cannot be created
	 */
	private HarnessEvaluationRequest request(HarnessModelProfile profile, Path artifact,
		PromptTestGenerationPolicy.PromptTest promptTest, Path receiptRoot) throws IOException
	{
		Path project = Files.createDirectories(receiptRoot.resolve("project"));
		ObjectNode turn = scope.json().createObjectNode();
		turn.put("type", "turn");
		turn.put("prompt", promptTest.renderedPrompt());
		String input = scope.json().writeValueAsString(turn) + "\n{\"type\":\"complete\"}\n";
		return new HarnessEvaluationRequest(receiptRoot.resolve("runner"), artifact, project, profile, input,
			receiptRoot.resolve("transcript.jsonl"), receiptRoot.resolve("receipt.json"), false, List.of());
	}

	/**
	 * Creates retained harness evidence.
	 *
	 * @param request the request
	 * @return the execution
	 * @throws IOException if evidence cannot be written
	 */
	private HarnessEvaluationExecution execution(HarnessEvaluationRequest request) throws IOException
	{
		Files.writeString(request.transcript(), "{\"executor\":true}\n");
		return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(request.project(), request.transcript()),
			10, 20);
	}
}
