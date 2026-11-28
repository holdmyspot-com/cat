package com.holdmyspot.cat.common.build.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.secrets.BuildAdmissionSecrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import org.testng.annotations.Test;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Exercises the OS process-handle stop boundary with two independently owned leaf fixture JVMs.
 */
public final class SystemBuildProcessStopsContractTest extends BuildProcessStopsContract
{
	/**
	 * Verifies readiness wins when a timed watch returns after the readiness file is published.
	 *
	 * @throws IOException if the owned readiness fixture cannot be written
	 * @throws InterruptedException if readiness observation is interrupted
	 */
	@Test
	public void observesReadyAfterWatchTimeout() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("build-child-deadline-"));
			Path ready = directory.resolve("ready");
			try (WatchService watch = new WatchService()
			{
				@Override
				public WatchKey poll(long timeout, TimeUnit unit)
				{
					try
					{
						Files.createFile(ready);
					}
					catch (IOException failure)
					{
						throw new UncheckedIOException(failure);
					}
					return null;
				}

				@Override
				public WatchKey poll()
				{
					throw new UnsupportedOperationException();
				}

				@Override
				public WatchKey take()
				{
					throw new UnsupportedOperationException();
				}

				@Override
				public void close()
				{
				}
			})
			{
				BuildChild.awaitFile(watch, ready, Duration.ofSeconds(5));
				requireThat(ready, "publishedReadiness").isRegularFile();
			}
		}
	}

	/**
	 * Starts selected and peer leaf processes for the production stop adapter.
	 *
	 * @return owned system-process fixture
	 * @throws IOException if fixture processes cannot start or publish readiness
	 * @throws InterruptedException if readiness observation is interrupted
	 */
	@Override
	protected Fixture startFixture() throws IOException, InterruptedException
	{
		SystemFixture fixture = new SystemFixture();
		try
		{
			Path root = fixture.cleanup.add(Files.createTempDirectory("build-process-stops-contract-"));
			fixture.selected = startActor(root.resolve("selected"));
			fixture.peer = startActor(root.resolve("peer"));
			return fixture;
		}
		catch (IOException | InterruptedException | RuntimeException failure)
		{
			try
			{
				fixture.close();
			}
			catch (IOException cleanupFailure)
			{
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * Starts one watched leaf fixture process with private working, home, temporary, and output paths.
	 *
	 * @param directory process-owned fixture directory
	 * @return ready leaf process
	 * @throws IOException if process startup or readiness fails
	 * @throws InterruptedException if readiness observation is interrupted
	 */
	private static Actor startActor(Path directory) throws IOException, InterruptedException
	{
		Files.createDirectory(directory);
		Path home = Files.createDirectory(directory.resolve("home"));
		Path temporary = Files.createDirectory(directory.resolve("temporary"));
		Path stdout = Files.createFile(directory.resolve("stdout.log"));
		Path stderr = Files.createFile(directory.resolve("stderr.log"));
		ProcessBuilder builder = new ProcessBuilder(BuildChild.fixtureCommand(directory.toString(), "grandchild"));
		builder.directory(directory.toFile());
		builder.redirectOutput(stdout.toFile());
		builder.redirectError(stderr.toFile());
		builder.environment().put("HOME", home.toString());
		builder.environment().put("TMPDIR", temporary.toString());
		builder.environment().put("TMP", temporary.toString());
		builder.environment().put("TEMP", temporary.toString());
		return new Actor(directory, builder);
	}

	/**
	 * Stops and joins a process whose readiness could not be observed.
	 *
	 * @param process owned process
	 * @param failure startup failure that remains primary
	 */
	private static void stopAfterFailedReadiness(Process process, Throwable failure)
	{
		process.destroyForcibly();
		try
		{
			if (!process.waitFor(5, TimeUnit.SECONDS))
				failure.addSuppressed(new IOException("Fixture process remained live after forced cleanup"));
		}
		catch (InterruptedException interruption)
		{
			failure.addSuppressed(new IOException("Interrupted while joining failed fixture startup", interruption));
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Retains one leaf process and its owned fixture directory for cleanup.
	 */
	private static final class Actor
	{
		private final Path directory;
		private final Process process;

		/**
		 * Creates the cleanup owner for one ready leaf process.
		 *
		 * @param directory process-owned fixture directory
		 * @param builder command and owned process environment
		 */
		private Actor(Path directory, ProcessBuilder builder) throws IOException, InterruptedException
		{
			this.directory = directory;
			try (WatchService watch = BuildChild.watchFor(directory))
			{
				process = builder.start();
				try
				{
					BuildChild.awaitFile(watch, directory.resolve("grandchild.ready"), process);
				}
				catch (IOException | InterruptedException | RuntimeException failure)
				{
					try
					{
						failure.addSuppressed(new IOException("Fixture readiness state: pid=" + process.pid() +
							", alive=" + process.isAlive() + ", ready=" +
							Files.exists(directory.resolve("grandchild.ready")) + ", stdout=" +
							Files.readString(directory.resolve("stdout.log")) + ", stderr=" +
							Files.readString(directory.resolve("stderr.log"))));
					}
					catch (IOException diagnosticFailure)
					{
						failure.addSuppressed(diagnosticFailure);
					}
					stopAfterFailedReadiness(process, failure);
					throw failure;
				}
			}
		}
	}

	/**
	 * Owns selected and peer processes until both have exited and their temporary root can be removed.
	 */
	private static final class SystemFixture implements Fixture
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final LongConsumer forceStop = BuildAdmissionSecrets.getBuildAdmissionAccess().systemForceStop();
		private Actor selected;
		private Actor peer;
		private InterruptedException cleanupInterruption;

		/**
		 * Creates the owner for both actors under one temporary root.
		 */
		private SystemFixture()
		{
		}

		@Override
		public LongConsumer forceStop()
		{
			return forceStop;
		}

		@Override
		public long selectedPid()
		{
			return selected.process.pid();
		}

		@Override
		public long peerPid()
		{
			return peer.process.pid();
		}

		@Override
		public boolean selectedAlive()
		{
			return selected.process.isAlive();
		}

		@Override
		public boolean selectedPidReused()
		{
			return ProcessHandle.of(selected.process.pid()).isPresent();
		}

		@Override
		public boolean peerAlive()
		{
			return peer.process.isAlive();
		}

		@Override
		public boolean awaitSelectedExit() throws InterruptedException
		{
			return selected.process.waitFor(5, TimeUnit.SECONDS);
		}

		@Override
		public void finishSelected() throws IOException, InterruptedException
		{
			Files.createFile(selected.directory.resolve("release"));
			if (!selected.process.waitFor(5, TimeUnit.SECONDS))
				throw new IOException("Selected leaf fixture did not finish after release");
		}

		@Override
		public void close() throws IOException
		{
			boolean selectedStopped = stopAndJoin(selected);
			boolean peerStopped = stopAndJoin(peer);
			if (!selectedStopped || !peerStopped)
			{
				if (cleanupInterruption != null)
					Thread.currentThread().interrupt();
				throw new IOException("Could not prove all owned fixture processes stopped");
			}
			IOException cleanupFailure = null;
			try
			{
				cleanup.close();
			}
			catch (IOException exception)
			{
				cleanupFailure = exception;
			}
			if (cleanupInterruption != null)
			{
				Thread.currentThread().interrupt();
				IOException interruptionFailure = new IOException("Interrupted while joining fixture cleanup",
					cleanupInterruption);
				if (cleanupFailure != null)
					interruptionFailure.addSuppressed(cleanupFailure);
				throw interruptionFailure;
			}
			if (cleanupFailure != null)
				throw cleanupFailure;
		}

		/**
		 * Forcibly stops and joins one actor before its fixture directory can be removed.
		 *
		 * @param actor process owner, or null when startup did not reach that actor
		 * @return true when no process remains live
		 */
		private boolean stopAndJoin(Actor actor)
		{
			if (actor == null)
				return true;
			if (actor.process.isAlive())
				actor.process.destroyForcibly();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (actor.process.isAlive())
			{
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0)
					return false;
				try
				{
					actor.process.waitFor(remaining, TimeUnit.NANOSECONDS);
				}
				catch (InterruptedException exception)
				{
					if (cleanupInterruption == null)
						cleanupInterruption = exception;
				}
			}
			return !actor.process.isAlive();
		}
	}
}
