package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests strict extraction of a harness-neutral terminal assistant response.
 */
public final class HarnessTranscriptTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public HarnessTranscriptTest()
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
	 * Verifies the exact assistant response is returned from one completed turn.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void extractsCompletedTurnResponse() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempFile("harness-transcript-", ".jsonl"));
			Files.writeString(transcript, "{\"type\":\"started\",\"runId\":\"run-1\"}\n" +
				"{\"type\":\"turn-completed\",\"runId\":\"run-1\",\"exitCode\":0,\"error\":\"\"," +
				"\"assistant_response\":\"exact response\"}\n" +
				"{\"type\":\"completed\",\"runId\":\"run-1\"}\n");

			String response = HarnessTranscript.finalAssistantResponse(scope.json(), transcript);

			requireThat(response, "response").isEqualTo("exact response");
		}
	}

	/**
	 * Verifies terminal format drift is rejected rather than silently ignored.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*turn-completed event has unsupported or missing fields.*")
	public void rejectsUnknownTerminalField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempFile("harness-transcript-", ".jsonl"));
			Files.writeString(transcript, "{\"type\":\"turn-completed\",\"runId\":\"run-1\",\"exitCode\":0," +
				"\"error\":\"\",\"assistant_response\":\"response\",\"unexpected\":true}\n");

			HarnessTranscript.finalAssistantResponse(scope.json(), transcript);
		}
	}

	/**
	 * Verifies multiple terminal responses are rejected as ambiguous evaluator evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*must contain exactly one completed turn.*")
	public void rejectsMultipleCompletedTurns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempFile("harness-transcript-", ".jsonl"));
			String completed = "{\"type\":\"turn-completed\",\"runId\":\"run-1\",\"exitCode\":0," +
				"\"error\":\"\",\"assistant_response\":\"response\"}\n";
			Files.writeString(transcript, completed + completed);

			HarnessTranscript.finalAssistantResponse(scope.json(), transcript);
		}
	}
}
