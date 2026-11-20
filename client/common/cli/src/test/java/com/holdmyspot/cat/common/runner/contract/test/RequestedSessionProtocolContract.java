package com.holdmyspot.cat.common.runner.contract.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessEvaluationResult;
import com.holdmyspot.cat.common.runner.NestedSessionReceipt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Shared checks for a normal requested session, run against its emulator and production bridge.
 */
public final class RequestedSessionProtocolContract
{
	/**
	 * Runs the same normal requested-session assertions against one subject.
	 *
	 * @param subject the requested-session implementation under test
	 * @throws IOException if the subject or its retained evidence cannot be read
	 */
	public static void verifiesNormalRequestedSession(Subject subject) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("requested-session-contract-"));
			Path artifact = Files.createDirectories(root.resolve("artifact"));
			Files.createDirectories(root.resolve("project"));
			Inputs inputs = new Inputs(root, artifact, root.resolve("copies"), root.resolve("transcript.jsonl"),
				root.resolve("receipt.json"), List.of("first requested prompt", "second \"prompt\"\nwith newline"),
				List.of("first controlled reply", "reply with \"quotes\"\nand newline"));
			Observation observation = subject.run(inputs);

			JsonMapper json = JsonMapper.builder().build();
			List<JsonNode> events = Files.readAllLines(observation.transcript()).stream().map(json::readTree).toList();
			List<String> eventTypes = events.stream().map(event -> event.path("type").asString()).toList();
			requireThat(eventTypes, "eventTypes").isEqualTo(
				List.of("started", "session_id", "turn_completed", "turn_completed", "completed"));
			requireThat(events.get(1).path("session_id").asString(), "sessionId").isNotBlank();

			requireThat(observation.receivedPrompts(), "receivedPrompts").isEqualTo(inputs.prompts());
			for (int turn = 0; turn < inputs.prompts().size(); turn += 1)
			{
				JsonNode completed = events.get(turn + 2);
				requireThat(completed.path("assistant_response").asString(), "assistantResponse").
					isEqualTo(inputs.replies().get(turn));
				requireThat(completed.path("assistant_messages").size(), "assistantMessageCount").isEqualTo(1);
				requireThat(completed.path("assistant_messages").get(0).asString(), "assistantMessage").
					isEqualTo(inputs.replies().get(turn));
				requireThat(completed.path("exit_code").asInt(-1), "exitCode").isEqualTo(0);
				requireThat(completed.path("error").asString(), "turnError").isEmpty();
			}

			HarnessEvaluationResult receipt = NestedSessionReceipt.read(json, observation.receipt(),
				observation.retainedSession().resolve("project"));
			requireThat(receipt.retainedProject(), "retainedProject").
				isEqualTo(observation.retainedSession().resolve("project"));
			requireThat(receipt.rawResult().startsWith(observation.retainedSession()), "rawResultWithinSession").isTrue();
			requireThat(Files.mismatch(receipt.rawResult(), observation.transcript()), "transcriptCopy").isEqualTo(-1L);
			requireThat(Files.isDirectory(receipt.retainedProject()), "retainedProjectExists").isTrue();
			requireThat(Files.isRegularFile(receipt.rawResult()), "transcriptExists").isTrue();
			requireThat(Files.readString(receipt.retainedProject().resolve("result.txt")), "retainedProjectBytes").
				isEqualTo("result");
			requireThat(Files.exists(observation.retainedSession().resolve("home")), "retainedHome").isFalse();
		}
	}

	/**
	 * Runs a requested session and supplies the evidence that the shared contract checks.
	 */
	@FunctionalInterface
	public interface Subject
	{
		/**
		 * Runs a requested session using the supplied prompts and evidence paths.
		 *
		 * @param inputs the controlled session inputs
		 * @return the transcript, receipt, retained nested session and prompts received by the session
		 * @throws IOException if the session fails
		 */
		Observation run(Inputs inputs) throws IOException;
	}

	/**
	 * The controlled prompts, replies and artifact paths for one normal session.
	 *
	 * @param root the fixture root
	 * @param artifact the artifact directory
	 * @param resultsFolder the destination for the copied requested nested session
	 * @param transcript the retained transcript path
	 * @param receipt the retained receipt path
	 * @param prompts the requested turn prompts, in order
	 * @param replies the controlled assistant replies, in order
	 */
	public record Inputs(Path root, Path artifact, Path resultsFolder, Path transcript, Path receipt,
		List<String> prompts, List<String> replies)
	{
		/**
		 * Copies fixture inputs so callers cannot mutate a running contract.
		 *
		 * @param root the fixture root
		 * @param artifact the artifact directory
		 * @param resultsFolder the destination for the copied requested nested session
		 * @param transcript the retained transcript path
		 * @param receipt the retained receipt path
		 * @param prompts the controlled turn prompts, in order
		 * @param replies the controlled replies, in order
		 */
		public Inputs
		{
			prompts = List.copyOf(prompts);
			replies = List.copyOf(replies);
		}

		/**
		 * Returns the controlled JSONL turn and completion records.
		 *
		 * @return the session input
		 */
		public String records()
		{
			JsonMapper json = JsonMapper.builder().build();
			StringBuilder records = new StringBuilder(21);
			for (String prompt : prompts)
				records.append(json.createObjectNode().put("type", "turn").put("prompt", prompt)).append('\n');
			return records.append("{\"type\":\"complete\"}\n").toString();
		}
	}

	/**
	 * Evidence produced by one requested-session subject.
	 *
	 * @param transcript the durable session transcript
	 * @param receipt the retained-evidence receipt
	 * @param retainedSession the nested session named by the receipt
	 * @param receivedPrompts the turn prompts observed by the subject
	 */
	public record Observation(Path transcript, Path receipt, Path retainedSession, List<String> receivedPrompts)
	{
		/**
		 * Copies observed prompts so callers cannot mutate the contract result.
		 *
		 * @param transcript the durable session transcript
		 * @param receipt the retained-evidence receipt
		 * @param retainedSession the nested session named by the receipt
		 * @param receivedPrompts the prompts observed by the subject
		 */
		public Observation
		{
			receivedPrompts = List.copyOf(receivedPrompts);
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private RequestedSessionProtocolContract()
	{
	}
}
