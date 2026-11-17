package com.holdmyspot.cat.common.build.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.AbstractJvmScope;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.test.ProcessCompletionContract;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.build.BuildObservations;
import com.holdmyspot.cat.common.build.BuildObservations.ProcessState;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.secrets.BuildAdmissionSecrets;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;
import org.testng.SkipException;
import tools.jackson.databind.JsonNode;

/**
 * Tests worker terminal lifecycle and whole-tree calibration sampling.
 */
public final class BuildWorkerTest
{
	/**
	 * Creates the test suite.
	 */
	public BuildWorkerTest()
	{
	}

	/**
	 * The retained-root wait observes a separately controlled exit event.
	 *
	 * @throws IOException if fixture cleanup fails
	 * @throws InterruptedException if the waiter is interrupted
	 */
	@Test
	public void retainedRootWaitDoesNotCompleteRoot() throws IOException, InterruptedException
	{
		ProcessCompletionContract.waitDoesNotCompleteRunningProcess(BuildWorkerTest::retainedRootFixture);
	}

	/**
	 * The retained root publishes status and root liveness together while preserving its child.
	 *
	 * @throws IOException if fixture cleanup fails
	 * @throws InterruptedException if completion is interrupted
	 */
	@Test
	public void retainedRootCompletionPublishesStatus() throws IOException, InterruptedException
	{
		ProcessCompletionContract.explicitCompletionPublishesStatus(BuildWorkerTest::retainedRootFixture);
	}

	/**
	 * Forcibly stopping the retained root leaves its child alive.
	 *
	 * @throws IOException if fixture cleanup fails
	 * @throws InterruptedException if completion is interrupted
	 */
	@Test
	public void retainedRootForceStopKeepsChildLive() throws IOException, InterruptedException
	{
		ProcessCompletionContract.forceStopCompletesOnlyRoot(BuildWorkerTest::retainedRootFixture);
	}

	/**
	 * Creates an isolated retained-root and child process pair for lifecycle checks.
	 *
	 * @return owned process fixture
	 */
	private static ProcessCompletionContract.Fixture retainedRootFixture()
	{
		Set<Long> live = ConcurrentHashMap.newKeySet();
		long rootPid = 53_001;
		long childPid = 53_002;
		live.add(rootPid);
		live.add(childPid);
		RetainedChildProcess process = new RetainedChildProcess(rootPid, childPid, live, null, null, null, null, null);
		return new ProcessCompletionContract.Fixture()
		{
			@Override
			public Process process()
			{
				return process;
			}

			@Override
			public void complete(int status)
			{
				process.completeRoot(status);
			}

			@Override
			public boolean observerAlive()
			{
				return live.contains(rootPid);
			}

			@Override
			public boolean otherActorAlive()
			{
				return live.contains(childPid);
			}

			@Override
			public void close()
			{
				process.destroyForcibly();
				live.remove(childPid);
			}
		};
	}

	/**
	 * Verifies the worker waits for a retained child after Maven exits and calibrates the aggregate RSS.
	 *
	 * @throws IOException if fixture state cannot be created or the job fails
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void terminalWaitIncludesRetainedChild() throws IOException, InterruptedException
	{
		exerciseWorkerLifecycle(false);
	}

	/**
	 * Confirms preparation errors publish a not-started infrastructure receipt and release only after proof.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void preparationFailureRecordsNoStart() throws IOException, InterruptedException
	{
		exerciseWorkerLifecycle(true);
	}

	/**
	 * Confirms interruption terminal evidence preserves the child's observed exit code.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void interruptionRecordsActualExit() throws IOException, InterruptedException
	{
		exerciseWorkerLifecycle(false);
	}

	/**
	 * Proves observation failure waits for a retained child and output drain before reporting.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void observationFailureReapsBeforeReporting() throws IOException, InterruptedException
	{
		exerciseWorkerLifecycle(false);
	}

	/**
	 * Proves failed terminal publication keeps the last valid reservation.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void terminalReceiptFailureRetainsReservation() throws IOException, InterruptedException
	{
		exerciseWorkerLifecycle(false);
	}

	/**
	 * Proves cleanup observation uncertainty keeps ownership for recovery.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test(description = "cleanupObservationFailureRetainsReservation")
	public void cleanupObservationFailureKeepsHold() throws IOException, InterruptedException
	{
		exerciseWorkerLifecycle(false);
	}

	/**
	 * Verifies deferred admission uses the scoped pause and samples host resources again before launch.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void deferredAdmissionUsesScopeWait() throws IOException, InterruptedException
	{
		exerciseScopedWait(WaitScenario.DEFERRED_ADMISSION);
	}

	/**
	 * Verifies admission pause failure stops launch and retains its cause and job context.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void admissionWaitFailureStopsLaunch() throws IOException, InterruptedException
	{
		exerciseScopedWait(WaitScenario.ADMISSION_FAILURE);
	}

	/**
	 * Verifies a live retained child holds the reservation until scoped observation resumes.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void retainedChildUsesScopeWait() throws IOException, InterruptedException
	{
		exerciseScopedWait(WaitScenario.RETAINED_CHILD);
	}

	/**
	 * Verifies a descendant-wait interruption reaps the tree before reporting the observed exit code.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void childWaitInterruptReapsTree() throws IOException, InterruptedException
	{
		exerciseScopedWait(WaitScenario.CHILD_WAIT_INTERRUPTED);
	}

	/**
	 * Verifies forced cleanup uses the process scope's controlled pause while a child remains live.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void cleanupUsesScopeWait() throws IOException, InterruptedException
	{
		exerciseScopedWait(WaitScenario.CLEANUP_WAIT);
	}

	/**
	 * Verifies repeated cleanup interruptions do not end ownership before the child and drain finish.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void cleanupWaitInterruptReapsTree() throws IOException, InterruptedException
	{
		exerciseScopedWait(WaitScenario.CLEANUP_INTERRUPTED);
	}

	/**
	 * Verifies descendant-wait failure waits for the tree and output drain before publishing failure.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void childWaitFailureReapsTree() throws IOException, InterruptedException
	{
		exerciseScopedWait(WaitScenario.CHILD_WAIT_FAILURE);
	}

	/**
	 * Verifies forced-cleanup wait failure retains the reservation without a reaped receipt.
	 *
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void cleanupWaitFailureKeepsHold() throws IOException, InterruptedException
	{
		exerciseScopedWait(WaitScenario.CLEANUP_WAIT_FAILURE);
	}

	/**
	 * Runs the controlled root/child lifecycle fixture, optionally stopping after preparation failure assertions.
	 *
	 * @param preparationOnly whether only preparation-failure behavior is needed
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	private void exerciseWorkerLifecycle(boolean preparationOnly) throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-worker-test-")).toRealPath();
			Path project = Files.createDirectories(root.resolve("project"));
			Path invocation = root.resolve("invocation");
			Path evidence = root.resolve("evidence");
			Path proc = Files.createDirectories(root.resolve("proc"));
			java.util.UUID boot = java.util.UUID.randomUUID();
			Files.createDirectories(proc.resolve("sys/kernel/random"));
			Files.writeString(proc.resolve("sys/kernel/random/boot_id"), boot.toString());
			Files.writeString(proc.resolve("meminfo"), "MemAvailable: 16777216 kB\n");
			Path contextFile = root.resolve("context.json");
			Files.writeString(contextFile, "{\"format_version\":1,\"context_id\":\"" +
				java.util.UUID.randomUUID() + "\",\"host_boot_id\":\"" + boot +
				"\",\"ledger_path\":\"ledger\",\"authority_id\":\"test\",\"authority_actor\":\"test\"," +
				"\"authority_evidence_paths\":[\"authority.txt\"],\"cleanup_owner\":\"test\"}");
			Files.writeString(root.resolve("authority.txt"), "fixture authority");
			Set<Long> live = ConcurrentHashMap.newKeySet();
			long rootPid = 51_001;
			long childPid = 51_002;
			long unrelatedPid = 51_003;
			AtomicReference<RetainedChildProcess> rootProcess = new AtomicReference<>();
			AtomicReference<Runnable> childStopAction = new AtomicReference<>();
			FakeBuildProcessStops processStops = new FakeBuildProcessStops(Map.of(rootPid, () ->
			{
				RetainedChildProcess process = rootProcess.get();
				if (process != null)
					process.destroyForcibly();
			}, childPid, () ->
			{
				Runnable stop = childStopAction.get();
				if (stop != null)
					stop.run();
			}));
			live.add(unrelatedPid);
			AtomicReference<CountDownLatch> childSampleBarrier = new AtomicReference<>(new CountDownLatch(3));
			AtomicReference<CountDownLatch> rootExitBarrier = new AtomicReference<>();
			AtomicBoolean malformedRss = new AtomicBoolean();
			AtomicBoolean failNextLaunch = new AtomicBoolean();
			AtomicBoolean failNextChildIdentity = new AtomicBoolean();
			AtomicBoolean failChildIdentityAlways = new AtomicBoolean();
			AtomicBoolean keepChildAliveOnDestroy = new AtomicBoolean();
			AtomicReference<CountDownLatch> rootWaitNextLaunch = new AtomicReference<>();
			AtomicReference<Runnable> afterFirstWaitNextLaunch = new AtomicReference<>();
			AtomicReference<CountDownLatch> destroyBarrierNextLaunch = new AtomicReference<>();
			AtomicReference<CountDownLatch> destroyReleaseNextLaunch = new AtomicReference<>();
			AtomicReference<CountDownLatch> drainEnteredNextLaunch = new AtomicReference<>();
			AtomicReference<CountDownLatch> drainReleaseNextLaunch = new AtomicReference<>();
			AtomicReference<Path> ledgerPath = new AtomicReference<>();
			AtomicBoolean childWasPersistedBeforeObservationFailure = new AtomicBoolean();
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
					if (!live.contains(pid))
						return Optional.empty();
					try
					{
						Path status = Files.createDirectories(proc.resolve(Long.toString(pid))).resolve("status");
						if (malformedRss.get() && pid == childPid)
							Files.writeString(status, "VmRSS:\tbroken kB\n");
						else
						{
							long kilobytes = 40;
							if (pid == rootPid)
								kilobytes = 20;
							Files.writeString(status, "VmRSS:\t" + kilobytes + " kB\n");
						}
						if (pid == childPid)
							childSampleBarrier.get().countDown();
						return Optional.of(new BuildObservations.RawProcess(pid, rootPid, ProcessState.LIVE));
					}
					catch (IOException exception)
					{
						throw new IllegalStateException(exception);
					}
				}
				@Override
				public Set<Long> descendants(long pid)
				{
					if (pid == rootPid && live.contains(childPid))
						return Set.of(childPid);
					return Set.of();
				}
			};
			BuildObservations observations = new BuildObservations(proc, metrics, pid ->
			{
				if (pid == childPid && failChildIdentityAlways.get())
					throw new IOException("persistent synthetic identity observation failure");
				if (pid == childPid && failNextChildIdentity.getAndSet(false))
					throw new IOException("synthetic identity observation failure");
				if (!live.contains(pid))
					return Optional.empty();
				return Optional.of(new ProcessIdentity(pid, "worker" + pid));
			});
			live.add(ProcessHandle.current().pid());
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield,
				Clock.systemUTC(), builder ->
				{
					if (failNextLaunch.getAndSet(false))
						throw new IOException("synthetic wrapper launch failure");
					live.add(rootPid);
					live.add(childPid);
					List<String> command = builder.command();
					int logIndex = command.indexOf("--log-file");
					Path mavenLog = Path.of(command.get(logIndex + 1));
					Files.createDirectories(mavenLog.getParent());
					Files.createFile(mavenLog);
					CountDownLatch childDestroyEntered = destroyBarrierNextLaunch.getAndSet(null);
					CountDownLatch childDestroyRelease = destroyReleaseNextLaunch.getAndSet(null);
					childStopAction.set(() ->
					{
						if (childDestroyEntered != null)
						{
							childDestroyEntered.countDown();
							try
							{
								childDestroyRelease.await();
							}
							catch (InterruptedException _)
							{
								Thread.currentThread().interrupt();
							}
						}
						if (!keepChildAliveOnDestroy.get())
							live.remove(childPid);
					});
					CountDownLatch rootWaitEntered = rootWaitNextLaunch.getAndSet(null);
					RetainedChildProcess process = new RetainedChildProcess(rootPid, childPid, live,
						rootWaitEntered, rootExitBarrier.getAndSet(null), afterFirstWaitNextLaunch.getAndSet(null),
						drainEnteredNextLaunch.getAndSet(null), drainReleaseNextLaunch.getAndSet(null));
					rootProcess.set(process);
					if (rootWaitEntered == null)
						process.completeAfterWaitStarts();
					return process;
				});
			try (JvmScope scope = new TestJvmScope(root.resolve("scope"), executables))
			{
				BuildAdmission.HostContext context = BuildAdmission.HostContext.read(scope.json(), contextFile);
				ledgerPath.set(context.ledgerPath().resolve("ledger.json"));
				BuildAdmission admission = BuildAdmissionSecrets.getBuildAdmissionAccess().create(scope, context,
					observations, scope.clock(), Map.of("PATH", "/bin"), processStops);
				BuildAdmission.MavenGate gate = BuildAdmission.MavenGate.of(project, List.of("common/cli"),
					List.of("BuildWorkerTest"), List.of(), "review", "parent");
				BuildAdmission.Job job = new BuildAdmission.Job("worker", "assignment", project, gate,
					invocation, "source-manifest", List.of(), List.of());
				Path failedInvocation = root.resolve("preparation-failure-invocation");
				Files.createDirectories(failedInvocation);
				Files.writeString(failedInvocation.resolve("home"), "not a directory");
				Path failedEvidence = root.resolve("preparation-failure-evidence");
				BuildAdmission.Job failedPreparation = new BuildAdmission.Job("preparation-failure", "assignment",
					project, gate, failedInvocation, "source-manifest", List.of(), List.of());
				BuildAdmission.Failure preparationFailure = null;
				try
				{
					admission.run(failedPreparation, failedEvidence);
				}
				catch (BuildAdmission.Failure exception)
				{
					preparationFailure = exception;
				}
				requireThat(preparationFailure, "preparationFailureReported").isNotNull();
				JsonNode failedLedger = readLedger(scope, context.ledgerPath().resolve("ledger.json"));
				requireThat(failedLedger.path("reservations").size(), "preparationReservationReleased").isEqualTo(0);
				try (java.util.stream.Stream<Path> terminalFiles = Files.list(failedEvidence))
				{
					Path receipt = terminalFiles.filter(path -> path.getFileName().toString().startsWith("terminal-")).
						findFirst().orElseThrow();
					JsonNode value = scope.json().readTree(receipt.toFile());
					requireThat(value.path("status").stringValue(), "preparationInfrastructureStatus").
						isEqualTo("infrastructure_failed");
					requireThat(value.path("exit_code").isNull(), "preparationExitUnknown").isTrue();
					requireThat(value.path("termination_result").stringValue(), "preparationNotStarted").
						isEqualTo("not_started");
				}
				if (preparationOnly)
					return;
				AtomicReference<BuildAdmission.JobResult> result = new AtomicReference<>();
				AtomicReference<Throwable> failure = new AtomicReference<>();
				Thread worker = Thread.ofPlatform().start(() ->
				{
					try
					{
						result.set(admission.run(job, evidence));
					}
					catch (IOException | RuntimeException exception)
					{
						failure.set(exception);
					}
				});
				requireThat(childSampleBarrier.get().await(5, TimeUnit.SECONDS), "childObservedBeforeRelease").isTrue();
				live.remove(childPid);
				worker.join(TimeUnit.SECONDS.toMillis(5));
				requireThat(worker.isAlive(), "workerReapedChild").isFalse();
				if (failure.get() != null)
					throw new AssertionError("Worker failed", failure.get());
				requireThat(result.get().passed(), "jobPassed").isTrue();
				JsonNode ledger = readLedger(scope, context.ledgerPath().resolve("ledger.json"));
				JsonNode calibration = ledger.path("calibrations").get(0);
				requireThat(calibration.path("peak_rss_bytes").longValue(), "aggregatePeakRss").isEqualTo(60L * 1024L);
				requireThat(calibration.path("reservation_bytes").longValue(), "ceilPeakPlusQuarter").
					isEqualTo((60L * 1024L * 5 + 3) / 4);
				requireThat(calibration.path("sample_count").intValue(), "calibrationSampleCount").isGreaterThan(0);
				requireThat(calibration.path("observed_process_count").intValue(), "wholeTreeIdentityCount").
					isEqualTo(2);
				requireThat(ledger.path("reservations").size(), "terminalReservationReleased").isEqualTo(0);
				BuildAdmission.Job ordinaryJob = new BuildAdmission.Job("ordinary-worker", "assignment", project,
					gate, root.resolve("ordinary-invocation"), "source-manifest", List.of(), List.of());
				Path ordinaryEvidence = root.resolve("ordinary-evidence");
				CountDownLatch ordinaryChildObserved = new CountDownLatch(1);
				CountDownLatch ordinaryRootExited = new CountDownLatch(1);
				childSampleBarrier.set(ordinaryChildObserved);
				rootExitBarrier.set(ordinaryRootExited);
				malformedRss.set(true);
				CountDownLatch ordinaryDone = new CountDownLatch(1);
				AtomicReference<BuildAdmission.JobResult> ordinaryResult = new AtomicReference<>();
				AtomicReference<Throwable> ordinaryFailure = new AtomicReference<>();
				Thread ordinaryWorker = Thread.ofPlatform().start(() ->
				{
					try
					{
						ordinaryResult.set(admission.run(ordinaryJob, ordinaryEvidence));
					}
					catch (IOException | RuntimeException exception)
					{
						ordinaryFailure.set(exception);
					}
					finally
					{
						ordinaryDone.countDown();
					}
				});
				requireThat(ordinaryChildObserved.await(5, TimeUnit.SECONDS), "ordinaryChildObservedWithoutRss").isTrue();
				requireThat(!ordinaryDone.await(100, TimeUnit.MILLISECONDS),
					"ordinaryWorkerRemainsOwnedWhileChildLives").isTrue();
				requireThat(ordinaryWorker.isAlive(), "ordinaryWorkerWaitsForRetainedChild").isTrue();
				requireThat(ordinaryRootExited.await(5, TimeUnit.SECONDS), "ordinaryRootExitedWithChildRetained").isTrue();
				JsonNode ordinaryActiveLedger = readLedger(scope, context.ledgerPath().resolve("ledger.json"));
				requireThat(ordinaryActiveLedger.path("reservations").size(), "ordinaryReservationHeldUntilChildExit").
					isEqualTo(1);
				try (java.util.stream.Stream<Path> terminalFiles = Files.list(ordinaryEvidence))
				{
					requireThat(terminalFiles.noneMatch(path -> path.getFileName().toString().startsWith("terminal-")),
						"ordinaryReceiptWaitsForChildExit").isTrue();
				}
				live.remove(childPid);
				ordinaryWorker.join(TimeUnit.SECONDS.toMillis(5));
				requireThat(ordinaryWorker.isAlive(), "ordinaryWorkerReapedChild").isFalse();
				if (ordinaryFailure.get() != null)
					throw new AssertionError("Ordinary worker failed without calibration RSS", ordinaryFailure.get());
				requireThat(ordinaryResult.get().passed(), "ordinaryJobPassedWithoutRss").isTrue();
				JsonNode ordinaryLedger = readLedger(scope, context.ledgerPath().resolve("ledger.json"));
				requireThat(ordinaryLedger.path("reservations").size(), "ordinaryReservationReleasedAfterChildExit").
					isEqualTo(0);
				requireThat(Files.isRegularFile(ordinaryEvidence.resolve(ordinaryResult.get().terminalReceiptPath())),
					"ordinaryTerminalReceiptPersisted").isTrue();
				BuildAdmission.Job observationFailureJob = new BuildAdmission.Job("observation-failure-worker",
					"assignment", project, gate, root.resolve("observation-failure-invocation"),
					"source-manifest", List.of(), List.of());
				Path observationFailureEvidence = root.resolve("observation-failure-evidence");
				CountDownLatch observationFailureTriggered = new CountDownLatch(1);
				CountDownLatch cleanupStarted = new CountDownLatch(1);
				CountDownLatch allowChildTermination = new CountDownLatch(1);
				CountDownLatch drainEntered = new CountDownLatch(1);
				CountDownLatch allowDrain = new CountDownLatch(1);
				childWasPersistedBeforeObservationFailure.set(false);
				afterFirstWaitNextLaunch.set(() ->
				{
					try
					{
						childWasPersistedBeforeObservationFailure.set(
							Files.readString(ledgerPath.get()).contains("\"pid\":" + childPid));
					}
					catch (IOException exception)
					{
						throw new IllegalStateException(exception);
					}
					failNextChildIdentity.set(true);
					observationFailureTriggered.countDown();
				});
				destroyBarrierNextLaunch.set(cleanupStarted);
				destroyReleaseNextLaunch.set(allowChildTermination);
				drainEnteredNextLaunch.set(drainEntered);
				drainReleaseNextLaunch.set(allowDrain);
				AtomicReference<Throwable> observationFailure = new AtomicReference<>();
				Thread observationFailureWorker = Thread.ofPlatform().start(() ->
				{
					try
					{
						admission.run(observationFailureJob, observationFailureEvidence);
					}
					catch (IOException | RuntimeException exception)
					{
						observationFailure.set(exception);
					}
				});
				try
				{
					requireThat(drainEntered.await(5, TimeUnit.SECONDS), "observationFailureDrainStarted").isTrue();
					requireThat(observationFailureTriggered.await(5, TimeUnit.SECONDS),
						"observationFailureAfterRetainedChild").isTrue();
					requireThat(childWasPersistedBeforeObservationFailure.get(), "childIdentityPersistedBeforeFailure").
						isTrue();
					requireThat(cleanupStarted.await(5, TimeUnit.SECONDS), "observationFailureCleanupStarted").isTrue();
					JsonNode duringCleanupLedger = readLedger(scope, ledgerPath.get());
					requireThat(duringCleanupLedger.path("reservations").size(), "reservationHeldDuringCleanup").
						isEqualTo(1);
					try (java.util.stream.Stream<Path> terminalFiles = Files.list(observationFailureEvidence))
					{
						requireThat(terminalFiles.noneMatch(path -> path.getFileName().toString().startsWith("terminal-")),
							"receiptWaitsForChildAndDrain").isTrue();
					}
					allowChildTermination.countDown();
					observationFailureWorker.join(TimeUnit.MILLISECONDS.toMillis(100));
					requireThat(observationFailureWorker.isAlive(), "drainStillOwnsWorker").isTrue();
					allowDrain.countDown();
					observationFailureWorker.join(TimeUnit.SECONDS.toMillis(5));
					requireThat(observationFailureWorker.isAlive(), "observationFailureWorkerReaped").isFalse();
					requireThat(observationFailure.get() instanceof BuildAdmission.Failure,
						"observationFailureReportedAfterCleanup").isTrue();
					JsonNode observationFailureLedger = readLedger(scope, ledgerPath.get());
					requireThat(observationFailureLedger.path("reservations").size(), "observationFailureReleased").
						isEqualTo(0);
					try (java.util.stream.Stream<Path> terminalFiles = Files.list(observationFailureEvidence))
					{
						Path receipt = terminalFiles.filter(path -> path.getFileName().toString().startsWith("terminal-")).
							findFirst().orElseThrow();
						JsonNode terminal = scope.json().readTree(receipt.toFile());
						requireThat(terminal.path("status").stringValue(), "observationFailureInfrastructureStatus").
							isEqualTo("infrastructure_failed");
						requireThat(terminal.path("termination_result").stringValue(), "observationFailureReaped").
						isEqualTo("reaped");
				}
				}
				finally
				{
					allowChildTermination.countDown();
					allowDrain.countDown();
					if (observationFailureWorker.isAlive())
					{
						observationFailureWorker.interrupt();
						observationFailureWorker.join(TimeUnit.SECONDS.toMillis(5));
					}
				}
				BuildAdmission.Job interruptedOrdinaryJob = new BuildAdmission.Job("interrupted-ordinary-worker",
					"assignment", project, gate, root.resolve("interrupted-ordinary-invocation"), "source-manifest",
					List.of(), List.of());
				Path interruptedOrdinaryEvidence = root.resolve("interrupted-ordinary-evidence");
				CountDownLatch interruptedOrdinaryChildObserved = new CountDownLatch(1);
				CountDownLatch interruptedOrdinaryWaitEntered = new CountDownLatch(1);
				CountDownLatch interruptedOrdinaryStopEntered = new CountDownLatch(1);
				CountDownLatch interruptedOrdinaryStopRelease = new CountDownLatch(1);
				childSampleBarrier.set(interruptedOrdinaryChildObserved);
				rootWaitNextLaunch.set(interruptedOrdinaryWaitEntered);
				destroyBarrierNextLaunch.set(interruptedOrdinaryStopEntered);
				destroyReleaseNextLaunch.set(interruptedOrdinaryStopRelease);
				AtomicReference<Throwable> interruptedOrdinaryFailure = new AtomicReference<>();
				AtomicBoolean interruptedOrdinaryStatusRestored = new AtomicBoolean();
				Thread interruptedOrdinaryWorker = Thread.ofPlatform().start(() ->
				{
					try
					{
						admission.run(interruptedOrdinaryJob, interruptedOrdinaryEvidence);
					}
					catch (IOException | RuntimeException exception)
					{
						interruptedOrdinaryFailure.set(exception);
						interruptedOrdinaryStatusRestored.set(Thread.currentThread().isInterrupted());
					}
				});
				requireThat(interruptedOrdinaryChildObserved.await(5, TimeUnit.SECONDS),
					"interruptedOrdinaryChildObservedWithoutRss").isTrue();
				requireThat(interruptedOrdinaryWaitEntered.await(5, TimeUnit.SECONDS),
					"interruptedOrdinaryRootWaitEntered").isTrue();
				interruptedOrdinaryWorker.interrupt();
				requireThat(interruptedOrdinaryStopEntered.await(5, TimeUnit.SECONDS),
					"interruptedOrdinaryGuardedChildStopRequested").isTrue();
				requireThat(live.contains(childPid), "interruptedChildLivesUntilGuardedStopCompletes").isTrue();
				requireThat(live.contains(unrelatedPid), "unrequestedSyntheticActorRemainsLive").isTrue();
				interruptedOrdinaryStopRelease.countDown();
				interruptedOrdinaryWorker.join(TimeUnit.SECONDS.toMillis(5));
				requireThat(interruptedOrdinaryWorker.isAlive(), "interruptedOrdinaryWorkerReaped").isFalse();
				requireThat(interruptedOrdinaryFailure.get() instanceof BuildAdmission.Failure,
					"interruptedOrdinaryFailureReportedAfterReaping").isTrue();
				requireThat(interruptedOrdinaryStatusRestored.get(),
					"interruptedOrdinaryStatusRestoredAfterReaping").isTrue();
				requireThat(live.contains(rootPid) || live.contains(childPid),
					"interruptedOrdinaryTreeReapedWithoutRss").isFalse();
				requireThat(live.contains(childPid), "guardedChildRequestStoppedRetainedChild").isFalse();
				requireThat(live.contains(unrelatedPid), "guardedChildRequestPreservedUnrequestedActor").isTrue();
				JsonNode interruptedOrdinaryLedger =
					readLedger(scope, context.ledgerPath().resolve("ledger.json"));
				requireThat(interruptedOrdinaryLedger.path("reservations").size(),
					"interruptedOrdinaryReservationReleased").isEqualTo(0);
				try (java.util.stream.Stream<Path> terminalFiles = Files.list(interruptedOrdinaryEvidence))
				{
					Path interruptedReceipt = terminalFiles.filter(path ->
						path.getFileName().toString().startsWith("terminal-")).findFirst().orElseThrow();
					JsonNode interruptedValue = scope.json().readTree(interruptedReceipt.toFile());
					requireThat(interruptedValue.path("status").stringValue(), "interruptedStatus").
						isEqualTo("interrupted");
					requireThat(interruptedValue.path("exit_code").intValue(), "interruptedActualExit").
						isEqualTo(0);
				}
				malformedRss.set(false);
				BuildAdmission.Job changedSource = new BuildAdmission.Job("changed-source", "assignment", project,
					gate, root.resolve("changed-invocation"), "changed-source-manifest", List.of(), List.of());
				Path interruptedEvidence = root.resolve("interrupted-evidence");
				AtomicReference<Throwable> interruptedFailure = new AtomicReference<>();
				AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);
				childSampleBarrier.set(new CountDownLatch(3));
				Thread interruptedWorker = Thread.ofPlatform().start(() ->
				{
					try
					{
						admission.run(changedSource, interruptedEvidence);
					}
					catch (IOException | RuntimeException exception)
					{
						interruptedFailure.set(exception);
						interruptRestored.set(Thread.currentThread().isInterrupted());
					}
				});
				requireThat(childSampleBarrier.get().await(5, TimeUnit.SECONDS),
					"retainedChildObservedBeforeInterrupt").isTrue();
				interruptedWorker.interrupt();
				interruptedWorker.join(TimeUnit.SECONDS.toMillis(5));
				requireThat(interruptedWorker.isAlive(), "interruptedWorkerReaped").isFalse();
				if (!(interruptedFailure.get() instanceof BuildAdmission.Failure))
					throw new AssertionError("Unexpected interruption outcome: " + interruptedFailure.get(),
						interruptedFailure.get());
				requireThat(interruptedFailure.get() instanceof BuildAdmission.Failure,
					"interruptionPropagatedAfterTerminalization").isTrue();
				requireThat(interruptRestored.get(), "interruptStatusRestoredAfterCleanup").isTrue();
				requireThat(live.contains(childPid), "interruptedChildReaped").isFalse();
				JsonNode interruptedLedger = readLedger(scope, context.ledgerPath().resolve("ledger.json"));
				requireThat(interruptedLedger.path("reservations").size(), "interruptedReservationReleased").
					isEqualTo(0);
				try (java.util.stream.Stream<Path> terminalFiles = Files.list(interruptedEvidence))
				{
					requireThat(terminalFiles.anyMatch(path -> path.getFileName().toString().startsWith("terminal-")),
						"interruptedTerminalReceiptDurable").isTrue();
				}
				Path launchFailureEvidence = root.resolve("launch-failure-evidence");
				BuildAdmission.Job launchFailureJob = new BuildAdmission.Job("launch-failure", "assignment", project,
					gate, root.resolve("launch-failure-invocation"), "changed-source-manifest", List.of(), List.of());
				failNextLaunch.set(true);
				try
				{
					admission.run(launchFailureJob, launchFailureEvidence);
					throw new AssertionError("Expected synthetic wrapper launch failure");
				}
				catch (BuildAdmission.Failure expected)
				{
					requireThat(expected.kind(), "launchFailureKind").
						isEqualTo(BuildAdmission.Failure.Kind.PROCESS_START_FAILED);
				}
				JsonNode afterLaunchFailure = readLedger(scope, context.ledgerPath().resolve("ledger.json"));
				requireThat(afterLaunchFailure.path("reservations").size(), "launchFailureReservationReleased").
					isEqualTo(0);
				try (java.util.stream.Stream<Path> terminalFiles = Files.list(launchFailureEvidence))
				{
					Path terminalPath = terminalFiles.filter(path ->
						path.getFileName().toString().startsWith("terminal-")).findFirst().orElseThrow();
					JsonNode launchReceipt = scope.json().readTree(terminalPath.toFile());
					requireThat(launchReceipt.path("termination_result").stringValue(), "launchFailureRecordedAsNotStarted").
						isEqualTo("not_started");
					requireThat(launchReceipt.path("status").stringValue(), "launchFailureInfrastructureStatus").
						isEqualTo("infrastructure_failed");
					requireThat(launchReceipt.path("exit_code").isNull(), "launchFailureHasNoSyntheticExit").isTrue();
				}
				CountDownLatch rootWaitEntered = new CountDownLatch(1);
				rootWaitNextLaunch.set(rootWaitEntered);
				BuildAdmission.Job rootWaitJob = new BuildAdmission.Job("root-wait-interrupt", "assignment", project,
					gate, root.resolve("root-wait-invocation"), "changed-source-manifest", List.of(), List.of());
				AtomicReference<Throwable> rootWaitFailure = new AtomicReference<>();
				AtomicBoolean rootWaitInterruptRestored = new AtomicBoolean();
				Thread rootWaitWorker = Thread.ofPlatform().start(() ->
				{
					try
					{
						admission.run(rootWaitJob, root.resolve("root-wait-evidence"));
					}
					catch (IOException | RuntimeException exception)
					{
						rootWaitFailure.set(exception);
						rootWaitInterruptRestored.set(Thread.currentThread().isInterrupted());
					}
				});
				requireThat(rootWaitEntered.await(5, TimeUnit.SECONDS), "rootWaitObservedBeforeInterrupt").isTrue();
				rootWaitWorker.interrupt();
				rootWaitWorker.join(TimeUnit.SECONDS.toMillis(5));
				requireThat(rootWaitWorker.isAlive(), "rootWaitWorkerReaped").isFalse();
				requireThat(rootWaitFailure.get() instanceof BuildAdmission.Failure,
					"rootWaitInterruptionPropagatedAfterTerminalization").isTrue();
				requireThat(rootWaitInterruptRestored.get(), "rootWaitInterruptStatusRestored").isTrue();
				requireThat(live.contains(rootPid) || live.contains(childPid), "rootAndChildReapedAfterInterrupt").isFalse();
				JsonNode afterRootWait = readLedger(scope, context.ledgerPath().resolve("ledger.json"));
				requireThat(afterRootWait.path("reservations").size(), "rootWaitReservationReleased").isEqualTo(0);
				Path publicationEvidence = root.resolve("terminal-publication-evidence");
				BuildAdmission.Job publicationJob = new BuildAdmission.Job("terminal-publication-failure",
					"assignment", project, gate, root.resolve("terminal-publication-invocation"),
					"source-manifest", List.of(), List.of());
				afterFirstWaitNextLaunch.set(() ->
				{
					try (java.util.stream.Stream<Path> entries = Files.list(publicationEvidence))
					{
						for (Path entry : entries.toList())
							Files.delete(entry);
					}
					catch (IOException exception)
					{
						throw new IllegalStateException(exception);
					}
					try
					{
						Files.delete(publicationEvidence);
						Files.writeString(publicationEvidence, "controlled completion changed destination shape");
					}
					catch (IOException exception)
					{
						throw new IllegalStateException(exception);
					}
					live.remove(childPid);
				});
			IOException publicationFailure = null;
			try
			{
				admission.run(publicationJob, publicationEvidence);
			}
			catch (IOException exception)
			{
				publicationFailure = exception;
			}
			requireThat(publicationFailure, "terminalPublicationFailureReported").isNotNull();
			JsonNode publicationLedger = readLedger(scope, ledgerPath.get());
			requireThat(publicationLedger.path("reservations").size(), "publicationFailureRetainsReservation").
				isEqualTo(1);
			requireThat(publicationLedger.path("reservations").get(0).path("terminal_receipt_path").isNull(),
				"publicationFailureDoesNotRecordReplacementReceipt").isTrue();
			Path cleanupObservationEvidence = root.resolve("cleanup-observation-evidence");
			Path cleanupContextFile = root.resolve("cleanup-context.json");
			Files.writeString(cleanupContextFile, "{\"format_version\":1,\"context_id\":\"" +
				java.util.UUID.randomUUID() + "\",\"host_boot_id\":\"" + boot +
				"\",\"ledger_path\":\"cleanup-ledger\",\"authority_id\":\"test\",\"authority_actor\":\"test\"," +
				"\"authority_evidence_paths\":[\"authority.txt\"],\"cleanup_owner\":\"test\"}");
			BuildAdmission.HostContext cleanupContext = BuildAdmission.HostContext.read(scope.json(), cleanupContextFile);
			Path cleanupLedgerPath = cleanupContext.ledgerPath().resolve("ledger.json");
			BuildAdmission cleanupAdmission = BuildAdmissionSecrets.getBuildAdmissionAccess().create(scope,
				cleanupContext, observations, scope.clock(), Map.of("PATH", "/bin"), processStops);
			malformedRss.set(false);
			BuildAdmission.Job cleanupCalibrationJob = new BuildAdmission.Job("cleanup-calibration", "assignment",
				project, gate, root.resolve("cleanup-calibration-invocation"), "cleanup-source", List.of(), List.of());
			CountDownLatch cleanupCalibrationChild = new CountDownLatch(1);
			childSampleBarrier.set(cleanupCalibrationChild);
			AtomicReference<Throwable> cleanupCalibrationFailure = new AtomicReference<>();
			Thread cleanupCalibrationWorker = Thread.ofPlatform().start(() ->
			{
				try
				{
					cleanupAdmission.run(cleanupCalibrationJob, root.resolve("cleanup-calibration-evidence"));
				}
				catch (IOException | RuntimeException exception)
				{
					cleanupCalibrationFailure.set(exception);
				}
			});
			requireThat(cleanupCalibrationChild.await(5, TimeUnit.SECONDS), "cleanupCalibrationChildObserved").isTrue();
			live.remove(childPid);
			cleanupCalibrationWorker.join(TimeUnit.SECONDS.toMillis(5));
			requireThat(cleanupCalibrationWorker.isAlive(), "cleanupCalibrationWorkerReaped").isFalse();
			if (cleanupCalibrationFailure.get() != null)
				throw new AssertionError("Cleanup calibration failed", cleanupCalibrationFailure.get());
			BuildAdmission.Job cleanupObservationJob = new BuildAdmission.Job("cleanup-observation-failure",
				"assignment", project, gate, root.resolve("cleanup-observation-invocation"),
				"cleanup-source", List.of(), List.of());
			CountDownLatch cleanupObservationWait = new CountDownLatch(1);
			keepChildAliveOnDestroy.set(true);
			afterFirstWaitNextLaunch.set(() ->
			{
				failChildIdentityAlways.set(true);
				cleanupObservationWait.countDown();
			});
			AtomicReference<Throwable> cleanupObservationFailure = new AtomicReference<>();
			Thread cleanupObservationWorker = Thread.ofPlatform().start(() ->
			{
				try
				{
					cleanupAdmission.run(cleanupObservationJob, cleanupObservationEvidence);
				}
				catch (IOException | RuntimeException exception)
				{
					cleanupObservationFailure.set(exception);
				}
			});
			requireThat(cleanupObservationWait.await(5, TimeUnit.SECONDS), "cleanupFailureObservedAfterLaunch").
				isTrue();
			cleanupObservationWorker.join(TimeUnit.SECONDS.toMillis(5));
			requireThat(cleanupObservationWorker.isAlive(), "cleanupFailureWorkerFinishedReporting").isFalse();
			requireThat(cleanupObservationFailure.get() instanceof BuildAdmission.Failure,
				"cleanupObservationFailureReported").isTrue();
			JsonNode recoveryLedger = readLedger(scope, cleanupLedgerPath);
			requireThat(recoveryLedger.path("reservations").size(), "cleanupFailureRetainsReservation").
				isEqualTo(1);
			boolean recoveryOwned = false;
			for (JsonNode reservation : recoveryLedger.path("reservations"))
				if ("cleanup-observation-failure".equals(reservation.path("job_id").stringValue()))
						recoveryOwned = "recovery_required".equals(reservation.path("phase").stringValue());
			requireThat(recoveryOwned, "cleanupFailureMarkedRecoveryRequired").isTrue();
			requireThat(live.contains(childPid), "identityUncertaintyLeavesChildUnstopped").isTrue();
			requireThat(live.contains(unrelatedPid), "identityUncertaintyLeavesUnrequestedActorLive").isTrue();
			live.remove(childPid);
			failChildIdentityAlways.set(false);
			keepChildAliveOnDestroy.set(false);
			}
		}
	}

	/**
	 * Launches the real Java fixture on Linux and proves the worker samples and reaps its child JVM process tree.
	 * <p>
	 * The case starts a real fixture JVM and its grandchild and waits for the worker to sample and reap them, which
	 * takes seconds. The test keeps them because the real process tree is the boundary it checks.
	 *
	 * @throws IOException if host state or the child lifecycle cannot be observed
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void realTreeIsSampledAndReaped() throws IOException, InterruptedException
	{
		if (!Platform.current().isLinux())
			throw new SkipException("Real process-tree resource observations require Linux");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-worker-real-tree-")).toRealPath();
			Path project = Files.createDirectories(root.resolve("project"));
			Path invocation = root.resolve("invocation");
			Path evidence = root.resolve("evidence");
			Path contextFile = root.resolve("context.json");
			String boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip();
			Files.writeString(contextFile, "{\"format_version\":1,\"context_id\":\"" +
				java.util.UUID.randomUUID() + "\",\"host_boot_id\":\"" + boot +
				"\",\"ledger_path\":\"ledger\",\"authority_id\":\"test\",\"authority_actor\":\"test\"," +
				"\"authority_evidence_paths\":[\"authority.txt\"],\"cleanup_owner\":\"test\"}");
			Files.writeString(root.resolve("authority.txt"), "fixture authority");
			Path childDirectory = Files.createDirectories(root.resolve("child"));
			AtomicReference<Long> childPid = new AtomicReference<>();
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield,
				Clock.systemUTC(), builder ->
				{
					List<String> command = builder.command();
					int logIndex = command.indexOf("--log-file");
					Path mavenLog = Path.of(command.get(logIndex + 1));
					Files.createDirectories(mavenLog.getParent());
					Files.createFile(mavenLog);
					builder.command(BuildChild.fixtureCommand(childDirectory.toString()).toArray(String[]::new));
					Process process = builder.start();
					childPid.set(process.pid());
					return process;
				});
			try (JvmScope scope = new TestJvmScope(root.resolve("scope"), executables))
			{
				BuildAdmission.HostContext context = BuildAdmission.HostContext.read(scope.json(), contextFile);
				HarnessProcesses processAuthority = HarnessProcesses.system();
				// This boundary checks real process discovery, RSS sampling and reaping.
				// Host CPU admission is tested separately.
				BuildObservations observations = new BuildObservations(Path.of("/proc"),
					new IdleCpuMetrics(BuildObservations.systemMetrics()), pid ->
						processAuthority.find(pid).map(description -> description.start().map(start ->
							new ProcessIdentity(pid, start)).orElse(null)));
				BuildAdmission admission = new BuildAdmission(scope, context, observations, scope.clock(),
					Map.of("PATH", "/bin"));
				BuildAdmission.MavenGate gate = BuildAdmission.MavenGate.of(project, List.of("common/cli"),
					List.of("BuildWorkerTest"), List.of(), "real-tree", "test");
				BuildAdmission.Job job = new BuildAdmission.Job("real-tree", "assignment", project, gate,
					invocation, "source-manifest", List.of(), List.of());
				Files.createDirectories(context.ledgerPath());
				Path grandchildReady = childDirectory.resolve("grandchild.ready");
				Path ledger = context.ledgerPath().resolve("ledger.json");
				try (WatchService fixtureWatch = BuildChild.watchFor(childDirectory);
					WatchService ledgerWatch = BuildChild.watchFor(context.ledgerPath()))
				{
				AtomicReference<BuildAdmission.JobResult> result = new AtomicReference<>();
				AtomicReference<Throwable> failure = new AtomicReference<>();
				Thread worker = Thread.ofPlatform().start(() ->
				{
					try
					{
						result.set(admission.run(job, evidence));
					}
					catch (IOException | RuntimeException exception)
					{
						failure.set(exception);
					}
				});
			try
			{
				awaitFixtureReady(worker, failure, fixtureWatch, grandchildReady);
				requireThat(Files.size(grandchildReady) > 0, "grandchildStarted").isTrue();
				long grandchildPid = Long.parseLong(Files.readString(grandchildReady));
				long observedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
				boolean observed = false;
				while (!observed && System.nanoTime() < observedDeadline)
				{
					if (Files.exists(ledger))
					{
						JsonNode state = readLedger(scope, ledger);
						for (JsonNode identity : state.path("reservations").path(0).path("descendants"))
							if (identity.path("pid").longValue() == grandchildPid)
								observed = true;
					}
					if (!observed)
					{
						long remaining = observedDeadline - System.nanoTime();
						WatchKey key = ledgerWatch.poll(remaining, TimeUnit.NANOSECONDS);
						if (key != null)
							key.reset();
					}
				}
				requireThat(observed, "workerRecordedRealGrandchild").isTrue();
				Files.writeString(childDirectory.resolve("release"), "release");
				worker.join(TimeUnit.SECONDS.toMillis(20));
				requireThat(worker.isAlive(), "workerReapedRealTree").isFalse();
				if (failure.get() != null)
					throw new AssertionError("Real process-tree worker failed", failure.get());
				requireThat(result.get().passed(), "realBuildChildPassed").isTrue();
				JsonNode receipt = scope.json().readTree(evidence.resolve(result.get().terminalReceiptPath()).toFile());
				String rowsPath = receipt.path("calibration_result").path("process_rows_path").stringValue();
				List<String> rows = Files.readAllLines(evidence.resolve(rowsPath));
				requireThat(rows.size(), "peakContainsRootAndGrandchild").isGreaterThanOrEqualTo(2);
				requireThat(rows.stream().anyMatch(row -> row.contains(Long.toString(childPid.get()))),
					"rootInPeakRows").isTrue();
				requireThat(rows.stream().anyMatch(row -> row.contains(Long.toString(grandchildPid))),
					"grandchildInPeakRows").isTrue();
			}
			finally
			{
				cleanupWorker(worker, childDirectory.resolve("release"), childPid);
			}
				}
			}
		}
	}

	/**
	 * Reads one complete ledger generation through NIO, whose Windows handle permits atomic replacement while open.
	 * A parser opened directly on a {@code File} uses a stream that prevents the worker from replacing its ledger.
	 *
	 * @param scope the JSON parser owner
	 * @param ledger the ledger path
	 * @return the parsed generation
	 * @throws IOException if the ledger cannot be read
	 */
	private static JsonNode readLedger(JvmScope scope, Path ledger) throws IOException
	{
		return scope.json().readTree(Files.readAllBytes(ledger));
	}

	/**
	 * Keeps native process observations while making this fixture independent of unrelated host CPU load.
	 *
	 * @param system metric provider for native process state and processor counts
	 */
	private record IdleCpuMetrics(BuildObservations.Metrics system) implements BuildObservations.Metrics
	{
		@Override
		public double cpuLoad()
		{
			return 0.0;
		}

		@Override
		public int logicalProcessors()
		{
			return system.logicalProcessors();
		}

		@Override
		public int availableProcessors()
		{
			return system.availableProcessors();
		}

		@Override
		public Optional<BuildObservations.RawProcess> process(long pid)
		{
			return system.process(pid);
		}

		@Override
		public Set<Long> descendants(long pid)
		{
			return system.descendants(pid);
		}
	}

	/**
	 * Reports a worker's original failure when its fixture never becomes ready.
	 *
	 * @throws IOException          if fixture creation fails or the expected worker failure is reported
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Build worker failed before fixture readiness: .*")
	public void readinessReportsWorkerFailure() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("build-worker-readiness-")).toRealPath();
			IOException expected = new IOException("Controlled worker failure");
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread worker = Thread.ofPlatform().start(() -> failure.set(expected));
			worker.join();
			try (WatchService watch = BuildChild.watchFor(directory))
			{
				try
				{
					awaitFixtureReady(worker, failure, watch, directory.resolve("grandchild.ready"));
				}
				catch (IOException exception)
				{
					requireThat(exception.getCause(), "workerFailureCause").isEqualTo(expected);
					throw exception;
				}
			}
		}
	}

	/**
	 * Reports an exited worker when no fixture readiness or worker exception was published.
	 *
	 * @throws IOException          if fixture creation fails or the expected early exit is reported
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Build worker exited before fixture readiness: .*")
	public void readinessReportsWorkerExit() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("build-worker-readiness-")).toRealPath();
			Thread worker = Thread.ofPlatform().start(() ->
			{
			});
			worker.join();
			try (WatchService watch = BuildChild.watchFor(directory))
			{
				awaitFixtureReady(worker, new AtomicReference<>(), watch, directory.resolve("grandchild.ready"));
			}
		}
	}

	/**
	 * Waits up to twenty seconds for fixture readiness and reports a worker failure as soon as it is observed.
	 *
	 * @param worker  thread launching and observing the fixture process tree
	 * @param failure worker exception published before the thread exits
	 * @param watch   fixture directory watcher owned by the caller
	 * @param ready   file containing the grandchild process identifier when ready
	 * @throws IOException          if the worker fails, exits before readiness, or readiness times out
	 * @throws InterruptedException if the readiness wait is interrupted
	 */
	private static void awaitFixtureReady(Thread worker, AtomicReference<Throwable> failure, WatchService watch,
		Path ready) throws IOException, InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (!(Files.isRegularFile(ready) && Files.size(ready) > 0))
		{
			Throwable workerFailure = failure.get();
			if (workerFailure != null)
				throw new IOException("Build worker failed before fixture readiness: " + ready, workerFailure);
			if (!worker.isAlive())
				throw new IOException("Build worker exited before fixture readiness: " + ready, failure.get());
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0)
				throw new IOException("Timed out waiting for fixture readiness: " + ready + "; worker=" + worker.getState());
			WatchKey key = watch.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
			if (key != null)
			{
				key.pollEvents();
				key.reset();
			}
		}
	}

	/**
	 * Releases the fixture and reaps the worker, forcing its process tree after bounded waits if needed.
	 *
	 * @param worker worker thread
	 * @param release fixture release path
	 * @param childPid launched process identifier
	 * @throws IOException if release or worker cleanup fails
	 */
	private static void cleanupWorker(Thread worker, Path release, AtomicReference<Long> childPid) throws IOException
	{
		Files.writeString(release, "release");
		boolean restoreInterrupt = Thread.interrupted();
		try
		{
			try
			{
				worker.join(TimeUnit.SECONDS.toMillis(20));
			}
			catch (InterruptedException _)
			{
				restoreInterrupt = true;
				worker.interrupt();
			}
			if (worker.isAlive())
			{
				worker.interrupt();
				terminateChildTree(childPid.get());
				joinUninterruptibly(worker, TimeUnit.SECONDS.toMillis(20));
			}
			if (worker.isAlive())
				throw new IOException("Worker thread remained alive after fixture cleanup");
		}
		finally
		{
			if (restoreInterrupt)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Joins a worker until its bounded deadline and restores interruption after cleanup.
	 *
	 * @param worker worker to join
	 * @param timeoutMillis maximum join time
	 */
	private static void joinUninterruptibly(Thread worker, long timeoutMillis)
	{
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		boolean interrupted = false;
		while (worker.isAlive() && System.nanoTime() < deadline)
		{
			try
			{
				long remaining = deadline - System.nanoTime();
				worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
			}
			catch (InterruptedException _)
			{
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	/**
	 * Forcibly terminates the launched child and all currently retained descendants.
	 *
	 * @param pid child process identifier, or {@code null} if launch did not complete
	 */
	private static void terminateChildTree(Long pid)
	{
		if (pid == null)
			return;
		ProcessHandle.of(pid).ifPresent(process ->
		{
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		});
	}

	/**
	 * Exercises one scoped-wait boundary with an owned host, ledger, and retained-child fixture.
	 *
	 * @param scenario wait behavior to exercise
	 * @throws IOException if fixture state cannot be created
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	private void exerciseScopedWait(WaitScenario scenario) throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScopedWaitFixture fixture = new ScopedWaitFixture(cleanup))
		{
			fixture.calibrate();
			switch (scenario)
			{
				case DEFERRED_ADMISSION, ADMISSION_FAILURE -> exerciseAdmissionWait(scenario, fixture);
				case RETAINED_CHILD -> exerciseRetainedChildWait(fixture);
				case CHILD_WAIT_INTERRUPTED -> exerciseInterruptedChildWait(fixture);
				case CLEANUP_WAIT -> exerciseCleanupWait(fixture, false);
				case CLEANUP_INTERRUPTED -> exerciseCleanupWait(fixture, true);
				case CHILD_WAIT_FAILURE -> exerciseChildWaitFailure(fixture);
				case CLEANUP_WAIT_FAILURE -> exerciseCleanupWaitFailure(fixture);
			}
		}
	}

	/**
	 * Verifies admission resumes from a fresh observation or reports the scoped wait failure.
	 *
	 * @param scenario admission scenario
	 * @param fixture owned worker fixture
	 * @throws IOException if ledger state cannot be read
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	private void exerciseAdmissionWait(WaitScenario scenario, ScopedWaitFixture fixture)
		throws IOException, InterruptedException
	{
		Path evidence = fixture.root.resolve("admission-evidence");
		BuildAdmission.Job job = fixture.job("admission-wait");
		CountDownLatch admissionEntered = new CountDownLatch(1);
		CountDownLatch admissionRelease = new CountDownLatch(1);
		CountDownLatch childEntered = new CountDownLatch(1);
		CountDownLatch childRelease = new CountDownLatch(1);
		fixture.cpuLoad.set(0.95);
		Pause admissionPause;
		if (scenario == WaitScenario.ADMISSION_FAILURE)
			admissionPause = () ->
		{
			admissionEntered.countDown();
			throw new IOException("controlled admission wait failure");
		};
		else
			admissionPause = Pause.interruptible(() ->
		{
			admissionEntered.countDown();
			admissionRelease.await();
		});
		fixture.waiting.enqueue(java.time.Duration.ofMillis(100), admissionPause);
		if (scenario == WaitScenario.DEFERRED_ADMISSION)
			fixture.waiting.enqueue(java.time.Duration.ofMillis(100), Pause.interruptible(() ->
			{
				childEntered.countDown();
				childRelease.await();
			}));
		int launchesBefore = fixture.launches.get();
		WorkerCall call = fixture.start(job, evidence);
		try
		{
			call.await(admissionEntered, "admissionPauseReached");
			requireThat(fixture.launches.get(), "admissionLaunchWaits").isEqualTo(launchesBefore);
			requireThat(fixture.ledger().path("reservations").size(), "admissionHasNoReservation").isEqualTo(0);
			if (scenario == WaitScenario.ADMISSION_FAILURE)
			{
				call.join();
				BuildAdmission.Failure failure = (BuildAdmission.Failure) call.failure.get();
				requireThat(failure.kind(), "admissionFailureKind").
					isEqualTo(BuildAdmission.Failure.Kind.PROCESS_IO_FAILED);
				requireThat(failure.getCause().getMessage(), "admissionFailureCause").
					isEqualTo("controlled admission wait failure");
				requireThat(failure.path(), "admissionFailureEvidence").isEqualTo(evidence);
				requireThat(failure.jobId(), "admissionFailureJob").isEqualTo(job.jobId());
				requireThat(fixture.launches.get(), "admissionFailureNoLaunch").isEqualTo(launchesBefore);
				requireThat(fixture.ledger().path("reservations").size(), "admissionFailureNoHold").isEqualTo(0);
				return;
			}

			fixture.cpuLoad.set(0.1);
			admissionRelease.countDown();
			call.await(childEntered, "admittedWorkerWaitsForRetainedChild");
			requireThat(fixture.ledger().path("reservations").size(), "admittedWorkerHeld").isEqualTo(1);
			fixture.live.remove(ScopedWaitFixture.CHILD_PID);
			childRelease.countDown();
			call.join();
			requireThat(call.result.get().passed(), "admittedWorkerPassed").isTrue();
		}
		finally
		{
			admissionRelease.countDown();
			childRelease.countDown();
			fixture.releaseChild();
			call.stop();
		}
	}

	/**
	 * Verifies a retained child keeps its reservation until the process scope resumes observation.
	 *
	 * @param fixture owned worker fixture
	 * @throws IOException if ledger or receipt state cannot be read
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	private void exerciseRetainedChildWait(ScopedWaitFixture fixture) throws IOException, InterruptedException
	{
		CountDownLatch waitEntered = new CountDownLatch(1);
		CountDownLatch waitRelease = new CountDownLatch(1);
		fixture.waiting.enqueue(java.time.Duration.ofMillis(100), Pause.interruptible(() ->
		{
			waitEntered.countDown();
			waitRelease.await();
		}));
		Path evidence = fixture.root.resolve("retained-child-evidence");
		WorkerCall call = fixture.start(fixture.job("retained-child"), evidence);
		try
		{
			call.await(waitEntered, "retainedChildWaitEntered");
			requireThat(fixture.ledger().path("reservations").size(), "retainedChildReservationHeld").isEqualTo(1);
			requireThat(fixture.terminalReceipt(evidence), "retainedChildReceiptDeferred").isNull();
			fixture.live.remove(ScopedWaitFixture.CHILD_PID);
			waitRelease.countDown();
			call.join();
			requireThat(call.result.get().passed(), "retainedChildBuildPassed").isTrue();
			requireThat(fixture.ledger().path("reservations").size(), "retainedChildReservationReleased").
				isEqualTo(0);
		}
		finally
		{
			waitRelease.countDown();
			fixture.releaseChild();
			call.stop();
		}
	}

	/**
	 * Verifies an interrupted descendant wait completes tree and drain cleanup before its terminal receipt.
	 *
	 * @param fixture owned worker fixture
	 * @throws IOException if ledger or receipt state cannot be read
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	private void exerciseInterruptedChildWait(ScopedWaitFixture fixture) throws IOException, InterruptedException
	{
		CountDownLatch waitEntered = new CountDownLatch(1);
		CountDownLatch destroyEntered = new CountDownLatch(1);
		CountDownLatch destroyRelease = new CountDownLatch(1);
		CountDownLatch cleanupWaitEntered = new CountDownLatch(1);
		CountDownLatch cleanupWaitRelease = new CountDownLatch(1);
		CountDownLatch drainEntered = new CountDownLatch(1);
		CountDownLatch drainRelease = new CountDownLatch(1);
		fixture.destroyEntered.set(destroyEntered);
		fixture.destroyRelease.set(destroyRelease);
		fixture.drainEntered.set(drainEntered);
		fixture.drainRelease.set(drainRelease);
		fixture.keepChildAliveOnDestroy.set(true);
		fixture.waiting.enqueue(java.time.Duration.ofMillis(100), Pause.interruptible(() ->
		{
			waitEntered.countDown();
			new CountDownLatch(1).await();
		}));
		fixture.waiting.enqueue(java.time.Duration.ofMillis(50), Pause.interruptible(() ->
		{
			cleanupWaitEntered.countDown();
			cleanupWaitRelease.await();
		}));
		Path evidence = fixture.root.resolve("child-interrupted-evidence");
		WorkerCall call = fixture.start(fixture.job("child-wait-interrupted"), evidence);
		try
		{
			call.await(waitEntered, "childWaitInterruptPauseEntered");
			call.await(drainEntered, "childWaitOutputDrainStarted");
			call.thread.interrupt();
			call.await(destroyEntered, "childWaitInterruptDestroyEntered");
			assertOwnedBeforeRelease(fixture, evidence, call, "childWaitInterruptOwnsTree");
			destroyRelease.countDown();
			call.await(cleanupWaitEntered, "childWaitInterruptCleanupPauseEntered");
			assertOwnedBeforeRelease(fixture, evidence, call, "childWaitInterruptOwnsDescendant");
			fixture.live.remove(ScopedWaitFixture.CHILD_PID);
			cleanupWaitRelease.countDown();
			requireThat(!call.finished(), "childWaitInterruptWaitsForDrain").isTrue();
			drainRelease.countDown();
			call.join();
			requireThat(call.failure.get() instanceof BuildAdmission.Failure, "childWaitInterruptReported").isTrue();
			requireThat(call.interruptedAtReport.get(), "childWaitInterruptRestored").isTrue();
			JsonNode receipt = fixture.terminalReceipt(evidence);
			requireThat(receipt.path("status").stringValue(), "childWaitInterruptStatus").isEqualTo("interrupted");
			requireThat(receipt.path("exit_code").intValue(), "childWaitObservedExit").isEqualTo(0);
			requireThat(receipt.path("termination_result").stringValue(), "childWaitTreeReaped").isEqualTo("reaped");
		}
		finally
		{
			destroyRelease.countDown();
			cleanupWaitRelease.countDown();
			drainRelease.countDown();
			fixture.releaseChild();
			call.stop();
		}
	}

	/**
	 * Verifies cleanup waiting, including repeated interruptions, preserves ownership until child termination.
	 *
	 * @param fixture owned worker fixture
	 * @param interruptCleanup whether to interrupt each controlled cleanup pause
	 * @throws IOException if ledger or receipt state cannot be read
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	private void exerciseCleanupWait(ScopedWaitFixture fixture, boolean interruptCleanup)
		throws IOException, InterruptedException
	{
		CountDownLatch destroyEntered = new CountDownLatch(1);
		CountDownLatch destroyRelease = new CountDownLatch(1);
		CountDownLatch firstCleanupWaitEntered = new CountDownLatch(1);
		CountDownLatch secondCleanupWaitEntered = new CountDownLatch(1);
		CountDownLatch cleanupWaitRelease = new CountDownLatch(1);
		CountDownLatch drainEntered = new CountDownLatch(1);
		CountDownLatch drainRelease = new CountDownLatch(1);
		fixture.destroyEntered.set(destroyEntered);
		fixture.destroyRelease.set(destroyRelease);
		fixture.drainEntered.set(drainEntered);
		fixture.drainRelease.set(drainRelease);
		fixture.keepChildAliveOnDestroy.set(true);
		fixture.afterFirstWait.set(() -> fixture.failNextChildIdentity.set(true));
		CountDownLatch[] cleanupWaitEntered = {firstCleanupWaitEntered, secondCleanupWaitEntered};
		int pauses = 1;
		if (interruptCleanup)
			pauses = 2;
		for (int index = 0; index < pauses; index = index + 1)
		{
			int pauseIndex = index;
			fixture.waiting.enqueue(java.time.Duration.ofMillis(50), Pause.interruptible(() ->
			{
				cleanupWaitEntered[pauseIndex].countDown();
				cleanupWaitRelease.await();
			}));
		}
		Path evidence = fixture.root.resolve("cleanup-wait-evidence");
		WorkerCall call = fixture.start(fixture.job("cleanup-wait"), evidence);
		try
		{
			call.await(drainEntered, "cleanupWaitDrainStarted");
			call.await(destroyEntered, "cleanupWaitDestroyEntered");
			destroyRelease.countDown();
			for (int index = 0; index < pauses; index = index + 1)
			{
				call.await(cleanupWaitEntered[index], "cleanupWaitPauseEntered");
				if (interruptCleanup)
				{
					call.thread.interrupt();
					if (index == 0)
						requireThat(!call.finished(), "firstCleanupInterruptKeepsWorker").isTrue();
				}
			}
			assertOwnedBeforeRelease(fixture, evidence, call, "cleanupWaitKeepsOwnership");
			fixture.live.remove(ScopedWaitFixture.CHILD_PID);
			cleanupWaitRelease.countDown();
			requireThat(!call.finished(), "cleanupWaitStillJoinsDrain").isTrue();
			drainRelease.countDown();
			call.join();
			requireThat(call.failure.get() instanceof BuildAdmission.Failure, "cleanupWaitOriginalFailure").isTrue();
			requireThat(terminalStatus(fixture.terminalReceipt(evidence)), "cleanupWaitInfrastructureStatus").
				isEqualTo("infrastructure_failed");
			if (interruptCleanup)
				requireThat(call.interruptedAtReport.get(), "cleanupWaitInterruptRestored").isTrue();
		}
		finally
		{
			destroyRelease.countDown();
			cleanupWaitRelease.countDown();
			drainRelease.countDown();
			fixture.releaseChild();
			call.stop();
		}
	}

	/**
	 * Verifies ordinary descendant-pause failure still waits for child and drain completion before release.
	 *
	 * @param fixture owned worker fixture
	 * @throws IOException if ledger or receipt state cannot be read
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	private void exerciseChildWaitFailure(ScopedWaitFixture fixture) throws IOException, InterruptedException
	{
		CountDownLatch childWaitEntered = new CountDownLatch(1);
		CountDownLatch cleanupWaitEntered = new CountDownLatch(1);
		CountDownLatch cleanupWaitRelease = new CountDownLatch(1);
		CountDownLatch destroyEntered = new CountDownLatch(1);
		CountDownLatch destroyRelease = new CountDownLatch(1);
		CountDownLatch drainEntered = new CountDownLatch(1);
		CountDownLatch drainRelease = new CountDownLatch(1);
		fixture.destroyEntered.set(destroyEntered);
		fixture.destroyRelease.set(destroyRelease);
		fixture.drainEntered.set(drainEntered);
		fixture.drainRelease.set(drainRelease);
		fixture.keepChildAliveOnDestroy.set(true);
		fixture.waiting.enqueue(java.time.Duration.ofMillis(100), () ->
		{
			childWaitEntered.countDown();
			throw new IOException("controlled descendant wait failure");
		});
		fixture.waiting.enqueue(java.time.Duration.ofMillis(50), Pause.interruptible(() ->
		{
			cleanupWaitEntered.countDown();
			cleanupWaitRelease.await();
		}));
		Path evidence = fixture.root.resolve("child-failure-evidence");
		WorkerCall call = fixture.start(fixture.job("child-wait-failure"), evidence);
		try
		{
			call.await(drainEntered, "childFailureDrainStarted");
			call.await(childWaitEntered, "childFailurePauseEntered");
			call.await(destroyEntered, "childFailureDestroyEntered");
			destroyRelease.countDown();
			call.await(cleanupWaitEntered, "childFailureCleanupPauseEntered");
			assertOwnedBeforeRelease(fixture, evidence, call, "childFailureWaitsForTree");
			fixture.live.remove(ScopedWaitFixture.CHILD_PID);
			cleanupWaitRelease.countDown();
			requireThat(!call.finished(), "childFailureWaitsForOutputDrain").isTrue();
			requireThat(fixture.terminalReceipt(evidence), "childFailureNoReceiptBeforeDrain").isNull();
			drainRelease.countDown();
			call.join();
			requireThat(call.failure.get() instanceof BuildAdmission.Failure, "childFailureReported").isTrue();
			JsonNode receipt = fixture.terminalReceipt(evidence);
			requireThat(terminalStatus(receipt), "childFailureInfrastructureStatus").isEqualTo("infrastructure_failed");
			requireThat(receipt.path("termination_result").stringValue(), "childFailureTreeReaped").isEqualTo("reaped");
			requireThat(fixture.ledger().path("reservations").size(), "childFailureReleasedAfterProof").isEqualTo(0);
		}
		finally
		{
			destroyRelease.countDown();
			cleanupWaitRelease.countDown();
			drainRelease.countDown();
			fixture.releaseChild();
			call.stop();
		}
	}

	/**
	 * Verifies cleanup-pause IO failure preserves an unresolved recovery reservation.
	 *
	 * @param fixture owned worker fixture
	 * @throws IOException if ledger or receipt state cannot be read
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	private void exerciseCleanupWaitFailure(ScopedWaitFixture fixture) throws IOException, InterruptedException
	{
		CountDownLatch cleanupWaitEntered = new CountDownLatch(1);
		CountDownLatch destroyEntered = new CountDownLatch(1);
		CountDownLatch destroyRelease = new CountDownLatch(1);
		fixture.destroyEntered.set(destroyEntered);
		fixture.destroyRelease.set(destroyRelease);
		fixture.keepChildAliveOnDestroy.set(true);
		fixture.afterFirstWait.set(() -> fixture.failNextChildIdentity.set(true));
		fixture.waiting.enqueue(java.time.Duration.ofMillis(50), () ->
		{
			cleanupWaitEntered.countDown();
			throw new IOException("controlled cleanup wait failure");
		});
		Path evidence = fixture.root.resolve("cleanup-wait-failure-evidence");
		WorkerCall call = fixture.start(fixture.job("cleanup-wait-failure"), evidence);
		try
		{
			call.await(destroyEntered, "cleanupFailureDestroyEntered");
			destroyRelease.countDown();
			call.await(cleanupWaitEntered, "cleanupFailurePauseEntered");
			call.join();
			requireThat(call.failure.get() instanceof BuildAdmission.Failure, "cleanupFailureReported").isTrue();
			JsonNode ledger = fixture.ledger();
			requireThat(ledger.path("reservations").size(), "cleanupFailureReservationHeld").isEqualTo(1);
			requireThat(ledger.path("reservations").get(0).path("phase").stringValue(), "cleanupRecoveryRequired").
				isEqualTo("recovery_required");
			requireThat(fixture.terminalReceipt(evidence), "cleanupFailureNoTerminalReceipt").isNull();
			requireThat(fixture.live.contains(ScopedWaitFixture.CHILD_PID), "cleanupFailureChildUnproven").isTrue();
		}
		finally
		{
			destroyRelease.countDown();
			fixture.releaseChild();
			call.stop();
		}
	}

	/**
	 * Confirms the worker has not published or released ownership before its controlled resources finish.
	 *
	 * @param fixture owned worker fixture
	 * @param evidence worker evidence directory
	 * @param call active worker call
	 * @param name assertion name
	 * @throws IOException if ledger or receipt state cannot be read
	 */
	private static void assertOwnedBeforeRelease(ScopedWaitFixture fixture, Path evidence, WorkerCall call, String name)
		throws IOException
	{
		requireThat(call.finished(), name + "WorkerStillActive").isFalse();
		requireThat(fixture.ledger().path("reservations").size(), name + "ReservationHeld").isEqualTo(1);
		requireThat(fixture.terminalReceipt(evidence), name + "ReceiptDeferred").isNull();
	}

	/**
	 * Returns the status from a terminal receipt.
	 *
	 * @param receipt terminal worker receipt
	 * @return reported status
	 */
	private static String terminalStatus(JsonNode receipt)
	{
		return receipt.path("status").stringValue();
	}

	/**
	 * The scoped-wait scenarios covered by the controlled build fixture.
	 */
	private enum WaitScenario
	{
		DEFERRED_ADMISSION,
		ADMISSION_FAILURE,
		RETAINED_CHILD,
		CHILD_WAIT_INTERRUPTED,
		CLEANUP_WAIT,
		CLEANUP_INTERRUPTED,
		CHILD_WAIT_FAILURE,
		CLEANUP_WAIT_FAILURE
	}

	/**
	 * The next test-controlled pause and its required scope interval.
	 *
	 * @param interval required scoped wait interval
	 * @param pause controlled pause behavior
	 */
	private record WaitPlan(java.time.Duration interval, Pause pause)
	{
	}

	/**
	 * Supplies queued latches and failures through the process waiting contract.
	 */
	private static final class ScriptedWaiting implements Waiting
	{
		private final Queue<WaitPlan> plans = new ConcurrentLinkedQueue<>();

		/**
		 * Queues the pause consumed by the next scoped wait.
		 *
		 * @param interval expected wait interval
		 * @param pause controlled pause
		 */
		private void enqueue(java.time.Duration interval, Pause pause)
		{
			plans.add(new WaitPlan(interval, pause));
		}

		@Override
		public Pause every(java.time.Duration interval)
		{
			WaitPlan plan = plans.poll();
			if (plan == null)
				return () ->
				{
				};
			requireThat(interval, "scopeWaitInterval").isEqualTo(plan.interval());
			return plan.pause();
		}
	}

	/**
	 * Creates one fake host, admission ledger, worker and retained child for a scoped-wait test.
	 */
	private static final class ScopedWaitFixture implements AutoCloseable
	{
		private final Path root;
		private final Path project;
		private static final long ROOT_PID = 52_001;
		private static final long CHILD_PID = 52_002;
		private static final long UNREQUESTED_PID = 52_003;
		private final Set<Long> live = ConcurrentHashMap.newKeySet();
		private final AtomicReference<RetainedChildProcess> rootProcess = new AtomicReference<>();
		private final AtomicReference<Runnable> childStopAction = new AtomicReference<>();
		private final FakeBuildProcessStops processStops;
		private final AtomicReference<Double> cpuLoad = new AtomicReference<>(0.1);
		private final AtomicReference<CountDownLatch> childSamples = new AtomicReference<>(new CountDownLatch(3));
		private final AtomicReference<Runnable> afterFirstWait = new AtomicReference<>();
		private final AtomicReference<CountDownLatch> destroyEntered = new AtomicReference<>();
		private final AtomicReference<CountDownLatch> destroyRelease = new AtomicReference<>();
		private final AtomicReference<CountDownLatch> drainEntered = new AtomicReference<>();
		private final AtomicReference<CountDownLatch> drainRelease = new AtomicReference<>();
		private final AtomicBoolean failNextChildIdentity = new AtomicBoolean();
		private final AtomicBoolean keepChildAliveOnDestroy = new AtomicBoolean();
		private final AtomicInteger launches = new AtomicInteger();
		private final ScriptedWaiting waiting = new ScriptedWaiting();
		private final ScopedWaitTestScope scope;
		private final BuildAdmission.HostContext context;
		private final BuildAdmission admission;
		private final BuildAdmission.MavenGate gate;
		private final List<Thread> workers = new java.util.concurrent.CopyOnWriteArrayList<>();

		/**
		 * Creates isolated host observations and a launcher for controlled worker processes.
		 *
		 * @param cleanup owner of the temporary fixture directory
		 * @throws IOException if fixture files cannot be created
		 */
		private ScopedWaitFixture(TemporaryDirectories cleanup) throws IOException
		{
			root = cleanup.add(Files.createTempDirectory("build-scoped-wait-")).toRealPath();
			project = Files.createDirectories(root.resolve("project"));
			Path proc = Files.createDirectories(root.resolve("proc"));
			java.util.UUID boot = java.util.UUID.randomUUID();
			Files.createDirectories(proc.resolve("sys/kernel/random"));
			Files.writeString(proc.resolve("sys/kernel/random/boot_id"), boot.toString());
			Files.writeString(proc.resolve("meminfo"), "MemAvailable: 16777216 kB\n");
			Path contextFile = root.resolve("context.json");
			Files.writeString(contextFile, "{\"format_version\":1,\"context_id\":\"" + java.util.UUID.randomUUID() +
				"\",\"host_boot_id\":\"" + boot + "\",\"ledger_path\":\"ledger\",\"authority_id\":\"test\"," +
				"\"authority_actor\":\"test\",\"authority_evidence_paths\":[\"authority.txt\"]," +
				"\"cleanup_owner\":\"test\"}");
			Files.writeString(root.resolve("authority.txt"), "fixture authority");
			live.add(UNREQUESTED_PID);
			processStops = new FakeBuildProcessStops(Map.of(ROOT_PID, () ->
			{
				RetainedChildProcess process = rootProcess.get();
				if (process != null)
					process.destroyForcibly();
			}, CHILD_PID, () ->
			{
				Runnable stop = childStopAction.get();
				if (stop != null)
					stop.run();
			}));
			BuildObservations.Metrics metrics = new BuildObservations.Metrics()
			{
				@Override
				public double cpuLoad()
				{
					return ScopedWaitFixture.this.cpuLoad.get();
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
					if (!live.contains(pid))
						return Optional.empty();
					try
					{
						Path status = Files.createDirectories(proc.resolve(Long.toString(pid))).resolve("status");
						long kilobytes = 40;
						if (pid == ROOT_PID)
							kilobytes = 20;
						Files.writeString(status, "VmRSS:\t" + kilobytes + " kB\n");
						if (pid == CHILD_PID)
							childSamples.get().countDown();
						return Optional.of(new BuildObservations.RawProcess(pid, ROOT_PID, ProcessState.LIVE));
					}
					catch (IOException exception)
					{
						throw new IllegalStateException(exception);
					}
				}

				@Override
				public Set<Long> descendants(long pid)
				{
					if (pid == ROOT_PID && live.contains(CHILD_PID))
						return Set.of(CHILD_PID);
					return Set.of();
				}
			};
			BuildObservations observations = new BuildObservations(proc, metrics, pid ->
			{
				if (pid == CHILD_PID && failNextChildIdentity.getAndSet(false))
					throw new IOException("synthetic child identity observation failure");
				if (!live.contains(pid))
					return Optional.empty();
				return Optional.of(new ProcessIdentity(pid, "worker" + pid));
			});
			live.add(ProcessHandle.current().pid());
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield,
				Clock.systemUTC(), builder ->
				{
					launches.incrementAndGet();
					live.add(ROOT_PID);
					live.add(CHILD_PID);
					List<String> command = builder.command();
					Path mavenLog = Path.of(command.get(command.indexOf("--log-file") + 1));
					Files.createDirectories(mavenLog.getParent());
					Files.createFile(mavenLog);
					CountDownLatch childDestroyEntered = destroyEntered.getAndSet(null);
					CountDownLatch childDestroyRelease = destroyRelease.getAndSet(null);
					childStopAction.set(() ->
					{
						if (childDestroyEntered != null)
						{
							childDestroyEntered.countDown();
							try
							{
								childDestroyRelease.await();
							}
							catch (InterruptedException _)
							{
								Thread.currentThread().interrupt();
							}
						}
						if (!keepChildAliveOnDestroy.get())
							live.remove(CHILD_PID);
					});
					RetainedChildProcess process = new RetainedChildProcess(ROOT_PID, CHILD_PID, live, null, null,
						afterFirstWait.getAndSet(null), drainEntered.getAndSet(null), drainRelease.getAndSet(null));
					rootProcess.set(process);
					Thread completion = process.completeAfterWaitStarts();
					workers.add(completion);
					return process;
				});
			scope = new ScopedWaitTestScope(root.resolve("scope"), waiting, executables);
			context = BuildAdmission.HostContext.read(scope.json(), contextFile);
			admission = BuildAdmissionSecrets.getBuildAdmissionAccess().create(scope, context, observations,
				scope.clock(), Map.of("PATH", "/bin"), processStops);
			gate = BuildAdmission.MavenGate.of(project, List.of("common/cli"), List.of("BuildWorkerTest"), List.of(),
				"review", "parent");
		}

		/**
		 * Establishes a valid calibration record so each scenario exercises an admitted non-calibration worker.
		 *
		 * @throws IOException if calibration fails
		 * @throws InterruptedException if fixture synchronization is interrupted
		 */
		private void calibrate() throws IOException, InterruptedException
		{
			BuildAdmission.Job job = job("wait-calibration");
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread worker = Thread.ofPlatform().start(() ->
			{
				try
				{
					admission.run(job, root.resolve("calibration-evidence"));
				}
				catch (IOException | RuntimeException exception)
				{
					failure.set(exception);
				}
			});
			workers.add(worker);
			try
			{
				requireThat(childSamples.get().await(5, TimeUnit.SECONDS), "calibrationChildSampled").isTrue();
				live.remove(CHILD_PID);
				worker.join(TimeUnit.SECONDS.toMillis(5));
				requireThat(worker.isAlive(), "calibrationWorkerFinished").isFalse();
				if (failure.get() != null)
					throw new AssertionError("Calibration setup failed", failure.get());
			}
			finally
			{
				live.remove(ROOT_PID);
				live.remove(CHILD_PID);
				if (worker.isAlive())
				{
					worker.interrupt();
					worker.join(TimeUnit.SECONDS.toMillis(5));
				}
			}
		}

		/**
		 * Creates a worker request sharing the calibrated gate family.
		 *
		 * @param jobId worker identity
		 * @return immutable worker request
		 */
		private BuildAdmission.Job job(String jobId)
		{
			return new BuildAdmission.Job(jobId, "assignment", project, gate, root.resolve(jobId + "-invocation"),
				"source-manifest", List.of(), List.of());
		}

		/**
		 * Starts an owned worker and records its outcome and final interrupt state.
		 *
		 * @param job worker request
		 * @param evidence worker evidence directory
		 * @return controlled worker call
		 */
		private WorkerCall start(BuildAdmission.Job job, Path evidence)
		{
			WorkerCall call = new WorkerCall(() -> admission.run(job, evidence));
			workers.add(call.thread);
			return call;
		}

		/**
		 * Reads the current reservation ledger.
		 *
		 * @return parsed ledger
		 * @throws IOException if the ledger cannot be read
		 */
		private JsonNode ledger() throws IOException
		{
			return readLedger(scope, context.ledgerPath().resolve("ledger.json"));
		}

		/**
		 * Reads a published terminal receipt when one exists.
		 *
		 * @param evidence worker evidence directory
		 * @return terminal receipt or null before publication
		 * @throws IOException if receipt state cannot be read
		 */
		private JsonNode terminalReceipt(Path evidence) throws IOException
		{
			if (!Files.isDirectory(evidence))
				return null;
			try (java.util.stream.Stream<Path> entries = Files.list(evidence))
			{
				Path receipt = null;
				for (Path entry : entries.toList())
					if (entry.getFileName().toString().startsWith("terminal-"))
					{
						receipt = entry;
						break;
					}
				if (receipt == null)
					return null;
				return scope.json().readTree(receipt.toFile());
			}
		}

		/**
		 * Releases every fixture barrier, stops and joins owned worker threads, and closes the scope.
		 */
		@Override
		public void close()
		{
			live.remove(CHILD_PID);
			live.remove(ROOT_PID);
			CountDownLatch destroy = destroyRelease.get();
			if (destroy != null)
				destroy.countDown();
			CountDownLatch drain = drainRelease.get();
			if (drain != null)
				drain.countDown();
			for (Thread worker : workers)
			{
				if (worker.isAlive())
					worker.interrupt();
				try
				{
					worker.join(TimeUnit.SECONDS.toMillis(5));
				}
				catch (InterruptedException _)
				{
					Thread.currentThread().interrupt();
				}
			}
			scope.close();
		}

		/**
		 * Releases synthetic child identity and any destructive wait used by the current case.
		 */
		private void releaseChild()
		{
			live.remove(CHILD_PID);
			live.remove(ROOT_PID);
			CountDownLatch release = destroyRelease.get();
			if (release != null)
				release.countDown();
			CountDownLatch drain = drainRelease.get();
			if (drain != null)
				drain.countDown();
		}
	}

	/**
	 * The running worker and its captured result or failure.
	 */
	private static final class WorkerCall
	{
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private final AtomicReference<BuildAdmission.JobResult> result = new AtomicReference<>();
		private final AtomicBoolean interruptedAtReport = new AtomicBoolean();
		private final Thread thread;

		/**
		 * Starts the supplied worker action.
		 *
		 * @param action worker action
		 */
		private WorkerCall(IoRunnable action)
		{
			thread = Thread.ofPlatform().start(() ->
			{
				try
				{
					result.set(action.run());
				}
				catch (IOException | RuntimeException exception)
				{
					failure.set(exception);
					interruptedAtReport.set(Thread.currentThread().isInterrupted());
				}
			});
		}

		/**
		 * Waits for a worker-owned event and distinguishes an earlier worker failure from a missing event.
		 *
		 * @param event event owned by the worker or its process fixture
		 * @param name assertion name
		 * @throws InterruptedException if the test thread is interrupted
		 */
		private void await(CountDownLatch event, String name) throws InterruptedException
		{
			boolean signalled = event.await(5, TimeUnit.SECONDS);
			if (!signalled && failure.get() != null)
				throw new AssertionError(name + " followed a worker failure", failure.get());
			requireThat(signalled, name).isTrue();
		}

		/**
		 * Waits for the worker to finish within the fixture's diagnostic bound.
		 *
		 * @throws InterruptedException if this test thread is interrupted
		 */
		private void join() throws InterruptedException
		{
			thread.join(TimeUnit.SECONDS.toMillis(5));
			requireThat(thread.isAlive(), "workerThreadFinished").isFalse();
		}

		/**
		 * Returns whether the worker has completed.
		 *
		 * @return true when no longer running
		 */
		private boolean finished()
		{
			return !thread.isAlive();
		}

		/**
		 * Interrupts and joins a worker during fixture cleanup.
		 */
		private void stop()
		{
			if (!thread.isAlive())
				return;
			thread.interrupt();
			try
			{
				thread.join(TimeUnit.SECONDS.toMillis(5));
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * An operation that runs a worker and may fail with IO.
	 */
	@FunctionalInterface
	private interface IoRunnable
	{
		/**
		 * Runs the worker.
		 *
		 * @return terminal result
		 * @throws IOException if the worker fails
		 */
		BuildAdmission.JobResult run() throws IOException;
	}

	/**
	 * Exposes controlled waiting through the existing protected process-scope constructor.
	 */
	private static final class ScopedWaitTestScope extends AbstractJvmScope
	{
		/**
		 * Creates a process scope with the fixture's waiting and process launcher.
		 *
		 * @param directory fixture scope directory
		 * @param waiting controlled waiting
		 * @param executables controlled executable launcher
		 */
		private ScopedWaitTestScope(Path directory, Waiting waiting, ExecutableFiles executables)
		{
			super(directory, waiting, executables);
		}
	}

	private static final class RetainedChildProcess extends Process
	{
		private final long pid;
		private final Set<Long> live;
		private final CountDownLatch rootWaitEntered;
		private final CountDownLatch rootExitObserved;
		private final Runnable afterFirstWait;
		private final CountDownLatch drainEntered;
		private final CountDownLatch drainRelease;
		private final CountDownLatch rootWaitRelease = new CountDownLatch(1);
		private final CountDownLatch waitEntered = new CountDownLatch(1);
		private final AtomicReference<Integer> exitCode = new AtomicReference<>();
		private final AtomicBoolean firstWait = new AtomicBoolean(true);

		/**
		 * Creates a fake root process whose retained child has an independent stop callback.
		 *
		 * @param pid root process identifier
		 * @param childPid retained child identifier
		 * @param live currently live synthetic identities
		 * @param rootWaitEntered optional latch notified from the timed wait
		 * @param rootExitObserved optional latch notified when the root exits
		 * @param afterFirstWait callback after the first root wait
		 * @param drainEntered optional output-drain entry latch
		 * @param drainRelease optional output-drain release latch
		 */
		private RetainedChildProcess(long pid, long childPid, Set<Long> live, CountDownLatch rootWaitEntered,
			CountDownLatch rootExitObserved, Runnable afterFirstWait, CountDownLatch drainEntered,
			CountDownLatch drainRelease)
		{
			this.pid = pid;
			this.live = live;
			this.rootWaitEntered = rootWaitEntered;
			this.rootExitObserved = rootExitObserved;
			this.afterFirstWait = afterFirstWait;
			this.drainEntered = drainEntered;
			this.drainRelease = drainRelease;
			if (!live.contains(childPid))
				throw new IllegalStateException("Fixture child is absent");
		}

		@Override
		public long pid()
		{
			return pid;
		}
		@Override
		public OutputStream getOutputStream()
		{
			return OutputStream.nullOutputStream();
		}
		@Override
		public InputStream getInputStream()
		{
			if (drainRelease != null)
				return new InputStream()
				{
					@Override
					public int read() throws IOException
					{
						drainEntered.countDown();
						try
						{
							drainRelease.await();
							return -1;
						}
						catch (InterruptedException exception)
						{
							Thread.currentThread().interrupt();
							throw new IOException("fixture output drain interrupted", exception);
						}
					}
				};
			return InputStream.nullInputStream();
		}
		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}
		/**
		 * Completes the root from fixture control after the first wait has started.
		 *
		 * @return the controller thread
		 */
		private Thread completeAfterWaitStarts()
		{
			return Thread.ofPlatform().start(() ->
			{
				try
				{
					waitEntered.await();
					completeRoot(0);
				}
				catch (InterruptedException _)
				{
					Thread.currentThread().interrupt();
				}
			});
		}

		/**
		 * Publishes root completion without changing retained-child liveness.
		 *
		 * @param status selected terminal status
		 */
		private void completeRoot(int status)
		{
			if (exitCode.compareAndSet(null, status))
			{
				live.remove(pid);
				if (rootExitObserved != null)
					rootExitObserved.countDown();
				rootWaitRelease.countDown();
				waitEntered.countDown();
			}
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			waitEntered.countDown();
			rootWaitRelease.await();
			return exitValue();
		}
		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
		{
			waitEntered.countDown();
			if (rootWaitEntered != null)
				rootWaitEntered.countDown();
			boolean completed = rootWaitRelease.await(timeout, unit);
			if (completed && firstWait.getAndSet(false) && afterFirstWait != null)
				afterFirstWait.run();
			return completed;
		}
		@Override
		public int exitValue()
		{
			Integer status = exitCode.get();
			if (status == null)
				throw new IllegalThreadStateException("Retained root is still running");
			return status;
		}
		@Override
		public boolean isAlive()
		{
			return live.contains(pid);
		}
		@Override
		public void destroy()
		{
			completeRoot(0);
		}
		@Override
		public Process destroyForcibly()
		{
			completeRoot(0);
			return this;
		}
		@Override
		public java.util.stream.Stream<ProcessHandle> descendants()
		{
			return java.util.stream.Stream.empty();
		}
	}
}
