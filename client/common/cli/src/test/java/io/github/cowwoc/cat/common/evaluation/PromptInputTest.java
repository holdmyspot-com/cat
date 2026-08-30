package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

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
	 * Verifies runner input excludes evaluator-only scenario assertions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void hidesScenarioAssertionsFromRunner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-input"));
			Path scenario = root.resolve("scenario.md");
			Files.writeString(scenario, """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				The agent retained the changed configuration.
				""");
			ObjectNode testCase = scope.json().createObjectNode();
			testCase.put("scenario", true);

			String bridge = PromptInput.forTestCase(scope.json(), testCase, root, scenario);

			requireThat(bridge, "bridge").contains("Update the configuration.").
				doesNotContain("records-change").doesNotContain("retained the changed configuration");
		}
	}
}
