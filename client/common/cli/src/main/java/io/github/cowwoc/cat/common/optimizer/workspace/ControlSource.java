package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.json.JsonMapper;

/**
 * The source that control trials run on: the incumbent with the target prompt file emptied to zero bytes and each
 * dependent of the target removed. The target still exists but gives no instruction, and every other part of the prompt
 * system stays as the incumbent has it, so control trials measure the agent that the user would have without the
 * target. The source depends only on the fork, the target and its dependents, so it is the same for every candidate.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class ControlSource
{
	/**
	 * The content of an emptied prompt file.
	 */
	private static final FileContent EMPTY = new FileContent(new byte[0]);

	/**
	 * Prevents instantiation.
	 */
	private ControlSource()
	{
	}

	/**
	 * Lists the reasons why the control source cannot be built, without building it.
	 *
	 * @param workspace reads the fork's files
	 * @param fork the files recorded at the fork
	 * @param target the target prompt file, relative to the project directory
	 * @param dependents the dependents of the target in the project, each relative to the project directory
	 * @param json reads hook configurations
	 * @return one problem per dependent that the fork does not hold as the dependent describes it, in dependent order;
	 *   empty if the control source can be built
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the fork does not record {@code target} as a regular file, or if a dependent
	 *   does not lie in the project
	 * @throws IOException if a file cannot be read
	 */
	public static List<String> problems(WorkspacePort workspace, FileInventory fork, Path target,
		List<Dependent> dependents, JsonMapper json) throws IOException
	{
		List<String> problems = new ArrayList<>();
		removeAll(workspace, fork, target, dependents, json, problems);
		return problems;
	}

	/**
	 * Lists the reasons why control trials cannot remove the dependents of the target from their copy of a built
	 * plugin, without copying it.
	 *
	 * @param plugin the built plugin under test
	 * @param target the target prompt file, relative to the project directory
	 * @param removals the dependents that control trials remove from their copy of the plugin, each relative to the
	 *   plugin directory
	 * @param json reads hook configurations
	 * @return one problem per dependent that the plugin's base does not hold as the dependent describes it, or that is
	 *   the target's copy in the plugin, in dependent order; empty if control trials can remove every dependent
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if a file of the base cannot be read
	 */
	public static List<String> pluginProblems(PluginUnderTest.Built plugin, Path target, List<Dependent> removals,
		JsonMapper json) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(removals, "removals").isNotNull();
		requireThat(json, "json").isNotNull();
		Set<Path> targetCopies = new HashSet<>();
		for (PluginUnderTest.Mapping mapping : plugin.mappings())
		{
			if (target.startsWith(mapping.source()))
				targetCopies.add(mapping.target().resolve(mapping.source().relativize(target)));
		}
		List<String> problems = new ArrayList<>();
		List<Dependent> held = new ArrayList<>();
		for (Dependent removal : removals)
		{
			if (targetCopies.contains(removal.path()))
			{
				problems.add(DependentRemoval.describe(removal) + " is the target prompt file's copy in the plugin, " +
					"which control trials already empty; a dependent is another part of the prompt system");
			}
			else
				held.add(removal);
		}
		problems.addAll(DependentRemoval.problems(held, plugin.base(), json));
		return problems;
	}

	/**
	 * Returns the control source.
	 *
	 * @param workspace reads the fork's files
	 * @param fork the files recorded at the fork
	 * @param target the target prompt file, relative to the project directory
	 * @param dependents the dependents of the target in the project, each relative to the project directory
	 * @param json reads and writes hook configurations
	 * @return the overlay that empties the target prompt file and removes its dependents
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the fork does not record {@code target} as a regular file, if a dependent
	 *   does not lie in the project, or if {@link #problems problems} reports a problem
	 * @throws IOException if a file cannot be read
	 */
	public static Overlay of(WorkspacePort workspace, FileInventory fork, Path target, List<Dependent> dependents,
		JsonMapper json) throws IOException
	{
		List<String> problems = new ArrayList<>();
		Overlay control = removeAll(workspace, fork, target, dependents, json, problems);
		if (!problems.isEmpty())
			throw new IllegalArgumentException("The control source cannot be built: " + String.join("; ", problems));
		return control;
	}

	/**
	 * Empties the target prompt file and removes the dependents that the fork holds.
	 *
	 * @param workspace reads the fork's files
	 * @param fork the files recorded at the fork
	 * @param target the target prompt file, relative to the project directory
	 * @param dependents the dependents of the target in the project, each relative to the project directory
	 * @param json reads and writes hook configurations
	 * @param problems receives one problem per dependent that the fork does not hold as the dependent describes it
	 * @return the overlay
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the fork does not record {@code target} as a regular file, or if a dependent
	 *   does not lie in the project
	 * @throws IOException if a file cannot be read
	 */
	private static Overlay removeAll(WorkspacePort workspace, FileInventory fork, Path target,
		List<Dependent> dependents, JsonMapper json, List<String> problems) throws IOException
	{
		requireThat(workspace, "workspace").isNotNull();
		requireThat(fork, "fork").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(dependents, "dependents").isNotNull();
		requireThat(json, "json").isNotNull();
		if (!(fork.entry(target).orElse(null) instanceof RegularFile targetFile))
		{
			throw new IllegalArgumentException("The fork does not record the target prompt file " +
				PortablePaths.text(target));
		}
		Overlay incumbent = Overlay.of(fork);
		Overlay control = incumbent.write(target, EMPTY, targetFile.permissions());
		for (Dependent dependent : dependents)
		{
			if (dependent.location() != Dependent.Location.PROJECT)
				throw new IllegalArgumentException("The dependent does not lie in the project: " + dependent);
			Path path = dependent.path();
			if (path.equals(target))
			{
				problems.add(DependentRemoval.describe(dependent) + " is the target prompt file, which control trials " +
					"already empty; a dependent is another part of the prompt system");
				continue;
			}
			if (!(fork.entry(path).orElse(null) instanceof RegularFile file))
			{
				problems.add(DependentRemoval.describe(dependent) + ", which a dependent names, is not a regular file " +
					"of the project");
				continue;
			}
			Optional<byte[]> content;
			try
			{
				content = DependentRemoval.apply(dependent, workspace.readFile(incumbent, path).orElseThrow().bytes(),
					json);
			}
			catch (IllegalArgumentException e)
			{
				problems.add(e.getMessage());
				continue;
			}
			if (content.isEmpty())
				control = control.delete(path);
			else
				control = control.write(path, new FileContent(content.get()), file.permissions());
		}
		return control;
	}
}
