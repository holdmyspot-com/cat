package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.port.SourceSelection;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses the files of a candidate that every trial's project holds.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class TrialSelection
{
	/**
	 * Prevents construction.
	 */
	private TrialSelection()
	{
	}

	/**
	 * Returns the files that load the prompt system into a trial: the prompt sources, the in-project loader
	 * configuration, the project rules, the harness's instruction files in any directory and its top-level project
	 * directories, the project files that plugin mappings write into trial plugins and the in-project dependents. A
	 * trial's project holds no other file of the candidate; a prompt test links any other file that its trials need,
	 * such as one of the contract's ignored files.
	 *
	 * @param harness the harness that runs the trials
	 * @param promptSources the in-project prompt files and directories, relative to the project
	 * @param loaderConfiguration the files and directories that decide which prompt files load, relative to the project
	 *   or absolute; an absolute path lies outside the project and selects nothing
	 * @param contract the contract
	 * @param plugins the campaign's trial plugins
	 * @return the selection
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a prompt source is absolute
	 */
	public static SourceSelection base(Harness harness, Set<Path> promptSources, Set<Path> loaderConfiguration,
		Contract contract, List<TrialPlugin> plugins)
	{
		requireThat(contract, "contract").isNotNull();
		Set<Path> roots = roots(harness, promptSources, loaderConfiguration, plugins);
		for (Dependent dependent : contract.dependents())
		{
			if (dependent.location() == Dependent.Location.PROJECT)
				roots.add(dependent.path());
		}
		return new SourceSelection(roots, Set.copyOf(harness.instructionFiles()), List.of());
	}

	/**
	 * Returns the files that load the prompt system into a session before a contract exists: the prompt sources, the
	 * in-project loader configuration, the project rules, the harness's instruction files in any directory and its
	 * top-level project directories, and the project files that plugin mappings write into trial plugins.
	 *
	 * @param harness the harness that runs the sessions
	 * @param promptSources the in-project prompt files and directories, relative to the project
	 * @param loaderConfiguration the files and directories that decide which prompt files load, relative to the project
	 *   or absolute; an absolute path lies outside the project and selects nothing
	 * @param plugins the campaign's trial plugins
	 * @return the selection
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a prompt source is absolute
	 */
	public static SourceSelection base(Harness harness, Set<Path> promptSources, Set<Path> loaderConfiguration,
		List<TrialPlugin> plugins)
	{
		return new SourceSelection(roots(harness, promptSources, loaderConfiguration, plugins),
			Set.copyOf(harness.instructionFiles()), List.of());
	}

	/**
	 * Returns the directories and files that every base selection holds.
	 *
	 * @param harness the harness that runs the sessions
	 * @param promptSources the in-project prompt files and directories, relative to the project
	 * @param loaderConfiguration the files and directories that decide which prompt files load, relative to the project
	 *   or absolute
	 * @param plugins the campaign's trial plugins
	 * @return a modifiable set of paths relative to the project
	 * @throws NullPointerException if any of the arguments are null
	 */
	private static Set<Path> roots(Harness harness, Set<Path> promptSources, Set<Path> loaderConfiguration,
		List<TrialPlugin> plugins)
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(promptSources, "promptSources").isNotNull();
		requireThat(loaderConfiguration, "loaderConfiguration").isNotNull();
		requireThat(plugins, "plugins").isNotNull();

		Set<Path> roots = new HashSet<>(promptSources);
		for (Path path : loaderConfiguration)
		{
			if (!path.isAbsolute())
				roots.add(path);
		}
		roots.add(ProjectRuleLoader.rulesDirectory(Path.of("")));
		for (String directory : harness.projectDirectories())
			roots.add(Path.of(directory));
		for (TrialPlugin plugin : plugins)
		{
			for (TrialPlugin.Mapping mapping : plugin.mappings())
				roots.add(mapping.source());
		}
		return roots;
	}
}

