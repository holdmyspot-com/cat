package com.holdmyspot.cat.common.build.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.build.BuildObservations;
import com.holdmyspot.cat.common.build.BuildReservations;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import org.testng.annotations.Test;

/**
 * Tests immutable job and reviewed-family invariants used by shared reservations.
 */
public final class BuildReservationsTest
{
	/**
	 * Creates the test suite.
	 */
	public BuildReservationsTest()
	{
	}

	/**
	 * Confirms equivalent gate definitions derive one family identity.
	 */
	@Test
	public void equivalentDefinitionsShareFamily() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("reservation-family-"));
			Files.writeString(project.resolve("pom.xml"), "test project\n");
			Path pom = Path.of("pom.xml");
			BuildAdmission.MavenGate first = BuildAdmission.MavenGate.of(project, List.of("common/cli"),
				List.of("BuildAdmissionTest"), List.of(pom), "review", "reviewer");
			BuildAdmission.MavenGate second = BuildAdmission.MavenGate.of(project, List.of("common/cli"),
				List.of("BuildAdmissionTest"), List.of(pom), "review", "reviewer");
			requireThat(first.familyId(), "firstfamily").isEqualTo(second.familyId());
			requireThat(first.commandProfileId(), "profile").isNotBlank();
		}
	}

	/**
	 * Confirms two scheduler instances serialize one shared calibration claim and retain both decisions.
	 *
	 * @throws IOException if ledger fixtures cannot be created or read
	 * @throws InterruptedException if the competing scheduler threads are interrupted
	 */
	@Test
	public void sharedLedgerSerializesCalibrationClaims() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); TestJvmScope scope = new TestJvmScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-reservations-race-"));
			Path proc = Files.createDirectories(root.resolve("proc"));
			UUID boot = UUID.randomUUID();
			Files.createDirectories(proc.resolve("sys/kernel/random"));
			Files.writeString(proc.resolve("sys/kernel/random/boot_id"), boot.toString());
			Files.writeString(proc.resolve("meminfo"), "MemAvailable: 16777216 kB\n");
			Path contextFile = root.resolve("context.json");
			UUID contextId = UUID.randomUUID();
			Files.writeString(contextFile, "{\"format_version\":1,\"context_id\":\"" + contextId +
				"\",\"host_boot_id\":\"" + boot + "\",\"ledger_path\":\"ledger\",\"authority_id\":\"test\"," +
				"\"authority_actor\":\"test\",\"authority_evidence_paths\":[\"authority.txt\"]," +
				"\"cleanup_owner\":\"test\"}");
			Files.writeString(root.resolve("authority.txt"), "fixture authority");
			BuildAdmission.HostContext context = BuildAdmission.HostContext.read(scope.json(), contextFile);
			BuildObservations.Metrics metrics = new BuildObservations.Metrics()
			{
				@Override
				public double cpuLoad()
				{
					return 0.1;
				}
				@Override
				public int logicalProcessors()
				{
					return 4;
				}
				@Override
				public int availableProcessors()
				{
					return 4;
				}
				@Override
				public Optional<BuildObservations.RawProcess> process(long pid)
				{
					return Optional.empty();
				}
				@Override
				public Set<Long> descendants(long pid)
				{
					return Set.of();
				}
			};
			long controllerPid = ProcessHandle.current().pid();
			BuildObservations observations = new BuildObservations(proc, metrics, pid ->
			{
				if (pid == controllerPid)
					return Optional.of(new ProcessIdentity(pid, "controller"));
				return Optional.empty();
			});
			BuildAdmission.MavenGate gate = BuildAdmission.MavenGate.of(root, List.of("common/cli"),
				List.of("BuildAdmissionTest"), List.of(), "review", "parent");
			BuildAdmission.Job job = new BuildAdmission.Job("race", "assignment", root, gate,
				root.resolveSibling("invocation"), "source", List.of(), List.of());
			Path evidenceA = root.resolve("evidence-a");
			Path evidenceB = root.resolve("evidence-b");
			BuildReservations first = new BuildReservations(scope.fileLocks(), context, Clock.systemUTC());
			BuildReservations second = new BuildReservations(scope.fileLocks(), context, Clock.systemUTC());
			Files.createDirectories(context.ledgerPath());
			Path lockPath = context.ledgerPath().resolve("ledger.lock");
			Files.createFile(lockPath);
			Path lockFixture = Files.createDirectories(root.resolve("lock-fixture"));
			try (java.nio.file.WatchService lockWatch = BuildChild.watchFor(lockFixture);
				Process externalLock = new ProcessBuilder(BuildChild.fixtureCommand(lockFixture.toString(), "lock",
					lockPath.toString())).start())
			{
				try
				{
					BuildChild.awaitFile(lockWatch, lockFixture.resolve("lock.ready"), java.time.Duration.ofSeconds(5));
					requireThat(Files.exists(lockFixture.resolve("lock.ready")), "externalLockReady").isTrue();
					IOException lockFailure = null;
					try
					{
						first.tryClaim(job, observations, evidenceA);
					}
					catch (IOException exception)
					{
						lockFailure = exception;
					}
					requireThat(lockFailure, "realProcessLockBlocksClaim").isNotNull();
				}
				finally
				{
					Files.writeString(lockFixture.resolve("release"), "release");
					if (!externalLock.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
					{
						externalLock.destroyForcibly();
						externalLock.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
					}
				}
			}
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			AtomicReference<BuildReservations.Claim> claimA = new AtomicReference<>();
			AtomicReference<BuildReservations.Claim> claimB = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread threadA = Thread.ofPlatform().start(() -> reserve(first, job, observations, evidenceA, ready, start,
				claimA, failure));
			Thread threadB = Thread.ofPlatform().start(() -> reserve(second, job, observations, evidenceB, ready, start,
				claimB, failure));
			ready.await();
			start.countDown();
			threadA.join();
			threadB.join();
			if (failure.get() != null)
				throw new AssertionError("Concurrent reservation failed", failure.get());
			List<BuildReservations.Claim> claims = List.of(claimA.get(), claimB.get());
			requireThat(claims.stream().filter(claim -> claim.kind() == BuildReservations.Claim.Kind.CALIBRATION).count(),
				"oneCalibration").isEqualTo(1L);
				requireThat(claims.stream().filter(claim -> claim.kind() == BuildReservations.Claim.Kind.DEFERRED).count(),
					"oneDeferred").isEqualTo(1L);
			requireThat(claims.stream().allMatch(claim -> claim.admissionReceiptId() != null), "receiptIds").isTrue();
			requireThat(scope.json().readTree(context.ledgerPath().resolve("ledger.json").toFile()).
				path("reservations").size(), "oneReservation").isEqualTo(1);
			BuildAdmission.RecoveryResult recovery = first.reconcile(observations, root.resolve("recovery"));
			requireThat(recovery.reconciled(), "startingReservationNeedsRecovery").isFalse();
			requireThat(recovery.unresolvedReservations().size(), "uncertainEntryRetained").isEqualTo(1);
			Path ledger = context.ledgerPath().resolve("ledger.json");
			Files.writeString(ledger, "{\"format_version\":1,\"context_id\":\"" + contextId +
				"\",\"host_boot_id\":\"" + boot + "\",\"generation\":1,\"calibrations\":[]," +
				"\"reservations\":[{}]}");
			IOException malformedLedger = null;
			try
			{
				first.tryClaim(job, observations, evidenceA);
			}
			catch (IOException exception)
			{
				malformedLedger = exception;
			}
			requireThat(malformedLedger, "malformedReservationRefused").isNotNull();
		}
	}

	/**
	 * Releases a terminal-pending reservation only when its bound terminal receipt is valid and all actors are dead.
	 *
	 * @throws IOException if state fixtures cannot be written
	 */
	@Test(description = "infrastructureReceiptAllowsReleaseAfterProof")
	public void infrastructureReceiptReleasesAfterProof() throws IOException
	{
		terminalPendingRelease(false);
	}

	/**
	 * Keeps a reservation when a terminal receipt is bound to another reservation.
	 *
	 * @throws IOException if state fixtures cannot be written
	 */
	@Test(description = "mismatchedTerminalReceiptRetainsReservation")
	public void mismatchedReceiptKeepsReservation() throws IOException
	{
		terminalPendingRelease(true);
	}

	/**
	 * Exercises an infrastructure receipt and optionally a mismatched receipt.
	 *
	 * @param includeMismatch whether to replace the bound reservation identity
	 * @throws IOException if state fixtures cannot be written
	 */
	private void terminalPendingRelease(boolean includeMismatch) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); TestJvmScope scope = new TestJvmScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-reservation-recovery-"));
			UUID boot = UUID.fromString(Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip());
			Path contextFile = root.resolve("context.json");
			UUID contextId = UUID.randomUUID();
			Files.writeString(root.resolve("authority.txt"), "reviewed test authority");
			Files.writeString(contextFile, "{\"format_version\":1,\"context_id\":\"" + contextId +
				"\",\"host_boot_id\":\"" + boot + "\",\"ledger_path\":\"ledger\",\"authority_id\":\"test\"," +
				"\"authority_actor\":\"test\",\"authority_evidence_paths\":[\"authority.txt\"]," +
				"\"cleanup_owner\":\"test\"}");
			BuildAdmission.HostContext context = BuildAdmission.HostContext.read(scope.json(), contextFile);
			BuildObservations.Metrics metrics = new BuildObservations.Metrics()
			{
				@Override
				public double cpuLoad()
				{
					return 0.1;
				}
				@Override
				public int logicalProcessors()
				{
					return 4;
				}
				@Override
				public int availableProcessors()
				{
					return 4;
				}
				@Override
				public Optional<BuildObservations.RawProcess> process(long pid)
				{
					return Optional.empty();
				}
				@Override
				public Set<Long> descendants(long pid)
				{
					return Set.of();
				}
			};
			Path proc = Files.createDirectories(root.resolve("proc"));
			Files.createDirectories(proc.resolve("sys/kernel/random"));
			Files.writeString(proc.resolve("sys/kernel/random/boot_id"), boot.toString());
			Files.writeString(proc.resolve("meminfo"), "MemAvailable: 16777216 kB\n");
			BuildObservations observations = new BuildObservations(proc, metrics, _ -> Optional.empty());
			String reservationId = UUID.randomUUID().toString();
			String jobId = "recovered-job";
			String assignmentId = "assignment";
			String familyId = "family";
			Path evidence = Files.createDirectories(root.resolve("evidence"));
			Map<String, Object> terminal = new LinkedHashMap<>();
			terminal.put("format_version", 1);
			terminal.put("context_id", contextId.toString());
			terminal.put("host_boot_id", boot.toString());
			terminal.put("reservation_id", reservationId);
			terminal.put("job_id", jobId);
			terminal.put("assignment_id", assignmentId);
			terminal.put("family_id", familyId);
			terminal.put("status", "infrastructure_failed");
			terminal.put("exit_code", null);
			terminal.put("owned_processes", List.of());
			terminal.put("termination_result", "not_started");
			terminal.put("calibration_result", null);
			terminal.put("diagnostics_path", "diagnostics/maven.log");
			JsonMapper mapper = scope.json();
			String receiptId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				mapper.writeValueAsBytes(terminal)));
			terminal.put("receipt_id", receiptId);
			Path terminalPath = evidence.resolve("terminal.json");
			Files.writeString(terminalPath, mapper.writeValueAsString(terminal));
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("reservation_id", reservationId);
			row.put("job_id", jobId);
			row.put("assignment_id", assignmentId);
			row.put("family_id", familyId);
			row.put("phase", "terminal_pending_release");
			row.put("calibration", true);
			row.put("reserved_bytes", null);
			row.put("owner", Map.of("pid", ProcessHandle.current().pid(), "start", "controller"));
			row.put("process", null);
			row.put("descendants", List.of());
			row.put("evidence_path", "evidence");
			row.put("terminal_receipt_path", "terminal.json");
			Path ledgerDirectory = Files.createDirectories(context.ledgerPath());
			Files.createFile(ledgerDirectory.resolve("ledger.lock"));
			Map<String, Object> ledger = new LinkedHashMap<>();
			ledger.put("format_version", 1);
			ledger.put("context_id", contextId.toString());
			ledger.put("host_boot_id", boot.toString());
			ledger.put("generation", 1);
			ledger.put("calibrations", List.of());
			ledger.put("reservations", List.of(row));
			Files.writeString(ledgerDirectory.resolve("ledger.json"), mapper.writeValueAsString(ledger));
			BuildReservations reservations = new BuildReservations(scope.fileLocks(), context, Clock.systemUTC());
			BuildAdmission.RecoveryResult recovered = reservations.reconcile(observations, root.resolve("recovery"));
			requireThat(recovered.reconciled(), "validTerminalPendingEntryRecovered").isTrue();
			requireThat(mapper.readTree(ledgerDirectory.resolve("ledger.json").toFile()).path("reservations").size(),
				"verifiedTerminalEntryReleased").isEqualTo(0);
			if (includeMismatch)
			{
				row.put("reservation_id", UUID.randomUUID().toString());
				ledger.put("reservations", List.of(row));
				Files.writeString(ledgerDirectory.resolve("ledger.json"), mapper.writeValueAsString(ledger));
				BuildAdmission.RecoveryResult mismatch = reservations.reconcile(observations,
					root.resolve("recovery-mismatch"));
				requireThat(mismatch.reconciled(), "mismatchedReservationIsNotRecovered").isFalse();
				requireThat(mapper.readTree(ledgerDirectory.resolve("ledger.json").toFile()).
					path("reservations").size(), "mismatchedReservationRemainsHeld").isEqualTo(1);

				Files.writeString(terminalPath, "{}");
				BuildAdmission.RecoveryResult invalid = reservations.reconcile(observations,
					root.resolve("recovery-invalid"));
				requireThat(invalid.reconciled(), "invalidReceiptIsNotRecovered").isFalse();
				requireThat(mapper.readTree(ledgerDirectory.resolve("ledger.json").toFile()).
					path("reservations").size(), "invalidTerminalEntryRemainsHeld").isEqualTo(1);
			}
		}
		catch (java.security.NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException(exception);
		}
	}

	/**
	 * Attempts one claim after both racing threads reach the shared start barrier.
	 *
	 * @param reservations scheduler reservation store
	 * @param job requested job
	 * @param observations host measurement provider
	 * @param evidence worker evidence directory
	 * @param ready barrier reached before racing
	 * @param start barrier that releases both claim attempts
	 * @param result destination for the resulting claim
	 * @param failure destination for a thrown failure
	 */
	private static void reserve(BuildReservations reservations, BuildAdmission.Job job, BuildObservations observations,
		Path evidence, CountDownLatch ready, CountDownLatch start, AtomicReference<BuildReservations.Claim> result,
		AtomicReference<Throwable> failure)
	{
		try
		{
			ready.countDown();
			start.await();
			result.set(reservations.tryClaim(job, observations, evidence));
		}
		catch (IOException | InterruptedException | RuntimeException exception)
		{
			failure.compareAndSet(null, exception);
			Thread.currentThread().interrupt();
		}
	}
}
