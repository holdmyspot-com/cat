package io.github.cowwoc.cat.common;

import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.record;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

/**
 * Tests the comparison contract between retained evaluations.
 */
public final class PromptEvaluationComparisonTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationComparisonTest()
	{
	}

	/**
	 * Verifies records from different execution profiles cannot be compared.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*executor_profile differs.*")
	public void rejectsDifferentProfiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path baseline = root.resolve("baseline.json");
			Path candidate = root.resolve("candidate.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), baseline.toString()});
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-b",
				context.toString(), candidate.toString()});
			Path baselinePlan = writeStaticExecutionPlan(root);
			Path candidatePlan = write(root, "candidate-plan.json", Files.readString(baselinePlan).replace(
				"\"executor_profile\":\"executor-profile\"", "\"executor_profile\":\"other-profile\""));
			PromptEvaluation.run(new String[]{"bind-plan", baseline.toString(), baselinePlan.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", candidate.toString(), candidatePlan.toString()});
			PromptEvaluation.run(new String[]{"compare", baseline.toString(), candidate.toString()});
		}
	}

	/**
	 * Verifies a changed executor model cannot retain a stale profile label and remain comparable.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*executor_model differs.*")
	public void rejectsDifferentExecutorModels() throws IOException
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
			Path baselinePlan = writeStaticExecutionPlan(root);
			Path candidatePlan = write(root, "candidate-plan.json", Files.readString(baselinePlan).replace(
				"\"executor_model\":\"model-a\"", "\"executor_model\":\"other-model\""));
			PromptEvaluation.run(new String[]{"bind-plan", baseline.toString(), baselinePlan.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", candidate.toString(), candidatePlan.toString()});
			PromptEvaluation.run(new String[]{"compare", baseline.toString(), candidate.toString()});
		}
	}

	/**
	 * Verifies a comparison rejects records whose validator instruction or available context differs.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*validator_contract_sha256 differs.*")
	public void rejectsDifferentExecutionContexts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path baselineContext = write(root, "baseline-context.md", "First validator instruction\n");
			Path candidateContext = write(root, "candidate-context.md", "Second validator instruction\n");
			Path baseline = root.resolve("baseline.json");
			Path candidate = root.resolve("candidate.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				baselineContext.toString(), baseline.toString()});
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				candidateContext.toString(), candidate.toString()});
			Path plan = writeStaticExecutionPlan(root);
			PromptEvaluation.run(new String[]{"bind-plan", baseline.toString(), plan.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", candidate.toString(), plan.toString()});
			PromptEvaluation.run(new String[]{"compare", baseline.toString(), candidate.toString()});
		}
	}

	/**
	 * Verifies a versioned suite reaches whole-prompt 95% confidence certification and remains comparable.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsComparableAcceptedTrials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path baseline = root.resolve("baseline.json");
			Path candidate = root.resolve("candidate.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), baseline.toString()});
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), candidate.toString()});
			Path plan = writeStaticExecutionPlan(root);
			PromptEvaluation.run(new String[]{"bind-plan", baseline.toString(), plan.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", candidate.toString(), plan.toString()});
			for (int trial = 1; trial <= 88; ++trial)
				for (String caseId : new String[]{"requested-change", "unrelated-request", "edge-condition"})
				{
					record(baseline, caseId, trial, "passed");
					record(candidate, caseId, trial, "passed");
				}
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", candidate.toString()}));
			assertTrue(decision.contains("\"overall_decision\":\"PASS\""));
			assertTrue(Files.readString(candidate).contains("\"decision\""));
			String comparison = output(() -> PromptEvaluation.run(new String[]{"compare", baseline.toString(),
				candidate.toString()}));
			requireThat(comparison, "comparison").contains("\"comparison_contract_sha256\"").
				contains("\"compliance_rate\"").doesNotContain("\"pass_rate\"");
		}
	}

	/**
	 * Verifies a diagnostic comparison names every differing fixed condition without calling the records comparable.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void diagnosesDifferentComparisonFields() throws IOException
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
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "claude", "model-b",
				context.toString(), candidate.toString()});
			Path plan = writeStaticExecutionPlan(root);
			Path candidatePlan = write(root, "candidate-plan.json", Files.readString(plan).replace(
				"\"executor_model\":\"model-a\"", "\"executor_model\":\"model-b\""));
			PromptEvaluation.run(new String[]{"bind-plan", baseline.toString(), plan.toString()});
			PromptEvaluation.run(new String[]{"bind-plan", candidate.toString(), candidatePlan.toString()});

			String result = output(() -> PromptEvaluation.run(new String[]{"compare", baseline.toString(),
				candidate.toString(), "--diagnostic"}));
			requireThat(result, "result").contains("\"comparable\":false").
				contains("\"harness\"").contains("\"executor_model\"");
		}
	}

	/**
	 * Verifies a record cannot join a comparison until the evaluator binds its execution plan and epoch.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*comparison contract field evaluation_epoch differs.*")
	public void rejectsUnboundComparisonRecord() throws IOException
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
			PromptEvaluation.run(new String[]{"compare", baseline.toString(), candidate.toString()});
		}
	}
}
