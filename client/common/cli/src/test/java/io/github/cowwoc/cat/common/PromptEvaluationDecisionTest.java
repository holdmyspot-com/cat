package io.github.cowwoc.cat.common;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.record;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;

/**
 * Tests retained prompt-evaluation decision and report behavior.
 */
public final class PromptEvaluationDecisionTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationDecisionTest()
	{
	}

	/**
	 * Verifies one failed trial produces the terminal failed decision before an unnecessary retry.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsFailingTrial() throws IOException
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
			for (int trial = 1; trial <= 3; ++trial)
				for (String caseId : new String[]{"requested-change", "unrelated-request", "edge-condition"})
					record(run, caseId, trial, "failed");

			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));

			requireThat(decision, "decision").contains("\"overall_decision\":\"FAIL\"");
		}
	}

	/**
	 * Verifies the fixed evaluation contract rejects a suite that silently changes its SPRT policy.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*95%/85% SPRT.*")
	public void rejectsDifferentSprtPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path manifest = suite.resolve("suite.json");
			Files.writeString(manifest, Files.readString(manifest).replace("\"reject_pass_rate\": 0.85",
				"\"reject_pass_rate\": 0.80"));

			PromptEvaluation.run(new String[]{"validate-suite", suite.toString()});
		}
	}

	/**
	 * Verifies a terminal record has one stable report whose compliance rate is the confidence-qualified lower bound.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsTerminalComplianceRate() throws IOException
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
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 3, evaluationRunner);

			String result = output(() -> PromptEvaluation.run(new String[]{"report", run.toString()}));
			assertTrue(result.contains("\"decision\":\"FAIL\""));
			assertTrue(result.contains("\"compliance_rate\""));
			assertTrue(result.contains("\"confidence_level\":0.95"));
			assertTrue(result.contains("\"observed_pass_fraction\":0.0"));
		}
	}

	/**
	 * Verifies an SPRT pass remains running until the independently maintained lower confidence bound certifies it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsSprtPassRunningUntilCertified() throws IOException
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
			for (int trial = 1; trial <= 27; ++trial)
				for (String caseId : new String[]{"requested-change", "unrelated-request", "edge-condition"})
					record(run, caseId, trial, "passed");
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			assertTrue(decision.contains("\"overall_decision\":\"RUNNING\""));
			assertTrue(decision.contains("\"sprt_decision\":\"ACCEPT\""));
		}
	}

	/**
	 * Verifies an evaluation fails when perfect remaining runs cannot reach the required certification bound.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void failsWhenCertificationIsUnreachable() throws IOException
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
			for (int trial = 1; trial <= 100; ++trial)
			{
				String status = "failed";
				if (trial <= 90)
					status = "passed";
				for (String caseId : new String[]{"requested-change", "unrelated-request", "edge-condition"})
					record(run, caseId, trial, status);
			}
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			assertTrue(decision.contains("\"overall_decision\":\"FAIL\""));
			assertTrue(decision.contains("\"certification_unreachable\":true"));
		}
	}
}
