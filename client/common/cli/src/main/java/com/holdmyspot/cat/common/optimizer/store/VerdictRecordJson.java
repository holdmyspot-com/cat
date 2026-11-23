package com.holdmyspot.cat.common.optimizer.store;

import static com.holdmyspot.cat.common.optimizer.store.JsonFields.VERSION;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.field;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.nullable;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireVersion;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.stringValue;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.text;

import com.holdmyspot.cat.common.optimizer.execution.AssertionVerdict;
import com.holdmyspot.cat.common.optimizer.execution.VerdictRecord;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the file that holds one stored verdict.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class VerdictRecordJson
{
	private static final int FORMAT_VERSION = 1;
	private static final String JUDGE = "judge";

	/**
	 * Prevents instantiation.
	 */
	private VerdictRecordJson()
	{
	}

	/**
	 * Encodes a stored verdict.
	 *
	 * @param json the JSON mapper
	 * @param record the verdict
	 * @return the document
	 */
	static ObjectNode write(JsonMapper json, VerdictRecord record)
	{
		ObjectNode root = json.createObjectNode();
		root.put(VERSION, FORMAT_VERSION);
		AssertionVerdict verdict = record.verdict();
		root.put("assertion", verdict.assertion());
		root.put("verdict", EnumNames.lowerCase(verdict.verdict()));
		root.put("behavior", verdict.behavior());
		root.put("rationale", verdict.rationale());
		root.putObject(JUDGE).
			put("model", record.judgeModel()).
			put("effort", record.judgeEffort());
		root.put("trial", record.trial());
		return root;
	}

	/**
	 * Decodes a stored verdict.
	 *
	 * @param root the document
	 * @return the verdict
	 * @throws IllegalArgumentException if the document is malformed
	 */
	static VerdictRecord read(JsonNode root)
	{
		requireVersion(root, FORMAT_VERSION);
		JsonNode behaviorNode = nullable(root, "behavior");
		String behavior;
		if (behaviorNode == null)
			behavior = null;
		else
			behavior = stringValue(behaviorNode, "behavior");
		AssertionVerdict verdict = new AssertionVerdict(text(root, "assertion"), behavior, text(root, "rationale"));
		String expected = EnumNames.lowerCase(verdict.verdict());
		String stored = text(root, "verdict");
		if (!stored.equals(expected))
			throw new IllegalArgumentException("verdict: is " + stored + ", but the behavior makes it " + expected);
		JsonNode judge = field(root, JUDGE);
		return new VerdictRecord(verdict, text(judge, "model"), text(judge, "effort"), text(root, "trial"));
	}
}
