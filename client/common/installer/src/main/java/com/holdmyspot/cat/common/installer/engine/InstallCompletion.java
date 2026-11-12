package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The last step of every install that leaves the harness running a CAT version: it deletes the version folders that
 * the harness no longer needs, as {@link VersionFolders} describes, and discards the progress of an uninstall that
 * stopped, because the install makes that progress stale and a later uninstall must run every step again.
 * <p>
 * <b>Thread Safety:</b> This class is immutable, but it reads and deletes files that other processes may change;
 * {@link #complete} expects its caller to hold the harness lock, and {@link #completeLocked} takes it.
 */
public final class InstallCompletion
{
	private final VersionFolders folders;
	private final Path data;
	private final InstallerState state;

	/**
	 * Creates the completion of an install into a harness installation whose installer data lies in the
	 * installation's data directory.
	 *
	 * @param integration  the harness's integration, which tells which version folder the harness runs
	 * @param installation the harness installation
	 * @param waiting      creates the pauses between the retries of a deletion
	 * @throws NullPointerException if any of the arguments are null
	 */
	public InstallCompletion(HarnessIntegration integration, HarnessInstallation installation, Waiting waiting)
	{
		this(integration, installation, installation.dataDirectory(),
			InstallerState.of(installation.dataDirectory(), integration.harness()), waiting);
	}

	/**
	 * Creates the completion of an install.
	 *
	 * @param integration  the harness's integration, which tells which version folder the harness runs
	 * @param installation the harness installation, whose home bounds every deletion
	 * @param data         the installation's installer data directory
	 * @param state        the installer's state of the harness
	 * @param waiting      creates the pauses between the retries of a deletion
	 * @throws NullPointerException if any of the arguments are null
	 */
	InstallCompletion(HarnessIntegration integration, HarnessInstallation installation, Path data,
		InstallerState state, Waiting waiting)
	{
		requireThat(data, "data").isNotNull();
		this.folders = new VersionFolders(integration, installation, state, waiting);
		this.data = data;
		this.state = state;
	}

	/**
	 * Completes an install while the caller holds the harness lock.
	 *
	 * @param active  the version that the harness runs after the install
	 * @param before  the version that the harness ran before the install, or null when it ran no plugin version
	 * @param running the plugin folder that the running installer belongs to
	 * @return the version folders that were left and the running installer's folder when it is to be deleted
	 * @throws NullPointerException if {@code active} or {@code running} are null
	 * @throws IOException          if the uninstall's progress cannot be deleted
	 */
	FolderRetention complete(PluginVersion active, PluginVersion before, Path running) throws IOException
	{
		FolderRetention retention = folders.retain(active, before, running);
		Uninstaller.discard(data);
		return retention;
	}

	/**
	 * Completes an install that ran without the harness lock, taking the lock for this step. When another operation
	 * keeps holding the lock, nothing is deleted and the outcome says so.
	 *
	 * @param active  the version that the harness runs after the install
	 * @param before  the version that the harness ran before the install, or empty when it ran no plugin version
	 * @param running the plugin folder that the running installer belongs to
	 * @param clock   the source of the lock deadline
	 * @param waiting creates pauses between lock attempts
	 * @return the version folders that were left and the running installer's folder when it is to be deleted
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the lock cannot be opened or the uninstall's progress cannot be deleted
	 */
	public FolderRetention completeLocked(PluginVersion active, Optional<PluginVersion> before, Path running,
		Clock clock, Waiting waiting) throws IOException
	{
		requireThat(before, "before").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		FolderRetention locked = new FolderRetention(List.of("Another CAT operation held the harness lock, so no " +
			"old version folder was deleted and the progress of a stopped uninstall was kept; the next install tries " +
			"again."), List.of());
		Optional<ExclusiveFileLock> acquired;
		try
		{
			acquired = OperationLock.acquire(state, clock, OperationLock.WAIT, waiting);
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return locked;
		}
		if (acquired.isEmpty())
			return locked;
		try (ExclusiveFileLock lock = acquired.get())
		{
			if (!lock.isValid())
				return locked;
			return complete(active, before.orElse(null), running);
		}
	}
}
