package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.execution.PilotTrialRecovery.Discard;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Status;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.secrets.GuardPilotSecrets;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the guard pilot of a proposed contract before CAT presents it: at least {@value #TRIALS} control trials of each
 * prompt test in each applicable evaluator's weakest cell, and at least {@value #TRIALS_PER_CLASS} of each boundary
 * class of the test, on development test cases only, decided by the proposed built-in tools and judge. The results
 * show how often the agent already passes each assertion without the target prompts, so that the user can see a guard
 * that the agent fails even before any prompt is optimized.
 * <p>
 * Pilot trials are targeted: they enter no count, record no campaign event and spend no allowance.
 * <p>
 * A check program that cannot start for a cause that CAT names, a sandbox that cannot start, a launcher that is
 * missing, damaged or exited, or a launcher that is stalled, stops the pilot at once: it starts no further session and
 * pauses for the user's choice to retry or to stop, with the cause as the reason, and the trial is not charged an
 * infrastructure attempt.
 * <p>
 * A session that ends without an outcome is an infrastructure attempt, and its logical trial runs again with the same
 * test case. After {@value Campaign#INFRASTRUCTURE_FAILURE_LIMIT} infrastructure failures of one logical trial, the
 * pilot pauses: it starts no further session, and its result says that the user must choose to retry or to stop. A
 * pilot that runs again keeps the trials that it decided.
 * A trial interrupted by a host crash resumes in its original session when the broker can continue its completed
 * prefix. An attempt whose prefix cannot be continued is counted only after the broker settles its original launch.
 * <p>
 * A pilot that runs in a {@link PilotSlice} stops admitting trials as the driver does, cancels the sessions that still
 * run when the grace period ends, and stays unfinished, so that the next command continues it. A cancelled session is
 * not an infrastructure attempt: its trial runs again as the same logical trial with the same test case.
 */
public final class GuardPilot
{
	static
	{
		GuardPilotSecrets.setGuardPilotAccess(GuardPilot::new);
	}

	/**
	 * The least number of control trials of each prompt test.
	 */
	public static final int TRIALS = 20;
	/**
	 * The least number of control trials of each boundary class of a prompt test. A test with more than
	 * {@code TRIALS / TRIALS_PER_CLASS} classes runs more than {@value #TRIALS} trials.
	 */
	public static final int TRIALS_PER_CLASS = 2;
	/**
	 * Removes one of a passing pilot trial's project directories.
	 */
	@FunctionalInterface
	public interface DirectoryRemoval
	{
		/**
		 * Deletes a project directory.
		 *
		 * @param directory the project directory
		 * @throws IOException if the directory cannot be deleted
		 */
		void remove(Path directory) throws IOException;
	}

	/**
	 * Why a pilot that was kept before it ran every trial stopped, as a later reader sees it if the process that ran
	 * the pilot ended without finishing it.
	 */
	private static final String UNFINISHED = "The process that ran the pilot ended before every trial did";
	/**
	 * Why a pilot that ran in a slice stopped when the slice ended before every trial did.
	 */
	private static final String SLICE_ENDED = "The slice ended before every trial did";
	/**
	 * Why a pilot that ran in a slice stopped when the host interrupted the command.
	 */
	private static final String HOST_INTERRUPTED = "The host interrupted the command that ran the pilot";
	private static final TurnObserver.Progress NO_PROGRESS = new TurnObserver.Progress(0, Map.of(), false);
	/**
	 * The number of times that the pilot runs each check program on a trial, so that a program that does not decide
	 * the same input the same way shows before confirmation.
	 */
	private static final int CHECK_RUNS = 2;
	private static final PilotTrialRecovery NO_RECOVERY =
		new PilotTrialRecovery(Optional.empty(), NO_PROGRESS, List.of(), Optional.empty());
	private final SessionPort sessions;
	private final Map<Harness, SessionPort> evaluators;
	private final WorkspacePort workspace;
	private final Path target;
	private final DirectoryRemoval directoryRemoval;
	private final JudgeProtocol judge;
	private final VerdictRecords verdicts;
	private final ProposalSchema schema;
	private final JsonMapper json;
	private final int ceiling;
	private final CheckProgramEvaluation checkPrograms;

	/**
	 * Creates a guard pilot.
	 *
	 * @param sessions starts the trial and judge sessions
	 * @param workspace stages the control source that the trials work in
	 * @param target the normalized relative path of the target prompt
	 * @param judge writes the judge requests and reads their verdicts
	 * @param verdicts the campaign's stored verdicts, which the pilot's trials take and write as any trial does
	 * @param schema writes the definition of each prompt test, whose identity decides whether a stored pilot still
	 *   applies
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
	 * @param ceiling the maximum number of sessions that run at the same time
	 * @throws NullPointerException     if any required argument is null
	 * @throws IllegalArgumentException if {@code target} is absolute or {@code ceiling} is not positive
	 */
	public GuardPilot(SessionPort sessions, WorkspacePort workspace, Path target, JudgeProtocol judge,
		VerdictRecords verdicts, ProposalSchema schema, JsonMapper json, int ceiling)
	{
		this(sessions, workspace, target, judge, verdicts, schema, json, ceiling,
			TemporaryDirectories::deleteRecursively);
	}

	/**
	 * Creates a guard pilot with an owner judge and evaluator-specific trial sessions.
	 *
	 * @param sessions starts the owner judge and settles broker launches for removed evaluators
	 * @param evaluators starts trial sessions for each selected evaluator
	 * @param workspace stages the control source that the trials work in
	 * @param target the normalized relative path of the target prompt
	 * @param judge writes the judge requests and reads their verdicts
	 * @param verdicts the campaign's stored verdicts, which the pilot's trials take and write as any trial does
	 * @param schema writes the definition of each prompt test
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
	 * @param ceiling the maximum number of sessions that run at the same time
	 * @throws NullPointerException     if any argument or evaluator entry is null
	 * @throws IllegalArgumentException if {@code target} is absolute or {@code ceiling} is not positive
	 */
	public GuardPilot(SessionPort sessions, Map<Harness, SessionPort> evaluators, WorkspacePort workspace,
		Path target, JudgeProtocol judge, VerdictRecords verdicts, ProposalSchema schema, JsonMapper json, int ceiling)
	{
		this(sessions, evaluators, workspace, target, judge, verdicts, schema, json, ceiling,
			CheckProgramEvaluation.UNAVAILABLE);
	}

	/**
	 * Creates a guard pilot that runs the check programs of the tests that declare them.
	 *
	 * @param sessions      starts the owner judge and settles broker launches for removed evaluators
	 * @param evaluators    starts trial sessions for each selected evaluator
	 * @param workspace     stages the control source that the trials work in
	 * @param target        the normalized relative path of the target prompt
	 * @param judge         writes the judge requests and reads their verdicts
	 * @param verdicts      the campaign's stored verdicts, which the pilot's trials take and write as any trial does
	 * @param schema        writes the definition of each prompt test
	 * @param json          reads and writes the hook configurations from which control trials remove the target's
	 *   dependents
	 * @param ceiling       the maximum number of sessions that run at the same time
	 * @param checkPrograms runs each check program of a completed trial in the campaign owner's sandbox
	 * @throws NullPointerException     if any argument or evaluator entry is null
	 * @throws IllegalArgumentException if {@code target} is absolute or {@code ceiling} is not positive
	 */
	public GuardPilot(SessionPort sessions, Map<Harness, SessionPort> evaluators, WorkspacePort workspace,
		Path target, JudgeProtocol judge, VerdictRecords verdicts, ProposalSchema schema, JsonMapper json, int ceiling,
		CheckProgramEvaluation checkPrograms)
	{
		this(sessions, evaluators, workspace, target, judge, verdicts, schema, json, ceiling,
			TemporaryDirectories::deleteRecursively, checkPrograms);
	}

	/**
	 * Creates a guard pilot with a supplied project-directory remover.
	 *
	 * @param sessions starts the trial and judge sessions
	 * @param workspace stages the control source that the trials work in
	 * @param target the normalized relative path of the target prompt
	 * @param judge writes the judge requests and reads their verdicts
	 * @param verdicts the campaign's stored verdicts, which the pilot's trials take and write as any trial does
	 * @param schema writes the definition of each prompt test, whose identity decides whether a stored pilot still
	 *   applies
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
	 * @param ceiling the maximum number of sessions that run at the same time
	 * @param directoryRemoval deletes project directories of passing trials
	 * @throws NullPointerException if any required argument is null
	 * @throws IllegalArgumentException if {@code target} is absolute or {@code ceiling} is not positive
	 */
	private GuardPilot(SessionPort sessions, WorkspacePort workspace, Path target, JudgeProtocol judge,
		VerdictRecords verdicts, ProposalSchema schema, JsonMapper json, int ceiling, DirectoryRemoval directoryRemoval)
	{
		this(sessions, Map.of(), workspace, target, judge, verdicts, schema, json, ceiling, directoryRemoval,
			CheckProgramEvaluation.UNAVAILABLE);
	}

	/**
	 * Creates a guard pilot with evaluator sessions and a supplied project-directory remover.
	 *
	 * @param sessions         starts the owner judge and provides the default trial sessions
	 * @param evaluators       starts trial sessions for the selected evaluators
	 * @param workspace        stages the control source that the trials work in
	 * @param target           the normalized relative path of the target prompt
	 * @param judge            writes the judge requests and reads their verdicts
	 * @param verdicts         the campaign's stored verdicts, which the pilot's trials take and write as any trial does
	 * @param schema           writes the definition of each prompt test
	 * @param json             reads and writes the control hook configurations
	 * @param ceiling          the maximum number of sessions that run at the same time
	 * @param directoryRemoval deletes project directories of passing trials
	 * @param checkPrograms    runs each check program of a completed trial
	 */
	private GuardPilot(SessionPort sessions, Map<Harness, SessionPort> evaluators, WorkspacePort workspace,
		Path target, JudgeProtocol judge, VerdictRecords verdicts, ProposalSchema schema, JsonMapper json, int ceiling,
		DirectoryRemoval directoryRemoval, CheckProgramEvaluation checkPrograms)
	{
		requireThat(sessions, "sessions").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		FileInventory.requireInventoryPath(target, "target");
		requireThat(target.isAbsolute(), "target.isAbsolute()").isFalse();
		requireThat(directoryRemoval, "directoryRemoval").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(verdicts, "verdicts").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(ceiling, "ceiling").isPositive();
		requireThat(checkPrograms, "checkPrograms").isNotNull();
		this.sessions = sessions;
		this.evaluators = Map.copyOf(evaluators);
		this.workspace = workspace;
		this.target = target;
		this.directoryRemoval = directoryRemoval;
		this.judge = judge;
		this.verdicts = verdicts;
		this.schema = schema;
		this.json = json;
		this.ceiling = ceiling;
		this.checkPrograms = checkPrograms;
	}

	/**
	 * Returns the boundary classes of a prompt test's development test cases, which the pilot samples.
	 *
	 * @param contract the proposed contract
	 * @param test the prompt test
	 * @return the distinct classes in alphabetical order, without the empty class of untagged test cases
	 * @throws NullPointerException if any of the arguments are null
	 */
	private static List<String> boundaryClasses(Contract contract, PromptTest test)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(test, "test").isNotNull();
		Set<String> classes = new TreeSet<>();
		for (TestCase testCase : contract.split().testCases(test, Part.DEVELOPMENT))
		{
			if (!testCase.boundaryClass().isEmpty())
				classes.add(testCase.boundaryClass());
		}
		return List.copyOf(classes);
	}

	/**
	 * Returns how many trials the pilot of a prompt test draws: {@value #TRIALS}, or {@value #TRIALS_PER_CLASS} of
	 * each boundary class of its development test cases if that is more.
	 *
	 * @param contract the proposed contract
	 * @param test the prompt test
	 * @return the number of trials
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static int trialCount(Contract contract, PromptTest test)
	{
		return Math.max(TRIALS, TRIALS_PER_CLASS * boundaryClasses(contract, test).size());
	}

	/**
	 * Returns a prompt test's pilot cell for a single-evaluator contract.
	 *
	 * @param contract the proposed contract
	 * @param testId the prompt test
	 * @return the cell, or empty if it depends on an unranked model family
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the test has no cell or the contract has multiple evaluators
	 */
	public static Optional<Cell> pilotCell(Contract contract, String testId)
	{
		requireThat(contract, "contract").isNotNull();
		TrialProfiles profiles = contract.profiles();
		if (profiles.byEvaluator().size() > 1)
			throw new IllegalArgumentException("Choose an evaluator before selecting a pilot cell");
		return pilotCell(profiles, testId);
	}

	/**
	 * Returns the cell that a prompt test's pilot runs in: the test's weakest cell when every model is allowed, since
	 * the certification scope is known only at confirmation.
	 *
	 * @param profiles the trial profiles of one evaluator
	 * @param testId the prompt test
	 * @return the cell, or empty if it depends on a model whose family is unranked
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the test has no cell
	 */
	public static Optional<Cell> pilotCell(TrialProfiles profiles, String testId)
	{
		requireThat(profiles, "profiles").isNotNull();
		return CellTier.weakestCell(profiles, CertificationScope.WEAKEST_CELL, testId);
	}

	/**
	 * Returns the identity of a prompt test's definition: the test and the requirements that it checks, which the
	 * trials and the judge receive, the judge that decides its trials, and the dependents of the target that control
	 * trials remove.
	 *
	 * @param contract the proposed contract
	 * @param test a prompt test of {@code contract}
	 * @return the identity, which changes whenever the scenario, the test cases, the assertions, a checked
	 *   requirement, the judge or a dependent changes
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Sha256 definition(Contract contract, PromptTest test)
	{
		return Sha256.of(schema.toJson(contract, test).getBytes(UTF_8));
	}

	/**
	 * Checks whether the stored controls of each evaluator ran under the proposed conditions, without starting any
	 * sessions. A result that recorded failed trials can still match its conditions.
	 *
	 * @param contract the proposal's contract
	 * @param plugins the prepared trial plugins of each evaluator, in installation order
	 * @param control the control overlay
	 * @param selection the files the control trials receive
	 * @param stored the previously presented pilot results
	 * @return whether each stored result matches its current conditions
	 * @throws NullPointerException if any of the arguments are null
	 */
	public boolean matchesStored(Contract contract, Map<Harness, List<TrialPlugin>> plugins, Overlay control,
		SourceSelection selection, Map<String, PilotResult> stored)
	{
		requireThat(plugins, "plugins").isNotEmpty();
		// The pilot of a shared contract is keyed by evaluator, so each stored result names its own
		boolean shared = plugins.size() > 1;
		Harness sole = plugins.keySet().iterator().next();
		Sha256 files = control.snapshot().identity();
		for (PilotResult result : stored.values())
		{
			Harness evaluator = sole;
			if (shared)
				evaluator = result.evaluator();
			if (evaluator == null || !plugins.containsKey(evaluator))
				return false;
			TrialProfiles profiles = contract.profiles().forEvaluator(evaluator);
			Optional<PromptTest> test = contract.findTest(result.testId());
			if (test.isEmpty() || profiles.cellsOf(result.testId()).isEmpty())
				return false;
			Optional<Cell> cell = pilotCell(profiles, result.testId());
			if (cell.isEmpty() || !conditionsFor(contract, test.orElseThrow(), cell.orElseThrow(), shared, files,
				selection, plugins.get(evaluator)).matches(result))
				return false;
		}
		return true;
	}

	/**
	 * Runs each applicable test and evaluator whose stored pilot needs more trials.
	 * <p>
	 * A stored pilot is reused when it ran every trial, every trial ended with a decision, and its test's definition,
	 * its cell, the control source's files, the selection, the session environment version and the judge version are
	 * unchanged, so a revision reruns only the tests whose definition changed or whose earlier pilot has a failed trial,
	 * and every test once the trials' projects or the sessions change what the model receives or how the judge
	 * decides. A stored pilot that is unfinished but otherwise unchanged keeps the trials that it decided, with their
	 * test cases, and runs only the others. A test whose cell is unknown, or that
	 * has no development test case, has no pilot. A session that the provider rejects because of a rate limit, or
	 * refuses, runs again once the harness's shared hold ends: a trial with the same test case, and a judge without
	 * spending one of its attempts. A refusal that follows a 30-minute hold starts no further session, because waiting
	 * does not fix it, and each unfinished test's result records why the pilot stopped.
	 * <p>
	 * A trial session that fails or cannot start, a judge that fails
	 * {@value Driver#JUDGE_ATTEMPTS} times, and a trial record that cannot be read are infrastructure attempts: the
	 * logical trial runs again with the same test case. When one logical trial has
	 * {@value Campaign#INFRASTRUCTURE_FAILURE_LIMIT} infrastructure failures, no further session starts, and each
	 * unfinished test's result is paused. A trial or judge session that CAT interrupts is not an infrastructure
	 * attempt, and nothing pauses on interruptions alone: no further session starts, each unfinished test's result
	 * records the interruption as why the pilot stopped, and a later run keeps the decided trials and decides the
	 * others. A trial whose built-in tool cannot be decided from its record is not an
	 * infrastructure attempt. The pilot records that the proposed tool could not decide the trial.
	 *
	 * @param contract the proposed contract
	 * @param plugins the campaign's trial plugins, which every trial installs
	 * @param control the control source: the incumbent with the target prompt file emptied and its dependents in the
	 *   project removed
	 * @param excluded the project-relative paths that the staged control source leaves out, so that a trial cannot
	 *   read the prompt tests
	 * @param selection the files of the control source that the staged control source holds
	 * @param stored the pilots previously stored for the campaign, by test and evaluator
	 * @param random draws the seed of each test's development stream
	 * @return the pilots by test and evaluator, in contract order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if evaluator trial ports are missing or disagree with the assigned profiles
	 * @throws IOException if the control source or its copies of the trial plugins cannot be staged, or the project
	 *   copies of a control trial that passes every assertion cannot be deleted
	 * @throws InterruptedException if the thread is interrupted while it waits for a session. Every running session
	 *   is interrupted too.
	 */
	public Map<String, PilotResult> run(Contract contract, List<TrialPlugin> plugins, Overlay control,
		Set<Path> excluded, SourceSelection selection, Map<String, PilotResult> stored, RandomGenerator random)
		throws IOException, InterruptedException
	{
		return run(contract, plugins, control, excluded, selection, stored, random, _ ->
		{
		});
	}

	/**
	 * Runs each applicable test and evaluator whose stored pilot needs more trials, as
	 * {@link #run(Contract, List, Overlay, Set, SourceSelection, Map, RandomGenerator)} does, and hands the pilots to a
	 * checkpoint before a new trial launches, after each observer decision and when a trial is decided.
	 *
	 * @param contract the proposed contract
	 * @param plugins the campaign's trial plugins, which every trial installs
	 * @param control the control source: the incumbent with the target prompt file emptied and its dependents in the
	 *   project removed
	 * @param excluded the project-relative paths that the staged control source leaves out, so that a trial cannot
	 *   read the prompt tests
	 * @param selection the files of the control source that the staged control source holds
	 * @param stored the pilots previously stored for the campaign, by test and evaluator
	 * @param random draws the seed of each test's development stream
	 * @param checkpoint keeps the pilots' in-progress launches, observer decisions and trial results. A pilot that has
	 *   more trials to run at that point has a stop reason that says so.
	 * @return the pilots by test and evaluator, in contract order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if evaluator trial ports are missing or disagree with the assigned profiles
	 * @throws IOException if the control source or its copies of the trial plugins cannot be staged, the project
	 *   copies of a control trial that passes every assertion cannot be deleted, or the checkpoint fails. A failed
	 *   checkpoint starts no further session and interrupts the running ones.
	 * @throws InterruptedException if the thread is interrupted while it waits for a session. Every running session
	 *   is interrupted too.
	 */
	public Map<String, PilotResult> run(Contract contract, List<TrialPlugin> plugins, Overlay control,
		Set<Path> excluded, SourceSelection selection, Map<String, PilotResult> stored, RandomGenerator random,
		PilotCheckpoint checkpoint) throws IOException, InterruptedException
	{
		return run(contract, plugins, control, excluded, selection, stored, random, checkpoint, null);
	}

	/**
	 * Runs each applicable test and evaluator whose stored pilot needs more trials, as
	 * {@link #run(Contract, List, Overlay, Set, SourceSelection, Map, RandomGenerator, PilotCheckpoint)} does, within
	 * a slice's time bound.
	 * <p>
	 * The pilot admits a trial only if the slice's admission rule does: the trial is expected to end by the slice's
	 * deadline, which the 90th-percentile duration of the trials that completed in this slice decides once 5 have
	 * completed, and the prompt test's time limit decides before. Judges start until the grace period ends. When the
	 * grace period ends, or the host interrupts the command, the pilot interrupts the sessions that still run and
	 * ignores their results. A pilot that the slice left with trials to run says so in its stop reason, and
	 * {@link #endedBySlice(Map)} recognizes it.
	 *
	 * @param contract the proposed contract
	 * @param plugins the campaign's trial plugins, which every trial installs
	 * @param control the control source: the incumbent with the target prompt file emptied and its dependents in the
	 *   project removed
	 * @param excluded the project-relative paths that the staged control source leaves out, so that a trial cannot
	 *   read the prompt tests
	 * @param selection the files of the control source that the staged control source holds
	 * @param stored the pilots previously stored for the campaign, by test and evaluator
	 * @param random draws the seed of each test's development stream
	 * @param checkpoint keeps the pilots' in-progress launches, observer decisions and trial results
	 * @param slice the time bound, or {@code null} if the pilot runs until every trial ends or the pilot stops
	 * @return the pilots by test and evaluator, in contract order
	 * @throws NullPointerException if any of the arguments other than {@code slice} are null
	 * @throws IllegalArgumentException if evaluator trial ports are missing or disagree with the assigned profiles
	 * @throws IOException if the control source or its copies of the trial plugins cannot be staged, the project
	 *   copies of a control trial that passes every assertion cannot be deleted, the host's interrupt record cannot
	 *   be read, a session cannot be interrupted, or the checkpoint fails. A failure starts no further session and
	 *   interrupts the running ones.
	 * @throws InterruptedException if the thread is interrupted while it waits for a session. Every running session
	 *   is interrupted too.
	 */
	public Map<String, PilotResult> run(Contract contract, List<TrialPlugin> plugins, Overlay control,
		Set<Path> excluded, SourceSelection selection, Map<String, PilotResult> stored, RandomGenerator random,
		PilotCheckpoint checkpoint, PilotSlice slice) throws IOException, InterruptedException
	{
		requireThat(plugins, "plugins").isNotNull();
		return runPilot(contract, _ -> plugins, control, excluded, selection, stored, random, checkpoint, slice);
	}

	/**
	 * Runs controls from one staged source with each evaluator's ordered plugin copies.
	 *
	 * @param contract the proposed contract
	 * @param plugins the prepared plugin lists, keyed by the selected evaluator
	 * @param control the common control overlay
	 * @param excluded the project-relative paths that the staged source leaves out
	 * @param selection the union of files that the evaluator trial projects receive
	 * @param stored the previous pilots, by test and evaluator
	 * @param random draws the seed of each test's development stream
	 * @param checkpoint persists the pilots
	 * @param slice the time bound, or null for a full pilot
	 * @return the pilots by test and evaluator, in contract order
	 * @throws IOException if the source or plugins cannot be staged or the pilots cannot be stored
	 * @throws InterruptedException if the thread is interrupted while waiting for a session
	 * @throws IllegalArgumentException if plugin lists are missing or do not match the evaluator scope
	 */
	public Map<String, PilotResult> run(Contract contract, Map<Harness, List<TrialPlugin>> plugins, Overlay control,
		Set<Path> excluded, SourceSelection selection, Map<String, PilotResult> stored, RandomGenerator random,
		PilotCheckpoint checkpoint, PilotSlice slice) throws IOException, InterruptedException
	{
		Set<Harness> scope = contract.profiles().byEvaluator().keySet();
		if (scope.isEmpty() || !scope.equals(plugins.keySet()))
			throw new IllegalArgumentException("Provide trial plugin lists for the evaluator scope " + scope);
		return runPilot(contract, plugins::get, control, excluded, selection, stored, random, checkpoint, slice);
	}

	/**
	 * Runs or resumes the controls for each selected evaluator and test whose pilot remains pending.
	 *
	 * @param contract the proposed contract
	 * @param pluginSelection selects the prepared plugins for an evaluator
	 * @param control the common control overlay
	 * @param excluded the project-relative paths that the staged source leaves out
	 * @param selection the project files that trial projects receive
	 * @param stored the previous pilots
	 * @param random draws the seed of each test's development stream
	 * @param checkpoint persists the pilots
	 * @param slice the time bound, or null for a full pilot
	 * @return the pilots in contract order
	 * @throws IOException if the source or plugins cannot be staged or the pilots cannot be stored
	 * @throws InterruptedException if waiting for a session is interrupted
	 */
	private Map<String, PilotResult> runPilot(Contract contract, Function<Harness, List<TrialPlugin>> pluginSelection,
		Overlay control, Set<Path> excluded, SourceSelection selection, Map<String, PilotResult> stored,
		RandomGenerator random, PilotCheckpoint checkpoint, PilotSlice slice) throws IOException, InterruptedException
	{
		requireThat(checkpoint, "checkpoint").isNotNull();
		requireThat(contract, "contract").isNotNull();
		requireThat(control, "control").isNotNull();
		requireThat(excluded, "excluded").isNotNull();
		requireThat(selection, "selection").isNotNull();
		requireThat(stored, "stored").isNotNull();
		requireThat(random, "random").isNotNull();
		Map<Harness, TrialProfiles> scopes = contract.profiles().byEvaluator();
		boolean shared = scopes.size() > 1;
		if ((shared || !evaluators.isEmpty()) && !evaluators.keySet().equals(scopes.keySet()))
			throw new IllegalArgumentException("Provide evaluator trial ports for " + scopes.keySet());
		List<TrialProfiles> localProfiles = List.copyOf(scopes.values());
		if (localProfiles.isEmpty())
			localProfiles = List.of(contract.profiles());
		Map<String, PilotResult> pilots = new LinkedHashMap<>();
		Set<String> matching = new HashSet<>();
		List<Pilot> pending = new ArrayList<>();
		// Keyed by files rather than the overlay, so that a pilot still applies when confirmation records the fork
		Sha256 files = control.snapshot().identity();
		for (PromptTest test : contract.tests())
		{
			if (contract.split().testCases(test, Part.DEVELOPMENT).isEmpty())
				continue;
			for (TrialProfiles local : localProfiles)
			{
				if (local.cellsOf(test.id()).isEmpty())
					continue;
				Optional<Cell> selected = pilotCell(local, test.id());
				if (selected.isEmpty())
					continue;
				Cell cell = selected.orElseThrow();
				List<TrialPlugin> plugins = List.copyOf(pluginSelection.apply(cell.evaluator().orElse(null)));
				PilotConditions conditions = conditionsFor(contract, test, cell, shared, files, selection, plugins);
				String key = PilotResult.key(test.id(), conditions.evaluator());
				PilotResult earlier = stored.get(key);
				if (earlier != null && conditions.matches(earlier))
				{
					matching.add(key);
					if (earlier.appliesTo(conditions.definition(), cell.id(), conditions.control()))
					{
						pilots.put(key, earlier);
						continue;
					}
				}
				else
					earlier = null;
				pending.add(preparePilot(test, boundaryClasses(contract, test), trialCount(contract, test), conditions, earlier,
					random));
				// Reserves the test and evaluator's place in contract order
				pilots.put(key, null);
			}
		}
		discardObsolete(stored, matching, scopes);
		if (pending.stream().anyMatch(pilot -> pilot.paused))
		{
			for (Pilot pilot : pending)
			{
				if (!pilot.paused)
					pilot.stopReason = UNFINISHED;
				pilots.put(pilot.key(), pilot.result());
			}
			return pilots;
		}
		Set<Path> selectedRoots = new HashSet<>(selection.roots());
		selectedRoots.add(target);
		SourceSelection controlSelection = new SourceSelection(selectedRoots, selection.names(), selection.patterns());
		Set<Path> controlExcluded = new HashSet<>(excluded);
		controlExcluded.remove(target);
		Path source = workspace.stage(control, controlExcluded, controlSelection);
		StagedTarget.read(source, control.snapshot(), target);
		if (!pending.isEmpty())
		{
			Map<Optional<Harness>, List<TrialPluginInput>> directories = new HashMap<>();
			for (Pilot pilot : pending)
			{
				Optional<Harness> evaluator = pilot.conditions.cell().evaluator();
				if (!directories.containsKey(evaluator))
				{
					directories.put(evaluator, TrialPlugins.of(evaluator.orElse(null), pilot.conditions.plugins(),
						contract, workspace, source, true, json));
				}
			}
			new Run(contract, source, control.snapshot(), directories, pending, pilots, checkpoint, slice).run();
		}
		for (Pilot pilot : pending)
			pilots.put(pilot.key(), pilot.result());
		return pilots;
	}

	/**
	 * Builds the identities used both to reuse a stored pilot and to confirm its proposal.
	 *
	 * @param contract the proposal's contract
	 * @param test the prompt test
	 * @param cell the selected pilot cell
	 * @param shared whether the persisted pilot key includes the evaluator
	 * @param files the control snapshot's identity
	 * @param selection the files the control trials receive
	 * @param plugins the trial plugins in installation order
	 * @return the current control conditions
	 */
	private PilotConditions conditionsFor(Contract contract, PromptTest test, Cell cell, boolean shared, Sha256 files,
		SourceSelection selection, List<TrialPlugin> plugins)
	{
		Harness evaluator = cell.evaluator().orElse(null);
		Sha256 definition = Sha256.of(schema.toJson(contract, test, evaluator).getBytes(UTF_8));
		SessionPort trials = trialSessions(evaluator);
		Harness pilotEvaluator = null;
		if (shared)
			pilotEvaluator = evaluator;
		return new PilotConditions(cell, pilotEvaluator, trials, definition,
			controlIdentity(files, selection, trials, evaluator, plugins, test.hasCheckProgram()), plugins);
	}

	/**
	 * Prepares a pilot, keeping its draws and receipts when its previous conditions still apply.
	 *
	 * @param test the prompt test
	 * @param classes the boundary classes that the pilot samples
	 * @param trialCount the number of trials that the pilot draws
	 * @param conditions the current conditions of its control trials
	 * @param earlier the matching prior pilot, or {@code null} if there is none
	 * @param random supplies seeds for new pilots
	 * @return the prepared pilot
	 */
	private static Pilot preparePilot(PromptTest test, List<String> classes, int trialCount,
		PilotConditions conditions, PilotResult earlier, RandomGenerator random)
	{
		if (earlier == null)
			return new Pilot(test, classes, trialCount, conditions, random.nextLong(), List.of(), List.of(), Map.of());
		Pilot pilot = new Pilot(test, classes, trialCount, conditions, earlier.seed(), earlier.trials(),
			earlier.checkProgramProblems(), earlier.pendingTrials());
		pilot.stopReason = retainedPause(earlier);
		pilot.paused = pilot.stopReason != null;
		return pilot;
	}

	/**
	 * Selects the trial sessions, or the owner's broker for an unassigned or removed evaluator.
	 *
	 * @param evaluator the assigned evaluator, or {@code null} for a legacy pilot
	 * @return the session port
	 */
	private SessionPort trialSessions(Harness evaluator)
	{
		if (evaluator == null)
			return sessions;
		return evaluators.getOrDefault(evaluator, sessions);
	}

	/**
	 * Grants another budget to exhausted draws after the user chooses retry. The caller persists this reset before
	 * recording the campaign's choice, so either write can be interrupted without losing the choice or its effect.
	 *
	 * @param pilots the persisted pilot results
	 * @return the results with exhausted draw budgets cleared and all decided trials kept
	 */
	public static Map<String, PilotResult> retryPaused(Map<String, PilotResult> pilots)
	{
		Map<String, PilotResult> reset = new LinkedHashMap<>(pilots);
		reset.replaceAll((_, pilot) ->
		{
			Map<Integer, PilotTrialRecovery> recovery = new LinkedHashMap<>(pilot.pendingTrials());
			boolean resetPositions = recovery.values().removeIf(GuardPilot::exhausted);
			if (!pilot.paused() && !resetPositions)
				return pilot;
			return new PilotResult(pilot.testId(), pilot.definition(), pilot.cellId(), pilot.control(), pilot.seed(),
				pilot.trials(), pilot.failedTrials(), pilot.checkProgramProblems(), UNFINISHED, false, recovery,
				pilot.evaluator(), pilot.trialCount());
		});
		return reset;
	}

	/**
	 * Recognizes an exhausted draw before admitting any work, including a checkpoint that precedes the pause report.
	 *
	 * @param pilot the previous result for the same pilot
	 * @return the pause reason, or {@code null} if its trials can continue
	 */
	private static String retainedPause(PilotResult pilot)
	{
		if (pilot.paused())
			return pilot.stopReason();
		return pilot.pendingTrials().entrySet().stream().filter(entry -> exhausted(entry.getValue())).
			min(Map.Entry.comparingByKey()).map(entry -> exhaustionReason(pilot.key(), entry.getKey(),
				entry.getValue().infrastructureAttempts())).orElse(null);
	}

	/**
	 * Tests whether a draw has no unsettled launch and has spent its current budget.
	 *
	 * @param trial the draw's saved recovery state
	 * @return whether the draw requires an explicit retry
	 */
	private static boolean exhausted(PilotTrialRecovery trial)
	{
		return trial.requestId().isEmpty() &&
			trial.infrastructureAttempts().size() >= Campaign.INFRASTRUCTURE_FAILURE_LIMIT;
	}

	/**
	 * Describes the exhausted draw and every prior failure in its current budget.
	 *
	 * @param test     the test's identifier
	 * @param position the zero-based draw position
	 * @param attempts why the attempts ended
	 * @return the reason the pilot paused
	 */
	private static String exhaustionReason(String test, int position, List<String> attempts)
	{
		StringBuilder reason = new StringBuilder(128).append("Pilot trial ").append(position + 1).
			append(" of `").append(test).append("` ended without an outcome ").append(attempts.size()).append(" times:");
		for (int i = 0; i < attempts.size(); ++i)
		{
			if (i > 0)
				reason.append(';');
			reason.append(" (").append(i + 1).append(") ").append(attempts.get(i));
		}
		return reason.toString();
	}

	/**
	 * Settles old launches whose pilot no longer describes the proposed test, cell or source.
	 *
	 * @param stored the previous pilots
	 * @param matching the keys that will retain the previous pilot
	 * @param scopes the selected evaluator profiles
	 * @throws IOException if an obsolete launch cannot be settled
	 */
	private void discardObsolete(Map<String, PilotResult> stored, Set<String> matching,
		Map<Harness, TrialProfiles> scopes) throws IOException
	{
		for (PilotResult previous : stored.values())
		{
			if (matching.contains(previous.key()))
				continue;
			Harness evaluator = previous.evaluator();
			if (evaluator == null && scopes.size() == 1)
				evaluator = scopes.keySet().iterator().next();
			SessionPort trials = trialSessions(evaluator);
			for (PilotTrialRecovery trial : previous.pendingTrials().values())
			{
				if (trial.requestId().isPresent())
					discard(trials, observe(trials, trial.requestId().orElseThrow()));
			}
		}
	}

	/**
	 * Gets the state of an original launch while keeping its request ID until the broker settles it.
	 *
	 * @param trials the session port of the original launch
	 * @param requestId the original launch
	 * @return the broker's state
	 * @throws IOException if the lookup fails or the launch is still live
	 */
	private static RetainedTrialResponse observe(SessionPort trials, UUID requestId) throws IOException
	{
		RetainedTrialResponse response = trials.retainedTrial(RetainedTrialRequest.status(requestId));
		if (!requestId.equals(response.requestId()))
			throw new IOException("The broker answered for a different pilot launch: " + response.requestId());
		if (response.status() == Status.BUSY)
			throw new IOException("The pilot launch " + requestId + " is still settling. Run the command again.");
		if (response.status() == Status.DISCARDED)
			throw new IOException("The broker reported a discard while inspecting pilot launch " + requestId);
		return response;
	}

	/**
	 * Requires a broker acknowledgement that the observed original launch cannot run again.
	 *
	 * @param trials the session port of the original launch
	 * @param observed the broker's last observation of the original launch
	 * @throws IOException if the broker does not confirm that the launch is gone
	 */
	private static void discard(SessionPort trials, RetainedTrialResponse observed) throws IOException
	{
		// A stuck slot stays as it is for the user to remove, and its launch cannot start again
		if (observed.status() == Status.ABSENT || observed.status() == Status.STUCK)
			return;
		UUID requestId = observed.requestId();
		RetainedTrialResponse answer = trials.retainedTrial(RetainedTrialRequest.discard(requestId, observed.identity()));
		if (!requestId.equals(answer.requestId()) || (answer.status() != Status.DISCARDED &&
			answer.status() != Status.ABSENT))
		{
			throw new IOException("The broker has not confirmed discard of pilot launch " + requestId +
				". Run the command again.");
		}
	}

	/**
	 * Returns why the pilots paused.
	 *
	 * @param pilots the pilots by test and evaluator
	 * @return the reason, which names the logical trial whose attempts ended without an outcome; empty if no pilot paused
	 * @throws NullPointerException if {@code pilots} is null
	 */
	public static Optional<String> pauseReason(Map<String, PilotResult> pilots)
	{
		requireThat(pilots, "pilots").isNotNull();
		for (PilotResult pilot : pilots.values())
		{
			if (pilot.paused())
				return Optional.of(pilot.stopReason());
		}
		return Optional.empty();
	}

	/**
	 * Indicates whether the pilots stopped because the slice that ran them ended: a later command continues them.
	 *
	 * @param pilots the pilots by test and evaluator
	 * @return {@code true} if a pilot stopped because its slice ended before it ran every trial
	 * @throws NullPointerException if {@code pilots} is null
	 */
	public static boolean endedBySlice(Map<String, PilotResult> pilots)
	{
		requireThat(pilots, "pilots").isNotNull();
		for (PilotResult pilot : pilots.values())
		{
			if (SLICE_ENDED.equals(pilot.stopReason()))
				return true;
		}
		return false;
	}

	/**
	 * Indicates whether the stored pilots include a checkpoint left while trials remained to run.
	 *
	 * @param pilots the pilots by test and evaluator
	 * @return whether a checkpoint was saved during execution or at the end of a slice
	 * @throws NullPointerException if {@code pilots} is null
	 */
	public static boolean hasUnfinishedCheckpoint(Map<String, PilotResult> pilots)
	{
		return endedBySlice(pilots) || pilots.values().stream().anyMatch(pilot -> UNFINISHED.equals(pilot.stopReason()));
	}

	/**
	 * The conditions that distinguish the results of control trials for one evaluator.
	 *
	 * @param cell the cell in which the trials run
	 * @param evaluator the evaluator for a shared pilot, or {@code null} for a singleton
	 * @param trialSessions starts and observes the evaluator's trials
	 * @param definition the identity of the prompt test
	 * @param control the identity of the control source and execution conditions
	 * @param plugins the plugins installed by these control trials
	 */
	private record PilotConditions(Cell cell, Harness evaluator, SessionPort trialSessions, Sha256 definition,
		Sha256 control, List<TrialPlugin> plugins)
	{
		/**
		 * Checks that a stored result describes these controls.
		 *
		 * @param result the stored pilot
		 * @return whether it ran under these conditions
		 */
		private boolean matches(PilotResult result)
		{
			return result.ranIn(definition, cell.id(), control);
		}
	}

	/**
	 * The pilot of one prompt test while it runs. The scheduling thread owns all state except the synchronized
	 * recovery state and snapshots, which the turn observers also access.
	 */
	private static final class Pilot
	{
		private final Object stateLock = new Object();
		private final PromptTest test;
		/**
		 * The boundary classes that the first draws sample, {@value #TRIALS_PER_CLASS} trials each.
		 */
		private final List<String> classes;
		private final int trialCount;
		private final PilotConditions conditions;
		private final long seed;
		/**
		 * The record of each decided trial, by draw position; {@code null} for a trial that is undecided.
		 */
		private final PilotTrial[] trials;
		/**
		 * Recovery and earlier infrastructure attempts of each undecided trial, by draw position.
		 */
		private final Map<Integer, PilotTrialRecovery> recovery = new LinkedHashMap<>();
		private final List<String> failedTrials = new ArrayList<>();
		/**
		 * The check-program problems of the trials that they kept from a decision, in the order that the trials ended.
		 */
		private final List<CheckProgramProblem> checkProgramProblems = new ArrayList<>();
		private int finished;
		private String stopReason;
		private boolean paused;

		/**
		 * Creates a pilot.
		 *
		 * @param test         the prompt test
		 * @param classes      the boundary classes that the pilot samples
		 * @param trialCount   the number of trials that the pilot draws
		 * @param conditions   the current conditions of the control trials
		 * @param seed         the seed of the test's development stream
		 * @param kept         the trials that an earlier run of this pilot decided, which this pilot keeps
		 * @param keptProblems the check-program problems that an earlier run of this pilot found, which this pilot
		 *   keeps without running their trials again
		 * @param undecided    the earlier pilot's undecided trials, by draw position
		 */
		Pilot(PromptTest test, List<String> classes, int trialCount, PilotConditions conditions, long seed,
			List<PilotTrial> kept, List<CheckProgramProblem> keptProblems, Map<Integer, PilotTrialRecovery> undecided)
		{
			this.test = test;
			this.classes = classes;
			this.trialCount = trialCount;
			this.trials = new PilotTrial[trialCount];
			this.conditions = conditions;
			this.seed = seed;
			recovery.putAll(undecided);
			for (PilotTrial trial : kept)
			{
				trials[trial.position()] = trial;
				++finished;
			}
			checkProgramProblems.addAll(keptProblems);
			finished += (int) keptProblems.stream().mapToInt(CheckProgramProblem::position).distinct().count();
		}

		/**
		 * Draws the test case of a trial. The first draws, {@value #TRIALS_PER_CLASS} for each boundary class of the
		 * test, take the classes in turn, so that every class has {@value #TRIALS_PER_CLASS} trials whatever the stream
		 * draws; the others draw from every development test case.
		 *
		 * @param generator the contract's generator
		 * @param position the trial's draw position
		 * @param stream the test's development stream
		 * @return the drawn test case
		 */
		DrawnTestCase draw(Generator generator, int position, DrawStream stream)
		{
			String cellId = conditions.cell().id();
			if (position < TRIALS_PER_CLASS * classes.size())
				return generator.drawOfClass(cellId, classes.get(position % classes.size()), stream);
			return generator.draw(cellId, stream);
		}

		/**
		 * Returns the key of this test and evaluator in a pilot checkpoint.
		 *
		 * @return the checkpoint key
		 */
		String key()
		{
			return PilotResult.key(test.id(), conditions.evaluator());
		}

		/**
		 * Indicates whether a trial was decided.
		 *
		 * @param position the trial's draw position
		 * @return {@code true} if the trial has a decision
		 */
		boolean isDecided(int position)
		{
			return trials[position] != null;
		}

		/**
		 * Indicates whether a trial ended with a check-program problem.
		 *
		 * @param position the trial's draw position
		 * @return {@code true} if a check program of the trial disagreed with itself or took too long
		 */
		boolean hasProblem(int position)
		{
			synchronized (stateLock)
			{
				return checkProgramProblems.stream().anyMatch(problem -> problem.position() == position);
			}
		}

		/**
		 * Records a decided trial.
		 *
		 * @param position the trial's draw position
		 * @param testCase the trial's test case
		 * @param assertions whether the trial passed each assertion that it decided, by assertion identifier
		 * @param record the judge's rationale, or the record of the failed built-in tools
		 */
		void decided(int position, DrawnTestCase testCase, Map<String, Boolean> assertions, String record)
		{
			synchronized (stateLock)
			{
				trials[position] = new PilotTrial(position, testCase.testCase().describedValues(),
					testCase.testCase().expectedOutcome(), testCase.testCase().boundaryClass(), assertions, record,
					recovery(position).infrastructureAttempts());
				recovery.remove(position);
				++finished;
			}
		}

		/**
		 * Records a trial attempt that ended without an outcome.
		 *
		 * @param position the trial's draw position
		 * @param reason why the attempt ended without an outcome
		 * @return the attempts that ended without an outcome so far, including this one
		 */
		List<String> infrastructureAttempt(int position, String reason)
		{
			synchronized (stateLock)
			{
				List<String> attempts = new ArrayList<>(recovery(position).infrastructureAttempts());
				attempts.add(reason);
				recovery.put(position, new PilotTrialRecovery(Optional.empty(), NO_PROGRESS, attempts, Optional.empty()));
				return attempts;
			}
		}

		/**
		 * Returns the recovery state of a trial.
		 *
		 * @param position the trial's draw position
		 * @return the stored state, or empty recovery if no launch or attempt has been recorded
		 */
		PilotTrialRecovery recovery(int position)
		{
			synchronized (stateLock)
			{
				return recovery.getOrDefault(position, NO_RECOVERY);
			}
		}

		/**
		 * Records a fresh launch without changing the trial's earlier infrastructure attempts.
		 *
		 * @param position the trial's draw position
		 * @param requestId the fresh launch's original request identifier
		 */
		void begin(int position, UUID requestId)
		{
			synchronized (stateLock)
			{
				recovery.put(position, new PilotTrialRecovery(Optional.of(requestId), NO_PROGRESS,
					recovery(position).infrastructureAttempts(), Optional.empty()));
			}
		}

		/**
		 * Records an observer decision for the current launch.
		 *
		 * @param position the trial's draw position
		 * @param requestId the original request identifier
		 * @param progress the completed-turn observer decision
		 * @throws IOException if the launch has been retired, has been cancelled or the progress is out of order
		 */
		void receipt(int position, UUID requestId, TurnObserver.Progress progress) throws IOException
		{
			synchronized (stateLock)
			{
				PilotTrialRecovery current = recovery(position);
				if (!current.requestId().equals(Optional.of(requestId)) || current.discard().isPresent())
					throw new IOException("The pilot launch " + requestId + " is no longer accepting turns");
				if (progress.completedTurn() != current.progress().completedTurn() + 1)
					throw new IOException("Observer progress for pilot launch " + requestId + " is out of order");
				recovery.put(position, new PilotTrialRecovery(current.requestId(), progress,
					current.infrastructureAttempts(), current.discard()));
			}
		}

		/**
		 * Records an intention to retire the original launch before requesting cancellation or discard.
		 *
		 * @param position the trial's draw position
		 * @param discard why this launch will be retired
		 * @return whether an unresolved original launch was recorded
		 */
		boolean markDiscard(int position, Discard discard)
		{
			synchronized (stateLock)
			{
				PilotTrialRecovery current = recovery(position);
				if (current.requestId().isEmpty())
					return false;
				recovery.put(position, new PilotTrialRecovery(current.requestId(), current.progress(),
					current.infrastructureAttempts(), Optional.of(discard)));
				return true;
			}
		}

		/**
		 * Clears a settled launch without charging an infrastructure attempt.
		 *
		 * @param position the trial's draw position
		 */
		void clearLaunch(int position)
		{
			synchronized (stateLock)
			{
				List<String> attempts = recovery(position).infrastructureAttempts();
				if (attempts.isEmpty())
					recovery.remove(position);
				else
					recovery.put(position, new PilotTrialRecovery(Optional.empty(), NO_PROGRESS, attempts, Optional.empty()));
			}
		}

		/**
		 * Records a trial that ended without a decision.
		 *
		 * @param position the trial's draw position
		 * @param reason why the trial ended without a decision
		 */
		void failed(int position, String reason)
		{
			synchronized (stateLock)
			{
				recovery.remove(position);
				failedTrials.add(reason);
				++finished;
			}
		}

		/**
		 * Records a trial that its check programs kept from a decision.
		 *
		 * @param position the trial's draw position
		 * @param problems the trial's check-program problems, at least one
		 */
		void problems(int position, List<CheckProgramProblem> problems)
		{
			synchronized (stateLock)
			{
				recovery.remove(position);
				checkProgramProblems.addAll(problems);
				++finished;
			}
		}

		/**
		 * Returns the pilot's result.
		 *
		 * @return the result, whose trials are in draw order
		 */
		PilotResult result()
		{
			return result(stopReason, paused);
		}

		/**
		 * Returns the pilot's result as it stands while the pilot runs: a pilot with trials left to run says that it did
		 * not finish, so that a later run keeps the trials that it decided and runs the others.
		 *
		 * @return the result, whose trials are in draw order
		 */
		PilotResult interim()
		{
			synchronized (stateLock)
			{
				if (finished >= trialCount)
					return result(null, false);
				return result(UNFINISHED, false);
			}
		}

		/**
		 * Returns the pilot's result with a reason why it stopped.
		 *
		 * @param reason why the pilot stopped before running every trial, or {@code null}
		 * @param pause {@code true} if the pilot paused for the user's choice
		 * @return the result, whose trials are in draw order
		 */
		private PilotResult result(String reason, boolean pause)
		{
			List<PilotTrial> decided = new ArrayList<>();
			for (PilotTrial trial : trials)
			{
				if (trial != null)
					decided.add(trial);
			}
			return new PilotResult(test.id(), conditions.definition(), conditions.cell().id(), conditions.control(), seed,
				decided, failedTrials, checkProgramProblems, reason, pause, recovery, conditions.evaluator(), trialCount);
		}
	}

	/**
	 * Returns the identity of the conditions that control trials run in: the control source's files, the files of it
	 * that the trials' projects hold, the session environment version and the judge version.
	 *
	 * @param files the identity of the control source's files
	 * @param selection the files of the control source that the staged control source holds
	 * @param trials the trial sessions of the selected evaluator
	 * @param evaluator the assigned evaluator, or null for an unassigned singleton
	 * @param plugins the selected trial plugins
	 * @param usesCheckProgram whether the test uses a check program
	 * @return the identity
	 */
	private Sha256 controlIdentity(Sha256 files, SourceSelection selection, SessionPort trials, Harness evaluator,
		List<TrialPlugin> plugins, boolean usesCheckProgram)
	{
		StringBuilder conditions = new StringBuilder(files.hex()).append("\nselection\n").append(selection.describe()).
			append("\nsession environment version ").append(trials.sessionEnvironmentVersion()).
			append("\njudge version ").append(sessions.judgeVersion());
		if (evaluator != null && !plugins.isEmpty())
		{
			conditions.append("\ntrial plugins ").append(new TreeMap<>(MeasurementIdentity.ofPlugins(plugins, Map.of()).
				values()));
		}
		if (usesCheckProgram)
			conditions.append("\ncheck program version ").append(checkPrograms.version());
		return Sha256.of(conditions.toString().getBytes(UTF_8));
	}

	/**
	 * A session that the pilot starts.
	 */
	private sealed interface Job
	{
		/**
		 * Returns the pilot that the session belongs to.
		 *
		 * @return the pilot
		 */
		Pilot pilot();

		/**
		 * Returns the draw position of the trial that the session runs or judges.
		 *
		 * @return the position
		 */
		int position();

		/**
		 * Returns the test case of the trial that the session runs or judges.
		 *
		 * @return the test case
		 */
		DrawnTestCase testCase();
	}

	/**
	 * A control trial.
	 *
	 * @param pilot the pilot that the trial belongs to
	 * @param position the trial's draw position
	 * @param testCase the trial's test case
	 * @param claim the prior launch's prefix to resume, or empty for a fresh launch
	 */
	private record TrialJob(Pilot pilot, int position, DrawnTestCase testCase, Optional<RetainedTrialClaim> claim)
		implements Job
	{
	}

	/**
	 * A judge of a completed control trial.
	 *
	 * @param pilot the pilot that the trial belongs to
	 * @param position the trial's draw position
	 * @param testCase the trial's test case
	 * @param trial the trial session's result
	 * @param judged the assertions that the judge session decides: those of {@code assertions} without a stored
	 *   verdict
	 * @param results the results of the trial's built-in tools
	 * @param section the rendered trial evidence, or empty if the judge template does not name it
	 * @param attempt the number of this judge attempt, starting at 1
	 * @param assertions every judged assertion of the trial of the session's evidence scope, in order
	 * @param keys the key of each of {@code assertions} by its identifier, or empty for assertions of the
	 *   {@code actions} scope, whose verdicts are never recorded
	 * @param stored the stored verdicts of the other assertions, by assertion identifier
	 * @param scopes the verdicts of the trial's evidence scopes, which every scope's session shares
	 */
	private record JudgeJob(Pilot pilot, int position, DrawnTestCase testCase, SessionResult.Completed trial,
		List<JudgedAssertion> judged, List<BuiltInToolResult> results, String section, int attempt,
		List<JudgedAssertion> assertions, Map<String, VerdictKey> keys, Map<String, VerdictRecord> stored,
		PilotScopes scopes)
		implements Job
	{
		/**
		 * Returns the next attempt of the judge.
		 *
		 * @return the attempt
		 */
		JudgeJob retry()
		{
			return new JudgeJob(pilot, position, testCase, trial, judged, results, section, attempt + 1, assertions,
				keys, stored, scopes);
		}

		/**
		 * Returns the evidence scope of the assertions that the session decides.
		 *
		 * @return the scope
		 */
		EvidenceScope scope()
		{
			return assertions.getFirst().scope();
		}

		/**
		 * Returns the description of the trial that its stored verdicts name.
		 *
		 * @return the description
		 */
		String trialName()
		{
			String name = "pilot trial " + (position + 1) + " of test " + pilot.test.id();
			Harness evaluator = pilot.conditions.evaluator();
			if (evaluator == null)
				return name;
			return name + ", evaluator " + EnumNames.lowerCase(evaluator);
		}
	}

	/**
	 * The verdicts of a control trial's evidence scopes, each of which its own judge session decides. The trial is
	 * decided once every scope is.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe. The pilot's thread is its only user.
	 */
	private static final class PilotScopes
	{
		private final List<JudgedAssertion> assertions;
		private final Set<EvidenceScope> scopes = EnumSet.noneOf(EvidenceScope.class);
		private final Map<EvidenceScope, Judgment> decided = new EnumMap<>(EvidenceScope.class);
		private boolean abandoned;

		/**
		 * Creates the verdicts of a trial whose scopes are not decided yet.
		 *
		 * @param assertions every judged assertion of the trial, in order
		 */
		PilotScopes(List<JudgedAssertion> assertions)
		{
			this.assertions = List.copyOf(assertions);
			for (JudgedAssertion assertion : assertions)
				scopes.add(assertion.scope());
		}

		/**
		 * Returns every judged assertion of the trial.
		 *
		 * @return the assertions, in order
		 */
		List<JudgedAssertion> assertions()
		{
			return assertions;
		}

		/**
		 * Records the verdict of one scope.
		 *
		 * @param scope    the scope
		 * @param judgment the verdict of the scope's assertions
		 * @return the trial's verdict once every scope is decided, the {@code response} scope's first, or empty
		 */
		Optional<Judgment> decide(EvidenceScope scope, Judgment judgment)
		{
			decided.put(scope, judgment);
			if (!decided.keySet().equals(scopes))
				return Optional.empty();
			return Optional.of(TrialJudgments.merged(List.copyOf(decided.values())));
		}

		/**
		 * Drops the trial's verdicts because a scope's judge could not decide it, so that the trial runs again and the
		 * other scopes' sessions are ignored.
		 */
		void abandon()
		{
			abandoned = true;
		}

		/**
		 * Indicates whether a scope's judge failure dropped the trial's verdicts.
		 *
		 * @return {@code true} if they are abandoned
		 */
		boolean isAbandoned()
		{
			return abandoned;
		}
	}

	/**
	 * The end of a session.
	 *
	 * @param job the session's work
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null}
	 */
	private record Ended(Job job, SessionResult result, Throwable failure)
	{
	}

	/**
	 * Runs the trials and judges of the pending pilots, at most {@link #ceiling} sessions at a time.
	 */
	private final class Run
	{
		private final Object checkpointLock = new Object();
		private final Contract contract;
		private final Path source;
		private final FileInventory inventory;
		private final Map<Optional<Harness>, List<TrialPluginInput>> plugins;
		private final List<Pilot> pilots;
		/**
		 * The pilots that every checkpoint holds, in contract order: a stored pilot that still applies stands for its
		 * test, and a pilot that runs has a {@code null} place until the checkpoint replaces it.
		 */
		private final Map<String, PilotResult> places;
		private final PilotCheckpoint checkpoint;
		/**
		 * The slice's time bound, or {@code null} if the pilot runs until every trial ends or the pilot stops.
		 */
		private final PilotSlice slice;
		private final Deque<Job> queue = new ArrayDeque<>();
		private final BlockingQueue<Ended> events = new LinkedBlockingQueue<>();
		private final Map<Job, SessionHandle> inFlight = new HashMap<>();
		/**
		 * When each trial session that runs started, in a slice.
		 */
		private final Map<Job, Instant> startedAt = new HashMap<>();
		/**
		 * The durations of the trials that completed in the slice, which its admission rule uses.
		 */
		private final SliceEvidence evidence = new SliceEvidence();
		private Instant cancellationEnd;
		private String stopReason;
		/**
		 * {@code true} if {@link #stopReason} is a pause for the user's choice to retry or to stop.
		 */
		private boolean paused;
		/**
		 * The failure to delete a passing trial's project copies, which ends the run once its running sessions end;
		 * {@code null} if every deletion succeeded.
		 */
		private IOException deletionFailure;
		/**
		 * The first checkpoint failure on a session's turn observer, reported to the pilot thread.
		 */
		private volatile IOException observerCheckpointFailure;

		/**
		 * Creates a run.
		 *
		 * @param contract the proposed contract
		 * @param source the staged control source
		 * @param inventory the control snapshot
		 * @param plugins the plugin directories that each evaluator's trials install
		 * @param pilots the pilots to run, in contract order
		 * @param places the pilots by test and evaluator in contract order, with {@code null} for pending pilots
		 * @param checkpoint keeps the pilots' in-progress launches, observer decisions and trial results
		 * @param slice the slice's time bound, or {@code null} if the pilot runs until every trial ends or the pilot
		 *   stops
		 */
		Run(Contract contract, Path source, FileInventory inventory, Map<Optional<Harness>, List<TrialPluginInput>> plugins,
			List<Pilot> pilots, Map<String, PilotResult> places, PilotCheckpoint checkpoint, PilotSlice slice)
		{
			this.contract = contract;
			this.source = source;
			this.inventory = inventory;
			this.plugins = plugins;
			this.pilots = pilots;
			this.places = places;
			this.checkpoint = checkpoint;
			this.slice = slice;
		}

		/**
		 * Draws each pilot's test cases, then runs their sessions until every trial ends or the pilot stops.
		 *
		 * @throws IOException if a passing trial's project copies cannot be deleted. No further session starts, and the
		 *   failure is thrown once the running sessions end. If the checkpoint fails, the running sessions are
		 *   interrupted and its failure is thrown. In a slice, a running session that cannot be interrupted when the
		 *   host interrupts the command or the grace period ends, or the host's interrupt record that cannot be read,
		 *   also fails the run.
		 * @throws InterruptedException if the thread is interrupted while it waits for a session. Every running session
		 *   is interrupted too.
		 */
		void run() throws IOException, InterruptedException
		{
			Generator generator = new Generator(contract);
			for (Pilot pilot : pilots)
			{
				DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, pilot.seed);
				for (int position = 0; position < pilot.trialCount; ++position)
				{
					// A kept trial still draws, so that every later trial keeps its test case
					DrawnTestCase drawn = generator.markTargeted(pilot.draw(generator, position, stream));
					if (!pilot.isDecided(position) && !pilot.hasProblem(position))
						queue.add(new TrialJob(pilot, position, drawn, Optional.empty()));
				}
			}
			try
			{
				while (true)
				{
					int decidedBefore = endedTrials();
					startSessions();
					if (endedTrials() > decidedBefore)
						save();
					if (inFlight.isEmpty())
						break;
					Ended ended = awaitEnd();
					// The slice ended, and the sessions that still ran were cancelled
					if (ended == null)
						break;
					inFlight.remove(ended.job());
					checkObserverFailure();
					recordDuration(ended);
					int endedBefore = endedTrials();
					switch (ended.job())
					{
						case TrialJob trial -> trialEnded(trial, ended.result(), ended.failure());
						case JudgeJob judge -> judgeEnded(judge, ended.result(), ended.failure());
					}
					if (endedTrials() > endedBefore)
						save();
				}
			}
			catch (InterruptedException e)
			{
				try
				{
					checkpointCancellation(HOST_INTERRUPTED);
				}
				catch (IOException suppressed)
				{
					e.addSuppressed(suppressed);
				}
				interruptSessions(e);
				throw e;
			}
			catch (IOException e)
			{
				interruptSessions(e);
				throw e;
			}
			if (deletionFailure != null)
				throw deletionFailure;
			// Trials that the slice's admission rule left in the queue run in a later command
			if (stopReason == null && !queue.isEmpty())
				stop(SLICE_ENDED);
			if (stopReason == null)
				return;
			for (Pilot pilot : pilots)
			{
				if (pilot.finished < pilot.trialCount)
				{
					pilot.stopReason = stopReason;
					pilot.paused = paused;
				}
			}
		}

		/**
		 * Interrupts every running session while preserving the cause of the run's failure.
		 *
		 * @param failure the run's failure, which receives any failures to interrupt a session
		 */
		private void interruptSessions(Exception failure)
		{
			for (SessionHandle handle : inFlight.values())
			{
				try
				{
					handle.interrupt();
				}
				catch (IOException e)
				{
					failure.addSuppressed(e);
				}
			}
		}

		/**
		 * Selects the retained prefix for an admitted trial, or settles the original launch before a replacement.
		 *
		 * @param trial the trial about to run
		 * @return the broker's resumable prefix, or empty when a fresh launch is allowed
		 * @throws IOException if the broker cannot settle an earlier launch, or the checkpoint fails
		 */
		private Optional<RetainedTrialClaim> reconcile(TrialJob trial) throws IOException
		{
			Pilot pilot = trial.pilot();
			int position = trial.position();
			PilotTrialRecovery state = pilot.recovery(position);
			if (state.requestId().isEmpty())
			{
				pauseIfExhausted(pilot, position, state.infrastructureAttempts());
				return Optional.empty();
			}
			UUID requestId = state.requestId().orElseThrow();
			SessionPort trials = pilot.conditions.trialSessions();
			RetainedTrialResponse observed = observe(trials, requestId);
			if (observed.status() == Status.READY && state.discard().isEmpty())
			{
				int completed = observed.completedTurns().orElseThrow();
				int remembered = state.progress().completedTurn();
				if (completed <= trial.testCase().turns().size() && (completed == remembered ||
					(completed == remembered + 1 && !state.progress().endedEarly())))
				{
					return Optional.of(new RetainedTrialClaim(requestId, observed.identity().orElseThrow(), completed));
				}
			}
			discard(trials, observed);
			Discard intent = state.discard().orElse(new Discard(
				"The previous pilot launch " + requestId + " did not have a resumable checkpoint", true));
			finishRetirement(trial, intent);
			return Optional.empty();
		}

		/**
		 * Waits for the next session to end. In a slice, the wait also ends when the host interrupts the command or the
		 * grace period ends: the sessions that still run are then interrupted and the pilot stops.
		 *
		 * @return the end of a session, or {@code null} if the pilot cancelled the sessions that still ran
		 * @throws IOException if the host's interrupt record cannot be read, or a session cannot be interrupted
		 * @throws InterruptedException if the thread is interrupted while it waits for a session
		 */
		private Ended awaitEnd() throws IOException, InterruptedException
		{
			if (slice == null)
				return events.take();
			while (true)
			{
				if (slice.settings().background() && cancellationEnd == null &&
					slice.interrupts().isInterruptedAfter(slice.start()))
					cancellationEnd = slice.clock().instant().plus(slice.settings().grace());
				Instant graceEnd = slice.graceEnd();
				if (cancellationEnd != null)
					graceEnd = cancellationEnd;
				Duration untilGraceEnd = Duration.between(slice.clock().instant(), graceEnd);
				Duration wait = slice.settings().pollInterval();
				if (untilGraceEnd.compareTo(wait) < 0)
					wait = untilGraceEnd;
				if (wait.isNegative())
					wait = Duration.ZERO;
				Ended ended = events.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
				if (ended != null)
					return ended;
				if (!slice.settings().background() && slice.interrupts().isInterruptedAfter(slice.start()))
				{
					cancel(HOST_INTERRUPTED);
					return null;
				}
				if (!slice.clock().instant().isBefore(graceEnd))
				{
					String reason = SLICE_ENDED;
					if (slice.settings().background())
						reason = "The user cancelled the background driver";
					cancel(reason);
					return null;
				}
			}
		}

		/**
		 * Interrupts the sessions that still run and stops the pilot. Their trials are not infrastructure attempts,
		 * and they run again as the same logical trials. Every session is interrupted even if interrupting one fails.
		 *
		 * @param reason why the pilot stopped
		 * @throws IOException if a session cannot be interrupted
		 */
		private void cancel(String reason) throws IOException
		{
			stop(reason);
			checkpointCancellation(reason);
			IOException failure = null;
			for (SessionHandle handle : inFlight.values())
			{
				try
				{
					handle.interrupt();
				}
				catch (IOException e)
				{
					if (failure == null)
						failure = e;
					else
						failure.addSuppressed(e);
				}
			}
			inFlight.clear();
			if (failure != null)
				throw failure;
		}

		/**
		 * Records noncharging cancellation intents before the pilot interrupts handles or drops queued judges.
		 *
		 * @param reason why the pilot cancelled the sessions
		 * @throws IOException if those intents cannot be recorded
		 */
		private void checkpointCancellation(String reason) throws IOException
		{
			Discard intent = new Discard(reason, false);
			boolean changed = false;
			for (Job job : inFlight.keySet())
				changed |= job.pilot().markDiscard(job.position(), intent);
			for (Job job : queue)
			{
				if (job instanceof JudgeJob)
					changed |= job.pilot().markDiscard(job.position(), intent);
			}
			if (changed)
				save();
		}

		/**
		 * Records how long a trial session took if it completed, for the slice's admission rule.
		 *
		 * @param ended the end of a session
		 */
		private void recordDuration(Ended ended)
		{
			Instant started = startedAt.remove(ended.job());
			if (started != null && ended.job() instanceof TrialJob && ended.result() instanceof SessionResult.Completed)
				evidence.recordDuration(Duration.between(started, slice.clock().instant()));
		}

		/**
		 * Returns the number of trials that ended, with a decision or without one that the proposal's tools could give.
		 *
		 * @return the number of trials
		 */
		private int endedTrials()
		{
			int ended = 0;
			for (Pilot pilot : pilots)
				ended += pilot.finished;
			return ended;
		}

		/**
		 * Returns the pilots as they stand.
		 *
		 * @return the pilots by test and evaluator, in contract order
		 */
		private Map<String, PilotResult> snapshot()
		{
			Map<String, PilotResult> snapshot = new LinkedHashMap<>(places);
			for (Pilot pilot : pilots)
				snapshot.put(pilot.key(), pilot.interim());
			return snapshot;
		}

		/**
		 * Serializes snapshots from scheduling and turn-observer threads.
		 *
		 * @throws IOException if the checkpoint cannot be written
		 */
		private void save() throws IOException
		{
			synchronized (checkpointLock)
			{
				checkObserverFailure();
				checkpoint.save(snapshot());
			}
		}

		/**
		 * Transfers a checkpoint failure from a turn observer to the pilot thread.
		 *
		 * @throws IOException if an observer could not write its checkpoint
		 */
		private void checkObserverFailure() throws IOException
		{
			IOException failure = observerCheckpointFailure;
			if (failure != null)
				throw failure;
		}

		/**
		 * Records a completed turn before its session can send another turn.
		 *
		 * @param trial the trial that finished the turn
		 * @param requestId the original launch
		 * @param progress the observer's decision
		 * @throws IOException if the launch no longer accepts turns or the checkpoint fails
		 */
		private void saveReceipt(TrialJob trial, UUID requestId, TurnObserver.Progress progress)
			throws IOException
		{
			synchronized (checkpointLock)
			{
				trial.pilot().receipt(trial.position(), requestId, progress);
				checkObserverFailure();
				try
				{
					checkpoint.save(snapshot());
				}
				catch (IOException e)
				{
					observerCheckpointFailure = e;
					throw e;
				}
			}
		}

		/**
		 * Starts queued sessions while capacity is free and the pilot has not stopped. In a slice, a trial starts only if
		 * the slice's admission rule admits it, and a judge starts until the grace period ends.
		 *
		 * @throws IOException if the control source, checkpoint or earlier launch cannot be used
		 */
		private void startSessions() throws IOException
		{
			if (slice != null && slice.settings().background() && cancellationEnd == null &&
				slice.interrupts().isInterruptedAfter(slice.start()))
				cancellationEnd = slice.clock().instant().plus(slice.settings().grace());
			while (stopReason == null && inFlight.size() < ceiling)
			{
				checkObserverFailure();
				Job job = nextAdmitted();
				if (job == null)
					return;
				if (job instanceof TrialJob trial)
				{
					StagedTarget.read(source, inventory, target);
					Optional<RetainedTrialClaim> claim = reconcile(trial);
					if (stopReason != null)
						return;
					job = new TrialJob(trial.pilot(), trial.position(), trial.testCase(), claim);
				}
				else if (job instanceof JudgeJob judging)
				{
					// The trial runs again after another scope's judge failed, so its verdicts are not needed
					if (judging.scopes().isAbandoned())
						continue;
					// A session that ran since the judge was queued may have stored the verdicts that it needs
					Optional<JudgeJob> rest = withStoredVerdicts(judging);
					if (rest.isEmpty())
						continue;
					job = rest.get();
				}
				SessionRequest request = switch (job)
				{
					case TrialJob trial -> trialRequest(trial);
					case JudgeJob judging -> judge.request(contract.judge(), judging.testCase(), judging.trial(),
						judging.judged(), judging.results(), judging.section());
				};
				if (slice != null)
					startedAt.put(job, slice.clock().instant());
				checkObserverFailure();
				SessionPort port = sessions;
				if (job instanceof TrialJob trial)
					port = trial.pilot().conditions.trialSessions();
				SessionHandle handle;
				try
				{
					if (job instanceof TrialJob trial && port.recoversTrials())
					{
						UUID requestId = trial.pilot().recovery(trial.position()).requestId().orElseThrow();
						handle = port.startTrial(request, requestId, trial.claim());
					}
					else
						handle = port.start(request);
				}
				catch (IOException e)
				{
					startedAt.remove(job);
					sessionFailed(job, "The " + roleName(job) + " session could not start: " + Driver.describe(e));
					continue;
				}
				inFlight.put(job, handle);
				Job started = job;
				handle.completion().whenComplete((result, failure) -> events.add(new Ended(started, result, failure)));
			}
		}

		/**
		 * Builds a trial request and checkpoints its original launch before it can be published.
		 *
		 * @param trial the assigned control trial
		 * @return the session request with its observer
		 * @throws IOException if a fresh launch cannot be checkpointed
		 */
		private SessionRequest trialRequest(TrialJob trial) throws IOException
		{
			Pilot pilot = trial.pilot();
			Cell cell = pilot.conditions.cell();
			return new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, trial.testCase().turns(), List.of(),
				pilot.test.timeLimit(), cell.profile(), TestWorkspace.of(pilot.test, trial.testCase().testCase()),
				Optional.empty(), true, plugins.get(cell.evaluator())).
				withTurnObserver(trialObserver(trial));
		}

		/**
		 * Creates the observer with the durable state for the trial's exact launch.
		 *
		 * @param trial the admitted trial
		 * @return the observer
		 * @throws IOException if a fresh launch cannot be checkpointed
		 */
		private TurnObserver trialObserver(TrialJob trial) throws IOException
		{
			Pilot pilot = trial.pilot();
			SessionPort trials = pilot.conditions.trialSessions();
			if (!trials.recoversTrials())
				return EarlyTrialEnd.forTest(pilot.test, trial.testCase().testCase(), trials);
			if (trial.claim().isEmpty())
			{
				pilot.begin(trial.position(), UUID.randomUUID());
				save();
			}
			PilotTrialRecovery state = pilot.recovery(trial.position());
			UUID requestId = state.requestId().orElseThrow();
			return EarlyTrialEnd.forTest(pilot.test, trial.testCase().testCase(), trials, state.progress(),
				progress -> saveReceipt(trial, requestId, progress));
		}

		/**
		 * Removes the first queued session that may start now from the queue.
		 *
		 * @return the session, or {@code null} if the queue holds none: it is empty, or its trials are not admitted
		 */
		private Job nextAdmitted()
		{
			Iterator<Job> candidates = queue.iterator();
			while (candidates.hasNext())
			{
				Job job = candidates.next();
				boolean startable = switch (job)
				{
					// An abandoned judge is dropped at once instead of waiting for a running session
					case JudgeJob judging -> judging.scopes().isAbandoned() || !waitsForRunningJudge(judging);
					case TrialJob trial -> slice == null || isAdmitted(trial);
				};
				if (startable)
				{
					candidates.remove();
					return job;
				}
			}
			return null;
		}

		/**
		 * Indicates whether the slice admits a trial: it is expected to end by the slice's deadline.
		 *
		 * @param trial the trial
		 * @return {@code true} if the slice admits it
		 */
		private boolean isAdmitted(TrialJob trial)
		{
			if (slice.settings().background())
				return cancellationEnd == null;
			return evidence.admits(slice.clock().instant(), slice.deadline(), trial.pilot().test.timeLimit());
		}

		/**
		 * Returns the name of a session's role.
		 *
		 * @param job the session's work
		 * @return {@code trial} or {@code judge}
		 */
		private static String roleName(Job job)
		{
			return switch (job)
			{
				case TrialJob _ -> "trial";
				case JudgeJob _ -> "judge";
			};
		}

		/**
		 * Consumes the end of a trial session. A completed trial's built-in tools decide it or send it to its judge.
		 *
		 * @param trial the trial
		 * @param result the session's result, or {@code null} if the session failed with an exception
		 * @param failure the exception, or {@code null}
		 * @throws IOException          if the checkpoint fails or the original launch has not settled
		 * @throws InterruptedException if the thread is interrupted while a check program runs
		 */
		private void trialEnded(TrialJob trial, SessionResult result, Throwable failure)
			throws IOException, InterruptedException
		{
			switch (result)
			{
				case null -> infrastructureFailed(trial, "The trial session failed: " + Driver.describe(failure));
				case SessionResult.Completed completed -> decideTools(trial, completed);
				case SessionResult.RateLimited _ ->
				{
					retire(trial, new Discard("The provider rate-limited the pilot trial", false));
					queue.addFirst(fresh(trial));
				}
				case SessionResult.Refused refused ->
				{
					retire(trial, new Discard("The provider refused the pilot trial", false));
					refused(fresh(trial), refused);
				}
				case SessionResult.Interrupted _ -> stopCancelled(trial, "The trial session was interrupted");
				case SessionResult.Failed failed -> infrastructureFailed(trial, failed.reason());
				case SessionResult.LaunchRefused refused when trial.claim().isPresent() ->
					infrastructureFailed(trial, refused.reason());
				case SessionResult.LaunchRefused refused -> stopCancelled(trial, refused.reason());
			}
		}

		/**
		 * Runs a completed trial's built-in tools, as {@link Driver} does for a campaign's trial: a tool that failed
		 * under {@code on_tool_failure: fail} fails its own assertion and no other, so the trial still waits for a
		 * judge of the assertions without a {@code tool} and the assertions whose failed tool leaves them to the judge.
		 * A trial whose
		 * tools decide every assertion starts no judge. A tool that the trial's record cannot decide makes the trial a
		 * failed trial with a recorded reason. Unreadable or unrenderable evidence makes it an infrastructure attempt.
		 * <p>
		 * Each check program runs twice on one staged copy of the trial's evidence. A program whose runs have different
		 * outcomes, or of which a run takes longer than {@link CheckProgramProblem#LONGEST_RUN}, gives the trial a
		 * check-program problem in place of a decision: it starts no judge and keeps its projects. Otherwise the first
		 * run decides the program's assertion.
		 *
		 * @param trial     the trial
		 * @param completed the trial session's result
		 * @throws IOException          if the checkpoint fails or the original launch has not settled
		 * @throws InterruptedException if the thread is interrupted while a check program runs
		 */
		private void decideTools(TrialJob trial, SessionResult.Completed completed)
			throws IOException, InterruptedException
		{
			Pilot pilot = trial.pilot();
			DrawnTestCase testCase = trial.testCase();
			List<BuiltInToolResult> results;
			String section = "";
			try
			{
				TrialEvidence evidence = TrialEvidence.read(pilot.conditions.trialSessions(), completed);
				if (pilot.test.hasCheckProgram())
				{
					Map<String, List<CheckProgram.Result>> runs = checkPrograms.run(pilot.test, testCase.testCase(),
						evidence, CHECK_RUNS);
					List<CheckProgramProblem> problems = CheckProgramProblem.find(trial.position(), runs);
					if (!problems.isEmpty())
					{
						// A program that cannot be trusted decides nothing, so the trial waits for a revision
						pilot.problems(trial.position(), problems);
						return;
					}
					results = pilot.test.decide(testCase.testCase(), evidence,
						CheckProgramEvaluation.toolResults(pilot.test, runs));
				}
				else
					results = pilot.test.decide(testCase.testCase(), evidence);
				if (contract.judge().prompt().contains("{{evidence}}"))
					section = evidence.render();
			}
			catch (UndecidableToolException e)
			{
				pilot.failed(trial.position(), "The built-in tools could not be decided: " + Driver.describe(e));
				return;
			}
			catch (CheckProgramStartFailure e)
			{
				if (e.isInfrastructure())
					infrastructureFailed(trial, "The built-in tools could not be decided: " + Driver.describe(e));
				else
					startFailed(trial, e);
				return;
			}
			catch (IOException e)
			{
				infrastructureFailed(trial, "The built-in tools could not be decided: " + Driver.describe(e));
				return;
			}
			List<JudgedAssertion> judged = JudgedAssertion.of(pilot.test, contract.requirements(), results);
			if (judged.isEmpty())
			{
				// Every assertion has a tool that decided it, so no judge has anything to decide
				boolean failed = results.stream().anyMatch(BuiltInToolResult::failsTrial);
				Map<String, Boolean> assertions = new LinkedHashMap<>();
				for (BuiltInToolResult result : results)
					assertions.put(result.assertion().id(), result.passed());
				String record;
				if (failed)
					record = Driver.toolRecord("Failed built-in tools:", Driver.failedTools(results));
				else
					record = Driver.toolRecord("Passed built-in tools:", results);
				pilot.decided(trial.position(), testCase, assertions, record);
				if (!failed)
					deleteProjects(completed);
				return;
			}
			// Each evidence scope is judged in its own session, the response scope first
			PilotScopes scopes = new PilotScopes(judged);
			Map<EvidenceScope, List<JudgedAssertion>> byScope = new EnumMap<>(EvidenceScope.class);
			for (JudgedAssertion assertion : judged)
				byScope.computeIfAbsent(assertion.scope(), _ -> new ArrayList<>()).add(assertion);
			List<JudgeJob> jobs = new ArrayList<>();
			for (Map.Entry<EvidenceScope, List<JudgedAssertion>> entry : byScope.entrySet())
			{
				List<JudgedAssertion> assertions = List.copyOf(entry.getValue());
				Map<String, VerdictKey> byId = new LinkedHashMap<>();
				if (entry.getKey() == EvidenceScope.RESPONSE)
				{
					List<VerdictKey> keys = verdicts.keys(contract.judge(), testCase, assertions, completed);
					for (int i = 0; i < assertions.size(); ++i)
						byId.put(assertions.get(i).assertion().id(), keys.get(i));
				}
				JudgeJob judging = new JudgeJob(pilot, trial.position(), testCase, completed, assertions, results,
					section, 1, assertions, Collections.unmodifiableMap(byId), Map.of(), scopes);
				Optional<JudgeJob> rest = withStoredVerdicts(judging);
				if (rest.isPresent())
					jobs.add(rest.get());
			}
			for (JudgeJob job : jobs.reversed())
				queue.addFirst(job);
		}

		/**
		 * Takes the stored verdicts of a judged trial. A trial whose assertions all have one is decided by them and
		 * starts no judge session; otherwise the session decides only the assertions without one.
		 *
		 * @param judging the judged trial
		 * @return the judge session that decides the rest, or empty if the stored verdicts decided the trial
		 * @throws IOException if the stored verdicts cannot be read
		 */
		private Optional<JudgeJob> withStoredVerdicts(JudgeJob judging) throws IOException
		{
			if (judging.keys().isEmpty())
				return Optional.of(judging);
			Map<String, VerdictRecord> stored = new HashMap<>(judging.stored());
			List<JudgedAssertion> missing = new ArrayList<>();
			for (JudgedAssertion assertion : judging.judged())
			{
				String id = assertion.assertion().id();
				Optional<VerdictRecord> found = verdicts.find(judging.keys().get(id));
				if (found.isPresent())
					stored.put(id, found.get());
				else
					missing.add(assertion);
			}
			if (missing.isEmpty())
			{
				decided(judging, VerdictRecords.judgment(judging.assertions(), stored, judging.trialName()));
				return Optional.empty();
			}
			return Optional.of(new JudgeJob(judging.pilot(), judging.position(), judging.testCase(), judging.trial(),
				List.copyOf(missing), judging.results(), judging.section(), judging.attempt(), judging.assertions(),
				judging.keys(), Map.copyOf(stored), judging.scopes()));
		}

		/**
		 * Indicates whether a judge session waits for a running one that decides one of its assertions' keys, so that
		 * it takes that session's verdict instead of deciding the same evidence again.
		 *
		 * @param judging the judged trial
		 * @return {@code true} if a running judge session decides one of the keys of {@code judging}
		 */
		private boolean waitsForRunningJudge(JudgeJob judging)
		{
			if (judging.keys().isEmpty())
				return false;
			Set<VerdictKey> needed = new HashSet<>();
			for (JudgedAssertion assertion : judging.judged())
				needed.add(judging.keys().get(assertion.assertion().id()));
			for (Job running : inFlight.keySet())
			{
				if (running instanceof JudgeJob other && !other.keys().isEmpty())
				{
					for (JudgedAssertion assertion : other.judged())
					{
						if (needed.contains(other.keys().get(assertion.assertion().id())))
							return true;
					}
				}
			}
			return false;
		}

		/**
		 * Consumes the end of a judge session. A valid judgment decides the trial; any other result reruns the judge
		 * until it has failed {@value Driver#JUDGE_ATTEMPTS} times.
		 *
		 * @param judging the judged trial
		 * @param result the session's result, or {@code null} if the session failed with an exception
		 * @param failure the exception, or {@code null}
		 * @throws IOException if the checkpoint fails or the original launch has not settled
		 */
		private void judgeEnded(JudgeJob judging, SessionResult result, Throwable failure) throws IOException
		{
			// The trial runs again after another scope's judge failed, so this session's verdicts are not needed
			if (judging.scopes().isAbandoned())
				return;
			switch (result)
			{
				case null -> sessionFailed(judging, "The judge session failed: " + Driver.describe(failure));
				case SessionResult.Completed completed ->
				{
					Optional<List<AssertionVerdict>> decided = judge.verdicts(completed, judging.judged());
					if (decided.isEmpty())
					{
						sessionFailed(judging, "The judge's output does not match its result schema");
						return;
					}
					decided(judging, recorded(judging, decided.get()));
				}
				// A rate limit is not a failure of the judge, which reruns once the shared hold ends
				case SessionResult.RateLimited _ -> queue.addFirst(judging);
				case SessionResult.Refused refused -> refused(judging, refused);
				// Like an interrupted trial, an interrupted judge is not an attempt of the judge or of its trial
				case SessionResult.Interrupted _ -> stopCancelled(judging, "The judge session was interrupted");
				case SessionResult.Failed failed -> sessionFailed(judging, failed.reason());
				case SessionResult.LaunchRefused refused -> stopCancelled(judging, refused.reason());
			}
		}

		/**
		 * Stores the verdicts of a judge session under their keys and returns the trial's judgment. The first verdict
		 * stored under a key counts, so a verdict that another trial's session stored first replaces this session's.
		 *
		 * @param judging the judged trial
		 * @param decided the session's verdict of each assertion that it decides
		 * @return the judgment
		 * @throws IOException if the verdicts cannot be stored
		 */
		private Judgment recorded(JudgeJob judging, List<AssertionVerdict> decided) throws IOException
		{
			if (judging.keys().isEmpty())
				return AssertionVerdict.judgment(decided);
			TrialProfile profile = contract.judge().profile();
			String trial = judging.trialName();
			Map<String, VerdictRecord> counted = new HashMap<>(judging.stored());
			for (AssertionVerdict verdict : decided)
			{
				VerdictKey key = judging.keys().get(verdict.assertion());
				counted.put(verdict.assertion(), verdicts.write(key, new VerdictRecord(verdict, profile.model(),
					profile.effort(), trial)));
			}
			return VerdictRecords.judgment(judging.assertions(), counted, trial);
		}

		/**
		 * Records the verdict of one evidence scope of a trial, and records the trial once every scope is decided. A
		 * trial that passes every assertion has its effects and its copy of the initial project deleted; a trial that
		 * fails one keeps them as the evidence of its failure.
		 *
		 * @param judging the judged trial
		 * @param scopeJudgment the judge's verdict of the scope's assertions
		 */
		private void decided(JudgeJob judging, Judgment scopeJudgment)
		{
			Optional<Judgment> trialJudgment = judging.scopes().decide(judging.scope(), scopeJudgment);
			if (trialJudgment.isEmpty())
				return;
			Judgment judgment = trialJudgment.get();
			Set<String> failed = new HashSet<>();
			for (FailedAssertion failure : judgment.failures())
				failed.add(failure.assertionId());
			Map<String, Boolean> assertions = new LinkedHashMap<>();
			for (BuiltInToolResult result : judging.results())
				assertions.put(result.assertion().id(), result.passed());
			for (JudgedAssertion assertion : judging.scopes().assertions())
			{
				String id = assertion.assertion().id();
				assertions.put(id, !failed.contains(id));
			}
			List<BuiltInToolResult> failedTools = Driver.failedTools(judging.results());
			String record = judgment.rationale();
			if (!failedTools.isEmpty())
				record = Driver.toolRecord("Failed built-in tools:", failedTools) + "\n\n" + record;
			judging.pilot().decided(judging.position(), judging.testCase(), assertions, record);
			if (assertions.containsValue(false))
				return;
			deleteProjects(judging.trial());
		}

		/**
		 * Deletes the effects and the copy of the initial project of a trial that passed every assertion. A failure to
		 * delete them is kept for the pilot's report and stops the pilot.
		 *
		 * @param trial the passing trial's session result
		 */
		private void deleteProjects(SessionResult.Completed trial)
		{
			try
			{
				directoryRemoval.remove(trial.effects());
				directoryRemoval.remove(trial.initialProject());
			}
			catch (IOException e)
			{
				if (deletionFailure == null)
					deletionFailure = e;
				else
					deletionFailure.addSuppressed(e);
				stop("The project copies of a passing trial could not be deleted: " + Driver.describe(e));
			}
		}

		/**
		 * Consumes a session that failed to start or ended without a usable result. A judge reruns until it has
		 * failed {@value Driver#JUDGE_ATTEMPTS} times; then, like a failed trial, its trial is an infrastructure
		 * attempt.
		 *
		 * @param job the session's work
		 * @param reason why the session failed
		 * @throws IOException if the checkpoint fails or the original launch has not settled
		 */
		private void sessionFailed(Job job, String reason) throws IOException
		{
			switch (job)
			{
				case TrialJob trial -> infrastructureFailed(trial, reason);
				case JudgeJob judging when judging.attempt() < Driver.JUDGE_ATTEMPTS -> queue.addFirst(judging.retry());
				case JudgeJob judging ->
				{
					judging.scopes().abandon();
					infrastructureFailed(judging, "The judge failed " + Driver.JUDGE_ATTEMPTS + " times. Last " +
						"failure: " + reason);
				}
			}
		}

		/**
		 * Records a trial attempt that ended without an outcome. The logical trial runs again with the same
		 * test case, unless it has now ended without an outcome {@value Campaign#INFRASTRUCTURE_FAILURE_LIMIT} times:
		 * then the pilot pauses.
		 *
		 * @param job    the session's work, which belongs to the logical trial
		 * @param reason why the attempt ended without an outcome
		 * @throws IOException if the checkpoint fails or the original launch has not settled
		 */
		private void infrastructureFailed(Job job, String reason) throws IOException
		{
			retire(job, new Discard(reason, true));
			if (job.pilot().recovery(job.position()).infrastructureAttempts().size() < Campaign.INFRASTRUCTURE_FAILURE_LIMIT)
				queue.addFirst(fresh(job));
		}

		/**
		 * Stops the pilot at once because a check program could not start for a cause that CAT names: no further session
		 * starts and the pilot pauses for the user's choice, with the cause as its reason. The trial's launch is
		 * retired without an infrastructure attempt, because nothing about the trial failed, so retrying runs the same
		 * logical trial again.
		 *
		 * @param trial   the trial whose check program could not start
		 * @param failure the start failure, which names the cause
		 * @throws IOException if the checkpoint fails or the original launch has not settled
		 */
		private void startFailed(TrialJob trial, CheckProgramStartFailure failure) throws IOException
		{
			retire(trial, new Discard("The check program could not start: " + failure.getMessage(), false));
			if (stopReason == null)
			{
				stopReason = "Pilot trial " + (trial.position() + 1) + " of `" + trial.pilot().key() + "` stopped the " +
					"pilot at once because its check program could not start: " + failure.getMessage();
				paused = true;
			}
		}

		/**
		 * Pauses once the trial has spent its infrastructure allowance.
		 *
		 * @param pilot    the trial's pilot
		 * @param position the trial's draw position
		 * @param attempts why its earlier attempts ended
		 */
		private void pauseIfExhausted(Pilot pilot, int position, List<String> attempts)
		{
			if (stopReason == null && attempts.size() >= Campaign.INFRASTRUCTURE_FAILURE_LIMIT)
			{
				stopReason = exhaustionReason(pilot.key(), position, attempts);
				paused = true;
			}
		}

		/**
		 * Records and settles a current launch before permitting a replacement.
		 *
		 * @param job the job associated with the original trial
		 * @param intent why the launch will be retired
		 * @throws IOException if the checkpoint fails or the broker has not settled the launch
		 */
		private void retire(Job job, Discard intent) throws IOException
		{
			Pilot pilot = job.pilot();
			if (pilot.markDiscard(job.position(), intent))
			{
				save();
				SessionPort trials = pilot.conditions.trialSessions();
				discard(trials, observe(trials, pilot.recovery(job.position()).requestId().orElseThrow()));
			}
			finishRetirement(job, intent);
		}

		/**
		 * Records a settled launch's disposition and checkpoints it before the position can launch again.
		 *
		 * @param job the job associated with the original trial
		 * @param intent why the launch was retired
		 * @throws IOException if the checkpoint fails
		 */
		private void finishRetirement(Job job, Discard intent) throws IOException
		{
			Pilot pilot = job.pilot();
			int position = job.position();
			if (intent.charged())
				pauseIfExhausted(pilot, position, pilot.infrastructureAttempt(position, intent.reason()));
			else
				pilot.clearLaunch(position);
			if (pilot.conditions.trialSessions().recoversTrials())
				save();
		}

		/**
		 * Stops after a CAT interruption or launch refusal and preserves its noncharging disposition.
		 *
		 * @param job the job that was interrupted or refused
		 * @param reason why the pilot stopped
		 * @throws IOException if the checkpoint fails
		 */
		private void stopCancelled(Job job, String reason) throws IOException
		{
			if (job.pilot().markDiscard(job.position(), new Discard(reason, false)))
				save();
			stop(reason);
		}

		/**
		 * Returns the same logical trial ready for a fresh launch.
		 *
		 * @param job the job that ran or judged the trial
		 * @return the original assignment without an earlier launch to resume
		 */
		private static TrialJob fresh(Job job)
		{
			return new TrialJob(job.pilot(), job.position(), job.testCase(), Optional.empty());
		}

		/**
		 * Consumes a session that the provider refused. The session runs again once the harness's shared hold ends,
		 * unless the refusal followed a hold of the longest length.
		 *
		 * @param job     the session's work
		 * @param refused the session's result
		 */
		private void refused(Job job, SessionResult.Refused refused)
		{
			if (refused.afterLongestHold())
				stop("The provider refused a pilot session after a 30-minute hold.");
			else
				queue.addFirst(job);
		}

		/**
		 * Stops the pilot from starting further sessions.
		 *
		 * @param reason why the pilot stopped
		 */
		private void stop(String reason)
		{
			if (stopReason == null)
				stopReason = reason;
		}
	}
}
