package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.IgnoredFiles;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.execution.TrialSelection;
import io.github.cowwoc.cat.common.optimizer.port.SourceSelection;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies that a trial's base selection holds the files that load the prompt system and nothing else of the project.
 */
public final class TrialSelectionTest
{
	/**
	 * Creates the test.
	 */
	public TrialSelectionTest()
	{
	}

	/**
	 * Verifies that the base selection holds the prompt sources, the harness's project files, the in-project loader
	 * configuration, CAT's project directory, the files that plugin mappings cover, the in-project dependents and the
	 * ignored files, and leaves out the project's other files.
	 */
	@Test
	public void baseHoldsOnlyThePromptSystem()
	{
		// Requirement: R-W15
		Contract base = ContractFixtures.contract();
		Contract contract = ContractFixtures.withDependents(new Contract(base.requirements(), base.tests(),
				base.profiles(), base.judge(), new IgnoredFiles(List.of("build/generated.md")), base.limitations(),
				base.minimumPassRate(), base.salt(), base.dependents()),
			List.of(new Dependent(Dependent.Location.PROJECT, "", Path.of("scripts/repair.sh"),
				new Dependent.WholeFile(), "Repairs a file.")));
		TrialPlugin plugin = new TrialPlugin("cat", "a".repeat(64),
			new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(new TrialPlugin.Mapping(Path.of("plugin/rules"), Path.of("rules"))));

		SourceSelection selection = TrialSelection.base(Harness.CODEX, Set.of(Path.of("docs/prompt.md")),
			Set.of(Path.of(".codex/config.toml"), Path.of("/home/user/.codex/config.toml")), contract,
			List.of(plugin));

		for (String selected : List.of("docs/prompt.md", "module/AGENTS.md", "AGENTS.override.md",
			".agents/skills/a/SKILL.md", ".codex/config.toml", ".cat/rules/r.md", "plugin/rules/x.md",
			"scripts/repair.sh", "build/generated.md"))
		{
			requireThat(selection.selects(Path.of(selected)), selected).isTrue();
		}
		for (String left : List.of("docs/other.md", "src/Main.java", "plugin/other.md", "module/CLAUDE.md"))
			requireThat(selection.selects(Path.of(left)), left).isFalse();
	}

	/**
	 * Verifies that before a contract exists, the base selection holds the prompt sources, the harness's project files,
	 * the in-project loader configuration, CAT's project directory and the files that plugin mappings cover.
	 */
	@Test
	public void baseBeforeContractHoldsThePromptSystem()
	{
		// Requirement: R-W15
		TrialPlugin plugin = new TrialPlugin("cat", "a".repeat(64),
			new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(new TrialPlugin.Mapping(Path.of("plugin/rules"), Path.of("rules"))));

		SourceSelection selection = TrialSelection.base(Harness.CLAUDE, Set.of(Path.of("docs/prompt.md")),
			Set.of(Path.of(".claude/settings.json")), List.of(plugin));

		for (String selected : List.of("docs/prompt.md", "module/CLAUDE.md", "CLAUDE.local.md",
			".claude/skills/a/SKILL.md", ".cat/rules/r.md", "plugin/rules/x.md"))
		{
			requireThat(selection.selects(Path.of(selected)), selected).isTrue();
		}
		for (String left : List.of("docs/other.md", "src/Main.java", "module/AGENTS.md", ".codex/config.toml"))
			requireThat(selection.selects(Path.of(left)), left).isFalse();
	}
}
