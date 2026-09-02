package io.github.cowwoc.cat.common.optimization.evaluator;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

/** Tests deterministic assertion evaluation at the typed evidence boundary. */
public final class DeterministicAssertionEvaluatorTest
{
	/** Creates the test fixture. */
	public DeterministicAssertionEvaluatorTest()
	{
	}

	/** Verifies a registered check returns one cited binary result. */
	@Test
	public void evaluatesRegisteredCheck()
	{
		DeterministicAssertionEvaluator evaluator = evaluator(request ->
			new DeterministicAssertionEvaluator.Result(DeterministicAssertionEvaluator.Verdict.PASS,
				Set.of(request.evidence().getFirst().identifier())));

		DeterministicAssertionEvaluator.Result result = evaluator.evaluate("check-clear", "assert-clear",
			List.of(evidence("output", new byte[]{1})));

		requireThat(result.verdict(), "verdict").isEqualTo(DeterministicAssertionEvaluator.Verdict.PASS);
		requireThat(result.citedEvidenceIdentifiers(), "citations").containsExactly(Set.of("output"));
	}

	/** Verifies checks cannot be selected outside the frozen registry. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unknown deterministic check: unknown.*")
	public void rejectsUnknownCheck()
	{
		evaluator(request -> new DeterministicAssertionEvaluator.Result(DeterministicAssertionEvaluator.Verdict.PASS,
			Set.of("output"))).evaluate("unknown", "assert-clear", List.of(evidence("output", new byte[]{1})));
	}

	/** Verifies a check cannot cite evidence outside its typed request. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Undeclared evidence citation: hidden.*")
	public void rejectsUndeclaredCitation()
	{
		evaluator(request -> new DeterministicAssertionEvaluator.Result(DeterministicAssertionEvaluator.Verdict.FAIL,
			Set.of("hidden"))).evaluate("check-clear", "assert-clear", List.of(evidence("output", new byte[]{1})));
	}

	/** Verifies required evidence cannot be omitted. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*requires retained evidence.*")
	public void rejectsMissingEvidence()
	{
		evaluator(request -> new DeterministicAssertionEvaluator.Result(DeterministicAssertionEvaluator.Verdict.PASS,
			Set.of("output"))).evaluate("check-clear", "assert-clear", List.of());
	}

	/** Verifies one untrusted evidence field cannot exceed the frozen limit. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Evidence field exceeds the byte limit: output.*")
	public void rejectsOversizedEvidence()
	{
		evaluator(request -> new DeterministicAssertionEvaluator.Result(DeterministicAssertionEvaluator.Verdict.PASS,
			Set.of("output"))).evaluate("check-clear", "assert-clear",
			List.of(evidence("output", new byte[EvaluatorProtocol.MAXIMUM_EVIDENCE_FIELD_BYTES + 1])));
	}

	/**
	 * Creates one evaluator with the supplied check.
	 *
	 * @param check the registered check
	 * @return the evaluator
	 */
	private static DeterministicAssertionEvaluator evaluator(DeterministicAssertionEvaluator.Check check)
	{
		return new DeterministicAssertionEvaluator(List.of(
			new DeterministicAssertionEvaluator.RegisteredCheck("check-clear", check)));
	}

	/**
	 * Creates one untrusted evidence field.
	 *
	 * @param identifier the field identifier
	 * @param content the exact bytes
	 * @return the evidence field
	 */
	private static EvaluatorProtocol.Evidence evidence(String identifier, byte[] content)
	{
		return new EvaluatorProtocol.Evidence(identifier, EvaluatorProtocol.EvidenceKind.EXECUTOR_OUTPUT, content);
	}
}
