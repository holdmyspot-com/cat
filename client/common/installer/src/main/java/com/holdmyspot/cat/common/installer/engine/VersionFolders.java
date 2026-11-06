package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deletes the version folders of CAT's plugin that a harness no longer needs, and the copies of them that the
 * harness keeps elsewhere, in every folder that the installation's layout declares. A harness session that started
 * before an install keeps running hooks and skills from its version folder until it restarts, so the folders of the
 * active version, of the version that the active one replaced, and the folder that the running installer belongs to
 * stay; every other version folder and copy is deleted without following links, and so is every other file or folder
 * in a folder of version folders that lies inside CAT's marketplace folder, which only the installer writes, such as
 * the plugin that an earlier layout put in that folder itself. A harness that selects the folder
 * it runs by the folder's name would run the replaced version's folder or the running installer's folder instead of
 * the active one's when the name selects it, so neither stays then: the running installer's folder is left for
 * deletion once the installer no longer needs its own files, and the other is deleted. A folder that cannot be
 * deleted, such as one whose file a process still has open on Windows, is left for a later install.
 * <p>
 * The installer's state records the active version and the version that it replaced, so that a later install of the
 * same version keeps the replaced version's folder.
 * <p>
 * <b>Thread Safety:</b> This class is immutable, but it reads and deletes files that other processes may change; its
 * callers hold the harness lock.
 */
final class VersionFolders
{
	/**
	 * The largest record of the active and replaced versions that is read.
	 */
	private static final int MAXIMUM_RECORD_BYTES = 1024;
	private final HarnessIntegration integration;
	private final HarnessInstallation installation;
	private final InstallerState state;

	/**
	 * Creates the version folders of a harness installation.
	 *
	 * @param integration  the harness's integration, which tells which version folder the harness runs
	 * @param installation the harness installation, whose home bounds every deletion
	 * @param state        the installer's state of the harness, which holds the record of the versions
	 * @throws NullPointerException if any of the arguments are null
	 */
	VersionFolders(HarnessIntegration integration, HarnessInstallation installation, InstallerState state)
	{
		requireThat(integration, "integration").isNotNull();
		requireThat(installation, "installation").isNotNull();
		requireThat(state, "state").isNotNull();
		this.integration = integration;
		this.installation = installation;
		this.state = state;
	}

	/**
	 * Records the version that the harness runs after an install, and deletes every version folder other than the
	 * active version's, the replaced version's and the running installer's, and also those two when the harness would
	 * run them instead of the active one's. The replaced version is the one that the
	 * harness ran before the install when that differs from the active one; when the install left the harness's
	 * version unchanged it is the version that the record names as replaced, and when the harness ran no version
	 * there is none.
	 *
	 * @param active  the version that the harness runs after the install
	 * @param before  the version that the harness ran before the install, or null when it ran no plugin version
	 * @param running the plugin folder that the running installer belongs to
	 * @return the folders that were left and the running installer's folder when it is to be deleted
	 * @throws NullPointerException if {@code active} or {@code running} are null
	 */
	FolderRetention retain(PluginVersion active, PluginVersion before, Path running)
	{
		requireThat(active, "active").isNotNull();
		requireThat(running, "running").isNotNull();
		PluginVersion replaced;
		try
		{
			replaced = replaced(active, before);
		}
		catch (IOException e)
		{
			return new FolderRetention(List.of("The record of CAT's versions " + record() + " could not be updated (" +
				describe(e) + "), so no old version folder was deleted; the next install tries again."), List.of());
		}
		List<String> notices = new ArrayList<>();
		List<Path> exitDeletions = new ArrayList<>();
		Path runningFolder = real(running);
		for (Path folder : installation.versionCopyFolders())
		{
			if (!Files.isDirectory(folder, NOFOLLOW_LINKS))
				continue;
			try (DirectoryStream<Path> children = Files.newDirectoryStream(folder))
			{
				for (Path child : children)
				{
					if (!isUnneeded(folder, child, active, replaced, runningFolder))
						continue;
					if (child.equals(runningFolder))
						exitDeletions.add(child);
					else
						delete(child, notices);
				}
			}
			catch (IOException e)
			{
				notices.add("The version folders in " + folder + " could not be listed (" + describe(e) + "); the " +
					"next install tries again.");
			}
		}
		return new FolderRetention(notices, exitDeletions);
	}

	/**
	 * Returns the version that the active one replaced, and records both when they changed.
	 *
	 * @param active the version that the harness runs after the install
	 * @param before the version that the harness ran before the install, or null when it ran no plugin version
	 * @return the replaced version, or null when there is none
	 * @throws IOException if the record cannot be read or written
	 */
	private PluginVersion replaced(PluginVersion active, PluginVersion before) throws IOException
	{
		List<PluginVersion> recorded = read();
		PluginVersion replaced;
		if (before == null)
			replaced = null;
		else if (!before.equals(active))
			replaced = before;
		else if (recorded.size() == 2 && recorded.getFirst().equals(active))
			replaced = recorded.getLast();
		else
			replaced = null;
		List<PluginVersion> versions = new ArrayList<>(List.of(active));
		if (replaced != null)
			versions.add(replaced);
		if (!versions.equals(recorded))
			write(versions);
		return replaced;
	}

	/**
	 * Reads the record of the versions.
	 *
	 * @return the active version, then the version that it replaced if any; empty when there is no valid record
	 * @throws IOException if the record exists but cannot be read
	 */
	private List<PluginVersion> read() throws IOException
	{
		Path record = record();
		StateFiles.requireNoLinks(record);
		if (Files.notExists(record, NOFOLLOW_LINKS))
			return List.of();
		String text = BoundedInput.readUtf8(record, MAXIMUM_RECORD_BYTES, "The record of CAT's versions");
		List<PluginVersion> versions = new ArrayList<>();
		for (String line : text.lines().toList())
		{
			try
			{
				versions.add(PluginVersion.parse(line));
			}
			catch (IllegalArgumentException _)
			{
				// A record that a later install cannot use names no version, and that install writes a valid one
				return List.of();
			}
		}
		if (versions.isEmpty() || versions.size() > 2)
			return List.of();
		return versions;
	}

	/**
	 * Replaces the record of the versions.
	 *
	 * @param versions the active version, then the version that it replaced if any
	 * @throws IOException if the record cannot be written
	 */
	private void write(List<PluginVersion> versions) throws IOException
	{
		StringBuilder text = new StringBuilder();
		for (PluginVersion version : versions)
			text.append(version).append('\n');
		StateFiles.createDirectories(state.directory());
		StateFiles.write(record(), text.toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Returns the record of the active version and the version that it replaced.
	 *
	 * @return the record in the installer's state
	 */
	private Path record()
	{
		return state.directory().resolve("versions");
	}

	/**
	 * Indicates whether an entry of a folder that holds copies of the plugin versions is the copy of a version that
	 * the harness no longer needs. A link is never one of CAT's copies. In a folder inside CAT's marketplace folder,
	 * which only the installer writes, every other entry is CAT's own, so a file, or a folder whose name names no plugin
	 * version, is what an earlier layout of the plugin left there. Elsewhere, an entry that is not a real directory, or
	 * whose name names no plugin version in that folder, is not one of CAT's copies.
	 *
	 * @param folder        the folder, one that the installation's layout declares
	 * @param child         the entry
	 * @param active        the version that the harness runs
	 * @param replaced      the version that the active one replaced, or null when there is none
	 * @param runningFolder the real path of the plugin folder that the running installer belongs to
	 * @return true if the entry is to be deleted
	 * @throws IOException if the entry's attributes cannot be read
	 */
	private boolean isUnneeded(Path folder, Path child, PluginVersion active, PluginVersion replaced,
		Path runningFolder) throws IOException
	{
		BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, NOFOLLOW_LINKS);
		// Windows reports a junction as both a directory and "other"
		if (attributes.isSymbolicLink() || attributes.isOther())
			return false;
		boolean catOwned = folder.startsWith(installation.marketplaceRoot());
		if (!attributes.isDirectory())
			return catOwned;
		Optional<PluginVersion> version = installation.copiedVersion(folder, child.getFileName().toString());
		if (version.isEmpty())
			return catOwned;
		if (version.get().equals(active))
			return false;
		// The replaced version's folder and the running installer's folder stay unless the harness would run them
		boolean kept = version.get().equals(replaced) || child.equals(runningFolder);
		return !kept || integration.runsInstead(version.get(), active);
	}

	/**
	 * Deletes a version folder without following links, or notes that it was left.
	 *
	 * @param folder  the version folder
	 * @param notices the sentences to which a note of a folder that was left is added
	 */
	private void delete(Path folder, List<String> notices)
	{
		try
		{
			LinkSafeDeletion.delete(folder, installation.home());
		}
		catch (IOException e)
		{
			notices.add("The old version folder " + folder + " could not be deleted (" + describe(e) + "); the next " +
				"install tries again.");
		}
	}

	/**
	 * Describes why a file operation failed.
	 *
	 * @param failure the failure
	 * @return the description
	 */
	private static String describe(IOException failure)
	{
		if (failure instanceof AccessDeniedException denied)
			return "access to " + denied.getFile() + " was denied";
		if (failure instanceof FileSystemException system && system.getReason() != null)
			return system.getFile() + ": " + system.getReason();
		return failure.getMessage();
	}

	/**
	 * Returns the real path of a file, or its absolute, normalized path when it does not exist.
	 *
	 * @param path the path
	 * @return the path to compare
	 */
	private static Path real(Path path)
	{
		try
		{
			return path.toRealPath();
		}
		catch (IOException _)
		{
			return path.toAbsolutePath().normalize();
		}
	}
}
