package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import org.testng.annotations.Test;

/**
 * Tests {@link PromptInput} runner-input materialization.
 */
public final class PromptInputTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptInputTest()
	{
	}

	/**
	 * Verifies a direct prompt produces one runner turn followed by completion.
	 *
	 * @throws IOException if JSON input cannot be serialized
	 */
	@Test
	public void createsDirectPromptInput() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			String input = PromptInput.forPrompt(scope.json(), "Update the configuration.");

			requireThat(input, "input").isEqualTo(
				"{\"type\":\"turn\",\"prompt\":\"Update the configuration.\"}" + System.lineSeparator() +
					"{\"type\":\"complete\"}" + System.lineSeparator());
		}
	}
}
