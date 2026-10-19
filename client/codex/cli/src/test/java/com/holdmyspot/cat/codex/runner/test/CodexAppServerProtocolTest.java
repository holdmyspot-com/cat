package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexAppServerProtocol;

import java.nio.file.Path;
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
			contains("\"method\":\"initialize\"").contains("\"name\":\"cat-runner\",\"version\"");
		requireThat(CodexAppServerProtocol.initialized(), "acknowledgement").
			isEqualTo("{\"method\":\"initialized\",\"params\":{}}\n");
	}

	/**
	 * Verifies a text turn is framed for an existing app-server thread.
	 */
	@Test
	public void framesTextTurn()
	{
		String expected = "{\"id\":2,\"method\":\"turn/start\",\"params\":{\"threadId\":\"thread-1\",\"input\":[" +
			"{\"type\":\"text\",\"text\":\"hello\"}]}}\n";
		requireThat(CodexAppServerProtocol.turnStart(2, "thread-1", "hello", ""), "request").isEqualTo(
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
}
