package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.execution.CheckProgramStartFailure;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramStartFailure.Reason;
import com.holdmyspot.cat.common.optimizer.execution.StartupDiagnosis;
import com.holdmyspot.cat.common.optimizer.execution.StartupDiagnosis.Evidence;
import com.holdmyspot.cat.common.optimizer.execution.StartupHistory;
import com.holdmyspot.cat.common.optimizer.execution.StartupSample;
import com.holdmyspot.cat.common.optimizer.execution.StartupStage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies what a check program's run that ends before its source launcher is ready reports, from the evidence that the
 * run left: a cause where the evidence shows one, and otherwise the values that tell a time limit shorter than a start
 * from a slow machine.
 */
public final class StartupDiagnosisTest
{
	private static final Duration LIMIT = Duration.ofSeconds(60);
	private static final Duration ELAPSED = Duration.ofMillis(60_100);
	private static final StartupHistory.Summary NORMAL = new StartupHistory.Summary(12, Duration.ofMillis(1_500),
		Duration.ofMillis(2_500));

	/**
	 * Creates the test.
	 */
	public StartupDiagnosisTest()
	{
	}

	/**
	 * A sandbox program that exits with its own error, such as one that the kernel's refusal of user namespaces causes,
	 * is a sandbox that cannot start, and the report is the sandbox's message and exit status, not a timeout.
	 */
	@Test
	public void sandboxErrorNamesTheSandboxMessage()
	{
		// Requirement: R-L23
		String message = "bwrap: No permissions to create new namespace, likely because the kernel does not allow " +
			"non-privileged user namespaces.";
		Evidence evidence = exited(1, message + "\n", List.of(sample(0, StartupStage.SANDBOX_SETUP, 10)));

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(evidence);

		requireThat(failure.reason(), "reason").isEqualTo(Reason.SANDBOX_CANNOT_START);
		requireThat(failure.getMessage(), "message").contains("sandbox could not start").contains(message).
			contains("status 1");
		requireThat(failure.isInfrastructure(), "isInfrastructure").isFalse();
	}

	/**
	 * A mount that the sandbox cannot make is reported the same way, with the sandbox's message.
	 */
	@Test
	public void sandboxMountFailureNamesTheMount()
	{
		// Requirement: R-L23
		String message = "bwrap: Can't mkdir /proc/4242: No such file or directory";
		Evidence evidence = exited(1, message, List.of());

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(evidence);

		requireThat(failure.reason(), "reason").isEqualTo(Reason.SANDBOX_CANNOT_START);
		requireThat(failure.getMessage(), "message").contains(message);
	}

	/**
	 * A launcher that exits before it is ready is reported with its exit status and output. A runtime that {@code env}
	 * cannot execute is one of them.
	 */
	@Test
	public void launcherExitNamesStatusAndOutput()
	{
		// Requirement: R-L23
		String missing = "env: '/runtime/bin/java': No such file or directory";
		String crashed = "Error occurred during initialization of VM";

		CheckProgramStartFailure notFound = StartupDiagnosis.diagnose(exited(127, missing,
			List.of(sample(0, StartupStage.LAUNCHER_STARTING, 10))));
		CheckProgramStartFailure crash = StartupDiagnosis.diagnose(exited(1, crashed + "\n", List.of()));

		requireThat(notFound.reason(), "notFoundReason").isEqualTo(Reason.LAUNCHER_UNUSABLE);
		requireThat(notFound.getMessage(), "notFoundMessage").contains("launcher exited with status 127").
			contains(missing);
		requireThat(crash.reason(), "crashReason").isEqualTo(Reason.LAUNCHER_UNUSABLE);
		requireThat(crash.getMessage(), "crashMessage").contains("status 1").contains(crashed);
	}

	/**
	 * A launcher that exits without a word says so.
	 */
	@Test
	public void launcherExitWithoutOutputSaysSo()
	{
		// Requirement: R-L23
		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(exited(1, "", List.of()));

		requireThat(failure.reason(), "reason").isEqualTo(Reason.LAUNCHER_UNUSABLE);
		requireThat(failure.getMessage(), "message").contains("no output");
	}

	/**
	 * A sandbox program that is still setting up, with its processes unchanged and using no processor time over the
	 * last sixth of the limit, is blocked, so the sandbox cannot start; the limit's end is not the report.
	 */
	@Test
	public void stalledSandboxSetupIsASandboxFailure()
	{
		// Requirement: R-L23
		Evidence evidence = limitEnded(samples(StartupStage.SANDBOX_SETUP, 59_750, 0), Optional.of(NORMAL));

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(evidence);

		requireThat(failure.reason(), "reason").isEqualTo(Reason.SANDBOX_CANNOT_START);
		requireThat(failure.getMessage(), "message").contains("sandbox could not start").contains("sandbox setup").
			contains("bwrap").contains("last 10 s");
	}

	/**
	 * A launcher whose processes stay the same and use no processor time over the last sixth of the limit is stalled,
	 * and the report names the stage that it stalled in and when that stage was first seen.
	 */
	@Test
	public void stalledLauncherNamesItsStage()
	{
		// Requirement: R-L23
		List<StartupSample> samples = new ArrayList<>(List.of(sample(0, StartupStage.SANDBOX_SETUP, 10)));
		samples.addAll(samplesFrom(500, StartupStage.LAUNCHER_STARTING, 59_750, 1_200, 0));

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(limitEnded(samples, Optional.of(NORMAL)));

		requireThat(failure.reason(), "reason").isEqualTo(Reason.LAUNCHER_STALLED);
		requireThat(failure.getMessage(), "message").contains("launcher is stalled").contains("launcher starting").
			contains("first seen at 0.5 s").contains("0 s of processor time in the last 10 s");
		requireThat(failure.isInfrastructure(), "isInfrastructure").isFalse();
	}

	/**
	 * A launcher that keeps using processor time is slow, not hung: the report is an infrastructure failure that gives
	 * the limit, the time taken, the last stage, when it was first seen, the processor time of the last window and how
	 * long earlier starts took, so that a reader can tell a short limit from a slow machine.
	 */
	@Test
	public void busyLauncherReportsValuesThatTellWhy()
	{
		// Requirement: R-L23
		List<StartupSample> samples = new ArrayList<>(List.of(sample(0, StartupStage.SANDBOX_SETUP, 10)));
		samples.addAll(samplesFrom(500, StartupStage.LAUNCHER_STARTING, 59_750, 0, 250));

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(limitEnded(samples, Optional.of(NORMAL)));

		requireThat(failure.reason(), "reason").isEqualTo(Reason.NOT_READY);
		requireThat(failure.isInfrastructure(), "isInfrastructure").isTrue();
		requireThat(failure.getMessage(), "message").contains("time limit of 60 s").contains("60.1 s").
			contains("launcher starting").contains("first seen at 0.5 s").
			contains("2.5 s of processor time in the last 10 s").contains("1.5 s at the median").
			contains("2.5 s at most").contains("12 runs");
	}

	/**
	 * Without an earlier run that reached ready, the report says that the normal time is not known.
	 */
	@Test
	public void noEarlierRunMeansNormalTimeUnknown()
	{
		// Requirement: R-L23
		Evidence evidence = limitEnded(samples(StartupStage.LAUNCHER_STARTING, 59_750, 250), Optional.empty());

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(evidence);

		requireThat(failure.reason(), "reason").isEqualTo(Reason.NOT_READY);
		requireThat(failure.getMessage(), "message").contains("normal start time is not known");
	}

	/**
	 * A limit shorter than the longest earlier start is said to be shorter, which tells a limit that is too short from a
	 * machine that is slower than usual; a limit that is longer says nothing of the kind.
	 */
	@Test
	public void limitShorterThanAnEarlierStartIsSaid()
	{
		// Requirement: R-L23
		List<StartupSample> samples = samples(StartupStage.LAUNCHER_STARTING, 1_750, 500);
		Duration shortLimit = Duration.ofSeconds(2);

		CheckProgramStartFailure shorter = StartupDiagnosis.diagnose(new Evidence(shortLimit,
			Duration.ofMillis(2_010), true, OptionalInt.empty(), "", samples, Optional.of(NORMAL), "bwrap"));
		CheckProgramStartFailure longer = StartupDiagnosis.diagnose(limitEnded(
			samples(StartupStage.LAUNCHER_STARTING, 59_750, 250), Optional.of(NORMAL)));

		requireThat(shorter.getMessage(), "shorterMessage").contains("shorter than the longest");
		requireThat(longer.getMessage(), "longerMessage").doesNotContain("shorter than");
	}

	/**
	 * A stage that CAT could not observe is an infrastructure failure that says so.
	 */
	@Test
	public void unobservedStageIsInfrastructure()
	{
		// Requirement: R-L23
		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(limitEnded(
			samples(StartupStage.NOT_OBSERVED, 59_750, 0), Optional.of(NORMAL)));

		requireThat(failure.reason(), "reason").isEqualTo(Reason.NOT_READY);
		requireThat(failure.getMessage(), "message").contains("not observed").contains("time limit of 60 s");
	}

	/**
	 * A tree that changed stage inside the window made progress, so it has not stalled.
	 */
	@Test
	public void stageChangeInsideTheWindowIsNotAStall()
	{
		// Requirement: R-L23
		List<StartupSample> samples = new ArrayList<>(samples(StartupStage.SANDBOX_SETUP, 55_000, 0));
		samples.addAll(samplesFrom(55_250, StartupStage.LAUNCHER_STARTING, 59_750, 0, 0));

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(limitEnded(samples, Optional.of(NORMAL)));

		requireThat(failure.reason(), "reason").isEqualTo(Reason.NOT_READY);
	}

	/**
	 * A tree whose processes changed inside the window made progress, so it has not stalled.
	 */
	@Test
	public void newProcessInsideTheWindowIsNotAStall()
	{
		// Requirement: R-L23
		List<StartupSample> samples = new ArrayList<>(samples(StartupStage.LAUNCHER_STARTING, 55_000, 0));
		for (long at = 55_250; at <= 59_750; at += 250)
			samples.add(new StartupSample(Duration.ofMillis(at), StartupStage.LAUNCHER_STARTING, Set.of(10L, 11L, 12L),
				Optional.of(Duration.ZERO)));

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(limitEnded(samples, Optional.of(NORMAL)));

		requireThat(failure.reason(), "reason").isEqualTo(Reason.NOT_READY);
	}

	/**
	 * A run that was not looked at for a whole window cannot be called stalled.
	 */
	@Test
	public void tooFewLooksAreNotAStall()
	{
		// Requirement: R-L23
		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(limitEnded(
			List.of(sample(59_750, StartupStage.LAUNCHER_STARTING, 0)), Optional.of(NORMAL)));

		requireThat(failure.reason(), "reason").isEqualTo(Reason.NOT_READY);
		requireThat(failure.getMessage(), "message").contains("could not be observed");
	}

	/**
	 * Processor time that the host does not show leaves nothing to call a stall, and the report says that CAT cannot
	 * tell.
	 */
	@Test
	public void unknownProcessorTimeIsNotAStall()
	{
		// Requirement: R-L23
		List<StartupSample> samples = new ArrayList<>();
		for (long at = 0; at <= 59_750; at += 250)
			samples.add(new StartupSample(Duration.ofMillis(at), StartupStage.LAUNCHER_STARTING, Set.of(10L, 11L),
				Optional.empty()));

		CheckProgramStartFailure failure = StartupDiagnosis.diagnose(limitEnded(samples, Optional.of(NORMAL)));

		requireThat(failure.reason(), "reason").isEqualTo(Reason.NOT_READY);
		requireThat(failure.getMessage(), "message").contains("could not be observed");
	}

	/**
	 * Creates the evidence of a run whose sandbox process exited.
	 *
	 * @param status  the exit status
	 * @param output  the output
	 * @param samples the looks at the process tree
	 * @return the evidence
	 */
	private static Evidence exited(int status, String output, List<StartupSample> samples)
	{
		return new Evidence(LIMIT, Duration.ofMillis(120), false, OptionalInt.of(status), output, samples,
			Optional.of(NORMAL), "bwrap");
	}

	/**
	 * Creates the evidence of a run that the time limit ended.
	 *
	 * @param samples the looks at the process tree
	 * @param normal  what earlier runs took to become ready
	 * @return the evidence
	 */
	private static Evidence limitEnded(List<StartupSample> samples, Optional<StartupHistory.Summary> normal)
	{
		return new Evidence(LIMIT, ELAPSED, true, OptionalInt.empty(), "", samples, normal, "bwrap");
	}

	/**
	 * Looks at a tree of two processes every quarter second from the start of the run.
	 *
	 * @param stage            the stage of every look
	 * @param untilMillis      the time of the last look
	 * @param cpuMillisPerSecond the processor time that the tree uses per second
	 * @return the looks
	 */
	private static List<StartupSample> samples(StartupStage stage, long untilMillis, long cpuMillisPerSecond)
	{
		return samplesFrom(0, stage, untilMillis, 0, cpuMillisPerSecond);
	}

	/**
	 * Looks at a tree of two processes every quarter second.
	 *
	 * @param fromMillis         the time of the first look
	 * @param stage              the stage of every look
	 * @param untilMillis        the time of the last look
	 * @param baseCpuMillis      the processor time that the tree had used at the first look
	 * @param cpuMillisPerSecond the processor time that the tree uses per second from then on
	 * @return the looks
	 */
	private static List<StartupSample> samplesFrom(long fromMillis, StartupStage stage, long untilMillis,
		long baseCpuMillis, long cpuMillisPerSecond)
	{
		List<StartupSample> samples = new ArrayList<>();
		for (long at = fromMillis; at <= untilMillis; at += 250)
			samples.add(sample(at, stage, baseCpuMillis + (at - fromMillis) * cpuMillisPerSecond / 1_000));
		return samples;
	}

	/**
	 * Looks at a tree of two processes once.
	 *
	 * @param atMillis  the time of the look
	 * @param stage     the stage
	 * @param cpuMillis the processor time that the tree has used
	 * @return the look
	 */
	private static StartupSample sample(long atMillis, StartupStage stage, long cpuMillis)
	{
		return new StartupSample(Duration.ofMillis(atMillis), stage, Set.of(10L, 11L),
			Optional.of(Duration.ofMillis(cpuMillis)));
	}
}
