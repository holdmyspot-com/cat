package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.PluginRemovals;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Builds and deletes the copies of the plugin under test that trials install: one for each staged source, so that a
 * candidate's edit and an emptied control file reach the plugin that its trials load.
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
	 * Starts the name of a copy of a built plugin's base, which is followed by the base digest.
	 */
	private static final String BASE_PREFIX = "base-";
	private final Path storage;

	/**
	 * Creates the plugin copies of a campaign.
	 *
	 * @param storage the directory that holds the plugin copies and the copies of built plugins' bases, which this
	 *   class creates if needed
	 * @throws NullPointerException if {@code storage} is null
	 */
	PluginCopies(Path storage)
	{
		requireThat(storage, "storage").isNotNull();
		this.storage = storage.toAbsolutePath().normalize();
	}

	/**
	 * Returns the plugin under test as the trials of a staged source install it, building it if it does not exist.
	 *
	 * @param plugin the plugin under test; a built plugin's base digest must be set
	 * @param source the staged source, whose directory name identifies its candidate
	 * @param removals the dependents that a built plugin's copy leaves out, which a plugin root ignores because its
	 *   staged source already leaves them out
	 * @return the plugin directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a built plugin's base digest is empty
	 * @throws IOException if the copy cannot be built, if the staged source has no plugin root, if the files of a
	 *   built plugin's base no longer have the identity of its base digest, or if the copy does not hold a dependent
	 */
	Path copy(PluginUnderTest plugin, Path source, PluginRemovals removals) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(removals, "removals").isNotNull();
		return switch (plugin)
		{
			case PluginUnderTest.Root root ->
			{
				Path directory = source.resolve(root.directory());
				if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
				{
					throw new WorkspaceException("The staged source " + source + " has no plugin root " +
						root.directory() + ". Run revise so that the contract author names the plugin's directory.");
				}
				yield directory;
			}
			case PluginUnderTest.Built built -> copy(built, source, removals);
		};
	}

	/**
	 * Returns a built plugin as the trials of a staged source install it, building it if it does not exist.
	 *
	 * @param plugin the built plugin; its base digest must be set
	 * @param source the staged source, whose directory name identifies its candidate
	 * @param removals the dependents that the copy leaves out
	 * @return the copy of the base with each mapped file of {@code source} written over its mapped path, without the
	 *   dependents
	 * @throws IllegalArgumentException if the plugin's base digest is empty
	 * @throws IOException if the copy cannot be built, if the base's files no longer have the identity of its base
	 *   digest, or if the copy does not hold a dependent
	 */
	private Path copy(PluginUnderTest.Built plugin, Path source, PluginRemovals removals) throws IOException
	{
		Path base = copyBase(plugin);
		Path directory = storage.resolve(source.getFileName() + COPY_SEPARATOR + identity(plugin, removals).hex());
		if (Files.isDirectory(directory))
			return directory;
		FileTree.build(directory, copy ->
		{
			FileTree.copy(base, copy, _ -> false);
			for (PluginUnderTest.Mapping mapping : plugin.mappings())
			{
				Path mapped = source.resolve(mapping.source());
				if (Files.isDirectory(mapped, NOFOLLOW_LINKS))
					FileTree.copy(mapped, copy.resolve(mapping.target()), _ -> false);
			}
			if (!removals.isEmpty())
				DependentRemoval.removeAll(removals.dependents(), copy, removals.json().orElseThrow());
		});
		return directory;
	}

	/**
	 * Returns the identity of a built plugin's base and mappings and of the removals, which name its copies.
	 *
	 * @param plugin the built plugin
	 * @param removals the dependents that the copy leaves out
	 * @return the identity
	 */
	private static Sha256 identity(PluginUnderTest.Built plugin, PluginRemovals removals)
	{
		StringJoiner text = new StringJoiner("\n");
		text.add(plugin.baseDigest());
		for (PluginUnderTest.Mapping mapping : plugin.mappings())
			text.add(mapping.source() + "\0" + mapping.target());
		for (Dependent dependent : removals.dependents())
		{
			String removal = switch (dependent.removal())
			{
				case Dependent.WholeFile _ -> "file";
				case Dependent.Span span -> "span\0" + span.text();
				case Dependent.HookRegistration hook -> "hook\0" + hook.event() + "\0" + hook.command();
			};
			text.add("remove\0" + dependent.path() + "\0" + removal);
		}
		return Sha256.of(text.toString().getBytes(UTF_8));
	}

	/**
	 * Copies the base of a built plugin, unless it has a copy already.
	 *
	 * @param plugin the built plugin; its base digest must be set
	 * @return the copy of the base
	 * @throws NullPointerException if {@code plugin} is null
	 * @throws IllegalArgumentException if the plugin's base digest is empty
	 * @throws IOException if the base cannot be copied, or if its files no longer have the identity of its base
	 *   digest
	 */
	Path copyBase(PluginUnderTest.Built plugin) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(plugin.baseDigest(), "plugin.baseDigest()").isNotEmpty();
		Path directory = storage.resolve(BASE_PREFIX + plugin.baseDigest());
		if (Files.isDirectory(directory))
			return directory;
		FileTree.build(directory, copy ->
		{
			FileTree.copy(plugin.base(), copy, _ -> false);
			// Compare the copy, not the base, so that a change during the copy is caught too
			String digest = InventoryScanner.scan(copy, Set.of()).identity().hex();
			if (!digest.equals(plugin.baseDigest()))
			{
				throw new WorkspaceException("The files of the plugin base " + plugin.base() + " changed after the " +
					"proposal recorded them. Run revise so that the proposal records the base as it is now.");
			}
		});
		return directory;
	}

	/**
	 * Deletes the plugin copies of a candidate's staged sources. The copies of built plugins' bases are kept.
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
