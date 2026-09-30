package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the Codex tool-call handoff that supplies the active host turn to inspection.
 */
public final class CodexOptimizerPreToolHookTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/** Creates the hook check. */
	public CodexOptimizerPreToolHookTest()
	{
	}

	/**
	 * Rewrites only the inspection launcher and preserves the other tool arguments.
	 *
	 * @throws IOException if controlled hook JSON cannot be read or written
	 */
	@Test
	public void bindsInspectionToHostToolCall() throws IOException
	{
		String command = "\"${PLUGIN_ROOT}/client/bin/optimizer-inspect\" --target 'prompt file.md'";
		ObjectNode event = event(command);
		event.put("session_id", "session-1");
		event.put("turn_id", "turn-1");
		event.put("tool_use_id", "tool-1");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CodexOptimizerPreToolHook.run(new ByteArrayInputStream(json.writeValueAsBytes(event)), output);
		JsonNode result = json.readTree(output.toByteArray());
		requireThat(result.at("/hookSpecificOutput/hookEventName").asString(), "eventName").
			isEqualTo("PreToolUse");
		requireThat(result.at("/hookSpecificOutput/permissionDecision").asString(), "decision").
			isEqualTo("allow");
		JsonNode updated = result.at("/hookSpecificOutput/updatedInput");
		requireThat(updated.path("workdir").asString(), "workingDirectory").isEqualTo("/tmp/project");
		requireThat(updated.path("command").asString(), "rewrittenCommand").isEqualTo(
			"\"${PLUGIN_ROOT}/client/bin/optimizer-inspect\" " +
			"--host-session-id 'session-1' --host-turn-id 'turn-1' --host-tool-use-id 'tool-1' " +
			"--host-model-id 'test-model' " +
			"--target 'prompt file.md'");
	}

	/**
	 * A shell tool that wraps the command in Bash still passes host identity to the inspection launcher.
	 *
	 * @throws IOException if controlled hook JSON or the test-owned launcher fails
	 */
	@Test
	public void bindsWrappedInspectionCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("wrapped-inspection-hook-"));
			Path home = Files.createDirectory(root.resolve("home"));
			Path launcher = home.resolve("plugins/cat-marketplace/plugins/cat/client/bin/optimizer-inspect");
			Files.createDirectories(launcher.getParent());
			Files.writeString(launcher, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
			if (!launcher.toFile().setExecutable(true))
				throw new IOException("Test launcher is not executable");
			Path sentinel = root.resolve("unexpected-command");
			String session = "session'$(touch " + sentinel + ")";
			String command = "/bin/bash -lc '\"${CODEX_HOME}/plugins/cat-marketplace/plugins/cat/" +
				"client/bin/optimizer-inspect\" --target prompt.md'";
			ObjectNode event = event(command);
			event.put("session_id", session);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			CodexOptimizerPreToolHook.run(new ByteArrayInputStream(json.writeValueAsBytes(event)), output);
			JsonNode updated = json.readTree(output.toByteArray()).at("/hookSpecificOutput/updatedInput");
			String rewritten = updated.path("command").asString();
			requireThat(rewritten, "wrappedCommand").contains("--host-session-id").contains("--target prompt.md'");
			ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-lc", rewritten).
				directory(root.toFile()).redirectErrorStream(true);
			processBuilder.environment().put("CODEX_HOME", home.toString());
			try (Process process = processBuilder.start())
			{
				String arguments = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				try
				{
					requireThat(process.waitFor(), "launcherExit").isEqualTo(0);
				}
				catch (InterruptedException failure)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Test launcher was interrupted", failure);
				}
				requireThat(arguments, "launcherArguments").contains("--host-session-id\n" + session + "\n").
					contains("--host-turn-id\nturn-1\n").contains("--target\nprompt.md\n");
			}
			requireThat(Files.notExists(sentinel), "noHostCommandExecution").isTrue();
		}
	}

	/**
	 * An unrelated shell command receives no rewritten input.
	 *
	 * @throws IOException if controlled hook JSON cannot be read or written
	 */
	@Test
	public void leavesUnrelatedCommandUntouched() throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CodexOptimizerPreToolHook.run(new ByteArrayInputStream(json.writeValueAsBytes(
			event("git status --short"))), output);
		requireThat(output.size(), "unrelatedOutputBytes").isEqualTo(0);
	}

	/**
	 * A host value containing shell syntax remains one literal argument.
	 *
	 * @throws IOException if controlled hook JSON cannot be read or written
	 */
	@Test
	public void quotesHostValues() throws IOException
	{
		ObjectNode event = event("'/opt/cat/client/bin/optimizer-inspect'");
		event.put("session_id", "session'$(touch /tmp/nope)");
		event.put("turn_id", "turn-1");
		event.put("tool_use_id", "tool-1");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CodexOptimizerPreToolHook.run(new ByteArrayInputStream(json.writeValueAsBytes(event)), output);
		JsonNode updated = json.readTree(output.toByteArray()).at("/hookSpecificOutput/updatedInput");
		requireThat(updated.path("command").asString(), "quotedCommand").contains(
			"--host-session-id 'session'\"'\"'$(touch /tmp/nope)'");
	}

	/**
	 * Missing native authority denies the inspection without rewriting its input.
	 *
	 * @throws IOException if controlled hook JSON cannot be read or written
	 */
	@Test
	public void deniesMissingHostAuthority() throws IOException
	{
		ObjectNode event = event("'/opt/cat/client/bin/optimizer-inspect'");
		event.remove("model");
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		CodexOptimizerPreToolHook.run(new ByteArrayInputStream(json.writeValueAsBytes(event)), output);

		JsonNode result = json.readTree(output.toByteArray()).path("hookSpecificOutput");
		requireThat(result.path("permissionDecision").asString(), "decision").isEqualTo("deny");
		requireThat(result.path("permissionDecisionReason").asString(), "reason").contains("model");
		requireThat(result.path("updatedInput").isMissingNode(), "noRewrite").isTrue();
	}

	/**
	 * Malformed native JSON reports an I/O failure that the main hook can block.
	 *
	 * @throws IOException if the native event is malformed
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMalformedNativeEvent() throws IOException
	{
		CodexOptimizerPreToolHook.run(new ByteArrayInputStream("{".getBytes(StandardCharsets.UTF_8)),
			new ByteArrayOutputStream());
	}

	/**
	 * Creates one controlled shell tool event.
	 *
	 * @param command pending shell command
	 * @return host hook event
	 */
	private ObjectNode event(String command)
	{
		ObjectNode event = json.createObjectNode();
		event.put("hook_event_name", "PreToolUse");
		event.put("tool_name", "Bash");
		event.put("session_id", "session-1");
		event.put("turn_id", "turn-1");
		event.put("tool_use_id", "tool-1");
		event.put("model", "test-model");
		ObjectNode input = event.putObject("tool_input");
		input.put("command", command);
		input.put("workdir", "/tmp/project");
		return event;
	}
}
