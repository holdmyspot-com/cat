package com.holdmyspot.cat.common.build;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.delegation.MavenSettings;
import com.holdmyspot.cat.common.delegation.Verification;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Owns Maven launch, bounded output capture and observed process-tree lifecycle.
 */
public final class BuildWorker
{
	private static final int MAXIMUM_DIAGNOSTIC_BYTES = 64 * 1024 * 1024;

	/**
	 * Forcibly stops one OS process when its handle has a stable start identity.
	 *
	 * @param pid process identifier
	 */
	public static void forceStop(long pid)
	{
		ProcessHandle.of(pid).filter(handle -> handle.info().startInstant().isPresent()).
			ifPresent(ProcessHandle::destroyForcibly);
	}

	private final JvmScope scope;
	private final BuildObservations observations;
	private final Map<String, String> inheritedEnvironment;
	private final LongConsumer forceStop;

	/**
	 * Creates a worker bound to one process scope and observation provider.
	 *
	 * @param scope process services
	 * @param observations resource observation provider
	 * @param clock clock for terminal receipts
	 * @param inheritedEnvironment parent environment
	 * @param forceStop guarded single-process forced-stop request
	 */
	public BuildWorker(JvmScope scope, BuildObservations observations, java.time.Clock clock,
		Map<String, String> inheritedEnvironment, LongConsumer forceStop)
	{
		this.scope = Objects.requireNonNull(scope, "scope");
		this.observations = Objects.requireNonNull(observations, "observations");
		this.inheritedEnvironment = Map.copyOf(inheritedEnvironment);
		Objects.requireNonNull(clock, "clock");
		this.forceStop = Objects.requireNonNull(forceStop, "forceStop");
	}

	/**
	 * Runs one already-reserved job and records its terminal result before release.
	 *
	 * @param job immutable build request
	 * @param claim ledger claim
	 * @param reservations shared ledger coordinator
	 * @param evidence owned evidence root
	 * @return terminal result
	 * @throws IOException if launch, output capture, observation, or persistence fails
	 */
	public BuildAdmission.JobResult run(BuildAdmission.Job job, BuildReservations.Claim claim,
		BuildReservations reservations, Path evidence) throws IOException
	{
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(claim, "claim");
		Files.createDirectories(evidence);
		Path invocation = job.invocation();
		Path diagnostics = invocation.resolve("diagnostics");
		Path wrapperLog = diagnostics.resolve("wrapper.log");
		Path mavenLog = diagnostics.resolve("maven.log").toAbsolutePath().normalize();
		Path home;
		Path repository;
		try
		{
			home = Files.createDirectories(invocation.resolve("home"));
			repository = Files.createDirectories(invocation.resolve("repository"));
			Files.createDirectories(invocation.resolve("temporary"));
			Files.createDirectories(diagnostics);
		}
		catch (IOException preparationFailure)
		{
			return failBeforeStart(job, claim, reservations, evidence, mavenLog, wrapperLog, preparationFailure);
		}
		ListBuilder arguments = new ListBuilder(BuildAdmission.command(job.gate()));
		arguments.insertBefore("verify", "-Dmaven.repo.local=" + repository);
		if (Files.exists(mavenLog, java.nio.file.LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Maven diagnostic log path already exists: " + mavenLog);
		Path settings;
		try
		{
			settings = MavenSettings.write(home, inheritedEnvironment);
		}
		catch (UnsupportedOperationException exception)
		{
			IOException preparationFailure = new IOException(exception.getMessage(), exception);
			return failBeforeStart(job, claim, reservations, evidence, mavenLog, wrapperLog, preparationFailure);
		}
		catch (IOException preparationFailure)
		{
			return failBeforeStart(job, claim, reservations, evidence, mavenLog, wrapperLog, preparationFailure);
		}
		arguments.insertBefore("verify", "--settings");
		arguments.insertBefore("verify", settings.toString());
		arguments.insertBefore("verify", "--log-file");
		arguments.insertBefore("verify", mavenLog.toString());
		Map<String, String> childEnvironment = new HashMap<>(Verification.environment(invocation, inheritedEnvironment));
		ProcessBuilder builder = new ProcessBuilder(arguments.values()).directory(job.project().toFile()).
			redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(childEnvironment);
		Process process;
		try
		{
			process = scope.executableFiles().run(builder);
		}
		catch (IOException exception)
		{
			BuildAdmission.JobResult terminal = writeTerminal(job, claim, reservations, evidence, mavenLog,
				"infrastructure_failed", null, Set.of(), null, "not_started");
			reservations.terminal(claim, terminal);
			throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.PROCESS_START_FAILED, wrapperLog, job.jobId(),
				claim.reservationId(), "Could not start Maven worker", exception);
		}
		ProcessIdentity root = null;
		Set<ProcessIdentity> retained = new java.util.LinkedHashSet<>();
		AtomicReference<IOException> drainFailure = new AtomicReference<>();
		Thread drain = null;
		InterruptionState interruptions = new InterruptionState();
		try (process)
		{
			try
			{
				drain = Thread.ofVirtual().start(() -> drain(process.getInputStream(), wrapperLog, drainFailure));
				root = identity(process.pid()).orElseThrow(() ->
					new IOException("Maven process has no stable identity"));
				retained.add(root);
				reservations.started(claim, root);
			}
			catch (IOException | RuntimeException exception)
			{
				destroyOwned(process, retained);
				if (root == null)
				{
					reservations.recoveryRequired(claim);
					throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.RECOVERY_REQUIRED, wrapperLog,
						job.jobId(), claim.reservationId(), "Launched worker identity is unknown; recovery required",
						exception);
				}
				try
				{
					awaitOwnedTermination(process, root, retained, drain, interruptions);
				}
				catch (IOException cleanupFailure)
				{
					reservations.recoveryRequired(claim);
					throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.RECOVERY_REQUIRED, wrapperLog,
						job.jobId(), claim.reservationId(), "Launch ownership could not be reaped; recovery required",
						cleanupFailure);
				}
				Integer observedExit;
				try
				{
					observedExit = process.exitValue();
				}
				catch (IllegalThreadStateException _)
				{
					observedExit = null;
				}
				BuildAdmission.JobResult terminal = writeTerminal(job, claim, reservations, evidence, mavenLog,
					"infrastructure_failed", observedExit, retained, null, "reaped");
				reservations.terminal(claim, terminal);
				throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.PROCESS_IO_FAILED, wrapperLog,
					job.jobId(), claim.reservationId(), "Maven worker ownership setup failed", exception);
			}
			Map<ProcessIdentity, Long> peakProcesses = Map.of();
			long peak = 0; int samples = 0; int observedProcessCount = 0; long maximumGapMillis = 0;
			long previousSampleNanos = 0;
			boolean processExited = false;
			boolean calibration = claim.kind() == BuildReservations.Claim.Kind.CALIBRATION;
			try
			{
				while (true)
				{
					Set<ProcessIdentity> alive;
					if (calibration)
					{
						BuildObservations.TreeSample tree = observations.tree(root, retained);
						alive = tree.alive();
						if (tree.aggregateRssBytes() > peak)
						{
							peak = tree.aggregateRssBytes();
							peakProcesses = tree.rssByIdentity();
						}
						observedProcessCount = Math.max(observedProcessCount, tree.observedProcesses());
						samples = samples + 1;
						long sampleNanos = System.nanoTime();
						if (previousSampleNanos != 0)
							maximumGapMillis = Math.max(maximumGapMillis,
								TimeUnit.NANOSECONDS.toMillis(sampleNanos - previousSampleNanos));
						previousSampleNanos = sampleNanos;
					}
					else
						alive = observations.alive(root, retained);
					retained.addAll(alive);
					reservations.observed(claim, root, retained);
					if (processExited && alive.isEmpty())
						break;
					if (processExited)
					{
						try
						{
							scope.waiting().every(Duration.ofMillis(100)).pause();
							InterruptedException interruption = takePauseInterruption();
							if (interruption != null)
							{
								interruptions.record(interruption);
								destroyOwned(process, retained);
								awaitOwnedTermination(process, root, retained, drain, interruptions);
								processExited = true;
							}
						}
						catch (IOException exception)
						{
							if (!isPauseInterruption(exception))
								throw exception;
							interruptions.record((InterruptedException) exception.getCause());
							Thread.interrupted();
							destroyOwned(process, retained);
							awaitOwnedTermination(process, root, retained, drain, interruptions);
							processExited = true;
						}
					}
					if (!processExited)
					{
						try
						{
							processExited = process.waitFor(100, TimeUnit.MILLISECONDS);
						}
						catch (InterruptedException exception)
						{
							destroyOwned(process, retained);
							interruptions.record(exception);
							awaitOwnedTermination(process, root, retained, drain, interruptions);
							processExited = true;
						}
					}
				}
			}
			catch (IOException | RuntimeException exception)
			{
				destroyOwned(process, retained);
				try
				{
					awaitOwnedTermination(process, root, retained, drain, interruptions);
				}
				catch (IOException cleanupFailure)
				{
					reservations.recoveryRequired(claim);
					throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.RECOVERY_REQUIRED, wrapperLog,
						job.jobId(), claim.reservationId(), "Worker termination could not be established; recovery required",
						cleanupFailure);
				}
				Integer observedExit;
				try
				{
					observedExit = process.exitValue();
				}
				catch (IllegalThreadStateException _)
				{
					observedExit = null;
				}
				BuildAdmission.JobResult failed = writeTerminal(job, claim, reservations, evidence, mavenLog,
					"infrastructure_failed", observedExit, retained, null, "reaped");
				reservations.terminal(claim, failed);
				throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.PROCESS_IO_FAILED, wrapperLog,
					job.jobId(), claim.reservationId(), "Maven worker failed after launch", exception);
			}
			try
			{
				drain.join();
			}
			catch (InterruptedException exception)
			{
				interruptions.record(exception);
				destroyOwned(process, retained);
				awaitOwnedTermination(process, root, retained, drain, interruptions);
			}
			IOException ioFailure = drainFailure.get();
			Integer exit;
			try
			{
				exit = process.exitValue();
			}
			catch (IllegalThreadStateException _)
			{
				exit = null;
			}
			if (!interruptions.occurred() && exit != null)
				Verification.requireMavenLog(Files.isRegularFile(mavenLog, java.nio.file.LinkOption.NOFOLLOW_LINKS),
					exit, wrapperLog);
			String status;
			if (interruptions.occurred())
				status = "interrupted";
			else if (ioFailure != null)
				status = "infrastructure_failed";
			else if (exit != null && exit == 0)
				status = "passed";
			else
				status = "failed";
			Path calibrationRowsPath;
			String provisionalId = java.util.UUID.randomUUID().toString();
			calibrationRowsPath = Path.of("calibration-" + provisionalId + ".jsonl");
			if (claim.kind() == BuildReservations.Claim.Kind.CALIBRATION && exit != null && exit == 0)
				writePeakRows(evidence.resolve(calibrationRowsPath), peakProcesses);
			Map<String, Object> calibrationResult = null;
			if (claim.kind() == BuildReservations.Claim.Kind.CALIBRATION)
			{
				String coverageResult;
				if (exit != null && exit == 0 && peak > 0)
					coverageResult = "complete";
				else
					coverageResult = "failed";
				String processRowsPath;
				if (exit != null && exit == 0)
					processRowsPath = calibrationRowsPath.toString();
				else
					processRowsPath = "";
				calibrationResult = Map.of("peak_rss_bytes", peak, "sample_count", samples,
					"maximum_gap_millis", maximumGapMillis, "observed_process_count", observedProcessCount,
					"coverage_result", coverageResult, "process_rows_path", processRowsPath);
			}
			BuildAdmission.JobResult result = writeTerminal(job, claim, reservations, evidence, mavenLog,
				status, exit, retained, calibrationResult, "reaped");
			if (claim.kind() == BuildReservations.Claim.Kind.CALIBRATION && exit != null && exit == 0)
			{
				try
				{
					reservations.calibrated(claim, peak, samples, maximumGapMillis, observedProcessCount, job,
						result.terminalReceiptPath());
				}
				catch (IOException calibrationFailure)
				{
					// The worker is reaped and its receipt is published, so the reservation is released even though
					// no calibration was recorded: the next build of the family calibrates again.
					try
					{
						reservations.terminal(claim, result);
					}
					catch (IOException releaseFailure)
					{
						calibrationFailure.addSuppressed(releaseFailure);
					}
					throw calibrationFailure;
				}
			}
			reservations.terminal(claim, result);
			if (ioFailure != null)
				throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.PROCESS_IO_FAILED, wrapperLog, job.jobId(),
					claim.reservationId(), "Maven worker output could not be retained", ioFailure);
			if (interruptions.occurred())
			{
				throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.PROCESS_IO_FAILED, wrapperLog,
					job.jobId(), claim.reservationId(), "Interrupted after Maven worker termination was recorded",
					interruptions.cause());
			}
			return result;
		}
		finally
		{
			interruptions.restore();
		}
	}

	/**
	 * Publishes the terminal proof for a worker that failed before process creation.
	 *
	 * @param job immutable build request
	 * @param claim persisted reservation
	 * @param reservations shared ledger coordinator
	 * @param evidence owned evidence directory
	 * @param mavenLog expected retained Maven log path
	 * @param wrapperLog worker diagnostics path
	 * @param cause preparation failure
	 * @return never returns because the preparation failure is reported
	 * @throws IOException if the terminal failure cannot be recorded
	 */
	private BuildAdmission.JobResult failBeforeStart(BuildAdmission.Job job, BuildReservations.Claim claim,
		BuildReservations reservations, Path evidence, Path mavenLog, Path wrapperLog, IOException cause)
		throws IOException
	{
		BuildAdmission.JobResult terminal = writeTerminal(job, claim, reservations, evidence, mavenLog,
			"infrastructure_failed", null, Set.of(), null, "not_started");
		try
		{
			reservations.terminal(claim, terminal);
		}
		catch (IOException publicationFailure)
		{
			throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.PROCESS_IO_FAILED, terminal.terminalReceiptPath(),
				job.jobId(), claim.reservationId(), "Preparation failed and terminal publication/release failed",
				publicationFailure);
		}
		throw new BuildAdmission.Failure(BuildAdmission.Failure.Kind.PROCESS_IO_FAILED, wrapperLog, job.jobId(),
			claim.reservationId(), "Maven worker preparation failed before launch", cause);
	}

	/**
	 * Writes one authenticated terminal result for normal execution, interruption, or launch failure.
	 *
	 * @param job immutable build request
	 * @param claim persisted reservation
	 * @param reservations shared ledger coordinator
	 * @param evidence owned evidence root
	 * @param mavenLog retained Maven log path
	 * @param status terminal status
	 * @param exit process exit status
	 * @param retained stable identities observed during execution
	 * @param calibrationResult calibration details or null
	 * @param terminationResult termination outcome
	 * @return persisted terminal result
	 * @throws IOException if receipt persistence fails
	 */
	private BuildAdmission.JobResult writeTerminal(BuildAdmission.Job job, BuildReservations.Claim claim,
		BuildReservations reservations, Path evidence, Path mavenLog, String status, Integer exit,
		Set<ProcessIdentity> retained, Map<String, Object> calibrationResult, String terminationResult)
		throws IOException
	{
		Map<String, Object> terminal = new java.util.LinkedHashMap<>();
		terminal.put("format_version", 1);
		terminal.put("context_id", reservations.contextId().toString());
		terminal.put("host_boot_id", reservations.hostBootId().toString());
		terminal.put("reservation_id", claim.reservationId());
		terminal.put("job_id", job.jobId());
		terminal.put("assignment_id", job.assignmentId());
		terminal.put("family_id", job.gate().familyId());
		terminal.put("status", status);
		terminal.put("exit_code", exit);
		terminal.put("owned_processes", retained.stream().map(identity -> Map.of("pid", identity.pid(),
			"start", identity.start())).toList());
		terminal.put("termination_result", terminationResult);
		terminal.put("calibration_result", calibrationResult);
		terminal.put("diagnostics_path", evidence.toAbsolutePath().normalize().relativize(mavenLog).toString());
		String receipt = hashReceipt(scope, terminal);
		Path receiptPath = Path.of("terminal-" + receipt + ".json");
		terminal.put("receipt_id", receipt);
		Path destination = evidence.resolve(receiptPath);
		try
		{
			Files.writeString(destination, scope.json().writeValueAsString(terminal));
		}
		catch (IOException exception)
		{
			throw new IOException("Could not publish terminal receipt for job " + job.jobId() + " reservation " +
				claim.reservationId() + " at " + destination, exception);
		}
		return new BuildAdmission.JobResult(job.jobId(), status, exit, mavenLog, claim.admissionReceiptId(), receipt,
			receiptPath);
	}

	/**
	 * Waits for the root, retained descendants and output consumer after forced termination.
	 *
	 * @param process root process
	 * @param root stable root identity
	 * @param retained previously observed descendants
	 * @param drain output consumer, if it started
	 * @param interruptions invocation interruption state
	 * @throws IOException if process observation fails while reaping
	 */
	private void awaitOwnedTermination(Process process, ProcessIdentity root, Set<ProcessIdentity> retained,
		Thread drain, InterruptionState interruptions) throws IOException
	{
		while (true)
		{
			try
			{
				if (!process.waitFor(100, TimeUnit.MILLISECONDS))
					continue;
				if (root == null || observations.alive(root, retained).isEmpty())
					break;
			}
			catch (InterruptedException exception)
			{
				interruptions.record(exception);
				destroyOwned(process, retained);
				continue;
			}
			try
			{
				scope.waiting().every(Duration.ofMillis(50)).pause();
				InterruptedException interruption = takePauseInterruption();
				if (interruption != null)
				{
					interruptions.record(interruption);
					destroyOwned(process, retained);
				}
			}
			catch (IOException exception)
			{
				if (!isPauseInterruption(exception))
					throw exception;
				interruptions.record((InterruptedException) exception.getCause());
				Thread.interrupted();
				destroyOwned(process, retained);
			}
		}
		if (drain != null)
		{
			while (drain.isAlive())
			{
				try
				{
					drain.join();
				}
				catch (InterruptedException exception)
				{
					interruptions.record(exception);
				}
			}
		}
	}

	/**
	 * Identifies only the interruption form produced by the scoped pause adapter.
	 *
	 * @param failure pause failure
	 * @return whether the failure wraps an interruption and the adapter preserved its flag
	 */
	private static boolean isPauseInterruption(IOException failure)
	{
		boolean hasInterruptionCause = failure.getCause() instanceof InterruptedException;
		return hasInterruptionCause && Thread.currentThread().isInterrupted();
	}

	/**
	 * Clears a returned pause's unwrapped interrupt signal for required cleanup.
	 *
	 * @return the interruption signal, or null when the pause returned without interruption
	 */
	private static InterruptedException takePauseInterruption()
	{
		if (!Thread.currentThread().isInterrupted())
			return null;
		Thread.interrupted();
		return new InterruptedException("Thread interrupted while waiting for process termination");
	}

	/**
	 * Retains interruptions until the invocation has completed receipt and reservation reporting.
	 */
	private static final class InterruptionState
	{
		private InterruptedException cause;

		/**
		 * Adds an interruption without discarding the first cause.
		 *
		 * @param interruption observed interruption
		 */
		private void record(InterruptedException interruption)
		{
			if (cause == null)
				cause = interruption;
			else if (cause != interruption)
				cause.addSuppressed(interruption);
		}

		/**
		 * Reports whether an interruption occurred during execution or cleanup.
		 *
		 * @return true after an interruption was recorded
		 */
		private boolean occurred()
		{
			return cause != null;
		}

		/**
		 * Returns the first interruption cause.
		 *
		 * @return original interruption
		 */
		private InterruptedException cause()
		{
			return cause;
		}

		/**
		 * Restores interruption after all reporting and ownership updates complete.
		 */
		private void restore()
		{
			if (occurred())
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Writes the maximum observed resident set for each process in the calibrated tree.
	 *
	 * @param path retained JSONL path
	 * @param rows maximum resident set by stable process identity
	 * @throws IOException if the rows cannot be persisted
	 */
	private static void writePeakRows(Path path, Map<ProcessIdentity, Long> rows) throws IOException
	{
		StringBuilder jsonl = new StringBuilder(rows.size() * 96);
		for (Map.Entry<ProcessIdentity, Long> row : rows.entrySet())
			jsonl.append("{\"pid\":").append(row.getKey().pid()).append(",\"start\":\"").
				append(row.getKey().start()).append("\",\"rss_bytes\":").append(row.getValue()).append("}\n");
		Files.writeString(path, jsonl.toString());
	}

	/**
	 * Hashes the canonical receipt body before its receipt identity is attached.
	 *
	 * @param scope invocation JSON mapper owner
	 * @param receipt receipt body
	 * @return SHA-256 hex digest
	 * @throws IOException if the receipt cannot be serialized
	 */
	private static String hashReceipt(JvmScope scope, Map<String, Object> receipt) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				scope.json().writeValueAsBytes(receipt)));
		}
		catch (NoSuchAlgorithmException impossible)
		{
			throw new IllegalStateException(impossible);
		}
	}

	/**
	 * Stops the process root and every currently retained child identity.
	 *
	 * @param process root process
	 * @param retained previously observed descendants
	 */
	private void destroyOwned(Process process, Set<ProcessIdentity> retained)
	{
		for (ProcessIdentity identity : retained)
			try
			{
				if (observations.identity(identity.pid()).filter(identity::equals).isPresent())
					forceStop.accept(identity.pid());
			}
			catch (IOException _)
			{
				// The subsequent lifecycle proof will retain the reservation if ownership is uncertain.
			}
		process.destroyForcibly();
	}

	/**
	 * Reads the stable launcher identity for a running process.
	 *
	 * @param pid process ID
	 * @return current process identity
	 * @throws IOException if a live process has no stable start token
	 */
	private Optional<ProcessIdentity> identity(long pid) throws IOException
	{
		return observations.identity(pid);
	}

	/**
	 * Consumes process output while retaining only a bounded prefix.
	 *
	 * @param input child output stream
	 * @param destination retained diagnostic file
	 * @param failure asynchronous output failure holder
	 */
	private static void drain(InputStream input, Path destination, AtomicReference<IOException> failure)
	{
		try (input; OutputStream output = Files.newOutputStream(destination))
		{
			byte[] buffer = new byte[8192]; long total = 0; int count;
			while (true)
			{
				count = input.read(buffer);
				if (count < 0)
					break;
				int keep = (int) Math.min(count, Math.max(0, MAXIMUM_DIAGNOSTIC_BYTES - total));
				if (keep > 0)
					output.write(buffer, 0, keep);
				total += count;
			}
			if (total > MAXIMUM_DIAGNOSTIC_BYTES)
				failure.set(new IOException("Maven diagnostics exceeded 64 MiB: " + destination));
		}
		catch (IOException exception)
		{
			failure.set(exception);
		}
	}

	/**
	 * Mutable command copy used to add isolated Maven repository configuration.
	 */
	private static final class ListBuilder
	{
		private final java.util.List<String> values;
		/**
		 * Copies a supported command.
		 *
		 * @param source reviewed command
		 */
		private ListBuilder(java.util.List<String> source)
		{
			values = new java.util.ArrayList<>(source);
		}
		/**
		 * Inserts one argument before the named goal.
		 *
		 * @param value goal argument
		 * @param addition inserted option
		 */
		private void insertBefore(String value, String addition)
		{
			values.add(values.indexOf(value), addition);
		}
		/**
		 * Returns an immutable command.
		 *
		 * @return command arguments
		 */
		private java.util.List<String> values()
		{
			return java.util.List.copyOf(values);
		}
	}
}
