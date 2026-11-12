package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.Conversation;
import com.holdmyspot.cat.common.runner.ConversationReply;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests {@link ConversationReply}, which {@code request-session --reply} runs.
 */
public final class ConversationReplyTest
{
	/**
	 * Creates the test fixture.
	 */
	public ConversationReplyTest()
	{
	}

	/**
	 * Verifies that the reply of a turn is its final reply alone, however many turns and events the conversation
	 * holds, and that a turn whose harness reported an exit status or an error says so after the reply.
	 *
	 * @throws IOException if the conversation's files cannot be written
	 */
	@Test(timeOut = 10_000)
	public void printsOnlyTheRequestedTurnsReply() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-conversation-")).resolve("conversation");
			try (Conversation conversation = Conversation.open(folder))
			{
				conversation.childRecord("{\"type\":\"harness_event\",\"event\":\"noise\"}");
				conversation.childRecord("{\"type\":\"waiting\",\"quiet_seconds\":30}");
				conversation.childRecord("{\"type\":\"turn_completed\",\"exit_code\":0,\"error\":\"\"," +
					"\"assistant_response\":\"first \\\"reply\\\"\\nsecond line\"}");
				conversation.childRecord("{\"type\":\"harness_event\",\"event\":\"noise\"}");
				conversation.childRecord("{\"type\":\"turn_completed\",\"exit_code\":3,\"error\":\"boom\"," +
					"\"assistant_response\":\"two\"}");

				requireThat(print(folder, "1"), "first").isEqualTo("0|first \"reply\"\nsecond line\n");
				requireThat(print(folder, "2"), "second").isEqualTo("0|two\nexit_code: 3\nerror: boom\n");
			}
		}
	}

	/**
	 * Verifies that a failed turn prints its reason and fails, and that the next turn's reply is still available.
	 *
	 * @throws IOException if the conversation's files cannot be written
	 */
	@Test(timeOut = 10_000)
	public void printsWhyATurnFailed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-conversation-")).resolve("conversation");
			try (Conversation conversation = Conversation.open(folder))
			{
				conversation.childRecord("{\"type\":\"turn_failed\",\"error\":\"usage limit\"}");
				conversation.childRecord("{\"type\":\"turn_completed\",\"exit_code\":0,\"error\":\"\"," +
					"\"assistant_response\":\"two\"}");

				requireThat(print(folder, "1"), "first").isEqualTo("1|turn 1 failed: usage limit\n");
				requireThat(print(folder, "2"), "second").isEqualTo("0|two\n");
			}
		}
	}

	/**
	 * Verifies that a turn that has not ended is pending until the conversation ends, and that the conversation's end
	 * then fails the wait, so that a check of the turn never waits for a {@code turn_end} line that cannot come.
	 *
	 * @throws IOException if the conversation's files cannot be written
	 */
	@Test(timeOut = 10_000)
	public void reportsPendingTurnUntilConversationEnds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-conversation-")).resolve("conversation");
			try (Conversation conversation = Conversation.open(folder))
			{
				requireThat(print(folder, "1"), "beforeAnyRecord").isEqualTo("75|turn 1 has not ended\n");
				conversation.childRecord("{\"type\":\"waiting\",\"quiet_seconds\":30}");
				requireThat(print(folder, "1"), "whileQuiet").isEqualTo("75|turn 1 has not ended\n");
				conversation.childRecord("{\"type\":\"turn_completed\",\"exit_code\":0,\"error\":\"\"," +
					"\"assistant_response\":\"one\"}");
				requireThat(print(folder, "2"), "nextTurn").isEqualTo("75|turn 2 has not ended\n");

				conversation.end("failed");

				requireThat(print(folder, "2"), "afterEnd").
					isEqualTo("1|the conversation ended as failed before turn 2 ended\n");
				requireThat(print(folder, "1"), "endedTurn").isEqualTo("0|one\n");
			}
		}
	}

	/**
	 * Verifies that {@code --status-only} prints nothing and returns the same status.
	 *
	 * @throws IOException if the conversation's files cannot be written
	 */
	@Test(timeOut = 10_000)
	public void statusOnlyPrintsNothing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-conversation-")).resolve("conversation");
			try (Conversation conversation = Conversation.open(folder))
			{
				conversation.childRecord("{\"type\":\"turn_completed\",\"exit_code\":0,\"error\":\"\"," +
					"\"assistant_response\":\"one\"}");

				requireThat(print(folder, "1", "--status-only"), "ended").isEqualTo("0|");
				requireThat(print(folder, "2", "--status-only"), "pending").isEqualTo("75|");
			}
		}
	}

	/**
	 * Verifies that the command refuses options that it does not define and a turn that is not a positive number.
	 *
	 * @throws IOException if the conversation's files cannot be written
	 */
	@Test(timeOut = 10_000)
	public void refusesBadOptions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-conversation-")).resolve("conversation");
			try (Conversation _ = Conversation.open(folder))
			{
				for (String[] args : new String[][]
					{
						{"--reply", folder.toString()},
						{"--reply", folder.toString(), "--turn", "one"},
						{"--reply", folder.toString(), "--turn", "0"},
						{"--reply", folder.toString(), "--turn", "1", "--turn", "2"},
						{"--reply", folder.toString(), "--turn", "1", "--wait", "5"}
					})
				{
					try
					{
						SessionRequestCli.reply(args, new PrintWriter(new StringWriter()));
						throw new AssertionError("Accepted " + String.join(" ", args));
					}
					catch (IllegalArgumentException _)
					{
						// Expected
					}
				}
			}
		}
	}

	/**
	 * Runs the reply command.
	 *
	 * @param folder  the conversation's folder
	 * @param turn    the turn's number
	 * @param options other options of the command
	 * @return the exit status, a bar and the command's output
	 * @throws IOException if the conversation cannot be read
	 */
	private static String print(Path folder, String turn, String... options) throws IOException
	{
		String[] args = new String[4 + options.length];
		args[0] = "--reply";
		args[1] = folder.toString();
		args[2] = "--turn";
		args[3] = turn;
		System.arraycopy(options, 0, args, 4, options.length);
		StringWriter output = new StringWriter();
		int status = SessionRequestCli.reply(args, new PrintWriter(output));
		return status + "|" + output.toString().replace("\r\n", "\n");
	}
}
