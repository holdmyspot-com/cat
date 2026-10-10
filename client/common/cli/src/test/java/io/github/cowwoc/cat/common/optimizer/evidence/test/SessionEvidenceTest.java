package io.github.cowwoc.cat.common.optimizer.evidence.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Assistant;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Entry;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Hook;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Tool;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;

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
			contains("Output:\nready\n").contains("{\"path\":\"VERSION\"}").
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
	 * Verifies that a tool's entire output remains present beyond 200,000 characters.
	 */
	@Test
	public void keepsLargeOutputComplete()
	{
		// Requirement: R-E21
		String output = "complete output\n".repeat(15_000) + "final sentinel";
		String rendered = new SessionEvidence(List.of(new Tool(1, "root", "shell", "cat large.txt", true, 0,
			output))).render();

		requireThat(rendered, "rendered").contains(output);
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
	 * Verifies that the activity list is independent of subsequent caller changes.
	 */
	@Test
	public void copiesEntries()
	{
		// Requirement: R-E21
		List<Entry> entries = new ArrayList<>();
		entries.add(new Hook(1, "root", "before-turn", "completed"));
		SessionEvidence evidence = new SessionEvidence(entries);
		entries.clear();

		requireThat(evidence.entries().size(), "entryCount").isEqualTo(1);
	}
}
