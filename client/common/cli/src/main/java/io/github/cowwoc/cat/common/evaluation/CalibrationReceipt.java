package io.github.cowwoc.cat.common.evaluation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.PromptEvaluation;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates retained semantic-control calibration receipts.
 */
public final class CalibrationReceipt
{
	/**
	 * Prevents instantiation.
	 */
	private CalibrationReceipt()
	{
	}

	/**
	 * Verifies that a retained receipt still proves calibration for the supplied immutable inputs.
	 *
	 * @param receipt the prior retained control receipt
	 * @param suitePath the immutable suite
	 * @param planPath the immutable execution plan
	 * @throws IOException if the receipt no longer proves the same calibration result
	 */
	public static void verify(Path receipt, Path suitePath, Path planPath) throws IOException
	{
		ObjectNode completed = PromptEvaluation.object(PromptEvaluation.read(receipt), "semantic-control receipt");
		if (!PromptEvaluation.text(completed, "status", receipt).equals("passed"))
			throw new IOException("Semantic-control receipt is not passing: " + receipt);
		if (!PromptEvaluation.text(completed, "suite_sha256", receipt).equals(PromptEvaluation.suiteFingerprint(suitePath)))
			throw new IOException("Semantic-control receipt was created for a different suite: " + receipt);
		if (!PromptEvaluation.text(completed, "execution_plan_sha256", receipt).equals(PromptEvaluation.sha256(planPath)))
			throw new IOException("Semantic-control receipt was created for a different execution plan: " + receipt);
		Path verdicts = receipt.getParent().resolve(PromptEvaluation.text(completed, "verdicts", receipt)).normalize();
		if (!Files.isRegularFile(verdicts, NOFOLLOW_LINKS) ||
			!PromptEvaluation.text(completed, "verdicts_sha256", receipt).equals(PromptEvaluation.sha256(verdicts)))
		{
			throw new IOException("Semantic-control receipt no longer has its retained verdicts: " + receipt);
		}
	}

	/**
	 * Validates every independent reviewer verdict against its declared control fixture.
	 *
	 * @param controls the evaluator-generated control cases
	 * @param verdicts the independent reviewer's verdict map
	 * @param verdictPath the retained verdict-map path
	 * @throws IOException if a verdict disagrees with the declared control
	 */
	public static void validate(ArrayNode controls, ObjectNode verdicts, Path verdictPath) throws IOException
	{
		for (JsonNode node : controls)
		{
			ObjectNode control = PromptEvaluation.object(node, "semantic control");
			String controlId = PromptEvaluation.text(control, "id", verdictPath);
			ObjectNode verdict = PromptEvaluation.object(verdicts.path(controlId),
				"semantic-control verdict " + controlId);
			ObjectNode actual = PromptEvaluation.object(verdict.path("assertions"),
				"semantic-control assertions " + controlId);
			ObjectNode expected = PromptEvaluation.object(control.path("expected_assertions"),
				"semantic-control expectations " + controlId);
			java.util.Set<String> actualIds = new java.util.HashSet<>();
			actual.propertyNames().forEach(actualIds::add);
			java.util.Set<String> expectedIds = new java.util.HashSet<>();
			expected.propertyNames().forEach(expectedIds::add);
			if (!actualIds.equals(expectedIds))
				throw new IOException("Semantic-control verdict assertions do not match the control contract: " + controlId);
			for (String assertionId : expectedIds)
			{
				String expectedStatus = PromptEvaluation.text(expected, assertionId, verdictPath);
				ObjectNode assertion = PromptEvaluation.object(actual.path(assertionId),
					"semantic-control assertion " + assertionId);
				String actualStatus = PromptEvaluation.text(assertion, "status", verdictPath);
				PromptEvaluation.text(assertion, "evidence", verdictPath);
				if (!actualStatus.equals(expectedStatus))
					throw new IOException("Semantic-control verdict disagrees for " + controlId + "/" + assertionId +
						": expected " + expectedStatus + " but observed " + actualStatus);
			}
		}
	}
}
