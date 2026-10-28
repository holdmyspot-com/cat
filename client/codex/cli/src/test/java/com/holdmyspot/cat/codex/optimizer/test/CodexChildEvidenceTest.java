package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.JvmScope;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests that the judge's record of a Codex trial shows the children that the root started and what the root received
 * from them.
 */
public final class CodexChildEvidenceTest
{
	/**
	 * The recorded run artifacts of a trial whose root started two children, waited once and then answered.
	 */
	private static final Path TRANSCRIPT = Path.of("src/test/resources/optimizer/two-children/transcript.jsonl");
	/**
	 * The thread of the child that the root started to answer the shipping question.
	 */
	private static final String SHIPPING = "01a17e47-ddce-75f3-8959-665ab548b937";
	/**
	 * The thread of the child that the root started to answer the sodium question.
	 */
	private static final String SODIUM = "01a17e47-efbd-7753-91e2-3b5ea7fbb960";

	/**
	 * Creates the test.
	 */
	public CodexChildEvidenceTest()
	{
	}

	/**
	 * Verifies that the record of a root that starts two children and waits on both holds, in order, both starts with
	 * what the harness recorded of each assignment, the wait, both findings that the root received with the child that
	 * sent each, and then the root's answer.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void recordsStartsAndReceivedFindings() throws IOException
	{
		// Requirement: R-E21
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-child-evidence-"));
			String rendered = sessions(jvm, root).readEvidence(TRANSCRIPT).render();

			// Codex records each assignment only in encrypted form, so the record says that it has no readable text
			List<String> ordered = List.of(
				"Actor: root\nStarted child " + SHIPPING + " (/root/shipping)\n" +
					"Assignment: not recorded in readable form\n",
				"Actor: root\nStarted child " + SODIUM + " (/root/sodium)\n" +
					"Assignment: not recorded in readable form\n",
				"Actor: root\nTool: wait\n",
				"Actor: root\nReceived from child " + SHIPPING + " (/root/shipping):\n" +
					"Message Type: FINAL_ANSWER\nTask name: /root\nSender: /root/shipping\nPayload:\n" +
					"Assigned subquestion: Where does Acme ship orders from? Acme ships orders from Turin, as stated in " +
					"`docs/facts.md`.\n",
				"Actor: root\nReceived from child " + SODIUM + " (/root/sodium):\n" +
					"Message Type: FINAL_ANSWER\nTask name: /root\nSender: /root/sodium\nPayload:\n" +
					"Assigned subquestion: What is the chemical symbol for sodium? The symbol is Na, supported by " +
					"`docs/facts.md`.\n",
				"Actor: root\nAssistant response position: 2\n");
			String remainder = rendered;
			for (String expected : ordered)
			{
				requireThat(remainder, "remainder").contains(expected);
				remainder = remainder.substring(remainder.indexOf(expected) + expected.length());
			}
		}
	}

	/**
	 * Verifies that a message whose text Codex recorded only in encrypted form says so, and that a message received
	 * after a root item that the app-server did not report still follows the message before it.
	 *
	 * @throws IOException if the trial cannot be written or read
	 */
	@Test
	public void placesUnreadableAndUnanchoredMessages() throws IOException
	{
		// Requirement: R-E21
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-child-evidence-"));
			Path artifacts = Files.createDirectories(root.resolve("artifacts"));
			Path transcript = Files.copy(TRANSCRIPT, artifacts.resolve("transcript.jsonl"));
			Path rollouts = Files.createDirectories(artifacts.resolve(HarnessRun.SESSION_TRANSCRIPTS));
			Path recorded = TRANSCRIPT.resolveSibling(HarnessRun.SESSION_TRANSCRIPTS);
			Path rootRollout = recorded.resolve("rollout-2026-10-27T12-53-13-01a17e47-bf52-75f1-b6c5-ca043f8beb4c.jsonl");
			List<String> lines = Files.readAllLines(rootRollout, UTF_8);
			StringBuilder text = new StringBuilder(Math.toIntExact(Files.size(rootRollout)) + 256);
			for (String line : lines)
			{
				// The first finding becomes encrypted, and the second follows an item that the app-server never reported
				String altered = line.replace("{\"type\":\"input_text\",\"text\":\"Message Type: FINAL_ANSWER\\nTask name: " +
					"/root\\nSender: /root/shipping\\nPayload:\\nAssigned subquestion: Where does Acme ship orders from? " +
					"Acme ships orders from Turin, as stated in `docs/facts.md`.\"}",
					"{\"type\":\"input_text\",\"text\":\"Sender: /root/shipping\\n\"}," +
						"{\"type\":\"encrypted_content\",\"encrypted_content\":\"gAAAA\"}");
				text.append(altered).append('\n');
				if (altered.contains("\"author\":\"/root/shipping\""))
				{
					text.append("{\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\"," +
						"\"thread_id\":\"01a17e47-bf52-75f1-b6c5-ca043f8beb4c\",\"item\":{\"id\":\"unreported\"}}}\n");
				}
			}
			Files.writeString(rollouts.resolve(rootRollout.getFileName()), text, UTF_8);

			String rendered = sessions(jvm, root).readEvidence(transcript).render();
			String encrypted = "Actor: root\nReceived from child " + SHIPPING + " (/root/shipping):\n" +
				"Sender: /root/shipping\n[encrypted content, not recorded in readable form]\n\n";
			String unanchored = "Actor: root\nReceived from child " + SODIUM + " (/root/sodium):\n";
			requireThat(rendered, "rendered").contains("Actor: root\nTool: wait\n");
			String afterWait = rendered.substring(rendered.indexOf("Actor: root\nTool: wait\n"));
			requireThat(afterWait, "afterWait").contains(encrypted + unanchored);
			requireThat(afterWait.substring(afterWait.indexOf(unanchored)), "afterSecond").
				contains("Actor: root\nAssistant response position: 2\n");
		}
	}

	/**
	 * Returns a port whose run directories are below a directory.
	 *
	 * @param jvm  the borrowed process scope
	 * @param root the directory
	 * @return the port
	 */
	private static CodexSessions sessions(JvmScope jvm, Path root)
	{
		return new CodexSessions(new RunnerSessions.Settings(jvm, root, root, List::of, "", false, Map.of(), root,
			HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer()));
	}
}
