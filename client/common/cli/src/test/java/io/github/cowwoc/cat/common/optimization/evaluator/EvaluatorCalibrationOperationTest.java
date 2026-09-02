package io.github.cowwoc.cat.common.optimization.evaluator;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests durable blinded evaluator-corpus execution and protocol selection.
 */
public final class EvaluatorCalibrationOperationTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public EvaluatorCalibrationOperationTest()
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
	 * Verifies every mandatory control is evaluated twice per protocol and an exact retry spends nothing.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void executesAndRetainsCalibration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluator-calibration-operation-"));
			EvaluatorCalibrationOperation.Corpus corpus = corpus(root);
			EvaluatorPolicy policy = new EvaluatorPolicy("evaluator-policy-v1", 0.95, 0.95, 0.05, 0.05,
				0.01, 2.5);
			EvaluatorProtocol single = protocol("single-v1", EvaluatorProtocol.Aggregation.SINGLE);
			EvaluatorProtocol adjudicated = protocol("adjudicated-v1",
				EvaluatorProtocol.Aggregation.TWO_PLUS_TIEBREAKER);
			AtomicInteger invocations = new AtomicInteger();
			EvaluatorCalibrationOperation.ProtocolRunner runner = request ->
			{
				invocations.incrementAndGet();
				long tokens = 100;
				if (request.protocol().aggregation() == EvaluatorProtocol.Aggregation.TWO_PLUS_TIEBREAKER)
					tokens = 220;
				int caseNumber = Integer.parseInt(request.evidence().identifier().substring("case-".length()));
				EvaluatorProtocol.Verdict expected = EvaluatorProtocol.Verdict.FAIL;
				if (caseNumber % 2 == 0)
					expected = EvaluatorProtocol.Verdict.PASS;
				return new EvaluatorCalibrationOperation.Decision(expected, tokens,
					Duration.ofMillis(10));
			};

			EvaluatorCalibrationDocument.Loaded first = EvaluatorCalibrationOperation.execute(scope.json(), root,
				policy, single, adjudicated, corpus, runner);
			EvaluatorCalibrationDocument.Loaded retry = EvaluatorCalibrationOperation.execute(scope.json(), root,
				policy, single, adjudicated, corpus, runner);

			requireThat(first.selection().selection().protocol(), "selectedProtocol").
				isEqualTo(EvaluatorPolicy.ProtocolKind.SINGLE);
			requireThat(retry.fingerprint(), "retainedFingerprint").isEqualTo(first.fingerprint());
			requireThat(invocations.get(), "runnerInvocations").isEqualTo(36);
		}
	}

	/**
	 * Creates one corpus containing every mandatory control and both expected verdicts.
	 *
	 * @param root the fixture root
	 * @return the frozen corpus
	 * @throws IOException if case evidence cannot be written or frozen
	 */
	private static EvaluatorCalibrationOperation.Corpus corpus(Path root) throws IOException
	{
		List<EvaluatorCalibrationOperation.CorpusCase> cases = new ArrayList<>();
		int index = 0;
		for (EvaluatorPolicy.Control control : EvaluatorPolicy.Control.values())
		{
			++index;
			Path evidence = Files.writeString(root.resolve("case-" + index + ".json"), "{\"case\":" + index + "}");
			EvaluatorProtocol.Verdict expected = EvaluatorProtocol.Verdict.FAIL;
			if (index % 2 == 0)
				expected = EvaluatorProtocol.Verdict.PASS;
			cases.add(new EvaluatorCalibrationOperation.CorpusCase("case-" + index, evidence, expected, control));
		}
		return EvaluatorCalibrationOperation.freezeCorpus(cases);
	}

	/**
	 * Creates one frozen evaluator protocol.
	 *
	 * @param identifier the protocol identifier
	 * @param aggregation the protocol aggregation form
	 * @return the protocol
	 */
	private static EvaluatorProtocol protocol(String identifier, EvaluatorProtocol.Aggregation aggregation)
	{
		HarnessModelProfile profile = new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH);
		String prompt = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
		String rubric = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
		if (aggregation == EvaluatorProtocol.Aggregation.SINGLE)
			return EvaluatorProtocol.single(identifier, profile, prompt, rubric, 64 * 1024);
		return EvaluatorProtocol.adjudicated(identifier, profile, prompt, rubric, 64 * 1024);
	}
}
