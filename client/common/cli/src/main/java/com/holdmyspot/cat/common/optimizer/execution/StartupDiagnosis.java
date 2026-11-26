package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.execution.CheckProgramStartFailure.Reason;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Decides what a check program's run that ended before its source launcher was ready reports.
 * <p>
 * A run that its sandbox program ended reports a cause: the sandbox program's own message, or the launcher's exit
 * status and output. A run that its time limit ended reports a cause only for a <b>stall</b>: over the last
 * {@value #WINDOW_DIVISOR}th of the limit, the process tree held the same processes in the same stage and used less
 * than one hundredth of the time that the window spans. Every other run is an infrastructure failure that reports the
 * limit, the time taken, the last stage and how long earlier runs took to become ready.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class StartupDiagnosis
{
	/** The time limit is divided by this to give the length of the window in which a stall is judged. */
	static final int WINDOW_DIVISOR = 6;
	/** A tree that uses less than one in this many of the window's nanoseconds of processor time is stalled. */
	private static final int STALL_SHARE_DIVISOR = 100;

	/**
	 * What the run showed when it ended.
	 *
	 * @param limit       the run's time limit
	 * @param elapsed     the time from launching the sandbox to the end of the run
	 * @param limitEnded  {@code true} if the time limit ended the run, {@code false} if the sandbox process exited
	 * @param exitStatus  the sandbox process's exit status; empty if the limit ended the run
	 * @param output      the output that the run produced before it ended
	 * @param samples     the looks at the process tree, in time order
	 * @param normal      what earlier runs of this launcher took to become ready, or empty if none did
	 * @param sandboxName the name that the sandbox program gives its own messages, such as {@code bwrap}
	 */
	public record Evidence(Duration limit, Duration elapsed, boolean limitEnded, OptionalInt exitStatus, String output,
		List<StartupSample> samples, Optional<StartupHistory.Summary> normal, String sandboxName)
	{
		/**
		 * Records the evidence.
		 *
		 * @param limit       the run's time limit
		 * @param elapsed     the time from launching the sandbox to the end of the run
		 * @param limitEnded  {@code true} if the time limit ended the run, {@code false} if the sandbox process exited
		 * @param exitStatus  the sandbox process's exit status; empty if the limit ended the run
		 * @param output      the output that the run produced before it ended
		 * @param samples     the looks at the process tree, in time order
		 * @param normal      what earlier runs of this launcher took to become ready, or empty if none did
		 * @param sandboxName the name that the sandbox program gives its own messages
		 * @throws NullPointerException     if any argument is null or {@code samples} holds null
		 * @throws IllegalArgumentException if the limit is not positive, or the run ended by exit and has no status
		 */
		public Evidence
		{
			requireThat(limit, "limit").isNotNull();
			requireThat(limit.isPositive(), "positiveLimit").isTrue();
			requireThat(elapsed, "elapsed").isNotNull();
			requireThat(exitStatus, "exitStatus").isNotNull();
			requireThat(limitEnded || exitStatus.isPresent(), "exitedRunHasStatus").isTrue();
			requireThat(output, "output").isNotNull();
			samples = List.copyOf(samples);
			requireThat(normal, "normal").isNotNull();
			requireThat(sandboxName, "sandboxName").isNotBlank();
		}
	}

	/**
	 * Decides the report of a run that ended before its launcher was ready.
	 *
	 * @param evidence what the run showed
	 * @return the failure to throw
	 * @throws NullPointerException if {@code evidence} is null
	 */
	public static CheckProgramStartFailure diagnose(Evidence evidence)
	{
		requireThat(evidence, "evidence").isNotNull();
		if (evidence.limitEnded())
			return diagnoseLimit(evidence);
		return diagnoseExit(evidence);
	}

	/**
	 * Decides the report of a run whose sandbox process exited before the launcher was ready.
	 *
	 * @param evidence what the run showed
	 * @return the failure to throw
	 */
	private static CheckProgramStartFailure diagnoseExit(Evidence evidence)
	{
		int status = evidence.exitStatus().getAsInt();
		String output = evidence.output().strip();
		String after = "status " + status + " after " + seconds(evidence.elapsed());
		if (output.startsWith(evidence.sandboxName() + ":"))
		{
			return new CheckProgramStartFailure(Reason.SANDBOX_CANNOT_START, "The check program sandbox could not " +
				"start: " + evidence.sandboxName() + " exited with " + after + ": " + output);
		}
		String ending;
		if (output.isEmpty())
			ending = " and printed no output.";
		else
			ending = ": " + output;
		return new CheckProgramStartFailure(Reason.LAUNCHER_UNUSABLE, "The check program launcher exited with " + after +
			", before it was ready" + ending);
	}

	/**
	 * Decides the report of a run that its time limit ended before the launcher was ready.
	 *
	 * @param evidence what the run showed
	 * @return the failure to throw
	 */
	private static CheckProgramStartFailure diagnoseLimit(Evidence evidence)
	{
		Duration window = evidence.limit().dividedBy(WINDOW_DIVISOR);
		Optional<Window> judged = window(evidence.samples(), window);
		String ended = "the time limit of " + seconds(evidence.limit()) + " ended after " + seconds(evidence.elapsed());
		String normal = normal(evidence);
		if (judged.isPresent() && judged.get().stalled())
		{
			StartupStage stage = judged.get().stage();
			String where = stage.description() + " stage (first seen at " + seconds(firstSeen(evidence.samples(), stage)) +
				") and its processes used " + seconds(judged.get().used()) + " of processor time in the last " +
				seconds(window);
			if (stage == StartupStage.SANDBOX_SETUP)
			{
				return new CheckProgramStartFailure(Reason.SANDBOX_CANNOT_START, "The check program sandbox could not " +
					"start: " + evidence.sandboxName() + " was still in its " + where + ", so it is blocked, not slow; " +
					ended + ". " + normal);
			}
			return new CheckProgramStartFailure(Reason.LAUNCHER_STALLED, "The check program launcher is stalled: its " +
				"processes were in the " + where + ", and it had not printed its ready line when " + ended + ". " +
				normal);
		}
		String reached = StartupStage.NOT_OBSERVED.description();
		Optional<StartupSample> last = evidence.samples().reversed().stream().
			filter(sample -> sample.stage() != StartupStage.NOT_OBSERVED).findFirst();
		if (last.isPresent())
		{
			StartupStage stage = last.get().stage();
			reached = stage.description() + ", first seen at " + seconds(firstSeen(evidence.samples(), stage));
		}
		String processor;
		if (judged.isPresent())
		{
			processor = "Its processes used " + seconds(judged.get().used()) + " of processor time in the last " +
				seconds(window) + ".";
		}
		else
			processor = "Its processor use over the last " + seconds(window) + " could not be observed.";
		return new CheckProgramStartFailure(Reason.NOT_READY, "The check program launcher was not ready when " + ended +
			". Last start-up stage reached: " + reached + ". " + processor + " " + normal);
	}

	/**
	 * Describes how long earlier runs of the launcher took to become ready.
	 *
	 * @param evidence what the run showed
	 * @return the sentences that tell a time limit that is too short from a machine that is slow
	 */
	private static String normal(Evidence evidence)
	{
		if (evidence.normal().isEmpty())
		{
			return "No earlier run of this launcher in this process reached ready, so its normal start time is not " +
				"known.";
		}
		StartupHistory.Summary summary = evidence.normal().get();
		String runs;
		if (summary.runs() == 1)
			runs = "1 run";
		else
			runs = summary.runs() + " runs";
		String text = "Earlier runs of this launcher that reached ready took " + seconds(summary.median()) +
			" at the median and " + seconds(summary.slowest()) + " at most (" + runs + ").";
		if (evidence.limit().compareTo(summary.slowest()) < 0)
			text += " The time limit is shorter than the longest of them.";
		return text;
	}

	/**
	 * Finds when a stage was first seen.
	 *
	 * @param samples the looks at the process tree, in time order
	 * @param stage   the stage
	 * @return the time of the first look that showed the stage
	 */
	private static Duration firstSeen(List<StartupSample> samples, StartupStage stage)
	{
		return samples.stream().filter(sample -> sample.stage() == stage).map(StartupSample::at).findFirst().
			orElseThrow();
	}

	/**
	 * Judges the last window of the looks. The window starts at the latest look that is at least the window's length
	 * before the last look. It is judged only if every look in it shows the same observed stage, the same processes and
	 * a known processor time.
	 *
	 * @param samples the looks at the process tree, in time order
	 * @param length  the window's length
	 * @return the judged window, or empty if the looks do not cover a window or do not show one stage, one set of
	 *   processes and the processor time throughout it
	 */
	private static Optional<Window> window(List<StartupSample> samples, Duration length)
	{
		if (samples.isEmpty())
			return Optional.empty();
		StartupSample last = samples.getLast();
		int first = -1;
		for (int i = samples.size() - 1; i >= 0 && first == -1; --i)
		{
			if (last.at().minus(samples.get(i).at()).compareTo(length) >= 0)
				first = i;
		}
		if (first == -1)
			return Optional.empty();
		StartupSample start = samples.get(first);
		if (start.stage() == StartupStage.NOT_OBSERVED || start.processorTime().isEmpty() ||
			last.processorTime().isEmpty())
		{
			return Optional.empty();
		}
		for (StartupSample sample : samples.subList(first, samples.size()))
		{
			if (sample.stage() != start.stage() || !sample.processIds().equals(start.processIds()))
				return Optional.empty();
		}
		Duration used = last.processorTime().get().minus(start.processorTime().get());
		if (used.isNegative())
			used = Duration.ZERO;
		return Optional.of(new Window(start.stage(), last.at().minus(start.at()), used));
	}

	/**
	 * Formats a duration for a report.
	 *
	 * @param duration the duration
	 * @return the duration in seconds, as {@link CheckProgramProblem#seconds(Duration)} states it
	 */
	private static String seconds(Duration duration)
	{
		return CheckProgramProblem.seconds(duration);
	}

	/**
	 * The last window of the looks, which all show one stage and one set of processes.
	 *
	 * @param stage the stage
	 * @param span  the time between the window's first and last look
	 * @param used  the processor time that the processes used in the window
	 */
	private record Window(StartupStage stage, Duration span, Duration used)
	{
		/**
		 * Tells whether the processes used less than one hundredth of the time that the window spans.
		 *
		 * @return {@code true} if they did
		 */
		boolean stalled()
		{
			return used.toNanos() * STALL_SHARE_DIVISOR < span.toNanos();
		}
	}

	/**
	 * Prevents construction.
	 */
	private StartupDiagnosis()
	{
	}
}
