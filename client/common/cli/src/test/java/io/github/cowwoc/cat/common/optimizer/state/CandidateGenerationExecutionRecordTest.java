package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Checks generation execution serialization and refusal of evidence from another operation. */
public final class CandidateGenerationExecutionRecordTest
{
	/** Creates the schema test. */
	public CandidateGenerationExecutionRecordTest()
	{
	}

	/**
	 * Serialization preserves the request, attempt, and completion joining one generation execution.
	 *
	 * @throws IOException if execution serialization fails
	 */
	@Test
	public void roundTripsGenerationExecution() throws IOException
	{
		CandidateGenerationExecutionRecord record = new CandidateGenerationExecutionRecord(
			CandidateGenerationExecutionRecord.SCHEMA_VERSION, "campaign",
			new EvidenceRef("candidate-generation-requests", "a".repeat(64)),
			new EvidenceRef("candidate-native-attempts", "b".repeat(64)),
			new EvidenceRef("native-run-completions", "c".repeat(64)));
		JsonMapper json = JsonMapper.builder().build();
		requireThat(json.readValue(json.writeValueAsBytes(record), CandidateGenerationExecutionRecord.class),
			"retainedExecutionIdentity").isEqualTo(record);
	}

	/** A trial assignment cannot substitute for the semantic generation request. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsTrialRequest()
	{
		new CandidateGenerationExecutionRecord(CandidateGenerationExecutionRecord.SCHEMA_VERSION, "campaign",
			new EvidenceRef("trial-assignments", "a".repeat(64)),
			new EvidenceRef("candidate-native-attempts", "b".repeat(64)),
			new EvidenceRef("native-run-completions", "c".repeat(64)));
	}
}
