package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

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
	 * @return the plugin directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a built plugin's base digest is empty
	 * @throws IOException if the copy cannot be built, if the staged source has no plugin root, or if the files of a
	 *   built plugin's base no longer have the identity of its base digest
	 */
	Path copy(PluginUnderTest plugin, Path source) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(source, "source").isNotNull();
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
			case PluginUnderTest.Built built -> copy(built, source);
		};
	}

	/**
	 * Returns a built plugin as the trials of a staged source install it, building it if it does not exist.
	 *
	 * @param plugin the built plugin; its base digest must be set
	 * @param source the staged source, whose directory name identifies its candidate
	 * @return the copy of the base with each mapped file of {@code source} written over its mapped path
	 * @throws IllegalArgumentException if the plugin's base digest is empty
	 * @throws IOException if the copy cannot be built, or if the base's files no longer have the identity of its
	 *   base digest
	 */
	private Path copy(PluginUnderTest.Built plugin, Path source) throws IOException
	{
		Path base = copyBase(plugin);
		Path directory = storage.resolve(source.getFileName() + COPY_SEPARATOR + identity(plugin).hex());
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
		});
		return directory;
	}

	/**
	 * Returns the identity of a built plugin's base and mappings, which name its copies.
	 *
	 * @param plugin the built plugin
	 * @return the identity
	 */
	private static Sha256 identity(PluginUnderTest.Built plugin)
	{
		StringJoiner text = new StringJoiner("\n");
		text.add(plugin.baseDigest());
		for (PluginUnderTest.Mapping mapping : plugin.mappings())
			text.add(mapping.source() + "\0" + mapping.target());
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
