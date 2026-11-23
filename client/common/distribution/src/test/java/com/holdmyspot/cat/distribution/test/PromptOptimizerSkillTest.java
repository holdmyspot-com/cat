package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests the guidance that the {@code prompt-optimizer} skill gives an agent for the commands it runs.
 */
public final class PromptOptimizerSkillTest
{
	/** Creates a test instance. */
	public PromptOptimizerSkillTest()
	{
	}

	/**
	 * Verifies that the skill tells an agent that passes a goal containing an apostrophe to write the apostrophe itself
	 * and never an escape sequence for it. A shell passes an escape such as {@code \x27} inside quotes unchanged, so
	 * {@code start} records the escape text as the goal, and a later {@code start} with the goal the user wrote is
	 * refused as naming a different goal.
	 *
	 * @throws IOException if the skill cannot be read
	 */
	@Test
	public void goalApostropheIsNotEscaped() throws IOException
	{
		List<URL> bodies = Collections.list(PromptOptimizerSkillTest.class.getClassLoader().
			getResources("skills/prompt-optimizer/first-use.md"));
		requireThat(bodies, "bodies").size().isEqualTo(1);
		String body;
		try (InputStream in = bodies.getFirst().openStream())
		{
			// The skill's source wraps lines, so phrases are matched across line breaks
			body = new String(in.readAllBytes(), UTF_8).replaceAll("\\s+", " ");
		}
		int start = body.indexOf("Add `--goal <text>`");
		requireThat(start, "goalStep").isGreaterThanOrEqualTo(0);
		int end = body.indexOf("If the user only asks to improve", start);
		requireThat(end, "goalStepEnd").isGreaterThan(start);
		String goalStep = body.substring(start, end);
		requireThat(goalStep, "goalStep").contains("apostrophe").contains("`\\x27`");
	}
}
