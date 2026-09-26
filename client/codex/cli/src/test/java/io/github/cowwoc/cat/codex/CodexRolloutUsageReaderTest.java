package io.github.cowwoc.cat.codex;

import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the pinned Codex rollout's per-response output accounting boundary.
 */
public final class CodexRolloutUsageReaderTest
{
	private static final UUID TURN = UUID.fromString("672db61d-dfd4-4aec-90ba-8dbad66e2b22");
	private static final UUID OTHER_TURN = UUID.fromString("7bc42d99-d01a-421b-a4aa-1b77b595fdae");
	private static final String META = """
		{"type":"session_meta","payload":{"session_id":"session-test","cli_version":"0.155.0"}}
		""";

	/**
	 * Creates the TestNG test class.
	 */
	public CodexRolloutUsageReaderTest()
	{
	}

	/**
	 * Sums distinct model responses for the requested root turn and ignores an exact repeated record.
	 *
	 * @throws IOException if the fixture cannot be parsed
	 */
	@Test
	public void countsUniqueResponsesForRootTurn() throws IOException
	{
		String rollout = META + response(TURN, TURN, "resp_first", 100, 100) +
			response(OTHER_TURN, OTHER_TURN, "resp_other", 300, 300) +
			response(TURN, TURN, "resp_second", 40, 140) + response(TURN, TURN, "resp_first", 100, 100);
		CodexRolloutUsageReader.TurnUsage usage = read(rollout, TURN).orElseThrow();
		requireThat(usage.outputTokens(), "outputTokens").isEqualTo(140L);
		requireThat(usage.responseCount(), "responseCount").isEqualTo(2);
		requireThat(usage.harnessVersion(), "harnessVersion").
			isEqualTo(CodexMeteringVersion.V_0_155_0);
	}

	/**
	 * Accepts stable 0.157.0 response usage after checking a completed native rollout.
	 *
	 * @throws IOException if the fixture cannot be parsed
	 */
	@Test
	public void countsStableCodexVersion() throws IOException
	{
		String rollout = META.replace("0.155.0", "0.157.0") +
			response(TURN, TURN, "resp_first", 163, 163) +
			response(TURN, TURN, "resp_second", 280, 443);
		CodexRolloutUsageReader.TurnUsage usage = read(rollout, TURN).orElseThrow();
		requireThat(usage.outputTokens(), "outputTokens").isEqualTo(443L);
		requireThat(usage.responseCount(), "responseCount").isEqualTo(2);
		requireThat(usage.harnessVersion(), "harnessVersion").isEqualTo(CodexMeteringVersion.V_0_157_0);
	}

	/**
	 * Leaves a turn pending when the rollout has no completed response usage record.
	 *
	 * @throws IOException if the fixture cannot be parsed
	 */
	@Test
	public void leavesUnmeasuredTurnPending() throws IOException
	{
		requireThat(read(META, TURN), "usage").isEmpty();
	}

	/**
	 * Rejects a newer rollout version until its usage shape has been checked.
	 *
	 * @throws IOException for the expected unsupported version
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Codex rollout version or session metadata is unsupported")
	public void rejectsUnvalidatedCodexVersion() throws IOException
	{
		read(META.replace("0.155.0", "0.158.0") + response(TURN, TURN, "resp_first", 100, 100), TURN);
	}

	/**
	 * Rejects an incomplete count instead of reporting an estimated zero.
	 *
	 * @throws IOException for the expected incomplete record
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Codex rollout contains an incomplete response usage record")
	public void rejectsMissingOutputCount() throws IOException
	{
		read(META + response(TURN, TURN, "resp_first", 100, 100).replace("\"usage\":{\"output_tokens\":100",
			"\"usage\":{\"input_tokens\":100"),
			TURN);
	}

	/**
	 * Rejects two different counts for one model response.
	 *
	 * @throws IOException for the expected conflicting record
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Codex rollout has conflicting counts for one response")
	public void rejectsChangedResponseCount() throws IOException
	{
		read(META + response(TURN, TURN, "resp_first", 100, 100) +
			response(TURN, TURN, "resp_first", 101, 101), TURN);
	}

	/**
	 * Reconciles each concrete turn independently when responses share a root turn.
	 *
	 * @throws IOException if the fixture cannot be parsed
	 */
	@Test
	public void countsResponsesFromMultipleConcreteTurns() throws IOException
	{
		String rollout = META + response(TURN, TURN, "resp_first", 100, 100) +
			response(TURN, OTHER_TURN, "resp_child", 40, 40);
		CodexRolloutUsageReader.TurnUsage usage = read(rollout, TURN).orElseThrow();
		requireThat(usage.outputTokens(), "outputTokens").isEqualTo(140L);
		requireThat(usage.responseCount(), "responseCount").isEqualTo(2);
	}

	/**
	 * Rejects a response sum that disagrees with Codex's concrete turn total.
	 *
	 * @throws IOException for the expected mismatch
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Codex response counts do not match its turn total")
	public void rejectsMismatchedTurnTotal() throws IOException
	{
		read(META + response(TURN, TURN, "resp_first", 100, 101), TURN);
	}

	/**
	 * Reads one rollout fixture without relying on a native Codex process or a global transcript path.
	 *
	 * @param rollout JSONL fixture
	 * @param turn root turn to measure
	 * @return measured usage, if available
	 * @throws IOException if the fixture is unsupported or malformed
	 */
	private static Optional<CodexRolloutUsageReader.TurnUsage> read(String rollout, UUID turn) throws IOException
	{
		return CodexRolloutUsageReader.readTurn(
			new ByteArrayInputStream(rollout.getBytes(StandardCharsets.UTF_8)), turn);
	}

	/**
	 * Builds one pinned-version response record.
	 *
	 * @param rootTurn root turn containing the response
	 * @param turn concrete turn containing the response
	 * @param responseId unique response ID
	 * @param outputTokens generated output count
	 * @param turnOutputTokens cumulative generated output for the concrete turn
	 * @return one JSONL record
	 */
	private static String response(UUID rootTurn, UUID turn, String responseId, long outputTokens,
		long turnOutputTokens)
	{
		return """
			{"type":"token_usage_record","payload":{"session_id":"session-test","root_turn_id":"%s",
			"turn_id":"%s","response_id":"%s","usage":{"output_tokens":%d},
			"turn_token_usage":{"output_tokens":%d}}}
			""".formatted(rootTurn, turn, responseId, outputTokens, turnOutputTokens);
	}
}
