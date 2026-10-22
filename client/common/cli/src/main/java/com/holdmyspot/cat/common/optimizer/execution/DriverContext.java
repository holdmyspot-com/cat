package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.inventory.Authority;
import com.holdmyspot.cat.common.optimizer.port.CandidateOverlays;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.random.RandomGenerator;

import tools.jackson.databind.json.JsonMapper;

/**
 * The stores, workspace, session protocols and run settings that a {@link Driver} uses for one campaign.
 * <p>
 * <b>Thread Safety:</b> Callers may read the context concurrently if its collaborators support concurrent access.
 *
 * @param store the campaign store
 * @param sessions starts nested sessions
 * @param workspace stages candidates, prepares prompt author copies and reads or writes the optimization worktree
 * @param interrupts reports whether the host interrupted the run
 * @param clock the clock
 * @param overlays the candidates' overlays
 * @param judge builds judge requests and reads their verdicts
 * @param triage builds triage requests and reads the impacts of failed development trials
 * @param promptAuthor builds prompt author requests and reads their reports
 * @param values builds the requests of the sessions that add development values after a certification
 *   attempt ended with formal failures, and reads their test cases
 * @param deliverer delivers the candidate that the campaign selects
 * @param calibrator calibrates the cap of a certification attempt
 * @param terminator requests termination of processes that an earlier driver left running
 * @param leftoverRuns finds the harness processes of runs that an earlier driver left running, in the session
 *   port's run roots
 * @param authority the confirmed edit authority, which validates prompt author attempts and diagnoses the edits that
 *   prompt authors report needing
 * @param promptSources the in-project prompt sources, relative to the project
 * @param environment the measurement conditions that the driver observes outside trials; it records no
 *   {@link MeasurementCondition#MODEL}, {@link MeasurementCondition#REASONING},
 *   {@link MeasurementCondition#TOOL_OUTPUT_LIMIT} or {@link MeasurementCondition#PROMPT_SOURCES}, which only
 *   sessions observe
 * @param lockFile the campaign's write-lock file
 * @param random the source of the certification trial stream's seed when a candidate is committed
 * @param settings the time limits of one bounded driver run (a slice)
 * @param json reads and writes the hook configurations that control trials remove dependents from
 */
public record DriverContext(CampaignStore store, SessionPort sessions, WorkspacePort workspace,
	HostInterruptSignal interrupts, Clock clock, CandidateOverlays overlays, JudgeProtocol judge,
	TriageProtocol triage, PromptAuthorProtocol promptAuthor, ValuesProtocol values, Deliverer deliverer,
	CapCalibrator calibrator, ProcessTerminator terminator, LeftoverRuns leftoverRuns, Authority authority,
	Set<Path> promptSources, MeasurementIdentity environment, Path lockFile, RandomGenerator random,
	SliceSettings settings, JsonMapper json)
{
	/**
	 * Creates a context.
	 *
	 * @param store the campaign store
	 * @param sessions starts nested sessions
	 * @param workspace stages candidates, prepares prompt author copies and reads or writes the optimization worktree
	 * @param interrupts reports whether the host interrupted the run
	 * @param clock the clock
	 * @param overlays the candidates' overlays
	 * @param judge builds judge requests and reads their verdicts
	 * @param triage builds triage requests and reads the impacts of failed development trials
	 * @param promptAuthor builds prompt author requests and reads their reports
	 * @param values builds the requests of the sessions that add development values after a certification
	 *   attempt ended with formal failures, and reads their test cases
	 * @param deliverer delivers the candidate that the campaign selects
	 * @param calibrator calibrates the cap of a certification attempt
	 * @param terminator requests termination of processes that an earlier driver left running
	 * @param leftoverRuns finds the harness processes of runs that an earlier driver left running, in the session
 *   port's run roots
	 * @param authority the confirmed edit authority, which validates prompt author attempts and diagnoses the edits
	 *   that prompt authors report needing
	 * @param promptSources the in-project prompt sources, relative to the project
	 * @param environment the measurement conditions that the driver observes outside trials
	 * @param lockFile the campaign's write-lock file
	 * @param random the source of the certification trial stream's seed when a candidate is committed
	 * @param settings the time limits of one bounded driver run (a slice)
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
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
		requireThat(triage, "triage").isNotNull();
		requireThat(promptAuthor, "promptAuthor").isNotNull();
		requireThat(values, "values").isNotNull();
		requireThat(deliverer, "deliverer").isNotNull();
		requireThat(calibrator, "calibrator").isNotNull();
		requireThat(terminator, "terminator").isNotNull();
		requireThat(leftoverRuns, "leftoverRuns").isNotNull();
		requireThat(authority, "authority").isNotNull();
		requireThat(promptSources, "promptSources").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(environment.values().keySet(), "environment.values().keySet()").
			doesNotContainAny(Set.of(MeasurementCondition.MODEL, MeasurementCondition.REASONING,
				MeasurementCondition.TOOL_OUTPUT_LIMIT, MeasurementCondition.PROMPT_SOURCES));
		requireThat(lockFile, "lockFile").isNotNull();
		requireThat(random, "random").isNotNull();
		requireThat(settings, "settings").isNotNull();
		requireThat(json, "json").isNotNull();
		promptSources = Set.copyOf(promptSources);
	}
}
