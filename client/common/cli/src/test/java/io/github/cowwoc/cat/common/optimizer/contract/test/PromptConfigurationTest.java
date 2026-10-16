package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.PromptConfiguration;
import io.github.cowwoc.cat.common.optimizer.contract.PromptConfigurationException;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;

import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link PromptConfiguration}.
 */
public final class PromptConfigurationTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public PromptConfigurationTest()
	{
	}

	/**
	 * Returns the problems that parsing reports.
	 *
	 * @param content a configuration that is refused
	 * @return the exception's problems
	 */
	private List<String> refusal(String content)
	{
		try
		{
			PromptConfiguration.parse(json, content);
		}
		catch (PromptConfigurationException e)
		{
			return e.problems();
		}
		throw new AssertionError("The configuration was accepted:\n" + content);
	}

	/**
	 * Verifies that the configuration lies in the target's test directory.
	 */
	@Test
	public void liesInTestDirectory()
	{
		// Requirement: R-W12
		requireThat(PromptConfiguration.location(Path.of("client/codex/rules/src/main/resources/rules/codex/read-file.md")),
			"location").isEqualTo(Path.of("client/codex/rules/src/test/resources/codex/read-file/prompt.json"));
		requireThat(PromptConfiguration.location(Path.of("CLAUDE.md")), "location").
			isEqualTo(Path.of(".cat/tests/CLAUDE/prompt.json"));
	}

	/**
	 * Verifies that a plugin built by a command, a plugin directory without a command and an installed plugin are read
	 * in order, for each harness.
	 *
	 * @throws PromptConfigurationException if the configuration is refused
	 */
	@Test
	public void readsEachForm() throws PromptConfigurationException
	{
		// Requirement: R-W12
		PromptConfiguration configuration = PromptConfiguration.parse(json, """
			{
			  "claude": {
			    "plugins": [
			      {
			        "name": "cat",
			        "build": {
			          "command": ["client/mvnw", "-f", "client/pom.xml", "package"],
			          "output": "client/target/runtime/claude"
			        }
			      },
			      {"name": "docs", "build": {"output": "plugins/docs"}},
			      {"name": "formatter@acme-tools"}
			    ]
			  },
			  "codex": {"plugins": []}
			}""");
		requireThat(configuration.plugins("claude"), "claude").isEqualTo(Optional.of(List.of(
			new PromptConfiguration.ConfiguredPlugin("cat", Optional.of(new TrialPlugin.Build(
				List.of("client/mvnw", "-f", "client/pom.xml", "package"), Path.of("client/target/runtime/claude")))),
			new PromptConfiguration.ConfiguredPlugin("docs", Optional.of(new TrialPlugin.Build(List.of(),
				Path.of("plugins/docs")))),
			new PromptConfiguration.ConfiguredPlugin("formatter@acme-tools", Optional.empty()))));
		requireThat(configuration.plugins("codex"), "codex").isEqualTo(Optional.of(List.of()));
	}

	/**
	 * Verifies that a configuration without a key for a harness lists no plugins for it.
	 *
	 * @throws PromptConfigurationException if the configuration is refused
	 */
	@Test
	public void missingHarnessIsEmpty() throws PromptConfigurationException
	{
		// Requirement: R-W12
		PromptConfiguration configuration = PromptConfiguration.parse(json, "{\"codex\": {\"plugins\": []}}");
		requireThat(configuration.plugins("claude"), "claude").isEmpty();
	}

	/**
	 * Verifies that content that is not a JSON object is refused at the root.
	 */
	@Test
	public void refusesNonObject()
	{
		// Requirement: R-W12
		requireThat(refusal("[]"), "problems").isEqualTo(List.of("$: must be an object"));
		requireThat(refusal("{"), "problems").size().isEqualTo(1);
		requireThat(refusal("{").getFirst(), "problem").startsWith("$: is not valid JSON: ");
	}

	/**
	 * Verifies that unknown keys are refused with their JSON paths, all at once.
	 */
	@Test
	public void refusesUnknownKeys()
	{
		// Requirement: R-W12
		requireThat(refusal("""
			{
			  "cursor": {},
			  "claude": {
			    "plugins": [{"name": "cat", "version": 2, "build": {"output": "out", "args": []}}],
			    "hooks": []
			  }
			}"""), "problems").isEqualTo(List.of(
			"$.cursor: is unknown. Keys: claude, codex",
			"$.claude.plugins[0].version: is unknown. Keys: name, build",
			"$.claude.plugins[0].build.args: is unknown. Keys: command, output",
			"$.claude.hooks: is unknown. Keys: plugins"));
	}

	/**
	 * Verifies that a harness without a plugin list, or with one of the wrong type, is refused.
	 */
	@Test
	public void refusesMissingPlugins()
	{
		// Requirement: R-W12
		requireThat(refusal("{\"claude\": {}}"), "problems").isEqualTo(List.of("$.claude.plugins: is missing"));
		requireThat(refusal("{\"claude\": []}"), "problems").isEqualTo(List.of("$.claude: must be an object"));
		requireThat(refusal("{\"codex\": {\"plugins\": {}}}"), "problems").
			isEqualTo(List.of("$.codex.plugins: must be a list"));
		requireThat(refusal("{\"codex\": {\"plugins\": [\"cat\"]}}"), "problems").
			isEqualTo(List.of("$.codex.plugins[0]: must be an object"));
	}

	/**
	 * Verifies that a missing, blank or repeated name, and an installed plugin's name without its marketplace, are
	 * refused.
	 */
	@Test
	public void refusesBadNames()
	{
		// Requirement: R-W12
		requireThat(refusal("""
			{
			  "codex": {
			    "plugins": [
			      {"build": {"output": "out"}},
			      {"name": " ", "build": {"output": "out"}},
			      {"name": "formatter"},
			      {"name": "cat", "build": {"output": "out"}},
			      {"name": "cat", "build": {"output": "other"}}
			    ]
			  }
			}"""), "problems").isEqualTo(List.of(
			"$.codex.plugins[0].name: is missing",
			"$.codex.plugins[1].name: must be a non-blank string",
			"$.codex.plugins[2].name: a plugin without build is named <plugin>@<marketplace>, as the harness's " +
				"install records name it",
			"$.codex.plugins[4].name: names the plugin cat a second time"));
	}

	/**
	 * Verifies that a build without an output, with an output outside the project, or with an empty or malformed
	 * command, is refused.
	 */
	@Test
	public void refusesBadBuilds()
	{
		// Requirement: R-W12
		requireThat(refusal("""
			{
			  "codex": {
			    "plugins": [
			      {"name": "a", "build": {"command": ["make"]}},
			      {"name": "b", "build": {"output": "../outside"}},
			      {"name": "c", "build": {"output": "/absolute"}},
			      {"name": "d", "build": {"command": [], "output": "out"}},
			      {"name": "e", "build": {"command": ["make", 2], "output": "out"}},
			      {"name": "f", "build": {"command": [" "], "output": "out"}},
			      {"name": "g", "build": "out"}
			    ]
			  }
			}"""), "problems").isEqualTo(List.of(
			"$.codex.plugins[0].build.output: is missing",
			"$.codex.plugins[1].build.output: must be a directory relative to the project root, inside the " +
				"project: ../outside",
			"$.codex.plugins[2].build.output: must be a directory relative to the project root, inside the " +
				"project: /absolute",
			"$.codex.plugins[3].build.command: must list at least the program; leave it out if the output needs no " +
				"build",
			"$.codex.plugins[4].build.command[1]: must be a string",
			"$.codex.plugins[5].build.command[0]: must be a non-blank string",
			"$.codex.plugins[6].build: must be an object"));
	}
}
