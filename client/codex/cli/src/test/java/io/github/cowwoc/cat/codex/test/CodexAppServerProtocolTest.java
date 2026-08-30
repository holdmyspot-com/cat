package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexAppServerProtocol;

import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

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
			contains("\"method\":\"initialize\"").contains("\"name\":\"cat-runner\",\"version\"").
			contains("\"capabilities\":{\"experimentalApi\":true}");
		requireThat(CodexAppServerProtocol.initialized(), "acknowledgement").
			isEqualTo("{\"method\":\"initialized\",\"params\":{}}\n");
	}

	/**
	 * Verifies a runner disables remote control without changing the user's persisted preference.
	 */
	@Test
	public void disablesRemoteControlEphemerally()
	{
		requireThat(CodexAppServerProtocol.remoteControlDisable(2), "request").isEqualTo(
			"{\"id\":2,\"method\":\"remoteControl/disable\",\"params\":{\"ephemeral\":true}}\n");
	}

	/**
	 * Verifies a text turn is framed for an existing app-server thread.
	 */
	@Test
	public void framesTextTurn()
	{
		String expected = "{\"id\":2,\"method\":\"turn/start\",\"params\":{\"threadId\":\"thread-1\",\"input\":[" +
			"{\"type\":\"text\",\"text\":\"hello\"}]}}\n";
		requireThat(CodexAppServerProtocol.turnStart(2, "thread-1", "hello"), "request").isEqualTo(
			expected);
	}

	/**
	 * Verifies every JSON control character is escaped before it reaches app-server's JSONL input.
	 */
	@Test
	public void escapesJsonControlCharacters()
	{
		String request = CodexAppServerProtocol.turnStart(2, "thread-1", "tab\treturn\rline\nlow\u0001");
		requireThat(request, "request").contains("tab\\treturn\\rline\\nlow\\u0001").
			doesNotContain("tab\treturn").doesNotContain("return\rline").doesNotContain("line\nlow");
	}

	/**
	 * Verifies active-turn controls preserve the thread and turn identifiers supplied by app-server.
	 */
	@Test
	public void framesActiveTurnControls()
	{
		requireThat(CodexAppServerProtocol.turnSteer(3, "thread-1", "turn-1", "change course"), "steer").
			isEqualTo("{\"id\":3,\"method\":\"turn/steer\",\"params\":{\"threadId\":\"thread-1\"," +
				"\"expectedTurnId\":\"turn-1\",\"input\":" +
				"[{\"type\":\"text\",\"text\":\"change course\"}]}}\n");
		requireThat(CodexAppServerProtocol.turnInterrupt(4, "thread-1", "turn-1"), "interrupt").
			isEqualTo("{\"id\":4,\"method\":\"turn/interrupt\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}" +
				"}\n");
	}

	/**
	 * Verifies a headless thread does not wait for interactive approval.
	 */
	@Test
	public void framesHeadlessThread()
	{
		String request = CodexAppServerProtocol.threadStart(Path.of("/").toAbsolutePath(), "");
		requireThat(request, "request").contains("\"sandbox\":\"workspace-write\"").
			contains("\"approvalPolicy\":\"never\"").contains("\"sessionStartSource\":\"startup\"").
			contains("\"id\":2");
	}

	/**
	 * Verifies a thread can follow a process-level request without reusing its identifier.
	 */
	@Test
	public void framesThreadWithExplicitRequestId()
	{
		String request = CodexAppServerProtocol.threadStart(3, Path.of("/").toAbsolutePath(), "", "", "");
		requireThat(request, "request").contains("\"id\":3");
	}

	/**
	 * Verifies a validator uses its named permission profile instead of granting its evidence write access.
	 */
	@Test
	public void framesValidatorPermissionProfile()
	{
		String request = CodexAppServerProtocol.threadStart(Path.of("/workspace").toAbsolutePath(), "",
			"", "cat-validator");

		requireThat(request, "request").contains("\"permissions\":\"cat-validator\"").
			contains("\"runtimeWorkspaceRoots\":[\"/workspace\"]").doesNotContain("\"sandbox\"");
	}

	/**
	 * Verifies a validator declares the retained executor evidence that its permission profile may read.
	 */
	@Test
	public void framesValidatorEvidenceWorkspaceRoots()
	{
		String request = CodexAppServerProtocol.threadStart(3, Path.of("/workspace").toAbsolutePath(), "", "",
			"cat-validator", List.of(Path.of("/workspace").toAbsolutePath(), Path.of("/evidence/project").toAbsolutePath(),
				Path.of("/evidence/result.txt").toAbsolutePath()));

		requireThat(request, "request").contains("\"permissions\":\"cat-validator\"").contains(
			"\"runtimeWorkspaceRoots\":[\"/workspace\",\"/evidence/project\",\"/evidence/result.txt\"]");
	}
}
