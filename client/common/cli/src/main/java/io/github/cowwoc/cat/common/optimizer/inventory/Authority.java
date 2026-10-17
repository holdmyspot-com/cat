package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning.Choice;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The edits that a confirmed contract authorizes during a campaign:
 * <ul>
 *   <li>Files at or below an in-project prompt source permit content edits and ordinary-file creates, renames and
 *   deletes.</li>
 *   <li>An in-project file that a symbolic link at or below a prompt source resolves to is a prompt file too, so the
 *   same changes are permitted at its own path and at the link's path.</li>
 *   <li>Existing symbolic links, their destinations and existing file modes stay fixed.</li>
 *   <li>Loader configuration stays fixed, even inside a prompt source.</li>
 *   <li>External files, whether reached through links or read directly from outside the project, are read-only: no
 *   operation may change one.</li>
 * </ul>
 * Authority applies to resolved targets: an edit made at a link path applies to the link's target.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Authority
{
	private static final List<Choice> CHOICES = List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP_CAMPAIGN,
		Choice.REVISE_CAMPAIGN);

	/**
	 * Indicates whether a resolved target lies outside the project, which makes it a read-only external file.
	 *
	 * @param target the path that symbolic links resolve to: relative to the project directory, or absolute for an
	 *               external file
	 * @return {@code true} if {@code target} is absolute
	 * @throws NullPointerException if {@code target} is null
	 */
	public static boolean isExternal(Path target)
	{
		return target.isAbsolute();
	}

	private final Set<Path> promptSources;
	private final Set<Path> loaderConfiguration;
	private final Set<Path> linkTargets;

	/**
	 * Creates an authority.
	 *
	 * @param promptSources the in-project prompt files and directories, relative to the project directory; every
	 *                      ordinary file at or below one may be edited, created or deleted
	 * @param loaderConfiguration the files and directories, relative to the project directory or absolute, that decide
	 *                            which prompt files load; nothing at or below one may change
	 * @param fork the files recorded at the fork; the in-project target of each symbolic link at or below a prompt
	 *             source, after the links on its path are followed, is a prompt file too
	 * @throws NullPointerException if any of the arguments are null or contain null
	 * @throws IllegalArgumentException if a path is empty, not normalized or starts with {@code ..}, or if a prompt
	 *                                  source is absolute
	 */
	public Authority(Set<Path> promptSources, Set<Path> loaderConfiguration, FileInventory fork)
	{
		for (Path promptSource : promptSources)
		{
			FileInventory.requireInventoryPath(promptSource, "promptSource");
			requireThat(promptSource, "promptSource").isRelative();
		}
		for (Path path : loaderConfiguration)
			FileInventory.requireInventoryPath(path, "loaderConfiguration");
		this.promptSources = Set.copyOf(promptSources);
		this.loaderConfiguration = Set.copyOf(loaderConfiguration);
		this.linkTargets = promptSourceLinkTargets(fork);
	}

	/**
	 * Returns the in-project files that symbolic links at or below a prompt source resolve to.
	 *
	 * @param fork the files recorded at the fork
	 * @return the resolved targets, relative to the project directory; a link that is part of a cycle, that resolves
	 *   to the project directory itself or to an external file contributes none
	 * @throws NullPointerException if {@code fork} is null
	 */
	private Set<Path> promptSourceLinkTargets(FileInventory fork)
	{
		requireThat(fork, "fork").isNotNull();
		Set<Path> targets = new HashSet<>();
		for (Entry<Path, FileEntry> entry : fork.entries().entrySet())
		{
			Path link = entry.getKey();
			if (!(entry.getValue() instanceof SymbolicLink) || link.isAbsolute() || !isPromptSource(link))
				continue;
			Optional<Path> target = fork.resolve(link);
			if (target.isPresent() && !isExternal(target.get()) && !target.get().toString().isEmpty())
				targets.add(target.get());
		}
		return Set.copyOf(targets);
	}

	/**
	 * Returns the files and directories that decide which prompt files load.
	 *
	 * @return the paths, relative to the project directory or absolute
	 */
	public Set<Path> loaderConfiguration()
	{
		return loaderConfiguration;
	}

	/**
	 * Returns the in-project prompt files: the regular files inside the project whose content a candidate may edit. A
	 * file that a prompt-source link reaches is listed at its own path, not at the link's.
	 *
	 * @param fork the files recorded at the fork
	 * @return the paths relative to the project directory, in path order
	 * @throws NullPointerException if {@code fork} is null
	 */
	public SortedSet<Path> promptFiles(FileInventory fork)
	{
		requireThat(fork, "fork").isNotNull();
		SortedSet<Path> promptFiles = new TreeSet<>();
		for (Entry<Path, FileEntry> entry : fork.entries().entrySet())
		{
			Path path = entry.getKey();
			if (entry.getValue() instanceof RegularFile && !isExternal(path) &&
				fork.resolve(path).equals(Optional.of(path)) && limitOf(Operation.EDIT_CONTENT, path).isEmpty())
			{
				promptFiles.add(path);
			}
		}
		return promptFiles;
	}

	/**
	 * Returns the limit that forbids an operation on a resolved target.
	 *
	 * @param operation the operation
	 * @param target the path the operation changes after symbolic links are resolved: relative to the project
	 *               directory, or absolute for an external file
	 * @return the limit, or empty if the operation is authorized
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code target} is empty, not normalized or starts with {@code ..}
	 */
	public Optional<Limit> limitOf(Operation operation, Path target)
	{
		requireThat(operation, "operation").isNotNull();
		FileInventory.requireInventoryPath(target, "target");
		if (isExternal(target))
			return Optional.of(Limit.EXTERNAL_TARGET_READ_ONLY);
		return switch (operation)
		{
			case CHANGE_MODE -> Optional.of(Limit.MODE_FIXED);
			case CHANGE_LINK -> Optional.of(Limit.LINK_FIXED);
			case EDIT_CONTENT, CREATE, DELETE -> projectLimitOf(target);
		};
	}

	/**
	 * Returns the limit that forbids a content edit, or creating or deleting an ordinary file, at a resolved target
	 * inside the project.
	 *
	 * @param target the resolved target, relative to the project directory
	 * @return the limit, or empty if the change is authorized
	 */
	private Optional<Limit> projectLimitOf(Path target)
	{
		if (isLoaderConfiguration(target))
			return Optional.of(Limit.LOADER_CONFIGURATION_FIXED);
		if (isPromptSource(target) || isPromptSourceLinkTarget(target))
			return Optional.empty();
		return Optional.of(Limit.OUTSIDE_PROMPT_SOURCES);
	}

	/**
	 * Indicates whether a resolved target lies at or below loader configuration.
	 *
	 * @param target the resolved target
	 * @return {@code true} if it does
	 */
	private boolean isLoaderConfiguration(Path target)
	{
		return loaderConfiguration.stream().anyMatch(target::startsWith);
	}

	/**
	 * Indicates whether a resolved target lies at or below an in-project prompt source.
	 *
	 * @param target the resolved target
	 * @return {@code true} if it does
	 */
	private boolean isPromptSource(Path target)
	{
		return promptSources.stream().anyMatch(target::startsWith);
	}

	/**
	 * Indicates whether a resolved target lies at or below an in-project file that a symbolic link at or below a prompt
	 * source resolves to.
	 *
	 * @param target the resolved target
	 * @return {@code true} if it does
	 */
	private boolean isPromptSourceLinkTarget(Path target)
	{
		return linkTargets.stream().anyMatch(target::startsWith);
	}

	/**
	 * Checks an edit that diagnosis found a requirement needs, and warns the user when the edit lies outside this
	 * authority. Every warning offers the same choices, none of which widens the authority: continue within it, stop
	 * the campaign or revise it.
	 *
	 * @param requirement the identifier of the requirement that needs the edit
	 * @param path the path at which the edit is needed, relative to the project directory or absolute
	 * @param operation the needed operation
	 * @param fork the files recorded at the fork, whose symbolic links resolve {@code path}
	 * @return the warning, or empty if the edit is authorized
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code requirement} is blank, or if {@code path} is empty, not normalized or
	 *                                  starts with {@code ..}
	 */
	public Optional<AuthorityWarning> diagnose(String requirement, Path path, Operation operation, FileInventory fork)
	{
		requireThat(requirement, "requirement").isNotBlank();
		requireThat(operation, "operation").isNotNull();
		Optional<Path> resolved = fork.resolve(path);
		if (resolved.isEmpty())
			return Optional.of(new AuthorityWarning(requirement, path, path, Limit.LINK_CYCLE, CHOICES));

		Path target = resolved.get();
		return limitOf(operation, target).
			map(limit -> new AuthorityWarning(requirement, path, target, limit, CHOICES));
	}
}
