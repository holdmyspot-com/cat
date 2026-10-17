package io.github.cowwoc.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.claude.optimizer.ClaudeProfileInventory;
import io.github.cowwoc.cat.claude.secrets.ClaudeProfileInventorySecrets;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.ProfileInventoryContract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeProfileInventory} over the inventory resource that it ships and over agent definitions in a
 * temporary artifact.
 */
public final class ClaudeProfileInventoryTest
{
	private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

	/**
	 * Creates the test.
	 */
	public ClaudeProfileInventoryTest()
	{
	}

	/**
	 * Returns the content of a skill whose frontmatter binds a profile.
	 *
	 * @param profile the profile; an empty dimension is left out of the frontmatter
	 * @return the content
	 */
	private static String skill(TrialProfile profile)
	{
		List<String> lines = new ArrayList<>(List.of("---", "name: review", "description: \"Reviews a change.\""));
		if (!profile.model().isEmpty())
			lines.add("model: " + profile.model());
		if (!profile.effort().isEmpty())
			lines.add("effort: " + profile.effort());
		lines.add("---");
		lines.add("Review the change.");
		return String.join("\n", lines) + "\n";
	}

	/**
	 * Returns the contract over an inventory whose artifact holds an {@code optimize-xhigh} definition.
	 *
	 * @param artifact the artifact
	 * @return the contract
	 * @throws IOException if the artifact cannot be written
	 */
	private static ProfileInventoryContract contract(Path artifact) throws IOException
	{
		writeAgent(artifact, "model: opus\neffort: high\n");
		ClaudeProfileInventory port = new ClaudeProfileInventory(artifact);
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
				return Path.of(".claude/skills/review/SKILL.md");
			}

			@Override
			public String content(TrialProfile profile)
			{
				return skill(profile);
			}
		});
	}

	/**
	 * Writes the {@code optimize-xhigh} agent definition into an artifact.
	 *
	 * @param artifact the artifact
	 * @param fields the frontmatter lines that follow the agent's name
	 * @throws IOException if the file cannot be written
	 */
	private static void writeAgent(Path artifact, String fields) throws IOException
	{
		Path agents = Files.createDirectories(artifact.resolve("agents/claude"));
		Files.writeString(agents.resolve("optimize-xhigh.md"), "---\nname: optimize-xhigh\n" + fields +
			"---\nImplementer tier: xhigh.\n", UTF_8);
	}

	/**
	 * Verifies that the shipped inventory lists {@code opus}, {@code fable} and {@code sonnet} with every documented
	 * effort, and {@code haiku} with no effort dimension, and names the documentation of each alias.
	 *
	 * @throws IOException if the inventory cannot be read
	 */
	@Test
	public void listsTheDocumentedAliases() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			ProfileCatalog inventory = new ClaudeProfileInventory(artifact).inventory();

			List<TrialProfile> expected = new ArrayList<>();
			for (String model : List.of("opus", "fable", "sonnet"))
			{
				for (String effort : EFFORTS)
					expected.add(new TrialProfile(model, effort));
			}
			expected.add(new TrialProfile("haiku", ""));
			requireThat(inventory.profiles(), "profiles").isEqualTo(expected);
			requireThat(inventory.source(), "source").contains("claude --help").
				contains("fable: `claude --help` names the alias").contains("claude-fable-5-1").
				contains("haiku: the changelog names the haiku alias");
		}
	}

	/**
	 * Verifies that the shipped capability ranking puts {@code fable} above {@code opus} above {@code sonnet} above
	 * {@code haiku}, names the documentation's address and reading date, and ranks every model of the shipped
	 * inventory.
	 *
	 * @throws IOException if the inventory or the ranking cannot be read
	 */
	@Test
	public void ranksTheDocumentedFamilies() throws IOException
	{
		// Requirement: R-E22
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			ClaudeProfileInventory port = new ClaudeProfileInventory(artifact);
			CapabilityRanking ranking = port.ranking();
			requireThat(ranking.families(), "families").isEqualTo(List.of("fable", "opus", "sonnet", "haiku"));
			requireThat(ranking.source(), "source").
				isEqualTo("https://platform.claude.com/docs/en/models/overview, read 2026-10-04");
			requireThat(ranking.unranked(port.inventory()), "unranked").isEmpty();
		}
	}

	/**
	 * Verifies that the shipped inventory lists each model's efforts from the lowest to the highest, so that a model's
	 * first listed effort is its lowest, which the weakest cell uses. A model without an
	 * effort dimension has one profile.
	 *
	 * @throws IOException if the inventory cannot be read
	 */
	@Test
	public void listsEachModelsEffortsLowestFirst() throws IOException
	{
		// Requirement: R-E19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			ProfileCatalog inventory = new ClaudeProfileInventory(artifact).inventory();
			List<String> ladder = List.of("low", "medium", "high", "xhigh", "max", "ultra");
			Map<String, Integer> previous = new HashMap<>();
			for (TrialProfile profile : inventory.profiles())
			{
				if (profile.effort().isEmpty())
				{
					requireThat(previous.put(profile.model(), -1), profile.toString()).isNull();
					continue;
				}
				int rank = ladder.indexOf(profile.effort());
				requireThat(rank, profile.toString()).isNotNegative();
				Integer lower = previous.put(profile.model(), rank);
				if (lower != null)
					requireThat(rank, profile.toString()).isGreaterThan(lower);
			}
			requireThat(previous.size(), "models").isEqualTo(4);
		}
	}

	/**
	 * Returns inventory resources that depart from the resource format.
	 *
	 * @return the departure, and the resource
	 */
	@DataProvider(name = "malformedResources")
	public Object[][] malformedResources()
	{
		String entry = "{\"model\": \"opus\", \"efforts\": [\"low\"], \"documentation\": \"the help\"}";
		return new Object[][]
			{
				{"another format version", "{\"format_version\": 2, \"source\": \"s\", \"models\": [" + entry + "]}"},
				{"an entry without documentation", "{\"format_version\": 1, \"source\": \"s\", \"models\": " +
					"[{\"model\": \"opus\", \"efforts\": [\"low\"]}]}"},
				{"an entry with an unknown field", "{\"format_version\": 1, \"source\": \"s\", \"models\": " +
					"[{\"model\": \"opus\", \"efforts\": [], \"documentation\": \"d\", \"alias\": \"o\"}]}"},
				{"a root with an unknown field", "{\"format_version\": 1, \"source\": \"s\", \"extra\": 1, " +
					"\"models\": [" + entry + "]}"}
			};
	}

	/**
	 * Verifies that an inventory resource that departs from the resource format is reported, rather than read as
	 * far as it goes.
	 *
	 * @param description the way in which the resource departs from the format
	 * @param resource the resource
	 */
	@Test(dataProvider = "malformedResources")
	public void rejectsAMalformedResource(String description, String resource)
	{
		// Requirement: R-C10
		try
		{
			ClaudeProfileInventorySecrets.access().parse(new ByteArrayInputStream(resource.getBytes(UTF_8)));
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").contains("claude-profiles.json");
			return;
		}
		throw new AssertionError("A resource with " + description + " must be reported");
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
	 * Verifies that only skills and subagents bind a profile.
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
	 * Verifies that skill frontmatter binds both dimensions, one dimension, or nothing.
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
	 * Verifies that subagent and plugin agent files bind a profile, that a skill's other files and the harness's
	 * memory and rule files do not, that {@code inherit} binds nothing, and that fields outside the frontmatter bind
	 * nothing.
	 *
	 * @throws IOException if the artifact cannot be written
	 */
	@Test
	public void readsSkillAndSubagentFrontmatterOnly() throws IOException
	{
		// Requirement: R-C10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ClaudeProfileInventory port = new ClaudeProfileInventory(
				cleanup.add(Files.createTempDirectory("cat-profiles-")));
			for (String name : List.of(".claude/agents/reviewer.md", "plugin/agents/claude/reviewer.md",
				"plugin/skills/review/SKILL.md"))
			{
				requireThat(port.canBind(Path.of(name)), "canBind(" + name + ")").isTrue();
			}
			for (String name : List.of(".claude/skills/review/notes.md", ".claude/agents/reviewer.toml",
				"docs/SKILL.md"))
			{
				requireThat(port.canBind(Path.of(name)), "canBind(" + name + ")").isFalse();
			}
			requireThat(port.binding("---\nname: reviewer\nmodel: inherit\neffort: \"low\"\n---\nReview.\n"),
				"inherit").isEqualTo(new TrialProfile("", "low"));
			requireThat(port.binding("No frontmatter.\nmodel: opus\n"), "noFrontmatter").
				isEqualTo(TrialProfile.HARNESS_DEFAULT);
			requireThat(port.binding("---\nname: reviewer\n---\nmodel: opus\neffort: high\n"), "body").
				isEqualTo(TrialProfile.HARNESS_DEFAULT);
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
			writeAgent(artifact, "model: sonnet\neffort: xhigh\n");

			requireThat(new ClaudeProfileInventory(artifact).roleProfile(), "roleProfile").
				isEqualTo(new TrialProfile("sonnet", "xhigh"));
		}
	}

	/**
	 * Verifies that a missing {@code optimize-xhigh} definition is reported rather than replaced by a default.
	 *
	 * @throws IOException if the artifact cannot be created
	 */
	@Test
	public void missingRoleAgentIsReported() throws IOException
	{
		// Requirement: R-E17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-profiles-"));
			try
			{
				new ClaudeProfileInventory(artifact).roleProfile();
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("optimize-xhigh");
				return;
			}
			throw new AssertionError("A missing role agent must be reported");
		}
	}

	/**
	 * Verifies that Claude supports every model name, including a name that its inventory does not list.
	 *
	 * @throws IOException if the artifact cannot be created
	 */
	@Test
	public void supportsEveryModel() throws IOException
	{
		// Requirement: R-D18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ClaudeProfileInventory port = new ClaudeProfileInventory(cleanup.add(Files.createTempDirectory("cat-profiles-")));
			for (String model : List.of("opus", "sonnet", "haiku", "gpt-5.6-luna", "unlisted-model"))
				requireThat(port.supportsModel(model), "supportsModel(" + model + ")").isTrue();
		}
	}

	/**
	 * Verifies that the inventory states no tool-output limit for Claude, whose limit CAT does not know, whatever the
	 * profile's tool-output token limit.
	 *
	 * @throws IOException if the artifact cannot be created
	 */
	@Test
	public void toolOutputLimitIsUnknown() throws IOException
	{
		// Requirement: R-C27
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ClaudeProfileInventory port = new ClaudeProfileInventory(cleanup.add(Files.createTempDirectory("cat-profiles-")));
			TrialProfile profile = new TrialProfile("opus", "high");
			requireThat(port.toolOutputBytes(profile), "default").isEqualTo(OptionalLong.empty());
			requireThat(port.toolOutputBytes(profile.withToolOutputTokenLimit(2000)), "limited").
				isEqualTo(OptionalLong.empty());
		}
	}
}
