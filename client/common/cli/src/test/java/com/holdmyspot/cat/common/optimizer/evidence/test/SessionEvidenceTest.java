package com.holdmyspot.cat.common.optimizer.evidence.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Assistant;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.ChildEvent;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Entry;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Hook;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Tool;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Verifies the complete, ordered presentation of a trial's harness activity.
 */
public final class SessionEvidenceTest
{
	/**
	 * Creates the tests.
	 */
	public SessionEvidenceTest()
	{
	}

	/**
	 * Verifies that tools, hooks and assistant positions retain their order and identify child activity.
	 */
	@Test
	public void preservesOrderAndActors()
	{
		// Requirement: R-E21
		SessionEvidence evidence = new SessionEvidence(List.of(
			new Tool(1, "root", "shell", "printf ready", true, 0, "ready\n"),
			new Assistant(1, "root", 1, "Root response is already supplied."),
			new Hook(1, "root", "after-tool", "completed"),
			new Tool(1, "child-7", "Read", "{\"path\":\"VERSION\"}", false, null, "1.0"),
			new Assistant(1, "child-7", 1, "Version read."),
			new Tool(2, "root", "shell", "false", true, 1, "")));

		String rendered = evidence.render();
		requireThat(rendered, "rendered").contains("Turn 1").contains("Turn 2").contains("Actor: root").
			contains("Actor: child child-7").contains("Input:\nprintf ready\n").contains("Exit status: 0").
			contains("Output: 6 characters; the agent received all of it\n").contains("{\"path\":\"VERSION\"}").
			contains("Output: 3 characters\n").contains("Output: none\n").
			contains("Hook: after-tool; status: completed").contains("Assistant response position: 1").
			contains("Version read.").doesNotContain("Root response is already supplied.");
		requireThat(rendered.indexOf("printf ready"), "firstToolPosition").
			isLessThan(rendered.indexOf("Assistant response position: 1"));
		requireThat(rendered.indexOf("Hook: after-tool"), "hookPosition").
			isLessThan(rendered.indexOf("Actor: child child-7"));
		requireThat(rendered.indexOf("Version read."), "childReplyPosition").
			isLessThan(rendered.indexOf("Turn 2"));
	}

	/**
	 * Verifies that a tool's output is described by its size instead of being copied, so that the evidence stays
	 * short however much a trial prints.
	 */
	@Test
	public void leavesOutOutputText()
	{
		String output = "complete output\n".repeat(15_000) + "final sentinel";
		String rendered = new SessionEvidence(List.of(new Tool(1, "root", "shell", "cat large.txt", true, 0,
			output))).render();

		requireThat(rendered, "rendered").doesNotContain("complete output").doesNotContain("final sentinel").
			contains("Output: 240014 characters; the agent received all of it\n");
	}

	/**
	 * Verifies that a command whose output the agent received only part of is reported as such, and that a blank
	 * line or trailing whitespace that the agent did not receive does not count as a missing part.
	 */
	@Test
	public void reportsPartlyReceivedOutput()
	{
		Tool whole = new Tool(1, "root", "shell", "cat short.txt", true, 0, "first  \n\nsecond\n");
		Tool cut = new Tool(1, "root", "shell", "cat long.txt", true, 0, "first\nsecond\nthird\n");
		String rendered = new SessionEvidence(List.of(whole, cut), List.of("first\nsecond\n[3 lines cut]")).render();

		requireThat(rendered, "rendered").
			contains("Input:\ncat short.txt\nExit status: 0\nOutput: 16 characters; the agent received all of it\n").
			contains("Input:\ncat long.txt\nExit status: 0\nOutput: 19 characters; the agent received only part of " +
				"it\n");
	}

	/**
	 * Verifies that a size counts characters rather than UTF-16 code units.
	 */
	@Test
	public void sizeCountsCharacters()
	{
		String rendered = new SessionEvidence(List.of(new Tool(1, "root", "shell", "echo", true, 0,
			"\uD83D\uDE00\n"))).render();

		requireThat(rendered, "rendered").contains("Output: 2 characters; the agent received all of it\n");
	}

	/**
	 * Verifies that child events name the child by identity and given name, keep the assignment and received text
	 * whole, and say so when the harness recorded no readable text, instead of leaving the judge to guess.
	 */
	@Test
	public void rendersChildEvents()
	{
		// Requirement: R-E21
		String rendered = new SessionEvidence(List.of(
			new ChildEvent(1, "root", "child-a", "/root/a", ChildEvent.Kind.STARTED, ""),
			new ChildEvent(1, "root", "child-b", "", ChildEvent.Kind.STARTED, "Find the capital\nof Japan."),
			new ChildEvent(1, "root", "child-b", "", ChildEvent.Kind.ENDED, "Status: completed"),
			new ChildEvent(1, "root", "child-a", "/root/a", ChildEvent.Kind.RECEIVED, "Tokyo is the capital."),
			new ChildEvent(1, "root", "child-b", "", ChildEvent.Kind.RECEIVED, ""))).render();

		requireThat(rendered, "rendered").isEqualTo("""
			No tool or hook activity was recorded.
			Turn 1
			Actor: root
			Started child child-a (/root/a)
			Assignment: not recorded in readable form

			Actor: root
			Started child child-b
			Assignment:
			Find the capital
			of Japan.

			Actor: root
			Child child-b finished
			Status: completed

			Actor: root
			Received from child child-a (/root/a):
			Tokyo is the capital.

			Actor: root
			Received from child child-b:
			(not recorded in readable form)

			""");
	}

	/**
	 * Verifies that an empty activity list explicitly establishes the absence of tools and hooks.
	 */
	@Test
	public void emptyEvidenceIsExplicit()
	{
		// Requirement: R-E21
		requireThat(new SessionEvidence(List.of()).render(), "rendered").contains("No tool or hook activity");
	}

	/**
	 * Verifies that the activity list and the received tool results are independent of subsequent caller changes.
	 */
	@Test
	public void copiesEntries()
	{
		// Requirement: R-E21
		List<Entry> entries = new ArrayList<>();
		entries.add(new Hook(1, "root", "before-turn", "completed"));
		List<String> received = new ArrayList<>();
		received.add("result");
		SessionEvidence evidence = new SessionEvidence(entries, received);
		entries.clear();
		received.clear();

		requireThat(evidence.entries().size(), "entryCount").isEqualTo(1);
		requireThat(evidence.received(), "received").isEqualTo(List.of("result"));
	}

	/**
	 * Verifies that evidence created without received tool results holds every recorded tool output as received,
	 * which describes a harness whose agents receive each output whole.
	 */
	@Test
	public void outputsAreReceivedByDefault()
	{
		SessionEvidence evidence = new SessionEvidence(List.of(new Hook(1, "root", "before-turn", "completed"),
			new Tool(1, "root", "shell", "true", true, 0, ""), new Tool(1, "root", "Read", "{}", false, null, "text")));

		requireThat(evidence.received(), "received").isEqualTo(List.of("", "text"));
	}
}
