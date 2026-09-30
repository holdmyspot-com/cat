package io.github.cowwoc.cat.common.optimizer.contract;

import java.io.IOException;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses a native evaluator response under its frozen machine-readable schema. */
public final class EvaluatorResponseParser
{
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).
		enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	/** Creates the parser. */
	public EvaluatorResponseParser()
	{
	}

	/**
	 * Parses one complete evaluator response.
	 *
	 * @param responseText native evaluator's complete final text
	 * @param schema frozen result schema
	 * @return validated semantic judgment
	 * @throws IOException if the response does not match the frozen schema
	 */
	public EvaluatorResponse parse(String responseText, EvaluatorResultSchema schema) throws IOException
	{
		Objects.requireNonNull(responseText, "responseText");
		Objects.requireNonNull(schema, "schema");
		JsonNode root;
		try
		{
			root = JSON.readTree(responseText);
		}
		catch (RuntimeException failure)
		{
			throw new IOException("Evaluator response is not one valid JSON value", failure);
		}
		if (root == null || !root.isObject() || root.size() != 2)
			throw new IOException("Evaluator response must contain exactly label and reason");
		JsonNode labelNode = root.get("label");
		JsonNode reasonNode = root.get("reason");
		if (labelNode == null || reasonNode == null || !labelNode.isString() || !reasonNode.isString())
			throw new IOException("Evaluator label and reason must be strings");
		EvaluatorLabel label;
		try
		{
			label = EvaluatorLabel.valueOf(labelNode.stringValue());
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Evaluator label is outside the frozen result schema", failure);
		}
		String reason = reasonNode.stringValue();
		if (reason.isBlank())
			throw new IOException("Evaluator reason must not be blank");
		return new EvaluatorResponse(label, reason);
	}
}
