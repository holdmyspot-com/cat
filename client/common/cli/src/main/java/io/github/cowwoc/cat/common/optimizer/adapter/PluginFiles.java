package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.port.PluginBase;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The files of a trial plugin as trials install it: the plugin's base copy with each file that a mapping copies from
 * the project written over its mapped path.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class PluginFiles
{
	private final Path project;
	private final PluginBase base;

	/**
	 * Creates the files of a trial plugin.
	 *
	 * @param project the project's root directory
	 * @param base    the trial plugin and its base copy
	 * @throws NullPointerException if any of the arguments are null
	 */
	public PluginFiles(Path project, PluginBase base)
	{
		requireThat(project, "project").isNotNull();
		requireThat(base, "base").isNotNull();
		this.project = project.toAbsolutePath().normalize();
		this.base = base;
	}

	/**
	 * Returns the trial plugin.
	 *
	 * @return the plugin
	 */
	public TrialPlugin plugin()
	{
		return base.plugin();
	}

	/**
	 * Returns the project file that a mapping copies to a path of the plugin, if one does.
	 *
	 * @param relative a path relative to the plugin directory
	 * @return the project file, relative to the project; empty if no mapping copies an existing project file to the
	 *   path
	 * @throws NullPointerException if {@code relative} is null
	 */
	public Optional<Path> projectFile(Path relative)
	{
		requireThat(relative, "relative").isNotNull();
		Path normalized = relative.normalize();
		List<TrialPlugin.Mapping> mappings = base.plugin().mappings();
		// A later mapping is written over an earlier one
		for (int i = mappings.size() - 1; i >= 0; --i)
		{
			TrialPlugin.Mapping mapping = mappings.get(i);
			Path target = mapping.target();
			if (!target.toString().isEmpty() && !normalized.startsWith(target))
				continue;
			Path source = mapping.source().resolve(target.relativize(normalized).toString());
			if (Files.isRegularFile(project.resolve(source), LinkOption.NOFOLLOW_LINKS))
				return Optional.of(source.normalize());
		}
		return Optional.empty();
	}

	/**
	 * Returns the file that trials install at a path of the plugin.
	 *
	 * @param relative a path relative to the plugin directory
	 * @return the absolute project file that a mapping copies to the path, or else the path in the plugin's base copy
	 * @throws NullPointerException if {@code relative} is null
	 */
	public Path resolve(Path relative)
	{
		Optional<Path> projectFile = projectFile(relative);
		if (projectFile.isPresent())
			return project.resolve(projectFile.get());
		return base.directory().toAbsolutePath().normalize().resolve(relative.normalize().toString());
	}
}
