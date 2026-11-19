package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionProtocolContract;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionProtocolContract.Inputs;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionProtocolContract.Observation;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingRun;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs the shared requested-session protocol contract through the public RequestingRun forwarding path.
 */
public final class RequestingRunProtocolContractTest
{
	/**
	 * Verifies the emulator completes ordered requested turns and retains the project and transcript named by its
	 * receipt.
	 *
	 * @throws IOException if the request or retained evidence cannot be read
	 */
	@Test
	public void completesNormalRequestedSession() throws IOException
	{
		RequestedSessionProtocolContract.verifiesNormalRequestedSession(
			RequestingRunProtocolContractTest::runRequestingSession);
	}

	/**
	 * Runs the session through the public request directory and forwarding operation.
	 *
	 * @param inputs the controlled session inputs
	 * @return the session evidence
	 * @throws IOException if the request or receipt cannot be read
	 */
	private static Observation runRequestingSession(Inputs inputs) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		RunOutput runOutput = serveRequestingSession(inputs, json);
		requireThat(runOutput.launches().size(), "requestedLaunchCount").isEqualTo(1);
		List<String> launched = runOutput.launches().getFirst();
		Path receipt = Path.of(ScriptedHarness.optionValue(launched, "--receipt"));
		Path responseDirectory = receipt.getParent();
		JsonNode response = json.readTree(responseDirectory.resolve("response.json").toFile());
		requireThat(response.path("status").asString(), "responseStatus").isEqualTo("completed");
		Path transcript = Path.of(response.path("transcript").asString());
		Path retainedRun = Path.of(response.path("run").asString());
		requireThat(response.path("session_id").asString(), "sessionId").isEqualTo(RequestingRun.SESSION_ID);
		List<String> prompts = runOutput.received().stream().map(record -> readPrompt(json, record)).
			filter(prompt -> !prompt.isEmpty()).toList();
		return new Observation(transcript, receipt, retainedRun, prompts);
	}

	/**
	 * Serves the piped two-turn request and closes its broker before returning the published response paths.
	 *
	 * @param inputs the controlled session inputs
	 * @param json the JSON mapper
	 * @return the child launch and records received by its fake
	 * @throws IOException if the request cannot complete
	 */
	private static RunOutput serveRequestingSession(Inputs inputs, JsonMapper json) throws IOException
	{
		try (RequestingRun parent = new RequestingRun(inputs.root().resolve("requesting-run"), new FileLocks(), 1,
			turn -> inputs.replies().get(turn - 1)))
		{
			try (PipedReader input = new PipedReader(); PipedWriter turns = new PipedWriter(input);
				CapturingTurnWriter output = new CapturingTurnWriter(json, turns, inputs.prompts()))
			{
				turns.write(turnRecord(json, inputs.prompts().getFirst()));
				turns.flush();
				List<String> arguments = List.of("--project", inputs.root().resolve("project").toString(), "--model",
					"model-a", "--effort", "effort-a", "--results", inputs.runRoot().toString());
				SessionRequestForwarder.serve("requested-session contract", "codex", inputs.artifact(), arguments,
					input, output, parent.requests(), Thread::yield);
				requireThat(output.completedTurns, "causallyForwardedTurns").isEqualTo(2);
			}
			return new RunOutput(parent.launches(), parent.received());
		}
	}

	/**
	 * Returns the prompt from a request record, or an empty string for non-turn records.
	 *
	 * @param json the JSON mapper
	 * @param record the record
	 * @return the prompt or an empty string
	 */
	private static String readPrompt(JsonMapper json, String record)
	{
		JsonNode value = json.readTree(record);
		if (value.path("type").asString().equals("turn"))
			return value.path("prompt").asString();
		return "";
	}

	/**
	 * Encodes one turn record.
	 *
	 * @param json the JSON mapper
	 * @param prompt the prompt
	 * @return the JSON line
	 */
	private static String turnRecord(JsonMapper json, String prompt)
	{
		return json.createObjectNode().put("type", "turn").put("prompt", prompt) + "\n";
	}

	/**
	 * Captures protocol output and releases the next input only after a complete turn event is written.
	 */
	private static final class CapturingTurnWriter extends Writer
	{
		private final JsonMapper json;
		private final PipedWriter turns;
		private final List<String> prompts;
		private final StringBuilder line = new StringBuilder();
		private int completedTurns;

		/**
		 * Creates a writer that forwards the next prompt in response to each completed-turn line.
		 *
		 * @param json the JSON mapper
		 * @param turns the connected input pipe
		 * @param prompts the prompts
		 */
		private CapturingTurnWriter(JsonMapper json, PipedWriter turns, List<String> prompts)
		{
			this.json = json;
			this.turns = turns;
			this.prompts = prompts;
		}

		@Override
		public void write(char[] characters, int offset, int length) throws IOException
		{
			for (int index = offset; index < offset + length; index += 1)
			{
				char character = characters[index];
				if (character == '\n')
				{
					JsonNode event = json.readTree(line.toString());
					line.setLength(0);
					if (event.path("type").asString().equals("turn_completed"))
					{
						completedTurns += 1;
						if (completedTurns < prompts.size())
						{
							turns.write(turnRecord(json, prompts.get(completedTurns)));
							turns.flush();
						}
						else
					{
						turns.write("{\"type\":\"complete\"}\n");
						turns.flush();
						turns.close();
					}
					}
				}
				else
					line.append(character);
			}
		}

		@Override
		public void flush()
		{
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * Captured child launch and input records after the broker closes.
	 *
	 * @param launches the child launches
	 * @param received the input records
	 */
	private record RunOutput(List<List<String>> launches, List<String> received)
	{
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	public RequestingRunProtocolContractTest()
	{
	}
}
