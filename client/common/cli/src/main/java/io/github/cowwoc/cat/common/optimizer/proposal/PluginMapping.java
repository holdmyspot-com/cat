package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Maps the project's prompt files onto the bases of the trial plugins that the project builds, so that a candidate's
 * edit reaches the plugin that trials install, and checks what the mappings leave out.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class PluginMapping
{
	/**
	 * Prevents construction.
	 */
	private PluginMapping()
	{
	}

	/**
	 * Maps the directory of each prompt file that does not load from the project onto a project-built plugin's base.
	 * A directory maps to the base directory that holds a regular file at the relative path of each of the directory's
	 * regular files, choosing the one with the most files identical to the directory's, and at least one; a tie goes
	 * to the base directory that comes first in path order. A directory without such a base directory maps each of its
	 * prompt files on its own to the base path, first in path order, that holds an identical file. A directory that an
	 * earlier mapping covers is not mapped again.
	 *
	 * @param project the project's files, by path relative to the project
	 * @param promptFiles the in-project prompt files, relative to the project
	 * @param loadsFromProject the prompt files that trials load from the staged project itself, relative to the
	 *   project
	 * @param base the files of the plugin's base, by path relative to the plugin directory
	 * @return the mappings, in project path order
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<TrialPlugin.Mapping> map(FileInventory project, Set<Path> promptFiles,
		Set<Path> loadsFromProject, FileInventory base)
	{
		requireThat(project, "project").isNotNull();
		requireThat(promptFiles, "promptFiles").isNotNull();
		requireThat(loadsFromProject, "loadsFromProject").isNotNull();
		requireThat(base, "base").isNotNull();

		SortedMap<Path, List<Path>> byDirectory = new TreeMap<>();
		for (Path file : promptFiles)
		{
			if (file.isAbsolute() || loadsFromProject.contains(file))
				continue;
			Path directory = file.getParent();
			if (directory == null)
				directory = Path.of("");
			byDirectory.computeIfAbsent(directory, _ -> new ArrayList<>()).add(file);
		}
		SortedSet<Path> baseDirectories = directories(base);

		List<TrialPlugin.Mapping> mappings = new ArrayList<>();
		for (Map.Entry<Path, List<Path>> entry : byDirectory.entrySet())
		{
			Path directory = entry.getKey();
			if (covers(mappings, directory))
				continue;
			Optional<Path> target = Optional.empty();
			// The project directory itself is not a mapping source, because its files hold more than one plugin's
			if (!directory.toString().isEmpty())
				target = bestDirectory(regularFiles(project, directory), base, baseDirectories);
			if (target.isPresent())
			{
				mappings.add(new TrialPlugin.Mapping(directory, target.get()));
				continue;
			}
			for (Path file : new TreeSet<>(entry.getValue()))
			{
				if (covers(mappings, file))
					continue;
				Optional<Path> identical = identicalPath(project, file, base);
				identical.ifPresent(path -> mappings.add(new TrialPlugin.Mapping(file, path)));
			}
		}
		mappings.sort((first, second) -> first.source().compareTo(second.source()));
		return mappings;
	}

	/**
	 * Checks a campaign's trial plugins against the project's prompt files.
	 *
	 * @param project the project's files, by path relative to the project
	 * @param promptFiles the in-project prompt files, relative to the project
	 * @param loadsFromProject the prompt files that trials load from the staged project itself, relative to the
	 *   project
	 * @param plugins the trial plugins
	 * @param bases the files of each project-built plugin's base, by plugin name
	 * @return the result
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code bases} lacks a project-built plugin
	 */
	public static Coverage check(FileInventory project, Set<Path> promptFiles, Set<Path> loadsFromProject,
		List<TrialPlugin> plugins, Map<String, FileInventory> bases)
	{
		requireThat(project, "project").isNotNull();
		requireThat(promptFiles, "promptFiles").isNotNull();
		requireThat(loadsFromProject, "loadsFromProject").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		requireThat(bases, "bases").isNotNull();

		List<Path> uncovered = new ArrayList<>();
		for (Path file : new TreeSet<>(promptFiles))
		{
			if (file.isAbsolute() || loadsFromProject.contains(file))
				continue;
			boolean mapped = false;
			for (TrialPlugin plugin : plugins)
			{
				if (plugin.mappedPath(file).isPresent())
				{
					mapped = true;
					break;
				}
			}
			if (!mapped)
				uncovered.add(file);
		}

		List<Difference> differing = new ArrayList<>();
		for (TrialPlugin plugin : plugins)
		{
			if (!plugin.isProjectBuilt())
				continue;
			FileInventory base = bases.get(plugin.name());
			if (base == null)
				throw new IllegalArgumentException("bases lacks the plugin " + plugin.name());
			for (TrialPlugin.Mapping mapping : plugin.mappings())
			{
				for (Map.Entry<Path, FileEntry.RegularFile> entry : regularFiles(project, mapping.source()).entrySet())
				{
					Path file = mapping.source().resolve(entry.getKey());
					Path mapped = mapping.target().resolve(entry.getKey());
					if (base.entry(mapped).orElse(null) instanceof FileEntry.RegularFile inBase &&
						!inBase.contentHash().equals(entry.getValue().contentHash()))
					{
						differing.add(new Difference(plugin.name(), file, mapped));
					}
				}
			}
		}
		return new Coverage(uncovered, differing);
	}

	/**
	 * Indicates whether a mapping covers a path.
	 *
	 * @param mappings the mappings
	 * @param path a project path
	 * @return {@code true} if a mapping's source is the path or encloses it
	 */
	private static boolean covers(List<TrialPlugin.Mapping> mappings, Path path)
	{
		for (TrialPlugin.Mapping mapping : mappings)
		{
			if (path.startsWith(mapping.source()))
				return true;
		}
		return false;
	}

	/**
	 * Returns the regular files at or below a path, by path relative to it.
	 *
	 * @param inventory the files
	 * @param path a directory or a file, relative to the inventory's root
	 * @return the files in path order; the file itself, by the empty path, if {@code path} is a regular file
	 */
	private static SortedMap<Path, FileEntry.RegularFile> regularFiles(FileInventory inventory, Path path)
	{
		SortedMap<Path, FileEntry.RegularFile> files = new TreeMap<>();
		for (Map.Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			Path file = entry.getKey();
			if (!file.isAbsolute() && file.startsWith(path) && entry.getValue() instanceof FileEntry.RegularFile regular)
				files.put(path.relativize(file), regular);
		}
		return files;
	}

	/**
	 * Returns every directory of an inventory that holds a file, at any depth.
	 *
	 * @param inventory the files
	 * @return the directories in path order, the root, by the empty path, included
	 */
	private static SortedSet<Path> directories(FileInventory inventory)
	{
		SortedSet<Path> directories = new TreeSet<>();
		directories.add(Path.of(""));
		for (Path file : inventory.entries().keySet())
		{
			if (file.isAbsolute())
				continue;
			for (Path directory = file.getParent(); directory != null; directory = directory.getParent())
				directories.add(directory);
		}
		return directories;
	}

	/**
	 * Returns the base directory that holds a regular file at the relative path of each of a project directory's
	 * files and the most identical files.
	 *
	 * @param files the project directory's regular files, by path relative to it
	 * @param base the base's files
	 * @param candidates the base's directories, in path order
	 * @return the directory, or empty if none holds every relative path with at least one identical file
	 */
	private static Optional<Path> bestDirectory(SortedMap<Path, FileEntry.RegularFile> files, FileInventory base,
		SortedSet<Path> candidates)
	{
		Path best = null;
		int bestIdentical = 0;
		for (Path candidate : candidates)
		{
			int identical = 0;
			boolean holdsAll = true;
			for (Map.Entry<Path, FileEntry.RegularFile> entry : files.entrySet())
			{
				if (!(base.entry(candidate.resolve(entry.getKey())).orElse(null) instanceof FileEntry.RegularFile inBase))
				{
					holdsAll = false;
					break;
				}
				if (inBase.contentHash().equals(entry.getValue().contentHash()))
					++identical;
			}
			// A strict comparison keeps the first directory in path order among equals
			if (holdsAll && identical > bestIdentical)
			{
				best = candidate;
				bestIdentical = identical;
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Returns the base path that holds a file identical to a project file.
	 *
	 * @param project the project's files
	 * @param file the project file
	 * @param base the base's files
	 * @return the first such path in path order, or empty if there is none or the project file is not a regular file
	 */
	private static Optional<Path> identicalPath(FileInventory project, Path file, FileInventory base)
	{
		if (!(project.entry(file).orElse(null) instanceof FileEntry.RegularFile regular))
			return Optional.empty();
		Map<Path, FileEntry> sorted = new TreeMap<>(base.entries());
		for (Map.Entry<Path, FileEntry> entry : sorted.entrySet())
		{
			if (!entry.getKey().isAbsolute() && entry.getValue() instanceof FileEntry.RegularFile inBase &&
				inBase.contentHash().equals(regular.contentHash()))
			{
				return Optional.of(entry.getKey());
			}
		}
		return Optional.empty();
	}

	/**
	 * What a campaign's trial plugins leave out of trials.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param uncovered the prompt files that do not load from the project and that no mapping covers, in path order;
	 *   a candidate's edit to one of them never reaches trials
	 * @param differing the mapped files whose content differs from the base's file at their mapped path, in plugin
	 *   and then mapping order
	 */
	public record Coverage(List<Path> uncovered, List<Difference> differing)
	{
		/**
		 * Creates a coverage.
		 *
		 * @param uncovered the prompt files that do not load from the project and that no mapping covers
		 * @param differing the mapped files whose content differs from the base's file at their mapped path
		 * @throws NullPointerException if any argument is null
		 */
		public Coverage
		{
			uncovered = List.copyOf(uncovered);
			differing = List.copyOf(differing);
		}

		/**
		 * Returns the reason that a campaign cannot start, if its target does not load in trials.
		 *
		 * @param target the target prompt file, relative to the project
		 * @return the refusal, or empty if trials load the target
		 * @throws NullPointerException if {@code target} is null
		 */
		public Optional<String> targetRefusal(Path target)
		{
			requireThat(target, "target").isNotNull();
			if (!uncovered.contains(target))
				return Optional.empty();
			return Optional.of("Trials would not load the target " + target + ": it does not load from the " +
				"project, and no trial plugin that the project builds holds a copy of it. List the plugin that " +
				"ships it, with its build, in the prompt test configuration, and run start again.");
		}
	}

	/**
	 * A mapped project file whose content differs from the base's, which trials replace with the project's.
	 *
	 * @param plugin the trial plugin's name
	 * @param file the project file, relative to the project
	 * @param mapped the path that receives it, relative to the plugin directory
	 */
	public record Difference(String plugin, Path file, Path mapped)
	{
		/**
		 * Creates a difference.
		 *
		 * @param plugin the trial plugin's name
		 * @param file the project file, relative to the project
		 * @param mapped the path that receives it, relative to the plugin directory
		 * @throws NullPointerException if any argument is null
		 */
		public Difference
		{
			requireThat(plugin, "plugin").isNotNull();
			requireThat(file, "file").isNotNull();
			requireThat(mapped, "mapped").isNotNull();
		}
	}
}
