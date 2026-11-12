package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.inventory.Authority;
import com.holdmyspot.cat.common.optimizer.port.CandidateOverlays;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;
import com.holdmyspot.cat.common.optimizer.store.DevelopmentJudgeRecords;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import tools.jackson.databind.json.JsonMapper;

/**
 * The stores, workspace, session protocols and run settings that a {@link Driver} uses for one campaign.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param fileLocks the process-owned lock coordinator
 * @param store the campaign store
 * @param sessions starts nested sessions
 * @param workspace stages candidates, prepares prompt author copies and reads or writes the optimization worktree
 * @param interrupts reports whether the host interrupted the run
 * @param clock the clock
 * @param overlays the candidates' overlays
 * @param judge builds judge requests and reads their verdicts
 * @param triage builds triage requests and reads the impacts of failed development trials
 * @param ranking ranks a cell's approaches before its round reserves prompt authors
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
 * @param candidateJudges the profiles that a prompt test may examine as its development judge, cheapest first;
 *   empty if every test keeps the frozen judge
 * @param judgeRecords the development judges that earlier campaigns on the same project and target qualified
 * @param temporaryDirectory the directory under which the driver creates its temporary directories; the process
 *   temporary directory of the command that runs the driver
 */
public record DriverContext(FileLocks fileLocks, CampaignStore store, SessionPort sessions, WorkspacePort workspace,
	HostInterruptSignal interrupts, Clock clock, CandidateOverlays overlays, JudgeProtocol judge,
	TriageProtocol triage, ApproachRankingProtocol ranking, PromptAuthorProtocol promptAuthor, ValuesProtocol values,
	Deliverer deliverer,
	CapCalibrator calibrator, ProcessTerminator terminator, LeftoverRuns leftoverRuns, Authority authority,
	Set<Path> promptSources, MeasurementIdentity environment, Path lockFile, RandomGenerator random,
	SliceSettings settings, JsonMapper json, List<TrialProfile> candidateJudges, DevelopmentJudgeRecords judgeRecords,
	Path temporaryDirectory)
{
	/**
	 * Creates a context.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param store the campaign store
	 * @param sessions starts nested sessions
	 * @param workspace stages candidates, prepares prompt author copies and reads or writes the optimization worktree
	 * @param interrupts reports whether the host interrupted the run
	 * @param clock the clock
	 * @param overlays the candidates' overlays
	 * @param judge builds judge requests and reads their verdicts
	 * @param triage builds triage requests and reads the impacts of failed development trials
	 * @param ranking builds and validates approach rankings
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
	 * @param candidateJudges the profiles that a prompt test may examine as its development judge, cheapest first
	 * @param judgeRecords the development judges that earlier campaigns on the same project and target qualified
	 * @param temporaryDirectory the directory under which the driver creates its temporary directories
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code environment} records a condition that only sessions observe
	 */
	public DriverContext
	{
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(store, "store").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		requireThat(interrupts, "interrupts").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(overlays, "overlays").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(triage, "triage").isNotNull();
		requireThat(ranking, "ranking").isNotNull();
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
		requireThat(candidateJudges, "candidateJudges").isNotNull();
		requireThat(judgeRecords, "judgeRecords").isNotNull();
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		promptSources = Set.copyOf(promptSources);
		candidateJudges = List.copyOf(candidateJudges);
	}

	/**
	 * Creates a context whose prompt tests keep the frozen judge for their development trials.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param store the campaign store
	 * @param sessions starts nested sessions
	 * @param workspace stages candidates, prepares prompt author copies and reads or writes the optimization worktree
	 * @param interrupts reports whether the host interrupted the run
	 * @param clock the clock
	 * @param overlays the candidates' overlays
	 * @param judge builds judge requests and reads their verdicts
	 * @param triage builds triage requests and reads the impacts of failed development trials
	 * @param ranking builds and validates approach rankings
	 * @param promptAuthor builds prompt author requests and reads their reports
	 * @param values builds the requests of the sessions that add development values
	 * @param deliverer delivers the candidate that the campaign selects
	 * @param calibrator calibrates the cap of a certification attempt
	 * @param terminator requests termination of processes that an earlier driver left running
	 * @param leftoverRuns finds the harness processes of runs that an earlier driver left running
	 * @param authority the confirmed edit authority
	 * @param promptSources the in-project prompt sources, relative to the project
	 * @param environment the measurement conditions that the driver observes outside trials
	 * @param lockFile the campaign's write-lock file
	 * @param random the source of the certification trial stream's seed when a candidate is committed
	 * @param settings the time limits of one bounded driver run (a slice)
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
	 * @param temporaryDirectory the directory under which the driver creates its temporary directories
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code environment} records a condition that only sessions observe
	 */
	public DriverContext(FileLocks fileLocks, CampaignStore store, SessionPort sessions, WorkspacePort workspace,
		HostInterruptSignal interrupts, Clock clock, CandidateOverlays overlays, JudgeProtocol judge,
		TriageProtocol triage, ApproachRankingProtocol ranking, PromptAuthorProtocol promptAuthor,
		ValuesProtocol values, Deliverer deliverer, CapCalibrator calibrator, ProcessTerminator terminator,
		LeftoverRuns leftoverRuns, Authority authority, Set<Path> promptSources, MeasurementIdentity environment,
		Path lockFile, RandomGenerator random, SliceSettings settings, JsonMapper json, Path temporaryDirectory)
	{
		this(fileLocks, store, sessions, workspace, interrupts, clock, overlays, judge, triage, ranking, promptAuthor,
			values, deliverer, calibrator, terminator, leftoverRuns, authority, promptSources, environment, lockFile,
			random, settings, json, List.of(), DevelopmentJudgeRecords.none(), temporaryDirectory);
	}

	/**
	 * Creates a context that keeps no development judges between campaigns.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param store the campaign store
	 * @param sessions starts nested sessions
	 * @param workspace stages candidates, prepares prompt author copies and reads or writes the optimization worktree
	 * @param interrupts reports whether the host interrupted the run
	 * @param clock the clock
	 * @param overlays the candidates' overlays
	 * @param judge builds judge requests and reads their verdicts
	 * @param triage builds triage requests and reads the impacts of failed development trials
	 * @param ranking builds and validates approach rankings
	 * @param promptAuthor builds prompt author requests and reads their reports
	 * @param values builds the requests of the sessions that add development values
	 * @param deliverer delivers the candidate that the campaign selects
	 * @param calibrator calibrates the cap of a certification attempt
	 * @param terminator requests termination of processes that an earlier driver left running
	 * @param leftoverRuns finds the harness processes of runs that an earlier driver left running
	 * @param authority the confirmed edit authority
	 * @param promptSources the in-project prompt sources, relative to the project
	 * @param environment the measurement conditions that the driver observes outside trials
	 * @param lockFile the campaign's write-lock file
	 * @param random the source of the certification trial stream's seed when a candidate is committed
	 * @param settings the time limits of one bounded driver run (a slice)
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
	 * @param candidateJudges the profiles that a prompt test may examine as its development judge, cheapest first
	 * @param temporaryDirectory the directory under which the driver creates its temporary directories
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code environment} records a condition that only sessions observe
	 */
	public DriverContext(FileLocks fileLocks, CampaignStore store, SessionPort sessions, WorkspacePort workspace,
		HostInterruptSignal interrupts, Clock clock, CandidateOverlays overlays, JudgeProtocol judge,
		TriageProtocol triage, ApproachRankingProtocol ranking, PromptAuthorProtocol promptAuthor,
		ValuesProtocol values, Deliverer deliverer, CapCalibrator calibrator, ProcessTerminator terminator,
		LeftoverRuns leftoverRuns, Authority authority, Set<Path> promptSources, MeasurementIdentity environment,
		Path lockFile, RandomGenerator random, SliceSettings settings, JsonMapper json,
		List<TrialProfile> candidateJudges, Path temporaryDirectory)
	{
		this(fileLocks, store, sessions, workspace, interrupts, clock, overlays, judge, triage, ranking, promptAuthor,
			values, deliverer, calibrator, terminator, leftoverRuns, authority, promptSources, environment, lockFile,
			random, settings, json, candidateJudges, DevelopmentJudgeRecords.none(), temporaryDirectory);
	}
}
