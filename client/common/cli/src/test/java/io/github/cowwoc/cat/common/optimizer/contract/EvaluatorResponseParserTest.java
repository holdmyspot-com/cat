package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import org.testng.annotations.Test;

/** Tests the frozen evaluator result boundary. */
public final class EvaluatorResponseParserTest
{
	/** Creates the test fixture. */
	public EvaluatorResponseParserTest()
	{
	}

	/**
	 * Exact supported labels retain the evaluator's explanation.
	 *
	 * @throws IOException if a valid result cannot be parsed
	 */
	@Test
	public void parsesExactSupportedResponses() throws IOException
	{
		EvaluatorResponseParser parser = new EvaluatorResponseParser();
		EvaluatorResultSchema schema = EvaluatorResultSchema.LABEL_AND_REASON_JSON_V1;
		EvaluatorResponse compliant = parser.parse("{\"label\":\"COMPLIANT\",\"reason\":\"observed\"}", schema);
		requireThat(compliant.label(), "compliantLabel").isEqualTo(EvaluatorLabel.COMPLIANT);
		requireThat(compliant.reason(), "compliantReason").isEqualTo("observed");
		EvaluatorResponse noncompliant = parser.parse(
			"{\"label\":\"NONCOMPLIANT\",\"reason\":\"missing effect\"}", schema);
		requireThat(noncompliant.label(), "noncompliantLabel").isEqualTo(EvaluatorLabel.NONCOMPLIANT);
	}

	/**
	 * Another written response does not become a semantic compliance outcome.
	 */
	@Test
	public void rejectsMalformedOrDifferentResponses()
	{
		EvaluatorResponseParser parser = new EvaluatorResponseParser();
		String[] invalid = {
			"{\"label\":\"COMPLIANT\",\"reason\":\"observed\",\"extra\":1}",
			"{\"label\":\"COMPLIANT\",\"label\":\"NONCOMPLIANT\",\"reason\":\"observed\"}",
			"{\"label\":\"COMPLIANT\",\"reason\":\"observed\"} true",
			"{\"label\":\"compliant\",\"reason\":\"observed\"}",
			"{\"label\":\"COMPLIANT\",\"reason\":\" \"}",
			"{\"label\":\"COMPLIANT\",\"reason\":0}",
			"```json\n{\"label\":\"COMPLIANT\",\"reason\":\"observed\"}\n```"
		};
		for (String response : invalid)
		{
			try
			{
				parser.parse(response, EvaluatorResultSchema.LABEL_AND_REASON_JSON_V1);
				throw new AssertionError("Malformed evaluator response was accepted: " + response);
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "invalidEvaluatorResponse").contains("Evaluator");
			}
		}
	}
}
