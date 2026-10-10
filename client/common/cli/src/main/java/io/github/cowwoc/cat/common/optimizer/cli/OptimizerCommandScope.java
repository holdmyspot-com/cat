package io.github.cowwoc.cat.common.optimizer.cli;

import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.execution.CapCalibrator;
import io.github.cowwoc.cat.common.optimizer.execution.SliceSettings;
import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.workspace.Forks;
import io.github.cowwoc.cat.common.secrets.OptimizerCommandScopeAccess;
import io.github.cowwoc.cat.common.secrets.OptimizerCommandScopeSecrets;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Owns the values and services shared while one {@code prompt-optimizer} command runs.
 * <p>
 * A launcher process runs one command, so this scope lives as long as the process's command. Each harness supplies a
 * production scope that resolves the process environment; tests supply a scope of fakes. The core reads no
 * environment of its own.
 * <p>
 * Values that depend on the project, such as the project root and the campaign store, are resolved when a command
 * first needs them, so that a working directory outside a Git repository is refused by the command rather than by the
 * scope's creation.
 */
public abstract class OptimizerCommandScope implements AutoCloseable
{
	static
	{
		OptimizerCommandScopeSecrets.setAccess(new OptimizerCommandScopeAccess()
		{
			@Override
			public SliceSettings sliceSettings(OptimizerCommandScope scope)
			{
				return scope.sliceSettings();
			}

			@Override
			public Path projectRoot(OptimizerCommandScope scope) throws CommandRefusedException, IOException
			{
				return scope.projectRoot();
			}

			@Override
			public CampaignStore store(OptimizerCommandScope scope) throws CommandRefusedException, IOException
			{
				return scope.store();
			}

			@Override
			public List<Path> heldOutDirectories(OptimizerCommandScope scope) throws IOException
			{
				return scope.heldOutDirectories();
			}
		});
	}

	private boolean closed;
	private Path projectRoot;
	private CampaignStore store;

	/**
	 * Creates an open scope.
	 */
	protected OptimizerCommandScope()
	{
	}

	/**
	 * Returns the JSON mapper shared by this command.
	 *
	 * @return the mapper
	 * @throws IllegalStateException if this scope is closed
	 */
	public abstract JsonMapper json();

	/**
	 * Returns the port that starts nested sessions through the harness's runner.
	 *
	 * @return the port
	 * @throws IllegalStateException if this scope is closed
	 */
	public abstract SessionPort sessions();

	/**
	 * Returns the port that lists the harness's profile inventory, reads prompt files' profile bindings and reads
	 * the role profile.
	 *
	 * @return the port
	 * @throws IllegalStateException if this scope is closed
	 */
	public abstract ProfileInventory profiles();

	/**
	 * Returns the signal that reports whether the host user interrupted the host session.
	 *
	 * @return the signal
	 * @throws IllegalStateException if this scope is closed
	 */
	public abstract HostInterruptSignal interrupts();

	/**
	 * Returns the clock.
	 *
	 * @return the clock
	 * @throws IllegalStateException if this scope is closed
	 */
	public abstract Clock clock();

	/**
	 * Returns the launcher's working directory, below the project.
	 *
	 * @return the directory
	 * @throws IllegalStateException if this scope is closed
	 */
	public abstract Path directory();

	/**
	 * Returns the user's home directory supplied by the harness environment boundary.
	 *
	 * @return the directory whose {@code .cat/tests} tree holds scenarios for user rules
	 * @throws IllegalStateException if this scope is closed
	 */
	public abstract Path home();

	/**
	 * Returns the harness that runs the launcher.
	 *
	 * @return the harness
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Harness harness();

	/**
	 * Returns the source of campaign identifiers and draw seeds.
	 *
	 * @return the source
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract RandomGenerator random();

	/**
	 * Returns the launcher that runs {@code <harness> --version}.
	 *
	 * @return the launcher
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract HarnessVersion.Launcher harnessVersionLauncher();

	/**
	 * Returns the files, relative to the project, that decide which prompt files the harness loads.
	 *
	 * @return the files
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract List<Path> loaderConfiguration();

	/**
	 * Returns the forks of the project's campaigns.
	 *
	 * @return the forks
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Forks forks();

	/**
	 * Returns the calibration of the caps of certification attempts.
	 *
	 * @return the calibration
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract CapCalibrator calibrator();

	/**
	 * Returns the slice's time budget: the 8-minute budget plus the 1-minute grace period that fits the skill's
	 * 10-minute tool timeout.
	 *
	 * @return the budget
	 * @throws IllegalStateException if this scope is closed
	 */
	final SliceSettings sliceSettings()
	{
		ensureOpen();
		return SliceSettings.DEFAULT;
	}

	/**
	 * Reads the harness's version. Each call runs the launcher, so a command that never needs the version, such as
	 * {@code status}, never runs it.
	 *
	 * @return the first non-blank line of {@code <harness> --version}, or {@code unknown} if there is none or the
	 *   command fails
	 * @throws IllegalStateException if this scope is closed
	 */
	final String harnessVersion()
	{
		return HarnessVersion.read(harness().commandName(), harnessVersionLauncher());
	}

	/**
	 * Returns the project root: the directory that holds {@code .git}, at or above the working directory.
	 *
	 * @return the root
	 * @throws IllegalStateException if this scope is closed
	 * @throws CommandRefusedException if the working directory is not inside a Git repository
	 * @throws IOException if the working directory cannot be resolved
	 */
	final Path projectRoot() throws CommandRefusedException, IOException
	{
		ensureOpen();
		if (projectRoot == null)
		{
			Path start = directory().toRealPath();
			for (Path candidate = start; candidate != null; candidate = candidate.getParent())
			{
				if (Files.exists(candidate.resolve(".git")))
				{
					projectRoot = candidate;
					return projectRoot;
				}
			}
			throw new CommandRefusedException(start + " is not inside a Git repository.");
		}
		return projectRoot;
	}

	/**
	 * Returns the campaign store of the project.
	 *
	 * @return the store
	 * @throws IllegalStateException if this scope is closed
	 * @throws CommandRefusedException if the working directory is not inside a Git repository
	 * @throws IOException if the working directory cannot be resolved
	 */
	final CampaignStore store() throws CommandRefusedException, IOException
	{
		ensureOpen();
		if (store == null)
			store = new FileCampaignStore(projectRoot(), json());
		return store;
	}

	/**
	 * Lists the {@code held_out} directories of the project's campaigns.
	 *
	 * @return the real paths of those that exist
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the working directory is not inside a Git repository or the campaigns cannot be listed
	 */
	protected final List<Path> heldOutDirectories() throws IOException
	{
		try
		{
			return FileCampaignStore.heldOutDirectories(projectRoot());
		}
		catch (CommandRefusedException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Returns whether this scope is closed.
	 *
	 * @return {@code true} if this scope is closed
	 */
	public final boolean isClosed()
	{
		return closed;
	}

	/**
	 * Prevents further service access.
	 */
	@Override
	public void close()
	{
		closed = true;
	}

	/**
	 * Ensures that this scope remains open.
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	protected final void ensureOpen()
	{
		if (closed)
			throw new IllegalStateException("Scope is closed");
	}
}
