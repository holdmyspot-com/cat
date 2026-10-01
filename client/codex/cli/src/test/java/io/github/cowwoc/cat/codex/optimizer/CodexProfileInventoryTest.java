package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.cli.HarnessVersion;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.contract.ProfileInventoryContract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.Test;

/**
 * Tests {@link CodexProfileInventory} over the recorded output of {@code codex debug models} from codex-cli 0.159.1,
 * trimmed to the fields that the inventory reads, and over agent definitions in a temporary artifact.
 */
public final class CodexProfileInventoryTest
{
	private static final String VERSION = "codex-cli 0.159.1+23";
	private static final List<String> ULTRA = List.of("low", "medium", "high", "xhigh", "max", "ultra");
	private static final List<String> MAX = List.of("low", "medium", "high", "xhigh", "max");

	/**
	 * Creates the test.
	 */
	public CodexProfileInventoryTest()
	{
	}

	/**
	 * Returns the recorded output of {@code codex debug models}.
	 *
	 * @return the output
	 * @throws IOException if the resource cannot be read
	 */
	private static String recorded() throws IOException
	{
		try (InputStream stream = CodexProfileInventoryTest.class.getResourceAsStream(
			"/optimizer/codex-debug-models.json"))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8);
		}
	}

	/**
	 * Returns a launcher that answers {@code codex --version} and {@code codex debug models}.
	 *
	 * @param models the output of {@code codex debug models}
	 * @param commands receives each command that runs
	 * @return the launcher
	 */
	private static HarnessVersion.Launcher launcher(String models, List<List<String>> commands)
	{
		return command ->
		{
			commands.add(command);
			if (command.equals(List.of("codex", "--version")))
				return VERSION + "\n";
			if (command.equals(List.of("codex", "debug", "models")))
				return models;
			throw new IOException("Unexpected command: " + command);
		};
	}

	/**
	 * Returns the content of a custom agent that binds a profile.
	 *
	 * @param profile the profile; an empty dimension is left out of the file
	 * @return the content
	 */
	private static String agent(TrialProfile profile)
	{
		List<String> lines = new ArrayList<>(List.of("name = \"reviewer\"", "description = \"Reviews a change.\""));
		if (!profile.model().isEmpty())
			lines.add("model = \"" + profile.model() + "\"");
		if (!profile.effort().isEmpty())
			lines.add("model_reasoning_effort = \"" + profile.effort() + "\"");
		lines.add("developer_instructions = '''");
		lines.add("Review the change.");
		lines.add("'''");
		return String.join("\n", lines) + "\n";
	}

	/**
	 * Writes the {@code optimize-xhigh} agent definition into an artifact.
	 *
	 * @param artifact the artifact
	 * @param fields the lines that select the model and effort
	 * @throws IOException if the file cannot be written
	 */
	private static void writeAgent(Path artifact, String fields) throws IOException
	{
		Path agents = Files.createDirectories(artifact.resolve("agents/codex"));
		Files.writeString(agents.resolve("optimize-xhigh.toml"), "name = \"cat-optimize-xhigh\"\n" + fields +
			"developer_instructions = '''\nImplementer tier: xhigh.\n'''\n", UTF_8);
	}

	/**
	 * Returns the contract over an inventory whose artifact holds an {@code optimize-xhigh} definition.
	 *
	 * @param artifact the artifact
	 * @return the contract
	 * @throws IOException if the artifact or the recording cannot be read
	 */
	private static ProfileInventoryContract contract(Path artifact) throws IOException
	{
		writeAgent(artifact, "model = \"gpt-6.1-sol\"\nmodel_reasoning_effort = \"high\"\n");
		CodexProfileInventory port = new CodexProfileInventory(artifact, "codex",
			launcher(recorded(), new CopyOnWriteArrayList<>()));
		return new ProfileInventoryContract(new ProfileInventoryContract.Subject()
		{
			@Override
			public ProfileInventory port()
			{
				return port;
			}

			@Override
			public Path bindingFile()
			{
				return Path.of(".codex/agents/reviewer.toml");
			}

			@Override
			public String content(TrialProfile profile)
			{
				return agent(profile);
			}
		});
	}

	/**
	 * Verifies that the recorded inventory holds the 8 models that Codex lists, each with every reasoning effort that
	 * it supports, leaves out the hidden models, and names the command and the Codex version as its source.
	 *
	 * @throws IOException if the inventory cannot be read
	 */
	@Test
	public void keepsListedModelsWithTheirEfforts() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			List<List<String>> commands = new CopyOnWriteArrayList<>();
			ProfileCatalog inventory = new CodexProfileInventory(artifact, "codex", launcher(recorded(), commands)).
				inventory();

			Map<String, List<String>> expected = new LinkedHashMap<>();
			expected.put("gpt-6-astra", ULTRA);
			expected.put("gpt-6.1-sol", ULTRA);
			expected.put("gpt-6-sol", ULTRA);
			expected.put("gpt-6-luna", MAX);
			expected.put("gpt-5.6-sol", ULTRA);
			expected.put("gpt-5.6-terra", ULTRA);
			expected.put("gpt-5.6-luna", MAX);
			expected.put("gpt-5.5", List.of("low", "medium", "high", "xhigh"));
			Map<String, List<String>> actual = new LinkedHashMap<>();
			for (TrialProfile profile : inventory.profiles())
				actual.computeIfAbsent(profile.model(), _ -> new ArrayList<>()).add(profile.effort());
			requireThat(actual, "inventory").isEqualTo(expected);
			requireThat(inventory.source(), "source").isEqualTo("`codex debug models` of " + VERSION);
			requireThat(commands, "commands").contains(List.of("codex", "debug", "models"));
		}
	}

	/**
	 * Verifies that output that the harness prints before the model list, such as a warning, is skipped.
	 *
	 * @throws IOException if the inventory cannot be read
	 */
	@Test
	public void skipsOutputBeforeTheModelList() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			String output = "WARNING: proceeding, even though we could not update PATH\n" +
				"{\"models\":[{\"slug\":\"gpt-5.5\",\"visibility\":\"list\"," +
				"\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}\nWARNING: trailing\n";
			ProfileCatalog inventory = new CodexProfileInventory(artifact, "codex",
				launcher(output, new CopyOnWriteArrayList<>())).inventory();

			requireThat(inventory.profiles(), "profiles").isEqualTo(List.of(new TrialProfile("gpt-5.5", "low")));
		}
	}

	/**
	 * Verifies that a listed model that names no reasoning level is offered without an effort dimension.
	 *
	 * @throws IOException if the inventory cannot be read
	 */
	@Test
	public void modelWithoutLevelsHasNoEffortDimension() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			String output = "{\"models\":[{\"slug\":\"gpt-plain\",\"visibility\":\"list\"}]}\n";
			ProfileCatalog inventory = new CodexProfileInventory(artifact, "codex",
				launcher(output, new CopyOnWriteArrayList<>())).inventory();

			requireThat(inventory.profiles(), "profiles").isEqualTo(List.of(new TrialProfile("gpt-plain", "")));
		}
	}

	/**
	 * Verifies that a model list whose every model is hidden is reported, rather than offering no profile.
	 *
	 * @throws IOException if the artifact cannot be created
	 */
	@Test
	public void rejectsAListWithoutListedModels() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			String output = "{\"models\":[{\"slug\":\"gpt-hidden\",\"visibility\":\"hide\"," +
				"\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}\n";
			CodexProfileInventory port = new CodexProfileInventory(artifact, "codex",
				launcher(output, new CopyOnWriteArrayList<>()));
			try
			{
				port.inventory();
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("no model with visibility list");
				return;
			}
			throw new AssertionError("A model list without a listed model must be reported");
		}
	}

	/**
	 * Verifies that output without a model list is reported.
	 *
	 * @throws IOException if the artifact cannot be created
	 */
	@Test
	public void rejectsOutputWithoutModels() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			CodexProfileInventory port = new CodexProfileInventory(artifact, "codex",
				launcher("error: unknown subcommand 'debug'\n", new CopyOnWriteArrayList<>()));
			try
			{
				port.inventory();
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("codex debug models");
				return;
			}
			throw new AssertionError("Output without a model list must be reported");
		}
	}

	/**
	 * Verifies that the inventory lists profiles with a source.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void listsProfilesWithSource() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract(cleanup.add(Files.createTempDirectory("cat-profiles-"))).listsProfilesWithSource();
		}
	}

	/**
	 * Verifies that only custom agents bind a profile.
	 *
	 * @throws IOException if the artifact cannot be written
	 */
	@Test
	public void bindsOnlyBindingFiles() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract(cleanup.add(Files.createTempDirectory("cat-profiles-"))).bindsOnlyBindingFiles();
		}
	}

	/**
	 * Verifies that a custom agent binds both dimensions, one dimension, or nothing.
	 *
	 * @throws IOException if the artifact cannot be written
	 */
	@Test
	public void readsCompletePartialAndEmptyBindings() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract(cleanup.add(Files.createTempDirectory("cat-profiles-"))).readsCompletePartialAndEmptyBindings();
		}
	}

	/**
	 * Verifies that the role profile binds a dimension.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void roleProfileIsBound() throws IOException
	{
		// Requirement: R-E17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract(cleanup.add(Files.createTempDirectory("cat-profiles-"))).roleProfileIsBound();
		}
	}

	/**
	 * Verifies that a custom agent binds only through its top-level fields, and that Codex skills and other TOML
	 * files bind nothing.
	 *
	 * @throws IOException if the artifact cannot be created
	 */
	@Test
	public void readsTopLevelAgentFieldsOnly() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexProfileInventory port = new CodexProfileInventory(
				cleanup.add(Files.createTempDirectory("cat-profiles-")), "codex",
				launcher("", new CopyOnWriteArrayList<>()));
			requireThat(port.canBind(Path.of("plugin/agents/codex/reviewer.toml")), "pluginAgent").isTrue();
			for (String name : List.of(".codex/skills/review/SKILL.md", ".codex/config.toml",
				".codex/agents/reviewer.md"))
			{
				requireThat(port.canBind(Path.of(name)), "canBind(" + name + ")").isFalse();
			}
			String content = """
				name = "reviewer"
				developer_instructions = '''
				model = "gpt-5.5"
				'''
				model_reasoning_effort = 'low'

				[mcp_servers.docs]
				model = "gpt-6-sol"
				""";
			requireThat(port.binding(content), "binding").isEqualTo(new TrialProfile("", "low"));
		}
	}

	/**
	 * Verifies that the role profile is the one that the artifact's {@code optimize-xhigh} definition binds.
	 *
	 * @throws IOException if the artifact cannot be written
	 */
	@Test
	public void roleProfileComesFromTheInstalledAgent() throws IOException
	{
		// Requirement: R-E17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			writeAgent(artifact, "model = \"gpt-5.5\"\nmodel_reasoning_effort = \"xhigh\"\n");

			requireThat(new CodexProfileInventory(artifact, "codex", launcher("", new CopyOnWriteArrayList<>())).
				roleProfile(), "roleProfile").isEqualTo(new TrialProfile("gpt-5.5", "xhigh"));
		}
	}
}
