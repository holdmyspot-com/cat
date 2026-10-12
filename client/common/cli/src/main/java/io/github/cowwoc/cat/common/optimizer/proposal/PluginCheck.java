package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.runner.PluginManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What CAT finds when it checks a proposed plugin under test against the project and the plugin's base.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param plugin the plugin under test, with the identity of its base's files when it is a built plugin, or empty if
 *   the proposal names none
 * @param refusals the reasons that block confirmation, in the order found
 * @param differing the project paths of the mapped files whose content differs from the file at their mapped path in
 *   the base, in path order
 * @param unapprovedHooks the plugin's hooks that trials do not run because the user has not approved them in the
 *   harness, each as its event and command
 */
public record PluginCheck(Optional<PluginUnderTest> plugin, List<String> refusals, List<Path> differing,
	List<String> unapprovedHooks)
{
	/**
	 * The check of a proposal that names no plugin under test.
	 */
	public static final PluginCheck NONE = new PluginCheck(Optional.empty(), List.of(), List.of(), List.of());

	/**
	 * Creates a check.
	 *
	 * @param plugin the plugin under test, with the identity of its base's files when it is a built plugin, or empty
	 *   if the proposal names none
	 * @param refusals the reasons that block confirmation, in the order found
	 * @param differing the project paths of the mapped files whose content differs from the file at their mapped path
	 *   in the base, in path order
	 * @param unapprovedHooks the plugin's hooks that trials do not run because the user has not approved them in the
	 *   harness, each as its event and command
	 * @throws NullPointerException if any of the arguments are null
	 */
	public PluginCheck
	{
		requireThat(plugin, "plugin").isNotNull();
		refusals = List.copyOf(refusals);
		differing = List.copyOf(differing);
		unapprovedHooks = List.copyOf(unapprovedHooks);
	}

	/**
	 * Returns this check with the plugin's hooks that trials do not run.
	 *
	 * @param unapprovedHooks the plugin's hooks that trials do not run because the user has not approved them in the
	 *   harness, each as its event and command
	 * @return the check
	 * @throws NullPointerException if {@code unapprovedHooks} is null
	 */
	public PluginCheck withUnapprovedHooks(List<String> unapprovedHooks)
	{
		return new PluginCheck(plugin, refusals, differing, unapprovedHooks);
	}

	/**
	 * Checks a proposed plugin under test. A built plugin's mapped files are the regular files of the project inside
	 * a mapping's source directory; each must have a regular file at its mapped path in the base. The target prompt
	 * files belong to a plugin when they lie inside a directory that holds a plugin manifest, inside a plugin root, or
	 * inside a mapping's source directory, and they must not belong to more than one.
	 *
	 * @param plugin the plugin under test that the contract author proposes, or empty if it proposes none
	 * @param project the project's files
	 * @param targets the target prompt files, relative to the project directory
	 * @return the check
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the base's files cannot be read
	 */
	public static PluginCheck of(Optional<PluginUnderTest> plugin, FileInventory project, Set<Path> targets)
		throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(targets, "targets").isNotNull();
		if (plugin.isEmpty())
			return NONE;

		PluginUnderTest checked = plugin.get();
		List<String> refusals = new ArrayList<>();
		List<Path> differing = new ArrayList<>();
		switch (checked)
		{
			case PluginUnderTest.Root root ->
			{
				if (!holdsManifest(project, root.directory()))
				{
					refusals.add("The plugin root `" + root.directory() + "` holds neither " +
						quoted(PluginManifest.MANIFESTS) + ". Run revise with feedback that names the directory " +
						"that holds the plugin's manifest.");
				}
			}
			case PluginUnderTest.Built built ->
			{
				if (Files.isDirectory(built.base()))
				{
					FileInventory base = InventoryScanner.scan(built.base(), Set.of());
					List<String> missing = compare(built, project, base, differing);
					if (!missing.isEmpty())
					{
						refusals.add("These mapped files have no file at their mapped path in the base `" +
							built.base() + "`, so the plugin's users never receive them: " +
							String.join(", ", missing) + ". Run revise with feedback that corrects the mappings, or " +
							"build the plugin from the project and name the built directory as the base.");
					}
					checked = built.withBaseDigest(base.identity().hex());
				}
				else
				{
					refusals.add("The base `" + built.base() + "` of the plugin under test is not a directory. Run " +
						"revise with feedback that names the built plugin's directory.");
				}
			}
		}

		SortedSet<String> owners = new TreeSet<>();
		for (Path target : targets)
			owner(checked, project, target).ifPresent(owners::add);
		if (owners.size() > 1)
		{
			refusals.add("The target prompt files lie in more than one plugin: " + String.join(", ", owners) +
				". Each trial installs one plugin under test, so start a separate campaign for each plugin's " +
				"prompt files.");
		}
		Collections.sort(differing);
		return new PluginCheck(Optional.of(checked), refusals, differing, List.of());
	}

	/**
	 * Compares a built plugin's mapped files with the base.
	 *
	 * @param built the built plugin
	 * @param project the project's files
	 * @param base the base's files
	 * @param differing receives the project path of each mapped file whose content differs from the base's
	 * @return each mapped file that has no regular file at its mapped path in the base, as text that names both paths,
	 *   in mapping order and then path order
	 */
	private static List<String> compare(PluginUnderTest.Built built, FileInventory project, FileInventory base,
		List<Path> differing)
	{
		List<String> missing = new ArrayList<>();
		for (PluginUnderTest.Mapping mapping : built.mappings())
		{
			for (Entry<Path, FileEntry> entry : project.entries().entrySet())
			{
				Path path = entry.getKey();
				if (path.startsWith(mapping.source()) && entry.getValue() instanceof FileEntry.RegularFile file)
				{
					Path mapped = mapping.target().resolve(mapping.source().relativize(path));
					Optional<FileEntry> inBase = base.entry(mapped);
					if (inBase.isPresent() && inBase.get() instanceof FileEntry.RegularFile baseFile)
					{
						if (!baseFile.contentHash().equals(file.contentHash()))
							differing.add(path);
					}
					else
						missing.add("`" + path + "` → `" + mapped + "`");
				}
			}
		}
		return missing;
	}

	/**
	 * Returns the plugin that a target prompt file belongs to: the plugin under test when the file lies inside its
	 * root or a mapping's source directory, and otherwise the nearest enclosing directory that holds a plugin
	 * manifest.
	 *
	 * @param plugin the plugin under test
	 * @param project the project's files
	 * @param target the target prompt file, relative to the project directory
	 * @return the plugin's directory as Markdown code, or empty if the file belongs to no plugin
	 */
	private static Optional<String> owner(PluginUnderTest plugin, FileInventory project, Path target)
	{
		switch (plugin)
		{
			case PluginUnderTest.Root root ->
			{
				if (target.startsWith(root.directory()))
					return Optional.of("`" + root.directory() + "`");
			}
			case PluginUnderTest.Built built ->
			{
				for (PluginUnderTest.Mapping mapping : built.mappings())
				{
					if (target.startsWith(mapping.source()))
						return Optional.of("`" + built.base() + "`");
				}
			}
		}
		for (Path directory = target.getParent(); directory != null; directory = directory.getParent())
		{
			if (holdsManifest(project, directory))
				return Optional.of("`" + directory + "`");
		}
		if (holdsManifest(project, Path.of("")))
			return Optional.of("`.`");
		return Optional.empty();
	}

	/**
	 * Indicates whether a project directory holds a Codex or Claude plugin manifest.
	 *
	 * @param project the project's files
	 * @param directory the directory, relative to the project directory; empty for the project directory itself
	 * @return {@code true} if the directory holds either manifest
	 */
	private static boolean holdsManifest(FileInventory project, Path directory)
	{
		for (Path manifest : PluginManifest.MANIFESTS)
		{
			if (project.entry(directory.resolve(manifest)).isPresent())
				return true;
		}
		return false;
	}

	/**
	 * Returns paths as Markdown code joined by "nor".
	 *
	 * @param paths the paths
	 * @return the text
	 */
	private static String quoted(List<Path> paths)
	{
		return String.join(" nor ", paths.stream().map(path -> "`" + path + "`").toList());
	}
}
