package com.holdmyspot.cat.codex.optimizer.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.runner.HarnessRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lays out a recorded Codex trial the way the runner keeps it: the harness events as the transcript in an
 * {@code artifacts} directory, beside the {@value CodexRunner#ROLLOUTS} directory that holds the rollout recording
 * what the trial's agents received.
 */
final class RecordedTrial
{
	/**
	 * The JSON mapper that writes rollout lines.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prevents instantiation.
	 */
	private RecordedTrial()
	{
	}

	/**
	 * Writes a trial's transcript and one rollout.
	 *
	 * @param root    the existing directory to write the trial into
	 * @param events  the recorded harness events
	 * @param rollout the rollout's lines
	 * @return the transcript
	 * @throws IOException if the files cannot be written
	 */
	static Path write(Path root, Path events, List<String> rollout) throws IOException
	{
		Path artifacts = Files.createDirectories(root.resolve("artifacts"));
		Path transcript = Files.copy(events, artifacts.resolve("transcript.jsonl"));
		Path rollouts = Files.createDirectories(artifacts.resolve(HarnessRun.SESSION_TRANSCRIPTS));
		StringBuilder text = new StringBuilder();
		for (String line : rollout)
			text.append(line).append('\n');
		Files.writeString(rollouts.resolve("rollout-2026-10-15T12-00-00-root.jsonl"), text, UTF_8);
		return transcript;
	}

	/**
	 * Returns the rollout line of a command's result, shaped as Codex records the result of its command tool.
	 *
	 * @param exitStatus the command's exit status
	 * @param output     the part of the command's output that the agent received
	 * @return the line
	 */
	static String commandResult(int exitStatus, String output)
	{
		return toolResult("Chunk ID: 1a2b3c\nWall time: 0.0100 seconds\nProcess exited with code " + exitStatus +
			"\nOriginal token count: 20\nOutput:\n" + output);
	}

	/**
	 * Returns the rollout line of a tool's result whose output is a string.
	 *
	 * @param output the result's text
	 * @return the line
	 */
	static String toolResult(String output)
	{
		ObjectNode line = JSON.createObjectNode().put("type", "response_item");
		line.putObject("payload").put("type", "function_call_output").put("call_id", "call_1").put("output", output);
		return JSON.writeValueAsString(line);
	}
}
