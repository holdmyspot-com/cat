package com.holdmyspot.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Builds and deletes the copies of the trial plugins that trials install: one for each staged source and plugin that
 * the project builds, so that a candidate's edit and an emptied control file reach the plugin that its trials load, and
 * the copies of the plugins' bases.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent requests for one copy share it.
 */
final class PluginCopies
{
	/**
	 * Separates the name of a staged source from the identity of the plugin in the name of its plugin copy.
	 */
	private static final String COPY_SEPARATOR = "-plugin-";
	/**
	 * Starts the name of a copy of a trial plugin's base, which is followed by the base digest.
	 */
	private static final String BASE_PREFIX = "base-";
	private final Path storage;

	/**
	 * Creates the plugin copies of a campaign.
	 *
	 * @param storage the directory that holds the plugin copies and the copies of trial plugins' bases, which this
	 *   class creates if needed
	 * @throws NullPointerException if {@code storage} is null
	 */
	PluginCopies(Path storage)
	{
		requireThat(storage, "storage").isNotNull();
		this.storage = storage.toAbsolutePath().normalize();
	}

	/**
	 * Returns a trial plugin as the trials of a staged source install it, building it if it does not exist.
	 *
	 * @param plugin the trial plugin
	 * @param source the staged source, whose directory name identifies its candidate
	 * @param removals the dependents that the copy of a project-built plugin leaves out
	 * @return the copy of a project-built plugin's base with each file of {@code source} that a mapping covers written
	 *   over its mapped path, without the dependents; the base copy of any other plugin
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the copy cannot be built, if the plugin's base has no copy, or if the copy does not hold a
	 *   dependent
	 */
	Path copy(TrialPlugin plugin, Path source, PluginRemovals removals) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(removals, "removals").isNotNull();
		Path base = base(plugin.baseDigest());
		if (!Files.isDirectory(base, NOFOLLOW_LINKS))
		{
			throw new WorkspaceException("Campaign storage holds no base of the trial plugin " + plugin.name() + " at " +
				base + ". Run start so that CAT copies the plugin's base again.");
		}
		if (!plugin.isProjectBuilt())
			return base;
		Path directory = storage.resolve(source.getFileName() + COPY_SEPARATOR + identity(plugin, removals).hex());
		if (Files.isDirectory(directory))
			return directory;
		FileTree.build(directory, copy ->
		{
			FileTree.copy(base, copy, _ -> false);
			for (TrialPlugin.Mapping mapping : plugin.mappings())
			{
				Path mapped = source.resolve(mapping.source());
				// A file that the staged source lacks keeps the base's copy; the removals delete it when it is a dependent
				if (Files.exists(mapped, NOFOLLOW_LINKS))
					FileTree.copy(mapped, copy.resolve(mapping.target()), _ -> false);
			}
			if (!removals.isEmpty())
				DependentRemoval.removeAll(removals.dependents(), copy, removals.json().orElseThrow());
		});
		return directory;
	}

	/**
	 * Returns the identity of a project-built plugin's name, base and mappings and of the removals, which names its
	 * copies.
	 *
	 * @param plugin the project-built plugin
	 * @param removals the dependents that the copy leaves out
	 * @return the identity
	 */
	private static Sha256 identity(TrialPlugin plugin, PluginRemovals removals)
	{
		StringJoiner text = new StringJoiner("\n");
		text.add(plugin.name());
		text.add(plugin.baseDigest());
		for (TrialPlugin.Mapping mapping : plugin.mappings())
			text.add(PortablePaths.text(mapping.source()) + "\0" + PortablePaths.text(mapping.target()));
		for (Dependent dependent : removals.dependents())
		{
			String removal = switch (dependent.removal())
			{
				case Dependent.WholeFile _ -> "file";
				case Dependent.Span span -> "span\0" + span.text();
				case Dependent.HookRegistration hook -> "hook\0" + hook.event() + "\0" + hook.command();
			};
			text.add("remove\0" + PortablePaths.text(dependent.path()) + "\0" + removal);
		}
		return Sha256.of(text.toString().getBytes(UTF_8));
	}

	/**
	 * Copies a plugin directory as a trial plugin's base, unless storage holds a base with the same files already.
	 *
	 * @param directory the plugin directory
	 * @return the digest of the base's files
	 * @throws NullPointerException if {@code directory} is null
	 * @throws IOException if the directory cannot be read or copied, or if its files change during the copy
	 */
	String copyBase(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		String digest = InventoryScanner.scan(directory, Set.of()).identity().hex();
		Path base = base(digest);
		if (Files.isDirectory(base))
			return digest;
		FileTree.build(base, copy ->
		{
			FileTree.copy(directory, copy, _ -> false);
			// Compare the copy, not the directory, so that a change during the copy is caught too
			String copied = InventoryScanner.scan(copy, Set.of()).identity().hex();
			if (!copied.equals(digest))
			{
				throw new WorkspaceException("The files of the plugin directory " + directory + " changed while CAT " +
					"copied them. Run start again once nothing writes to the directory.");
			}
		});
		return digest;
	}

	/**
	 * Returns where storage holds a trial plugin's base.
	 *
	 * @param digest the digest of the base's files
	 * @return the absolute directory
	 * @throws NullPointerException if {@code digest} is null
	 */
	Path base(String digest)
	{
		requireThat(digest, "digest").isNotNull();
		return storage.resolve(BASE_PREFIX + digest);
	}

	/**
	 * Deletes the plugin copies of a candidate's staged sources. The copies of trial plugins' bases are kept.
	 *
	 * @param candidate the candidate's identity
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IOException if a copy cannot be deleted
	 */
	void delete(Sha256 candidate) throws IOException
	{
		requireThat(candidate, "candidate").isNotNull();
		if (Files.notExists(storage))
			return;
		try (DirectoryStream<Path> copies = Files.newDirectoryStream(storage, candidate.hex() + "*"))
		{
			for (Path copy : copies)
				TemporaryDirectories.deleteRecursively(copy);
		}
	}
}
