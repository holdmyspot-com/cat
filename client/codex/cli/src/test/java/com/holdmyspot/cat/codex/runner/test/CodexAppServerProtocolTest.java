package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexAppServerProtocol;

import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link CodexAppServerProtocol}.
 */
public final class CodexAppServerProtocolTest
{
	/**
	 * Verifies the initialization request identifies CAT as the app-server client.
	 */
	@Test
	public void framesInitialization()
	{
		requireThat(CodexAppServerProtocol.initialize(), "request").contains("\"id\":1").
			contains("\"method\":\"initialize\"").contains("\"name\":\"cat-runner\",\"version\"");
		requireThat(CodexAppServerProtocol.initialized(), "acknowledgement").
			isEqualTo("{\"method\":\"initialized\",\"params\":{}}\n");
	}

	/**
	 * Verifies a text turn is framed for an existing app-server thread with its reasoning effort.
	 */
	@Test
	public void framesTextTurn()
	{
		String expected = "{\"id\":2,\"method\":\"turn/start\",\"params\":{\"threadId\":\"thread-1\",\"input\":[" +
			"{\"type\":\"text\",\"text\":\"hello\"}],\"effort\":\"low\"}}\n";
		requireThat(CodexAppServerProtocol.turnStart(2, "thread-1", "hello", "low"), "request").isEqualTo(
			expected);
	}

	/**
	 * Verifies a headless thread does not wait for interactive approval.
	 */
	@Test
	public void framesHeadlessThread()
	{
		String request = CodexAppServerProtocol.threadStart(Path.of("/").toAbsolutePath().toAbsolutePath(), "", "", "");
		requireThat(request, "request").contains("\"sandbox\":\"workspace-write\"").
			contains("\"approvalPolicy\":\"never\"").contains("\"sessionStartSource\":\"startup\"").
			contains("\"id\":2");
	}

	/**
	 * Verifies that no thread is ephemeral, so that Codex keeps every nested session's thread and the nested session's
	 * harness session can continue after its runner ends.
	 */
	@Test
	public void framesPersistentThread()
	{
		// Requirement: R-I7
		Path directory = Path.of("/").toAbsolutePath();

		requireThat(CodexAppServerProtocol.threadStart(directory, "", "", ""), "request").doesNotContain("ephemeral");
	}

	/**
	 * Verifies that a resume names the recorded thread and the working directory, and confines the thread by the
	 * permission profile and workspace root that it names, or by the workspace-write sandbox without one, never asking
	 * for approval.
	 */
	@Test
	public void framesThreadResume()
	{
		Path directory = Path.of("/").toAbsolutePath();
		JsonMapper json = JsonMapper.builder().build();

		JsonNode profiled = json.readTree(CodexAppServerProtocol.threadResume(directory, "thread-1", "gpt-5", "",
			"cat-runner"));
		JsonNode sandboxed = json.readTree(CodexAppServerProtocol.threadResume(directory, "thread-1", "", "", ""));

		requireThat(profiled.path("method").asString(), "method").isEqualTo("thread/resume");
		JsonNode params = profiled.path("params");
		requireThat(params.path("threadId").asString(), "threadId").isEqualTo("thread-1");
		requireThat(params.path("cwd").asString(), "cwd").isEqualTo(directory.toString());
		requireThat(params.path("approvalPolicy").asString(), "approvalPolicy").isEqualTo("never");
		requireThat(params.path("model").asString(), "model").isEqualTo("gpt-5");
		requireThat(params.path("permissions").asString(), "permissions").isEqualTo("cat-runner");
		requireThat(params.path("runtimeWorkspaceRoots").get(0).asString(), "root").isEqualTo(directory.toString());
		requireThat(sandboxed.path("params").path("sandbox").asString(), "sandbox").isEqualTo("workspace-write");
		requireThat(sandboxed.path("params").has("model"), "model").isFalse();
	}

	/**
	 * Verifies that a thread started or resumed with {@link CodexAppServerProtocol#FULL_ACCESS} runs without a sandbox
	 * and names no permission profile, and that it never asks for approval.
	 */
	@Test
	public void framesFullAccessThreads()
	{
		Path directory = Path.of("/").toAbsolutePath();
		JsonMapper json = JsonMapper.builder().build();

		JsonNode started = json.readTree(CodexAppServerProtocol.threadStart(directory, "", "",
			CodexAppServerProtocol.FULL_ACCESS)).path("params");
		JsonNode resumed = json.readTree(CodexAppServerProtocol.threadResume(directory, "thread-1", "", "",
			CodexAppServerProtocol.FULL_ACCESS)).path("params");

		for (JsonNode params : new JsonNode[]{started, resumed})
		{
			requireThat(params.path("sandbox").asString(), "sandbox").isEqualTo("danger-full-access");
			requireThat(params.has("permissions"), "hasPermissions").isFalse();
			requireThat(params.has("runtimeWorkspaceRoots"), "hasRuntimeWorkspaceRoots").isFalse();
			requireThat(params.path("approvalPolicy").asString(), "approvalPolicy").isEqualTo("never");
		}
	}
}
