package com.holdmyspot.cat.common.optimizer.workspace.test;

import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.workspace.ControlSource;
import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;
import com.holdmyspot.cat.common.optimizer.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that the control source empties the target prompt file and removes each dependent that the contract lists,
 * and that it reports each dependent that the fork or the plugin does not hold as the contract lists it.
 */
public final class ControlSourceTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Path TARGET = Path.of("prompt.md");
	private static final String MENTION = "See prompt.md for the repair rules.\n";
	private static final String HOOKS = """
		{"hooks": {
		  "SessionStart": [{"hooks": [{"type": "command", "command": "start.sh"}]}],
		  "PostToolUse": [{"matcher": "^Bash$", "hooks": [{"type": "command", "command": "repair.sh"}]}]
		}}
		""";

	/**
	 * Verifies that control trials run on a source in which the target is empty, a whole-file dependent is gone, a span
	 * dependent is deleted from its file and a hook dependent is unregistered, while every other file and hook stays,
	 * including a file that mentions the target but is not listed as its dependent.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void removesEachDependent() throws IOException
	{
		// Requirement: R-W10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = project(cleanup.add(Files.createTempDirectory("cat-control-")));
			List<Dependent> dependents = List.of(
				new Dependent(Path.of("repair.sh"), new Dependent.WholeFile(),
					"Repairs the output that the prompt asks for."),
				new Dependent(Path.of("AGENTS.md"),
					new Dependent.Span("Read prompt.md first.\n"), "Routes the agent to the prompt."),
				new Dependent(Path.of("hooks.json"),
					new Dependent.HookRegistration("PostToolUse", "repair.sh"), "Runs the repair script."));

			List<String> problems = ControlSource.problems(project.port(), project.tree().inventory(), TARGET,
				dependents, JSON);
			Path staged = project.port().stage(ControlSource.of(project.port(), project.tree().inventory(), TARGET,
				dependents, JSON), Set.of());

			requireThat(problems, "problems").isEmpty();
			requireThat(Files.size(staged.resolve(TARGET)), "targetSize").isEqualTo(0L);
			requireThat(Files.exists(staged.resolve("repair.sh")), "scriptExists").isFalse();
			requireThat(Files.readString(staged.resolve("AGENTS.md")), "agents").isEqualTo("Be brief.\n");
			JsonNode hooks = JSON.readTree(Files.readString(staged.resolve("hooks.json"))).get("hooks");
			requireThat(hooks.has("PostToolUse"), "removedHook").isFalse();
			requireThat(hooks.has("SessionStart"), "otherHook").isTrue();
			requireThat(Files.readString(staged.resolve("other.md")), "other").isEqualTo(MENTION);
		}
	}

	/**
	 * Verifies that the control source reports the target listed as its own dependent, a dependent whose file the fork
	 * does not hold and a span that occurs more than once, and refuses to build while any is reported.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsUnheldDependents() throws IOException
	{
		// Requirement: R-W10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = project(cleanup.add(Files.createTempDirectory("cat-control-")));
			List<Dependent> dependents = List.of(
				new Dependent(TARGET, new Dependent.WholeFile(), "Is the prompt."),
				new Dependent(Path.of("missing.sh"), new Dependent.WholeFile(),
					"Was removed before the fork."),
				new Dependent(Path.of("AGENTS.md"), new Dependent.Span("r"),
					"Matches more than one place."));

			List<String> problems = ControlSource.problems(project.port(), project.tree().inventory(), TARGET,
				dependents, JSON);
			IllegalArgumentException refusal = null;
			try
			{
				ControlSource.of(project.port(), project.tree().inventory(), TARGET, dependents, JSON);
			}
			catch (IllegalArgumentException e)
			{
				refusal = e;
			}

			requireThat(problems, "problems").size().isEqualTo(3);
			requireThat(problems.get(0), "targetProblem").contains("is the target prompt file");
			requireThat(problems.get(1), "missingProblem").contains("missing.sh").contains("is not a regular file");
			requireThat(problems.get(2), "spanProblem").contains("AGENTS.md").contains("times");
			requireThat(refusal, "refusal").isNotNull();
		}
	}

	/**
	 * Verifies that a built plugin's removals are refused when one is the target's copy in the plugin, which control
	 * trials already empty, or when the plugin's base does not hold it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsUnheldPluginRemovals() throws IOException
	{
		// Requirement: R-W10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-control-")).resolve("plugin");
			writeFile(base.resolve("rules/codex/a.md"), "rule a\n", READ_WRITE);
			writeFile(base.resolve("bin/read-part"), "#!/bin/sh\n", EXECUTABLE);
			TrialPlugin plugin = new TrialPlugin("cat", InventoryScanner.scan(base, Set.of()).identity().hex(),
				new TrialPlugin.Build(List.of("build"), Path.of("out")),
				List.of(new TrialPlugin.Mapping(Path.of("rules"), Path.of("rules/codex"))));
			List<Dependent> removals = List.of(
				new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("rules/codex/a.md"), new Dependent.WholeFile(),
					"Is the prompt's copy."),
				new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.WholeFile(),
					"Reads the part that the rule names."),
				new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/run-part"), new Dependent.WholeFile(),
					"Runs the part that the rule names."));

			List<String> problems = ControlSource.pluginProblems(plugin, base, Path.of("rules/a.md"), removals,
				JSON);

			requireThat(problems, "problems").size().isEqualTo(2);
			requireThat(problems.get(0), "copyProblem").contains("is the target prompt file's copy in the plugin");
			requireThat(problems.get(1), "missingProblem").contains(Path.of("bin/run-part").toString()).
				contains("is not a regular file");
		}
	}

	/**
	 * Creates a project whose target prompt file has a script, a routing line and a hook that depend on it, plus a file
	 * that mentions it without depending on it, and records it as a fork tree.
	 *
	 * @param root the directory that owns the fixture
	 * @return the project
	 * @throws IOException if the fixture cannot be created
	 */
	private static Project project(Path root) throws IOException
	{
		Path base = Files.createDirectories(root.resolve("project"));
		Path campaign = root.resolve("campaign");
		writeFile(base.resolve(TARGET), "Repair every answer.\n", READ_WRITE);
		writeFile(base.resolve("repair.sh"), "#!/bin/sh\n", EXECUTABLE);
		writeFile(base.resolve("AGENTS.md"), "Read prompt.md first.\nBe brief.\n", READ_WRITE);
		writeFile(base.resolve("hooks.json"), HOOKS, READ_WRITE);
		writeFile(base.resolve("other.md"), MENTION, READ_WRITE);
		ForkTree tree = ForkTree.record(base, campaign, Set.of(TARGET, Path.of("repair.sh"), Path.of("AGENTS.md"),
			Path.of("hooks.json"), Path.of("other.md")));
		return new Project(tree, new Workspace(tree, campaign, campaign.resolve("worktree")));
	}

	/**
	 * A recorded project and the workspace port that reads it.
	 *
	 * @param tree the fork tree
	 * @param port the workspace port
	 */
	private record Project(ForkTree tree, WorkspacePort port)
	{
	}
}
