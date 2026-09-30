package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks turn identity and settlement from parsed native events without a campaign or process fixture. */
public final class ClaudeTurnObservationTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final ClaudeSessionInitialization INITIALIZATION =
		new ClaudeSessionInitialization("session", "launch-alias", Optional.empty());
	private static final String ASSISTANT = """
		{"type":"assistant","session_id":"session","parent_tool_use_id":null,
		 "message":{"role":"assistant","id":"message","model":"parent-model"}}
		""";
	private static final String RESULT = """
		{"type":"result","session_id":"session","uuid":"result-id",
		 "subtype":"success","is_error":false,"result":"answer"}
		""";

	/** Creates the test fixture. */
	public ClaudeTurnObservationTest()
	{
	}

	/**
	 * Joins the native parent model and terminal response while keeping the child model out of that claim.
	 *
	 * @throws IOException if valid native events are rejected
	 */
	@Test
	public void joinsParentModelToTerminalResult() throws IOException
	{
		String child = ASSISTANT.replace("\"parent_tool_use_id\":null", "\"parent_tool_use_id\":\"tool\"").
			replace("parent-model", "child-model");
		ClaudeTurnObservation observed = read(ASSISTANT, child, RESULT);
		requireThat(observed.sessionId(), "session").isEqualTo("session");
		requireThat(observed.reportedModelId(), "parentModel").isEqualTo(Optional.of("parent-model"));
		requireThat(observed.status(), "terminalStatus").isEqualTo(ClaudeTurnObservation.Status.COMPLETED);
		requireThat(observed.terminalEventOrdinal(), "terminalOrdinal").isEqualTo(3L);
		requireThat(observed.resultId(), "terminalIdentity").isEqualTo("result-id");
		requireThat(observed.response(), "nativeResponse").isEqualTo(Optional.of("answer"));
	}

	/**
	 * A native failure can settle before a model response without borrowing initialization settings.
	 *
	 * @throws IOException if the failure record is rejected
	 */
	@Test
	public void retainsFailureWithoutModelResponse() throws IOException
	{
		String failure = RESULT.replace("\"success\"", "\"error_during_execution\"").
			replace("\"is_error\":false", "\"is_error\":true");
		ClaudeTurnObservation observed = read(failure);
		requireThat(observed.status(), "failedStatus").isEqualTo(ClaudeTurnObservation.Status.FAILED);
		requireThat(observed.reportedModelId(), "unobservedModel").isEqualTo(Optional.empty());
		requireThat(observed.response(), "noSuccessfulResponse").isEqualTo(Optional.empty());
		ClaudeTurnObservation flagged = read(RESULT.replace("\"is_error\":false", "\"is_error\":true"));
		requireThat(flagged.status(), "successSubtypeWithError").isEqualTo(ClaudeTurnObservation.Status.FAILED);
		requireThat(flagged.response(), "flaggedResponseIsNotSuccess").isEqualTo(Optional.empty());
	}

	/**
	 * A success subtype with an error terminal reason remains failure even before a parent model response.
	 *
	 * @throws IOException if the native error result is rejected
	 */
	@Test
	public void retainsFailureTerminalReason() throws IOException
	{
		String error = RESULT.replace("\"subtype\":\"success\"",
			"\"subtype\":\"success\",\"terminal_reason\":\"api_error\"");
		ClaudeTurnObservation observed = read(error);
		Assert.assertEquals(observed.status(), ClaudeTurnObservation.Status.FAILED);
		Assert.assertTrue(observed.reportedModelId().isEmpty());
		Assert.assertTrue(observed.response().isEmpty());
		ClaudeTurnObservation aborted = read(error.replace("api_error", "aborted_streaming"));
		Assert.assertEquals(aborted.status(), ClaudeTurnObservation.Status.FAILED);
	}

	/** Conflicting parent models, foreign sessions, missing response evidence, and extra terminals are rejected. */
	@Test
	public void rejectsUnprovenTurnIdentity()
	{
		Assert.expectThrows(IOException.class, () -> read(ASSISTANT,
			ASSISTANT.replace("parent-model", "another-model"), RESULT));
		Assert.expectThrows(IOException.class, () -> read(ASSISTANT, RESULT.replace("session", "foreign")));
		Assert.expectThrows(IOException.class, () -> read(RESULT));
		Assert.expectThrows(IOException.class, () -> read(ASSISTANT));
		Assert.expectThrows(IOException.class, () -> read(ASSISTANT, RESULT, RESULT));
		Assert.expectThrows(IOException.class, () -> read(ASSISTANT,
			RESULT.replace("\"is_error\":false", "\"is_error\":\"false\"")));
		Assert.expectThrows(IOException.class, () -> read(ASSISTANT, RESULT.replace("\"success\"", "\"unknown\"")));
		Assert.expectThrows(IOException.class, () -> read(ASSISTANT,
			RESULT.replace("\"result\":\"answer\"", "\"result\":3")));
	}

	/**
	 * Supplies parsed records in their native order to the production turn observer.
	 *
	 * @param records ordered native record fixtures
	 * @return validated turn observation
	 * @throws IOException if a turn cannot be established
	 */
	private static ClaudeTurnObservation read(String... records) throws IOException
	{
		List<JsonNode> events = new ArrayList<>(records.length);
		for (String record : records)
			events.add(JSON.readTree(record));
		return ClaudeTurnObservation.read(INITIALIZATION, events);
	}
}
