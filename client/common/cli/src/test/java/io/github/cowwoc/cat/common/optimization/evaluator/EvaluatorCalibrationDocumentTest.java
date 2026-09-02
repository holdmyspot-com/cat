package io.github.cowwoc.cat.common.optimization.evaluator;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests strict persisted evaluator calibration and protocol selection evidence.
 */
public final class EvaluatorCalibrationDocumentTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public EvaluatorCalibrationDocumentTest()
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
	 * Verifies persisted reports reconstruct the exact cost-adjusted selected protocol and passing approval.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void loadsSelectedProtocolApproval() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluator-calibration-"));
			Path document = Files.writeString(root.resolve("calibration.json"), calibrationJson(""));

			EvaluatorCalibrationDocument.Loaded loaded = EvaluatorCalibrationDocument.read(scope.json(), document);

			requireThat(loaded.selection().selection().protocol(), "protocol").
				isEqualTo(EvaluatorPolicy.ProtocolKind.SINGLE);
			loaded.selection().approval().orElseThrow().verifyFor(loaded.selectedProtocol().orElseThrow());
			requireThat(loaded.selectedProtocol().orElseThrow().identifier(), "protocolIdentifier").
				isEqualTo("semantic-single-v1");
		}
	}

	/**
	 * Verifies undeclared persisted fields are rejected instead of silently changing the calibration contract.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*unsupported or missing fields.*")
	public void rejectsUnknownField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluator-calibration-unknown-"));
			Path document = Files.writeString(root.resolve("calibration.json"),
				calibrationJson(",\n  \"unexpected\": true"));

			EvaluatorCalibrationDocument.read(scope.json(), document);
		}
	}

	/** Verifies unsupported semantic evaluator profiles are rejected while decoding. */
	@Test
	public void rejectsUnsupportedEvaluatorProfile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluator-calibration-profile-"));
			Path document = Files.writeString(root.resolve("calibration.json"), calibrationJson("").replace(
				"reasoning_effort\": \"high\"", "reasoning_effort\": \"medium\""));
			try
			{
				EvaluatorCalibrationDocument.read(scope.json(), document);
				throw new AssertionError("Expected unsupported evaluator profile");
			}
			catch (IOException failure)
			{
				Throwable cause = failure;
				while (cause != null && (cause.getMessage() == null ||
					!cause.getMessage().contains("Unsupported SEMANTIC_EVALUATOR model profile")))
					cause = cause.getCause();
				if (cause == null)
					throw failure;
			}
		}
	}

	/**
	 * Returns one complete persisted calibration document.
	 *
	 * @param extraRootField optional root field including its leading comma
	 * @return the JSON document
	 */
	private static String calibrationJson(String extraRootField)
	{
		String controls = "[\"known_pass\",\"known_fail\",\"boundary\",\"ambiguous_evidence\"," +
			"\"prompt_injection\",\"self_preference\",\"position_bias\",\"rubric_manipulation\"," +
			"\"unsupported_evidence\"]";
		String report = "{\"total_decisions\":1000,\"correct_decisions\":980," +
			"\"expected_failing_decisions\":200,\"expected_passing_decisions\":800,\"false_passes\":1," +
			"\"false_fails\":19,\"repeated_pairs\":1000,\"reproducible_pairs\":970," +
			"\"passed_controls\":" + controls + ",\"observed_tokens\":100000," +
			"\"elapsed_nanos\":120000000000}";
		String adjudicatedReport = report.replace("\"correct_decisions\":980", "\"correct_decisions\":985").
			replace("\"false_fails\":19", "\"false_fails\":14").
			replace("\"reproducible_pairs\":970", "\"reproducible_pairs\":975").
			replace("\"observed_tokens\":100000", "\"observed_tokens\":220000");
		return """
			{
			  "schema_version": "evaluator-calibration-v1",
			  "policy": {
			    "identifier": "evaluator-policy-v1",
			    "minimum_accuracy": 0.97,
			    "minimum_reproducibility": 0.95,
			    "maximum_false_pass_rate": 0.01,
			    "maximum_false_fail_rate": 0.05,
			    "minimum_adjudication_accuracy_improvement": 0.01,
			    "maximum_adjudication_token_multiplier": 2.5
			  },
			  "single": {
			    "protocol": {
			      "identifier": "semantic-single-v1",
			      "model": "gpt-5.6-luna",
			      "reasoning_effort": "high",
			      "prompt_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			      "rubric_sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			      "maximum_evidence_bytes": 65536,
			      "aggregation": "single"
			    },
			    "corpus_sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
			    "report": %s
			  },
			  "adjudicated": {
			    "protocol": {
			      "identifier": "semantic-adjudicated-v1",
			      "model": "gpt-5.6-luna",
			      "reasoning_effort": "high",
			      "prompt_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			      "rubric_sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			      "maximum_evidence_bytes": 65536,
			      "aggregation": "two_plus_tiebreaker"
			    },
			    "corpus_sha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
			    "report": %s
			  }%s
			}
			""".formatted(report, adjudicatedReport, extraRootField);
	}
}
