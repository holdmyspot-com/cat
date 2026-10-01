package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.port.CandidateOverlays;
import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Everything a {@link Driver} reads, writes or calls for one campaign.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe if its components are.
 *
 * @param store the campaign store
 * @param sessions starts nested sessions
 * @param workspace stages candidates, prepares author copies and reads or writes the optimization worktree
 * @param interrupts reports whether the host interrupted the run
 * @param clock the clock
 * @param overlays the candidates' overlays
 * @param judge phrases judge sessions and reads their verdicts
 * @param author phrases author sessions
 * @param deliverer delivers the candidate that the campaign selects
 * @param calibrator calibrates the cap of a certification attempt
 * @param terminator terminates processes that an earlier driver left running
 * @param leftoverRuns finds the runner runs of this campaign that are still recorded as running
 * @param authority the confirmed edit authority; the driver replaces its authorized targets with the campaign's to
 *   validate author attempts and diagnose the edits that authors report needing
 * @param promptSources the participating prompt sources: paths relative to the project, or absolute paths of
 *   external targets
 * @param environment the measurement conditions that the driver observes outside trials; it records no
 *   {@link MeasurementCondition#MODEL}, {@link MeasurementCondition#REASONING},
 *   {@link MeasurementCondition#PROMPT_SOURCES} or {@link MeasurementCondition#JUDGE}, which only sessions observe
 * @param lockFile the campaign's write-lock file
 * @param random the source of the formal stream's seed at commit
 * @param settings the slice's time limits
 */
public record DriverContext(CampaignStore store, SessionPort sessions, WorkspacePort workspace,
	HostInterruptSignal interrupts, Clock clock, CandidateOverlays overlays, JudgeProtocol judge,
	AuthorProtocol author, Deliverer deliverer, CapCalibrator calibrator, ProcessTerminator terminator,
	LeftoverRuns leftoverRuns, Authority authority, Set<Path> promptSources, MeasurementIdentity environment,
	Path lockFile, RandomGenerator random, SliceSettings settings)
{
	/**
	 * Creates a context.
	 *
	 * @param store the campaign store
	 * @param sessions starts nested sessions
	 * @param workspace stages candidates, prepares author copies and reads or writes the optimization worktree
	 * @param interrupts reports whether the host interrupted the run
	 * @param clock the clock
	 * @param overlays the candidates' overlays
	 * @param judge phrases judge sessions and reads their verdicts
	 * @param author phrases author sessions
	 * @param deliverer delivers the candidate that the campaign selects
	 * @param calibrator calibrates the cap of a certification attempt
	 * @param terminator terminates processes that an earlier driver left running
	 * @param leftoverRuns finds the runner runs of this campaign that are still recorded as running
	 * @param authority the confirmed edit authority; the driver replaces its authorized targets with the campaign's
	 *   to validate author attempts and diagnose the edits that authors report needing
	 * @param promptSources the participating prompt sources: paths relative to the project, or absolute paths of
	 *   external targets
	 * @param environment the measurement conditions that the driver observes outside trials
	 * @param lockFile the campaign's write-lock file
	 * @param random the source of the formal stream's seed at commit
	 * @param settings the slice's time limits
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code environment} records a condition that only sessions observe
	 */
	public DriverContext
	{
		requireThat(store, "store").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		requireThat(interrupts, "interrupts").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(overlays, "overlays").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(author, "author").isNotNull();
		requireThat(deliverer, "deliverer").isNotNull();
		requireThat(calibrator, "calibrator").isNotNull();
		requireThat(terminator, "terminator").isNotNull();
		requireThat(leftoverRuns, "leftoverRuns").isNotNull();
		requireThat(authority, "authority").isNotNull();
		requireThat(promptSources, "promptSources").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(environment.values().keySet(), "environment.values().keySet()").
			doesNotContainAny(Set.of(MeasurementCondition.MODEL, MeasurementCondition.REASONING,
				MeasurementCondition.PROMPT_SOURCES, MeasurementCondition.JUDGE));
		requireThat(lockFile, "lockFile").isNotNull();
		requireThat(random, "random").isNotNull();
		requireThat(settings, "settings").isNotNull();
		promptSources = Set.copyOf(promptSources);
	}
}
