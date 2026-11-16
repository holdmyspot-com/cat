package com.holdmyspot.cat.common.build.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.build.BuildObservations;
import com.holdmyspot.cat.common.build.BuildAdmission.JobResult;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.AbstractJvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.build.BuildObservations.ProcessState;
import com.holdmyspot.cat.common.test.ProcessCompletionContract;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests focused Maven gate policy.
 */
public final class BuildAdmissionTest
{
	/**
	 * Creates the test.
	 */
	public BuildAdmissionTest()
	{
	}

	/**
	 * Verifies a reviewed gate produces the complete quiet Maven verify command with its selected modules and tests.
	 *
	 * @throws IOException if the gate definitions cannot be read
	 */
	@Test
	public void commandUsesTheReviewedGate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("reviewed-gate-"));
			BuildAdmission.MavenGate gate = BuildAdmission.MavenGate.of(project, List.of("common/cli"),
				List.of("BuildAdmissionTest"), List.of(), "review-1", "reviewer");
			List<String> command = BuildAdmission.command(gate);
			requireThat(command, "command").isEqualTo(List.of("client/mvnw", "-q", "-f", "client/pom.xml", "-pl",
				"common/cli", "-am", "-Dtest=BuildAdmissionTest", "-Dsurefire.failIfNoSpecifiedTests=false", "verify"));
		}
	}

	/**
	 * Applies strict memory, CPU, worker-count and overflow boundaries.
	 */
	@Test
	public void strictResourceThresholds()
	{
		long gib = 1024L * 1024 * 1024;
		requireThat(BuildAdmission.resourcesPermit(9 * gib, 0, 0, 0.949, 0, 4), "oneGiBCushion").isTrue();
		requireThat(BuildAdmission.resourcesPermit(8 * gib, 0, 0, 0.1, 0, 4), "exactMemoryThreshold").isFalse();
		requireThat(BuildAdmission.resourcesPermit(12 * gib, 2 * gib, 2 * gib, 0.1, 0, 4),
			"reservationMemoryThreshold").isFalse();
		requireThat(BuildAdmission.resourcesPermit(12 * gib, 0, 0, 0.95, 0, 4), "exactCpuThreshold").isFalse();
		requireThat(BuildAdmission.resourcesPermit(12 * gib, 0, 0, 0.1, 4, 4), "exactCpuCount").isFalse();
		requireThat(BuildAdmission.resourcesPermit(0, Long.MAX_VALUE, Long.MAX_VALUE, 0.1, 0, 4),
			"subtractionOverflow").isFalse();
	}

	/**
	 * Verifies source, output, Git and symlink overlaps reject before shared ledger state is created.
	 *
	 * @throws IOException if fixture setup or the expected path rejection fails
	 */
	@Test
	public void rejectUnsafeInvocationPaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); TestJvmScope scope = new TestJvmScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-admission-paths-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path contextFile = root.resolve("context.json");
			UUID boot = UUID.randomUUID();
			Files.writeString(contextFile, "{\"format_version\":1,\"context_id\":\"" +
				UUID.randomUUID() + "\",\"host_boot_id\":\"" + boot +
				"\",\"ledger_path\":\"ledger\",\"authority_id\":\"test\",\"authority_actor\":\"test\"," +
				"\"authority_evidence_paths\":[\"authority.txt\"],\"cleanup_owner\":\"test\"}");
			Files.writeString(root.resolve("authority.txt"), "test");
			BuildAdmission.HostContext context = BuildAdmission.HostContext.read(scope.json(), contextFile);
			BuildObservations.Metrics metrics = new BuildObservations.Metrics()
			{
				@Override
				public double cpuLoad()
				{
					throw new AssertionError("Invalid paths must be rejected first");
				}
				@Override
				public int logicalProcessors()
				{
					throw new AssertionError("Host metrics must not be read");
				}
				@Override
				public int availableProcessors()
				{
					throw new AssertionError("Host metrics must not be read");
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
			Path proc = Files.createDirectory(root.resolve("proc"));
			BuildObservations observations = new BuildObservations(proc, metrics, _ -> Optional.empty());
			BuildAdmission admission = new BuildAdmission(scope, context, observations, scope.clock(),
				Map.of("PATH", "/bin"));
			List<BuildAdmission.Job> invalid = List.of(
				job(project, project, List.of(".cat/sessions"), List.of()),
				job(project, project.resolve("src/generated"), List.of(".cat/sessions"), List.of()),
				job(project, project.resolve("target/cache"), List.of("target"), List.of("target")),
				job(project, project.resolve("build"), List.of(), List.of("build/generated")),
				job(project, root, List.of(), List.of()),
				job(project, project.resolve(".git/objects"), List.of(".git"), List.of()));
			Path link = project.resolve(".cat/sessions-link");
			Files.createDirectories(project.resolve(".cat/sessions"));
			Files.createSymbolicLink(link, project.resolve(".cat/sessions"));
			invalid = new ArrayList<>(invalid);
			invalid.add(job(project, link.resolve("worker"), List.of(".cat/sessions"), List.of()));
			for (BuildAdmission.Job request : invalid)
			{
				IOException failure = null;
				try
				{
					admission.run(request, root.resolve("evidence"));
				}
				catch (IOException exception)
				{
					failure = exception;
				}
				requireThat(failure, "pathFailure").isNotNull();
			}
			requireThat(Files.exists(context.ledgerPath()), "noLedgerOnInvalidPaths").isFalse();
		}
	}

	/**
	 * Verifies the complete batch rejects null entries and duplicate job IDs before creating state or launching a worker.
	 *
	 * @throws IOException if fixture setup fails
	 */
	@Test
	public void batchRejectsDuplicateBeforeState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			BuildAdmission.Job first = fixture.job("duplicate", fixture.project, fixture.gate, "source",
				fixture.root.resolve("first-invocation"));
			BuildAdmission.Job second = fixture.job("duplicate", fixture.project,
				fixture.gate, "source", fixture.root.resolve("second-invocation"));
			Throwable duplicateFailure = null;
			try
			{
				fixture.admission.run(List.of(first, second), fixture.evidence);
			}
			catch (IOException | RuntimeException exception)
			{
				duplicateFailure = exception;
			}
			requireThat(duplicateFailure, "duplicateJobFailure").isNotNull();
			requireThat(fixture.launchAttempts.get(), "duplicateLaunchAttempts").isEqualTo(0);
			requireThat(Files.exists(fixture.evidence), "duplicateEvidenceAbsent").isFalse();
			requireThat(Files.exists(fixture.context.ledgerPath()), "duplicateLedgerAbsent").isFalse();

			List<BuildAdmission.Job> withNull = new ArrayList<>();
			withNull.add(first);
			withNull.add(null);
			Throwable nullFailure = null;
			try
			{
				fixture.admission.run(withNull, fixture.evidence);
			}
			catch (IOException | RuntimeException exception)
			{
				nullFailure = exception;
			}
			requireThat(nullFailure, "nullJobFailure").isNotNull();
			requireThat(fixture.launchAttempts.get(), "nullJobLaunchAttempts").isEqualTo(0);
			requireThat(Files.exists(fixture.evidence), "nullJobEvidenceAbsent").isFalse();
		}
	}

	/**
	 * Verifies pairwise project isolation rejects a later job before earlier jobs create ledger or evidence state.
	 *
	 * @throws IOException if fixture setup fails
	 */
	@Test
	public void batchRejectsIsolationBeforeState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			Path otherProject = Files.createDirectories(fixture.project.resolve("nested-project"));
			BuildAdmission.Job first = fixture.job("first", fixture.project, fixture.gate, "source-a",
				fixture.root.resolve("first-invocation"));
			BuildAdmission.MavenGate otherGate = fixture.gate(otherProject, "BuildWorkerTest");
			BuildAdmission.Job later = fixture.job("later", otherProject, otherGate, "source-b",
				fixture.root.resolve("later-invocation"));
			IOException failure = null;
			try
			{
				fixture.admission.run(List.of(first, later), fixture.evidence);
			}
			catch (IOException exception)
			{
				failure = exception;
			}
			requireThat(failure, "pairwiseIsolationFailure").isNotNull();
			requireThat(fixture.launchAttempts.get(), "pairwiseIsolationLaunchAttempts").isEqualTo(0);
			requireThat(Files.exists(fixture.evidence), "pairwiseIsolationEvidenceAbsent").isFalse();
			requireThat(Files.exists(fixture.context.ledgerPath()), "pairwiseIsolationLedgerAbsent").isFalse();
		}
	}

	/**
	 * Verifies two eligible workers launch and remain live before either controlled process is released.
	 *
	 * @throws IOException if fixture setup or calibration fails
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void batchOverlapsAdmittedWorkers() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			fixture.seedCalibration();
			fixture.enqueue(LaunchPlan.blocked(), LaunchPlan.blocked());
			BuildAdmission.Job first = fixture.job("first", fixture.project, fixture.gate, "source",
				fixture.root.resolve("first-invocation"));
			BuildAdmission.Job second = fixture.job("second", fixture.project, fixture.gate, "source",
				fixture.root.resolve("second-invocation"));
			ControllerCall call = fixture.start(() -> fixture.admission.run(List.of(first, second), fixture.evidence));
			List<Long> processes = fixture.awaitProcesses(2);
			try
			{
				requireThat(processes.size(), "overlappingWorkerCount").isEqualTo(2);
				boolean firstObserved = fixture.registeredProcessesByPid.get(processes.get(0)).waitEntered.await(5,
					TimeUnit.SECONDS);
				requireThat(firstObserved, "firstWorkerObservedBeforeRelease").isTrue();
				boolean secondObserved = fixture.registeredProcessesByPid.get(processes.get(1)).waitEntered.await(5,
					TimeUnit.SECONDS);
				requireThat(secondObserved, "secondWorkerObservedBeforeRelease").isTrue();
				requireThat(fixture.isAlive(processes.get(0)), "firstWorkerLiveBeforeRelease").isTrue();
				requireThat(fixture.isAlive(processes.get(1)), "secondWorkerLiveBeforeRelease").isTrue();
				requireThat(fixture.maximumLiveWorkers.get(), "maximumConcurrentWorkers").isGreaterThanOrEqualTo(2);
			}
			finally
			{
				fixture.releaseProcesses();
			}
			fixture.join(call);
			requireThat(call.failure.get(), "overlapFailure").isNull();
			BuildAdmission.BatchResult result = (BuildAdmission.BatchResult) call.result.get();
			requireThat(result.results().size(), "overlapResults").isEqualTo(2);
		}
	}

	/**
	 * Verifies an earlier deferred calibration does not prevent a later calibrated job from launching.
	 *
	 * @throws IOException if fixture setup or calibration fails
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void batchSkipsDeferredCalibration() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			fixture.seedCalibration();
			fixture.enqueue(LaunchPlan.blocked(), LaunchPlan.blocked(), LaunchPlan.complete());
			BuildAdmission.Job activeJob = fixture.job("active", fixture.project, fixture.gate, "source",
				fixture.root.resolve("active-invocation"));
			ControllerCall active = fixture.start(() -> fixture.admission.run(activeJob, fixture.evidence));
			List<Long> activeProcesses = fixture.awaitProcesses(1);
			BuildAdmission.MavenGate otherGate = fixture.gate(fixture.project, "BuildWorkerTest");
			BuildAdmission.Job deferred = fixture.job("deferred", fixture.project, otherGate, "new-source",
				fixture.root.resolve("deferred-invocation"));
			BuildAdmission.Job eligible = fixture.job("eligible", fixture.project, fixture.gate, "source",
				fixture.root.resolve("eligible-invocation"));
			ControllerCall batch = fixture.start(() -> fixture.admission.run(List.of(deferred, eligible),
				fixture.evidence));
			List<Long> eligibleProcesses = fixture.awaitProcesses(1);
			try
			{
				requireThat(eligibleProcesses.size(), "laterEligibleLaunchCount").isEqualTo(1);
				boolean activeObserved = fixture.registeredProcessesByPid.get(activeProcesses.getFirst()).waitEntered.await(5,
					TimeUnit.SECONDS);
				requireThat(activeObserved, "activeWorkerObservedBeforeRelease").isTrue();
				boolean eligibleObserved = fixture.registeredProcessesByPid.
					get(eligibleProcesses.getFirst()).waitEntered.await(5, TimeUnit.SECONDS);
				requireThat(eligibleObserved, "eligibleWorkerObservedBeforeRelease").isTrue();
				requireThat(fixture.isAlive(activeProcesses.getFirst()), "activeWorkerStillLive").isTrue();
				requireThat(fixture.isAlive(eligibleProcesses.getFirst()), "laterEligibleWorkerLive").isTrue();
			}
			finally
			{
				fixture.releaseProcesses();
			}
			fixture.join(active);
			fixture.join(batch);
			requireThat(batch.failure.get(), "deferredBatchFailure").isNull();
			BuildAdmission.BatchResult result = (BuildAdmission.BatchResult) batch.result.get();
			requireThat(result.results().stream().map(JobResult::jobId).toList(), "deferredInputOrder").
				isEqualTo(List.of("deferred", "eligible"));
		}
	}

	/**
	 * Verifies ordinary Maven failure retains its exit receipt, does not stop later work, and results remain immutable
	 * and input ordered.
	 *
	 * @throws IOException if fixture setup or calibration fails
	 */
	@Test
	public void batchPreservesInputOrderAndFailures() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			fixture.seedCalibration();
			fixture.enqueue(LaunchPlan.completeWithExit(7), LaunchPlan.complete());
			BuildAdmission.Job first = fixture.job("first-fails", fixture.project, fixture.gate, "source",
				fixture.root.resolve("first-invocation"));
			BuildAdmission.Job second = fixture.job("second-passes", fixture.project, fixture.gate, "source",
				fixture.root.resolve("second-invocation"));
			BuildAdmission.BatchResult result = fixture.admission.run(List.of(first, second), fixture.evidence);
			requireThat(result.results().stream().map(JobResult::jobId).toList(), "inputOrder").
				isEqualTo(List.of("first-fails", "second-passes"));
			requireThat(result.results().stream().map(JobResult::status).toList(), "ordinaryFailureContinues").
				isEqualTo(List.of("failed", "passed"));
			JobResult failed = result.results().getFirst();
			requireThat(failed.exitCode(), "failedJobExitCode").isEqualTo(7);
			requireThat(Files.isRegularFile(fixture.context.ledgerPath().resolve("ledger.json")),
				"failedBatchLedgerExists").isTrue();
			JsonNode receipt = fixture.scope.json().readTree(fixture.evidence.resolve(failed.terminalReceiptPath()).toFile());
			requireThat(receipt.path("exit_code").intValue(), "failedBatchTerminalReceiptExitCode").isEqualTo(7);
			requireThat(result.passed(), "batchFailureStatus").isFalse();
			UnsupportedOperationException immutableFailure = null;
			try
			{
				result.results().add(result.results().getFirst());
			}
			catch (UnsupportedOperationException exception)
			{
				immutableFailure = exception;
			}
			requireThat(immutableFailure, "immutableBatchResults").isNotNull();
			requireThat(fixture.launchAttempts.get(), "bothOrdinaryJobsLaunched").isEqualTo(3);
		}
	}

	/**
	 * Verifies an infrastructure failure stops admission and joins every worker already launched.
	 *
	 * @throws IOException if fixture setup or calibration fails
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void batchJoinsOnInfrastructureFailure() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			fixture.seedCalibration();
			fixture.enqueue(LaunchPlan.blocked(), LaunchPlan.blocked(), LaunchPlan.failToStart());
			List<BuildAdmission.Job> jobs = List.of(
				fixture.job("first", fixture.project, fixture.gate, "source", fixture.root.resolve("first-invocation")),
				fixture.job("second", fixture.project, fixture.gate, "source", fixture.root.resolve("second-invocation")),
				fixture.job("third", fixture.project, fixture.gate, "source", fixture.root.resolve("third-invocation")));
			ControllerCall call = fixture.start(() -> fixture.admission.run(jobs, fixture.evidence));
			List<Long> processes = fixture.awaitProcesses(2);
			try
			{
				IOException failure = fixture.awaitCall(call);
				requireThat(failure, "infrastructureFailure").isNotNull();
				requireThat(call.failure.get().getMessage(), "primaryFailureMessage").contains("Could not start Maven worker");
				requireThat(fixture.launchAttempts.get(), "admittedOnlyThroughFailure").isEqualTo(4);
				List<String> suppressedJobs = new ArrayList<>();
				for (Throwable suppressed : failure.getSuppressed())
					if (suppressed instanceof BuildAdmission.Failure workerFailure)
						suppressedJobs.add(workerFailure.jobId());
				requireThat(suppressedJobs, "suppressedFailuresFollowInputOrder").isEqualTo(List.of("first", "second"));
			}
			finally
			{
				fixture.releaseProcesses();
			}
			fixture.join(call);
			requireThat(processes.stream().noneMatch(fixture::isAlive), "ownedWorkersWereJoined").isTrue();
		}
	}

	/**
	 * Verifies caller interruption stops admission, reaps launched workers and restores the caller's interrupt flag.
	 *
	 * @throws IOException if fixture setup or calibration fails
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void batchInterruptionReapsAllWorkers() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			fixture.seedCalibration();
			fixture.availableProcessors.set(1);
			fixture.enqueue(LaunchPlan.blocked(), LaunchPlan.blocked());
			List<BuildAdmission.Job> jobs = List.of(
				fixture.job("first", fixture.project, fixture.gate, "source", fixture.root.resolve("first-invocation")),
				fixture.job("second", fixture.project, fixture.gate, "source", fixture.root.resolve("second-invocation")));
			ControllerCall call = fixture.start(() -> fixture.admission.run(jobs, fixture.evidence));
			List<Long> processes = fixture.awaitProcesses(1);
			call.thread.interrupt();
			fixture.join(call);
			requireThat(call.failure.get(), "interruptionFailure").isNotNull();
			requireThat(call.interrupted.get(), "interruptRestored").isTrue();
			requireThat(fixture.launchAttempts.get(), "interruptionStopsAdmission").isEqualTo(2);
			requireThat(fixture.isAlive(processes.getFirst()), "interruptedWorkerReaped").isFalse();
		}
	}

	/**
	 * Verifies separate controllers sharing one ledger never exceed its host CPU reservation limit.
	 *
	 * @throws IOException if fixture setup or calibration fails
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void batchSharesReservations() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			fixture.seedCalibration();
			fixture.availableProcessors.set(1);
			fixture.enqueue(LaunchPlan.blocked(), LaunchPlan.complete());
			BuildAdmission.Job first = fixture.job("first-controller", fixture.project, fixture.gate, "source",
				fixture.root.resolve("first-invocation"));
			BuildAdmission.Job second = fixture.job("second-controller", fixture.project, fixture.gate, "source",
				fixture.root.resolve("second-invocation"));
			ControllerCall firstCall = fixture.start(() -> fixture.admission.run(first, fixture.evidence));
			List<Long> firstProcesses = fixture.awaitProcesses(1);
			BuildAdmission secondAdmission = fixture.admission();
			ControllerCall secondCall = fixture.start(() -> secondAdmission.run(second, fixture.evidence));
			fixture.awaitHostSample();
			try
			{
				boolean firstObserved = fixture.registeredProcessesByPid.get(firstProcesses.getFirst()).waitEntered.await(5,
					TimeUnit.SECONDS);
				requireThat(firstObserved, "firstControllerObservedBeforeRelease").isTrue();
				requireThat(fixture.launchAttempts.get(), "sharedLedgerPreventsSecondLaunch").isEqualTo(2);
				requireThat(fixture.maximumLiveWorkers.get(), "sharedLedgerWorkerLimit").isEqualTo(1);
			}
			finally
			{
				fixture.releaseProcesses();
			}
			fixture.join(firstCall);
			fixture.join(secondCall);
			requireThat(firstCall.failure.get(), "firstControllerResult").isNull();
			requireThat(secondCall.failure.get(), "secondControllerResult").isNull();
			requireThat(fixture.maximumLiveWorkers.get(), "sharedLedgerMaximum").isEqualTo(1);
		}
	}

	/**
	 * Verifies changed source identity recalibrates exclusively before another source uses the same family.
	 *
	 * @throws IOException if fixture setup or initial calibration fails
	 * @throws InterruptedException if fixture synchronization is interrupted
	 */
	@Test
	public void batchRecalibratesChangedSource() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); BatchFixture fixture = new BatchFixture(cleanup))
		{
			fixture.seedCalibration();
			fixture.enqueue(LaunchPlan.blocked(), LaunchPlan.complete());
			BuildAdmission.Job changed = fixture.job("changed-source", fixture.project, fixture.gate, "changed-source",
				fixture.root.resolve("changed-invocation"));
			BuildAdmission.Job oldSource = fixture.job("old-source", fixture.project, fixture.gate, "source",
				fixture.root.resolve("old-invocation"));
			ControllerCall call = fixture.start(() -> fixture.admission.run(List.of(changed, oldSource), fixture.evidence));
			List<Long> processes = fixture.awaitProcesses(1);
			try
			{
				boolean observed = fixture.registeredProcessesByPid.get(processes.getFirst()).waitEntered.await(5,
					TimeUnit.SECONDS);
				requireThat(observed, "recalibrationObservedBeforeRelease").isTrue();
				requireThat(fixture.launchAttempts.get(), "recalibrationExclusive").isEqualTo(2);
				requireThat(fixture.isAlive(processes.getFirst()), "calibrationStillLive").isTrue();
			}
			finally
			{
				fixture.releaseProcesses();
			}
			fixture.awaitProcesses(1);
			fixture.join(call);
			requireThat(call.failure.get(), "recalibrationFailure").isNull();
			BuildAdmission.BatchResult result = (BuildAdmission.BatchResult) call.result.get();
			requireThat(result.results().stream().map(JobResult::jobId).toList(), "recalibrationInputOrder").
				isEqualTo(List.of("changed-source", "old-source"));
			requireThat(fixture.maximumLiveWorkers.get(), "recalibrationMaximumWorkers").isEqualTo(1);
		}
	}

	/**
	 * Creates an immutable build request for path-boundary checks.
	 *
	 * @param project Maven project root
	 * @param invocation invocation-owned root
	 * @param exclusions reviewed project exclusions
	 * @param outputs reviewed build output roots
	 * @return validated request
	 * @throws IOException if gate inputs cannot be resolved
	 */
	private static BuildAdmission.Job job(Path project, Path invocation, List<String> exclusions,
		List<String> outputs) throws IOException
	{
		BuildAdmission.MavenGate gate = BuildAdmission.MavenGate.of(project, List.of("common/cli"),
			List.of("BuildAdmissionTest"), List.of(), "review", "parent");
		return new BuildAdmission.Job("worker", "assignment", project, gate, invocation, "source-manifest",
			exclusions, outputs);
	}

	/**
	 * The batch process wait observes an explicit completion event instead of supplying one.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 * @throws InterruptedException if the waiter is interrupted
	 */
	@Test
	public void batchProcessWaitDoesNotCompleteRoot() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessCompletionContract.waitDoesNotCompleteRunningProcess(() ->
				new BatchFixture(cleanup).completionFixture());
		}
	}

	/**
	 * The batch process publishes its selected exit status and host liveness together.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 * @throws InterruptedException if process completion is interrupted
	 */
	@Test
	public void batchProcessCompletionPublishesStatus() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessCompletionContract.explicitCompletionPublishesStatus(() ->
				new BatchFixture(cleanup).completionFixture());
		}
	}

	/**
	 * Forcibly stopping the batch process completes only its root process state.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 * @throws InterruptedException if process completion is interrupted
	 */
	@Test
	public void batchProcessForceStopCompletesRoot() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessCompletionContract.forceStopCompletesOnlyRoot(() ->
				new BatchFixture(cleanup).completionFixture());
		}
	}

	/**
	 * Owns isolated controller state and controlled process events for batch behavior tests.
	 */
	private static final class BatchFixture implements AutoCloseable
	{
		private final Path root;
		private final Path project;
		private final Path evidence;
		private final Path proc;
		private final Path contextFile;
		private final BuildAdmission.HostContext context;
		private final BatchScope scope;
		private final BuildObservations observations;
		private final BuildAdmission admission;
		private final BuildAdmission.MavenGate gate;
		private final AtomicInteger availableProcessors = new AtomicInteger(4);
		private final AtomicInteger launchAttempts = new AtomicInteger();
		private final AtomicInteger activeWorkers = new AtomicInteger();
		private final AtomicInteger maximumLiveWorkers = new AtomicInteger();
		private final AtomicLong nextPid = new AtomicLong(80_000);
		private final Map<Long, BatchProcess> processesByPid = new ConcurrentHashMap<>();
		private final Map<Long, BatchProcess> registeredProcessesByPid = new ConcurrentHashMap<>();
		private final Map<Long, Path> processStatusPaths = new ConcurrentHashMap<>();
		private final BlockingQueue<LaunchPlan> launchPlans = new LinkedBlockingQueue<>();
		private final BlockingQueue<Long> launchedProcesses = new LinkedBlockingQueue<>();
		private final Semaphore hostSamples = new Semaphore(0);
		private final List<ControllerCall> controllers = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final List<Thread> processControllers = new java.util.concurrent.CopyOnWriteArrayList<>();

		/**
		 * Creates isolated host observations, a shared ledger and controllable worker launches.
		 *
		 * @param cleanup owner for all fixture paths
		 * @throws IOException if the fixture files cannot be created
		 */
		private BatchFixture(TemporaryDirectories cleanup) throws IOException
		{
			root = cleanup.add(Files.createTempDirectory("build-admission-batch-"));
			project = Files.createDirectories(root.resolve("project"));
			evidence = root.resolve("evidence");
			proc = Files.createDirectories(root.resolve("proc"));
			UUID boot = UUID.randomUUID();
			Files.createDirectories(proc.resolve("sys/kernel/random"));
			Files.writeString(proc.resolve("sys/kernel/random/boot_id"), boot.toString());
			Files.writeString(proc.resolve("meminfo"), "MemAvailable: 67108864 kB\n");
			contextFile = root.resolve("context.json");
			String contextJson = "{\"format_version\":1,\"context_id\":\"" + UUID.randomUUID() +
				"\",\"host_boot_id\":\"" + boot + "\",\"ledger_path\":\"ledger\",\"authority_id\":" +
				"\"test\",\"authority_actor\":\"test\",\"authority_evidence_paths\":[" +
				"\"authority.txt\"],\"cleanup_owner\":\"test\"}";
			Files.writeString(contextFile, contextJson);
			Files.writeString(root.resolve("authority.txt"), "reviewed fixture authority");
			BuildObservations.Metrics metrics = new BuildObservations.Metrics()
			{
				@Override
				public double cpuLoad()
				{
					hostSamples.release();
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
					return BatchFixture.this.availableProcessors.get();
				}

				@Override
				public Optional<BuildObservations.RawProcess> process(long pid)
				{
					if (!registeredProcessesByPid.containsKey(pid))
						return Optional.empty();
					ProcessState state;
					if (processesByPid.containsKey(pid))
						state = ProcessState.LIVE;
					else
						state = ProcessState.EXITED;
					if (state == ProcessState.EXITED)
						return Optional.of(new BuildObservations.RawProcess(pid, ProcessHandle.current().pid(), state));
					try
					{
						Path status = processStatusPaths.computeIfAbsent(pid, _ -> proc.resolve(Long.toString(pid)).
							resolve("status"));
						Files.createDirectories(status.getParent());
						Files.writeString(status, "VmRSS:\t262144 kB\n");
						return Optional.of(new BuildObservations.RawProcess(pid, ProcessHandle.current().pid(),
							state));
					}
					catch (IOException exception)
					{
						throw new IllegalStateException(exception);
					}
				}

				@Override
				public Set<Long> descendants(long pid)
				{
					return Set.of();
				}
			};
			observations = new BuildObservations(proc, metrics, pid ->
			{
				if (pid == ProcessHandle.current().pid())
					return Optional.of(new ProcessIdentity(pid, "batchcontroller"));
				BatchProcess process = registeredProcessesByPid.get(pid);
				if (process == null || !process.isAlive())
					return Optional.empty();
				ProcessIdentity identity = new ProcessIdentity(pid, "batchworker" + pid);
				return Optional.of(identity);
			});
			ExecutableFiles executableFiles = ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield,
				Clock.systemUTC(), builder ->
				{
					launchAttempts.incrementAndGet();
					LaunchPlan plan = launchPlans.poll();
					if (plan == null)
						plan = LaunchPlan.complete();
					if (plan.startFailure())
						throw new IOException("controlled worker start failure");
					List<String> command = builder.command();
					Path mavenLog = Path.of(command.get(command.indexOf("--log-file") + 1));
					Files.createDirectories(mavenLog.getParent());
					Files.createFile(mavenLog);
					long pid = nextPid.incrementAndGet();
					BatchProcess process = new BatchProcess(pid, plan, this);
					processesByPid.put(pid, process);
					registeredProcessesByPid.put(pid, process);
					launchedProcesses.add(pid);
					if (plan.automatic())
						processControllers.add(process.completeAfterWaitStarts());
					return process;
				});
			scope = new BatchScope(root.resolve("scope"), executableFiles);
			context = BuildAdmission.HostContext.read(scope.json(), contextFile);
			admission = admission();
			gate = gate(project, "BuildAdmissionTest");
		}

		/**
		 * Creates another controller backed by the same context and observations.
		 *
		 * @return controller sharing the fixture ledger
		 */
		private BuildAdmission admission()
		{
			return new BuildAdmission(scope, context, observations, scope.clock(), Map.of("PATH", "/bin"));
		}

		/**
		 * Creates a reviewed gate for one fixture project and test selector.
		 *
		 * @param project project root
		 * @param testName selected test
		 * @return validated gate
		 * @throws IOException if gate inputs cannot be resolved
		 */
		private BuildAdmission.MavenGate gate(Path project, String testName) throws IOException
		{
			return BuildAdmission.MavenGate.of(project, List.of("common/cli"), List.of(testName), List.of(),
				"batch-review", "test");
		}

		/**
		 * Creates a job with a fixture-owned invocation path.
		 *
		 * @param jobId stable job identity
		 * @param project project root
		 * @param gate reviewed Maven gate
		 * @param source source manifest identity
		 * @param invocation isolated invocation path
		 * @return validated build job
		 */
		private BuildAdmission.Job job(String jobId, Path project, BuildAdmission.MavenGate gate, String source,
			Path invocation)
		{
			return new BuildAdmission.Job(jobId, "assignment-" + jobId, project, gate, invocation, source, List.of(),
				List.of());
		}

		/**
		 * Queues process outcomes in launch order.
		 *
		 * @param plans controlled launch plans
		 */
		private void enqueue(LaunchPlan... plans)
		{
			launchPlans.addAll(List.of(plans));
		}

		/**
		 * Establishes the supported controller calibration for the fixture gate.
		 *
		 * @throws IOException if calibration cannot complete
		 */
		private void seedCalibration() throws IOException
		{
			BuildAdmission.Job calibration = job("calibration", project, gate, "source", root.resolve("calibration-run"));
			BuildAdmission.JobResult result = admission.run(calibration, evidence);
			requireThat(result.passed(), "fixtureCalibrationPassed").isTrue();
			launchedProcesses.clear();
			hostSamples.drainPermits();
		}

		/**
		 * Starts a controller operation on an owned thread and records its terminal outcome.
		 *
		 * @param operation controller operation
		 * @return owner record for the controller thread
		 */
		private ControllerCall start(BatchOperation operation)
		{
			ControllerCall call = new ControllerCall();
			Thread controller = Thread.ofPlatform().start(() ->
			{
				try
				{
					call.result.set(operation.run());
				}
				catch (IOException | RuntimeException exception)
				{
					call.failure.set(exception);
				}
				finally
				{
					call.interrupted.set(Thread.currentThread().isInterrupted());
					call.done.countDown();
				}
			});
			call.thread = controller;
			controllers.add(call);
			return call;
		}

		/**
		 * Collects up to the requested number of launched fake worker processes.
		 *
		 * @param count number of launches to observe
		 * @return processes launched before the bounded fixture guard
		 * @throws InterruptedException if the fixture wait is interrupted
		 */
		private List<Long> awaitProcesses(int count) throws InterruptedException
		{
			List<Long> processes = new ArrayList<>();
			for (int index = 0; index < count; index = index + 1)
			{
				Long pid = launchedProcesses.poll(5, TimeUnit.SECONDS);
				if (pid == null)
					break;
				processes.add(pid);
			}
			return processes;
		}

		/**
		 * Waits for a controller terminal event and returns an I/O failure if one was recorded.
		 *
		 * @param call controller thread owner
		 * @return recorded I/O failure, or null when the call succeeded
		 * @throws InterruptedException if the fixture wait is interrupted
		 */
		private IOException awaitCall(ControllerCall call) throws InterruptedException
		{
			boolean finished = call.done.await(5, TimeUnit.SECONDS);
			requireThat(finished, "controllerReachedTerminal").isTrue();
			Throwable failure = call.failure.get();
			if (failure instanceof IOException ioFailure)
				return ioFailure;
			if (failure != null)
				throw new AssertionError("Unexpected controller failure", failure);
			return null;
		}

		/**
		 * Waits for another controller to read shared host measurements.
		 *
		 * @throws InterruptedException if the fixture wait is interrupted
		 */
		private void awaitHostSample() throws InterruptedException
		{
			boolean observed = hostSamples.tryAcquire(1, 5, TimeUnit.SECONDS);
			requireThat(observed, "secondControllerObservedSharedHost").isTrue();
		}

		/**
		 * Joins a controller thread, releasing its fake processes only after the failure guard expires.
		 *
		 * @param call controller thread owner
		 * @throws InterruptedException if the fixture wait is interrupted
		 */
		private void join(ControllerCall call) throws InterruptedException
		{
			boolean finished = call.done.await(5, TimeUnit.SECONDS);
			if (!finished)
			{
				releaseProcesses();
				call.thread.interrupt();
				finished = call.done.await(5, TimeUnit.SECONDS);
			}
			requireThat(finished, "controllerJoined").isTrue();
		}

		/**
		 * Releases every process owned by the fixture.
		 */
		private void releaseProcesses()
		{
			processesByPid.keySet().forEach(pid -> processesByPid.get(pid).release());
		}

		/**
		 * Checks whether a controlled worker process remains live.
		 *
		 * @param pid process identifier
		 * @return whether the process remains live
		 */
		private boolean isAlive(long pid)
		{
			return processesByPid.containsKey(pid);
		}

		/**
		 * Creates a standalone blocked process fixture for lifecycle checks.
		 *
		 * @return owned process fixture
		 */
		private ProcessCompletionContract.Fixture completionFixture()
		{
			long pid = nextPid.incrementAndGet();
			BatchProcess process = new BatchProcess(pid, LaunchPlan.blocked(), this);
			processesByPid.put(pid, process);
			launchedProcesses.add(pid);
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
					process.complete(status);
				}

				@Override
				public boolean observerAlive()
				{
					return isAlive(pid);
				}

				@Override
				public boolean otherActorAlive()
				{
					return false;
				}

				@Override
				public void close() throws IOException
				{
					BatchFixture owner = BatchFixture.this;
					try (owner; process)
					{
						process.destroyForcibly();
					}
				}
			};
		}

		@Override
		public void close()
		{
			releaseProcesses();
			boolean interrupted = Thread.interrupted();
			for (Thread controller : processControllers)
			{
				try
				{
					controller.join(5_000);
				}
				catch (InterruptedException _)
				{
					interrupted = true;
					controller.interrupt();
				}
			}
			for (ControllerCall call : controllers)
			{
				boolean finished = false;
				while (!finished)
				{
					try
					{
						call.thread.join(5_000);
						finished = !call.thread.isAlive();
						if (!finished)
						call.thread.interrupt();
					}
					catch (InterruptedException _)
					{
						interrupted = true;
						call.thread.interrupt();
					}
				}
			}
			scope.close();
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Supplies yielding waits and controlled process launch to admission tests.
	 */
	private static final class BatchScope extends AbstractJvmScope
	{
		/**
		 * Creates a scope that yields between retries and uses controlled worker processes.
		 *
		 * @param temporaryDirectory isolated scope directory
		 * @param executableFiles controlled process launcher
		 */
		private BatchScope(Path temporaryDirectory, ExecutableFiles executableFiles)
		{
			super(temporaryDirectory, _ -> Thread::yield, executableFiles);
		}
	}

	/**
	 * Models one build process whose exit is controlled by a fixture event.
	 */
	private static final class BatchProcess extends Process
	{
		private final long pid;
		private final LaunchPlan plan;
		private final BatchFixture fixture;
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicReference<Integer> exitCode = new AtomicReference<>();
		private final AtomicInteger activeWorkers;
		private final CountDownLatch waitEntered = new CountDownLatch(1);

		/**
		 * Creates a process whose release event controls its exit and worker liveness.
		 *
		 * @param pid controlled process identifier
		 * @param plan process outcome and release mode
		 * @param fixture owner of the process observations
		 */
		private BatchProcess(long pid, LaunchPlan plan, BatchFixture fixture)
		{
			this.pid = pid;
			this.plan = plan;
			this.fixture = fixture;
			this.activeWorkers = fixture.activeWorkers;
			int current = activeWorkers.incrementAndGet();
			fixture.maximumLiveWorkers.accumulateAndGet(current, Math::max);
		}

		/**
		 * Releases the selected automatic result from fixture control after a wait begins.
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
					complete(plan.exitCode());
				}
				catch (InterruptedException _)
				{
					Thread.currentThread().interrupt();
				}
			});
		}

		/**
		 * Makes the process exit on its next wait.
		 */
		private void release()
		{
			complete(plan.exitCode());
		}

		/**
		 * Publishes process exit once and updates the fixture's liveness view.
		 *
		 * @param status process exit status
		 */
		private void complete(int status)
		{
			synchronized (this)
			{
				if (exitCode.get() != null)
					return;
				fixture.activeWorkers.decrementAndGet();
				fixture.processesByPid.remove(pid, this);
				exitCode.set(status);
				release.countDown();
			}
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
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			waitEntered.countDown();
			release.await();
			return exitValue();
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
		{
			waitEntered.countDown();
			return release.await(timeout, unit);
		}

		@Override
		public int exitValue()
		{
			Integer status = exitCode.get();
			if (status == null)
				throw new IllegalThreadStateException("Controlled worker is still running");
			return status;
		}

		@Override
		public boolean isAlive()
		{
			return exitCode.get() == null;
		}

		@Override
		public void destroy()
		{
			complete(143);
		}

		@Override
		public Process destroyForcibly()
		{
			complete(143);
			return this;
		}
 	}

	/**
	 * Describes a controlled worker process's completion and launch result.
	 *
	 * @param automatic whether the process completes on its first wait
	 * @param exitCode controlled Maven exit code
	 * @param startFailure whether the launch boundary throws before a process is created
	 */
	private record LaunchPlan(boolean automatic, int exitCode, boolean startFailure)
	{
		/**
		 * Creates a worker that exits successfully when first observed.
		 *
		 * @return successful automatic process plan
		 */
		private static LaunchPlan complete()
		{
			return new LaunchPlan(true, 0, false);
		}

		/**
		 * Creates an automatic worker with the supplied Maven exit code.
		 *
		 * @param exitCode controlled Maven exit code
		 * @return automatic process plan
		 */
		private static LaunchPlan completeWithExit(int exitCode)
		{
			return new LaunchPlan(true, exitCode, false);
		}

		/**
		 * Creates a worker that remains live until the fixture releases it.
		 *
		 * @return event-controlled process plan
		 */
		private static LaunchPlan blocked()
		{
			return new LaunchPlan(false, 0, false);
		}

		/**
		 * Creates a launch attempt that fails before returning a process.
		 *
		 * @return failed launch plan
		 */
		private static LaunchPlan failToStart()
		{
			return new LaunchPlan(false, 0, true);
		}
	}

	@FunctionalInterface
	private interface BatchOperation
	{
		/**
		 * Runs one controller call from a fixture-owned thread.
		 *
		 * @return the controller result
		 * @throws IOException if controller execution fails
		 */
		Object run() throws IOException;
	}

	/**
	 * Retains the result and terminal state of one test controller thread.
	 */
	private static final class ControllerCall
	{
		private final CountDownLatch done = new CountDownLatch(1);
		private final AtomicReference<Object> result = new AtomicReference<>();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private final AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
		private Thread thread;
	}
}
