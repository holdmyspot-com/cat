package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.SessionRequestInstructions;
import org.testng.annotations.Test;

/**
 * Tests {@link SessionRequestInstructions}.
 */
public final class SessionRequestInstructionsTest
{
	/**
	 * Creates the test.
	 */
	public SessionRequestInstructionsTest()
	{
	}

	/**
	 * Verifies that the instructions say when to request a session, how many the session may request, which command
	 * makes a request and how to wait again, and that inputs and results pass as file paths.
	 */
	@Test
	public void explainsRequests()
	{
		// Requirement: R-I9
		String text = SessionRequestInstructions.text(3);

		requireThat(text, "text").contains("new session").contains("plugin").contains("up to 3").
			contains("\"$CAT_REQUEST_SESSION\" --harness").contains("--project").contains("--prompt").
			contains("--plugin").contains("--await").contains("file path");
	}
}
