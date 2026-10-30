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
		String request = CodexAppServerProtocol.threadStart(Path.of("/").toAbsolutePath().toAbsolutePath(), "", "", "",
			true);
		requireThat(request, "request").contains("\"sandbox\":\"workspace-write\"").
			contains("\"approvalPolicy\":\"never\"").contains("\"sessionStartSource\":\"startup\"").
			contains("\"id\":2");
	}

	/**
	 * Verifies that a thread is ephemeral unless the run keeps session transcripts, so that Codex keeps no rollout of it.
	 */
	@Test
	public void framesEphemeralThreadUnlessPersisted()
	{
		// Requirement: R-I7
		Path directory = Path.of("/").toAbsolutePath();

		requireThat(CodexAppServerProtocol.threadStart(directory, "", "", "", true), "ephemeral").
			contains("\"ephemeral\":true");
		requireThat(CodexAppServerProtocol.threadStart(directory, "", "", "", false), "persistent").
			doesNotContain("ephemeral");
	}
}
