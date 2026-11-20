package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
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
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Status;
import com.holdmyspot.cat.common.secrets.GuardPilotSecrets;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the guard pilot of a proposed contract before CAT presents it: {@value #TRIALS} control trials of each prompt
 * test in its weakest cell, on development test cases only, decided by the proposed built-in tools and judge. The
 * results show how often the agent already passes each assertion without the target prompts, so that the user can see
 * a guard that the agent fails even before any prompt is optimized.
 * <p>
 * Pilot trials are targeted: they enter no count, record no campaign event and spend no allowance.
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
	 * The number of control trials of each prompt test.
	 */
	public static final int TRIALS = 20;
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
	private static final PilotTrialRecovery NO_RECOVERY =
		new PilotTrialRecovery(Optional.empty(), NO_PROGRESS, List.of(), Optional.empty());
	private final SessionPort sessions;
	private final WorkspacePort workspace;
	private final Path target;
	private final DirectoryRemoval directoryRemoval;
	private final JudgeProtocol judge;
	private final ProposalSchema schema;
	private final JsonMapper json;
	private final int ceiling;

	/**
	 * Creates a guard pilot.
	 *
	 * @param sessions starts the trial and judge sessions
	 * @param workspace stages the control source that the trials work in
	 * @param target the normalized relative path of the target prompt
	 * @param judge writes the judge requests and reads their verdicts
	 * @param schema writes the definition of each prompt test, whose identity decides whether a stored pilot still
	 *   applies
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
	 * @param ceiling the maximum number of sessions that run at the same time
	 * @throws NullPointerException     if any required argument is null
	 * @throws IllegalArgumentException if {@code target} is absolute or {@code ceiling} is not positive
	 */
	public GuardPilot(SessionPort sessions, WorkspacePort workspace, Path target, JudgeProtocol judge,
		ProposalSchema schema, JsonMapper json, int ceiling)
	{
		this(sessions, workspace, target, judge, schema, json, ceiling, TemporaryDirectories::deleteRecursively);
	}

	/**
	 * Creates a guard pilot with a supplied project-directory remover.
	 *
	 * @param sessions starts the trial and judge sessions
	 * @param workspace stages the control source that the trials work in
	 * @param target the normalized relative path of the target prompt
	 * @param judge writes the judge requests and reads their verdicts
	 * @param schema writes the definition of each prompt test, whose identity decides whether a stored pilot still
	 *   applies
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
	 * @param ceiling the maximum number of sessions that run at the same time
	 * @param directoryRemoval deletes project directories of passing trials
	 * @throws NullPointerException if any required argument is null
	 * @throws IllegalArgumentException if {@code target} is absolute or {@code ceiling} is not positive
	 */
	private GuardPilot(SessionPort sessions, WorkspacePort workspace, Path target, JudgeProtocol judge,
		ProposalSchema schema, JsonMapper json, int ceiling, DirectoryRemoval directoryRemoval)
	{
		requireThat(sessions, "sessions").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		FileInventory.requireInventoryPath(target, "target");
		requireThat(target.isAbsolute(), "target.isAbsolute()").isFalse();
		requireThat(directoryRemoval, "directoryRemoval").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(ceiling, "ceiling").isPositive();
		this.sessions = sessions;
		this.workspace = workspace;
		this.target = target;
		this.directoryRemoval = directoryRemoval;
		this.judge = judge;
		this.schema = schema;
		this.json = json;
		this.ceiling = ceiling;
	}

	/**
	 * Returns the cell that a prompt test's pilot runs in: the test's weakest cell when every model is allowed, since
	 * the certification scope is known only at confirmation.
	 *
	 * @param contract the proposed contract
	 * @param testId the prompt test
	 * @return the cell, or empty if it depends on a model whose family is unranked
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the test has no cell
	 */
	public static Optional<Cell> pilotCell(Contract contract, String testId)
	{
		requireThat(contract, "contract").isNotNull();
		return CellTier.weakestCell(contract.profiles(), CertificationScope.WEAKEST_CELL, testId);
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
	 * Runs the pilot of each prompt test that a stored pilot does not describe.
	 * <p>
	 * A stored pilot is reused when it ran every trial, every trial ended with a decision, and its test's definition,
	 * its cell, the control source's files, the selection, the session environment version and the judge version are
	 * unchanged, so a revision reruns only the tests whose definition changed or whose earlier pilot has a failed run,
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
	 * infrastructure attempt: the proposal's tool is the cause, so the trial is a failed run that says why.
	 *
	 * @param contract the proposed contract
	 * @param plugins the campaign's trial plugins, which every trial installs
	 * @param control the control source: the incumbent with the target prompt file emptied and its dependents in the
	 *   project removed
	 * @param excluded the project-relative paths that the staged control source leaves out, so that a trial cannot
	 *   read the prompt tests
	 * @param selection the files of the control source that the staged control source holds
	 * @param stored the pilots that an earlier run of the same campaign stored, by prompt test
	 * @param random draws the seed of each test's development stream
	 * @return the pilot of each prompt test that has one, by prompt test, in contract order
	 * @throws NullPointerException if any of the arguments are null
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
	 * Runs the pilot of each prompt test that a stored pilot does not describe, as
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
	 * @param stored the pilots that an earlier run of the same campaign stored, by prompt test
	 * @param random draws the seed of each test's development stream
	 * @param checkpoint keeps the pilots' in-progress launches, observer decisions and trial results. A pilot that has
	 *   more trials to run at that point has a stop reason that says so.
	 * @return the pilot of each prompt test that has one, by prompt test, in contract order
	 * @throws NullPointerException if any of the arguments are null
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
	 * Runs the pilot of each prompt test that a stored pilot does not describe, as
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
	 * @param stored the pilots that an earlier run of the same campaign stored, by prompt test
	 * @param random draws the seed of each test's development stream
	 * @param checkpoint keeps the pilots' in-progress launches, observer decisions and trial results
	 * @param slice the time bound, or {@code null} if the pilot runs until every trial ends or the pilot stops
	 * @return the pilot of each prompt test that has one, by prompt test, in contract order
	 * @throws NullPointerException if any of the arguments other than {@code slice} are null
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
		requireThat(checkpoint, "checkpoint").isNotNull();
		requireThat(contract, "contract").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		requireThat(control, "control").isNotNull();
		requireThat(excluded, "excluded").isNotNull();
		requireThat(selection, "selection").isNotNull();
		requireThat(stored, "stored").isNotNull();
		requireThat(random, "random").isNotNull();
		// Keyed by the files rather than the overlay, so that a pilot still applies once confirmation records the same
		// files in the fork
		Sha256 files = controlIdentity(control.snapshot().identity(), selection);
		Map<String, PilotResult> pilots = new LinkedHashMap<>();
		Set<String> matching = new HashSet<>();
		List<Pilot> pending = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			Optional<Cell> cell = pilotCell(contract, test.id());
			if (cell.isEmpty() || contract.split().testCases(test, Part.DEVELOPMENT).isEmpty())
				continue;
			Sha256 definition = definition(contract, test);
			PilotResult earlier = stored.get(test.id());
			if (earlier != null && earlier.appliesTo(definition, cell.get().id(), files))
			{
				pilots.put(test.id(), earlier);
				continue;
			}
			Pilot pilot;
			if (earlier != null && earlier.ranIn(definition, cell.get().id(), files))
			{
				matching.add(test.id());
				pilot = new Pilot(test, cell.get(), definition, earlier.seed(), earlier.trials(), earlier.pendingTrials());
				if (earlier.paused())
					pilot.resetExhaustedAttempts();
			}
			else
				pilot = new Pilot(test, cell.get(), definition, random.nextLong(), List.of(), Map.of());
			pending.add(pilot);
			// Reserves the test's place in contract order
			pilots.put(test.id(), null);
		}
		discardObsolete(stored, matching);
		Set<Path> selectedRoots = new HashSet<>(selection.roots());
		selectedRoots.add(target);
		SourceSelection controlSelection = new SourceSelection(selectedRoots, selection.names(), selection.patterns());
		Set<Path> controlExcluded = new HashSet<>(excluded);
		controlExcluded.remove(target);
		Path source = workspace.stage(control, controlExcluded, controlSelection);
		StagedTarget.read(source, control.snapshot(), target);
		if (!pending.isEmpty())
		{
			List<Path> directories = TrialPlugins.of(plugins, contract, workspace, source, true, json);
			new Run(contract, source, control.snapshot(), directories, pending, pilots, files, checkpoint, slice).run();
		}
		for (Pilot pilot : pending)
			pilots.put(pilot.test.id(), pilot.result(files));
		return pilots;
	}

	/**
	 * Settles old launches whose pilot no longer describes the proposed test, cell or source.
	 *
	 * @param stored the previous pilots
	 * @param matching the tests that will retain the previous pilot
	 * @throws IOException if an obsolete launch cannot be settled
	 */
	private void discardObsolete(Map<String, PilotResult> stored, Set<String> matching) throws IOException
	{
		for (PilotResult previous : stored.values())
		{
			if (matching.contains(previous.testId()))
				continue;
			for (PilotTrialRecovery trial : previous.pendingTrials().values())
			{
				if (trial.requestId().isPresent())
					discard(observe(trial.requestId().orElseThrow()));
			}
		}
	}

	/**
	 * Gets the state of an original launch while keeping its request ID until the broker settles it.
	 *
	 * @param requestId the original launch
	 * @return the broker's state
	 * @throws IOException if the lookup fails or the launch is still live
	 */
	private RetainedTrialResponse observe(UUID requestId) throws IOException
	{
		RetainedTrialResponse response = sessions.retainedTrial(RetainedTrialRequest.status(requestId));
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
	 * @param observed the broker's last observation of the original launch
	 * @throws IOException if the broker does not confirm that the launch is gone
	 */
	private void discard(RetainedTrialResponse observed) throws IOException
	{
		if (observed.status() == Status.ABSENT)
			return;
		UUID requestId = observed.requestId();
		RetainedTrialResponse answer = sessions.retainedTrial(RetainedTrialRequest.discard(requestId, observed.identity()));
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
	 * @param pilots the pilot of each prompt test that has one, by prompt test
	 * @return the reason, which names the logical trial whose runs ended without an outcome; empty if no pilot paused
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
	 * @param pilots the pilot of each prompt test that has one, by prompt test
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
	 * The pilot of one prompt test while it runs. The scheduling thread owns all state except the synchronized
	 * recovery state and snapshots, which the turn observers also access.
	 */
	private static final class Pilot
	{
		private final Object stateLock = new Object();
		private final PromptTest test;
		private final Cell cell;
		private final Sha256 definition;
		private final long seed;
		/**
		 * The record of each decided trial, by draw position; {@code null} for a trial that is undecided.
		 */
		private final PilotTrial[] trials = new PilotTrial[TRIALS];
		/**
		 * Recovery and earlier infrastructure attempts of each undecided trial, by draw position.
		 */
		private final Map<Integer, PilotTrialRecovery> recovery = new LinkedHashMap<>();
		private final List<String> failedRuns = new ArrayList<>();
		private int finished;
		private String stopReason;
		private boolean paused;

		/**
		 * Creates a pilot.
		 *
		 * @param test the prompt test
		 * @param cell the cell that the trials run in
		 * @param definition the identity of the test's definition
		 * @param seed the seed of the test's development stream
		 * @param kept the trials that an earlier run of this pilot decided, which this pilot keeps
		 * @param undecided the earlier pilot's undecided trials, by draw position
		 */
		Pilot(PromptTest test, Cell cell, Sha256 definition, long seed, List<PilotTrial> kept,
			Map<Integer, PilotTrialRecovery> undecided)
		{
			this.test = test;
			this.cell = cell;
			this.definition = definition;
			this.seed = seed;
			recovery.putAll(undecided);
			for (PilotTrial trial : kept)
			{
				trials[trial.position()] = trial;
				++finished;
			}
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
					testCase.testCase().expectedOutcome(), assertions, record, recovery(position).infrastructureAttempts());
				recovery.remove(position);
				++finished;
			}
		}

		/**
		 * Records a run of a logical trial that ended without an outcome.
		 *
		 * @param position the trial's draw position
		 * @param reason why the run ended without an outcome
		 * @return the runs of the logical trial that ended without an outcome so far, including this one
		 */
		List<String> infrastructureAttempt(int position, String reason)
		{
			synchronized (stateLock)
			{
				List<String> runs = new ArrayList<>(recovery(position).infrastructureAttempts());
				runs.add(reason);
				recovery.put(position, new PilotTrialRecovery(Optional.empty(), NO_PROGRESS, runs, Optional.empty()));
				return runs;
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
		 * Grants another budget after the caller chose to retry a paused pilot.
		 */
		void resetExhaustedAttempts()
		{
			synchronized (stateLock)
			{
				recovery.values().removeIf(trial -> trial.requestId().isEmpty() &&
					trial.infrastructureAttempts().size() >= Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
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
				failedRuns.add(reason);
				++finished;
			}
		}

		/**
		 * Returns the pilot's result.
		 *
		 * @param control the identity of the conditions that the trials ran in
		 * @return the result, whose trials are in draw order
		 */
		PilotResult result(Sha256 control)
		{
			return result(control, stopReason, paused);
		}

		/**
		 * Returns the pilot's result as it stands while the pilot runs: a pilot with trials left to run says that it did
		 * not finish, so that a later run keeps the trials that it decided and runs the others.
		 *
		 * @param control the identity of the conditions that the trials ran in
		 * @return the result, whose trials are in draw order
		 */
		PilotResult interim(Sha256 control)
		{
			synchronized (stateLock)
			{
				if (finished >= TRIALS)
					return result(control, null, false);
				return result(control, UNFINISHED, false);
			}
		}

		/**
		 * Returns the pilot's result with a reason why it stopped.
		 *
		 * @param control the identity of the conditions that the trials ran in
		 * @param reason why the pilot stopped before running every trial, or {@code null}
		 * @param pause {@code true} if the pilot paused for the user's choice
		 * @return the result, whose trials are in draw order
		 */
		private PilotResult result(Sha256 control, String reason, boolean pause)
		{
			List<PilotTrial> decided = new ArrayList<>();
			for (PilotTrial trial : trials)
			{
				if (trial != null)
					decided.add(trial);
			}
			return new PilotResult(test.id(), definition, cell.id(), control, seed, decided, failedRuns, reason,
				pause, recovery);
		}
	}

	/**
	 * Returns the identity of the conditions that control trials run in: the control source's files, the files of it
	 * that the trials' projects hold, the session environment version and the judge version.
	 *
	 * @param files the identity of the control source's files
	 * @param selection the files of the control source that the staged control source holds
	 * @return the identity
	 */
	private Sha256 controlIdentity(Sha256 files, SourceSelection selection)
	{
		String conditions = files.hex() + "\nselection\n" + selection.describe() + "\nsession environment version " +
			sessions.sessionEnvironmentVersion() + "\njudge version " + sessions.judgeVersion();
		return Sha256.of(conditions.getBytes(UTF_8));
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
	 * @param judged the assertions that the judge decides
	 * @param results the results of the trial's built-in tools
	 * @param section the rendered trial evidence, or empty if the judge template does not name it
	 * @param attempt the number of this judge attempt, starting at 1
	 */
	private record JudgeJob(Pilot pilot, int position, DrawnTestCase testCase, SessionResult.Completed trial,
		List<JudgedAssertion> judged, List<BuiltInToolResult> results, String section, int attempt) implements Job
	{
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
		private final List<Path> plugins;
		private final List<Pilot> pilots;
		/**
		 * The pilots that every checkpoint holds, in contract order: a stored pilot that still applies stands for its
		 * test, and a pilot that runs has a {@code null} place until the checkpoint replaces it.
		 */
		private final Map<String, PilotResult> places;
		private final Sha256 files;
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
		 * @param plugins the plugin directories that the trials of {@code source} install
		 * @param pilots the pilots to run, in contract order
		 * @param places the pilot of each prompt test in contract order, with {@code null} for each of {@code pilots}
		 * @param files the identity of the conditions that the trials run in
		 * @param checkpoint keeps the pilots' in-progress launches, observer decisions and trial results
		 * @param slice the slice's time bound, or {@code null} if the pilot runs until every trial ends or the pilot
		 *   stops
		 */
		Run(Contract contract, Path source, FileInventory inventory, List<Path> plugins, List<Pilot> pilots,
			Map<String, PilotResult> places, Sha256 files, PilotCheckpoint checkpoint, PilotSlice slice)
		{
			this.contract = contract;
			this.source = source;
			this.inventory = inventory;
			this.plugins = plugins;
			this.pilots = pilots;
			this.places = places;
			this.files = files;
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
				for (int position = 0; position < TRIALS; ++position)
				{
					// A kept trial still draws, so that every later trial keeps its test case
					DrawnTestCase drawn = generator.markTargeted(generator.draw(pilot.cell.id(), stream));
					if (!pilot.isDecided(position))
						queue.add(new TrialJob(pilot, position, drawn, Optional.empty()));
				}
			}
			try
			{
				while (true)
				{
					startSessions();
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
				if (pilot.finished < TRIALS)
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
			RetainedTrialResponse observed = observe(requestId);
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
			discard(observed);
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
		 * @return the pilot of each prompt test that has one, by prompt test, in contract order
		 */
		private Map<String, PilotResult> snapshot()
		{
			Map<String, PilotResult> snapshot = new LinkedHashMap<>(places);
			for (Pilot pilot : pilots)
				snapshot.put(pilot.test.id(), pilot.interim(files));
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
				SessionRequest request = switch (job)
				{
					case TrialJob trial -> trialRequest(trial);
					case JudgeJob judging -> judge.request(contract.judge(), judging.testCase(), judging.trial(),
						judging.judged(), judging.results(), judging.section());
				};
				if (slice != null)
					startedAt.put(job, slice.clock().instant());
				checkObserverFailure();
				SessionHandle handle;
				try
				{
					if (job instanceof TrialJob trial && sessions.recoversTrials())
					{
						UUID requestId = trial.pilot().recovery(trial.position()).requestId().orElseThrow();
						handle = sessions.startTrial(request, requestId, trial.claim());
					}
					else
						handle = sessions.start(request);
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
			return new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, trial.testCase().turns(), List.of(),
				pilot.test.timeLimit(), pilot.cell.profile(), TestWorkspace.of(pilot.test, trial.testCase().testCase()),
				plugins, Optional.empty(), true).withTurnObserver(trialObserver(trial));
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
			if (!sessions.recoversTrials())
				return EarlyTrialEnd.forTest(pilot.test, trial.testCase().testCase(), sessions);
			if (trial.claim().isEmpty())
			{
				pilot.begin(trial.position(), UUID.randomUUID());
				save();
			}
			PilotTrialRecovery state = pilot.recovery(trial.position());
			UUID requestId = state.requestId().orElseThrow();
			return EarlyTrialEnd.forTest(pilot.test, trial.testCase().testCase(), sessions, state.progress(),
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
				boolean startable = slice == null || switch (job)
				{
					case JudgeJob _ -> true;
					case TrialJob trial -> isAdmitted(trial);
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
		 * @throws IOException if the checkpoint fails or the original launch has not settled
		 */
		private void trialEnded(TrialJob trial, SessionResult result, Throwable failure) throws IOException
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
		 * judge of the assertions without a {@code tool}, the assertions whose failed tool leaves them to the judge,
		 * and the assertion {@value TestCase#EXPECTED_OUTCOME} unless the test gives it a built-in tool. A trial whose
		 * tools decide every assertion starts no judge. A tool that the trial's record cannot decide makes the trial a
		 * failed run that records why; evidence that cannot be read or rendered makes the run an infrastructure
		 * attempt.
		 *
		 * @param trial the trial
		 * @param completed the trial session's result
		 * @throws IOException if the checkpoint fails or the original launch has not settled
		 */
		private void decideTools(TrialJob trial, SessionResult.Completed completed) throws IOException
		{
			Pilot pilot = trial.pilot();
			DrawnTestCase testCase = trial.testCase();
			List<BuiltInToolResult> results;
			String section = "";
			try
			{
				TrialEvidence evidence = TrialEvidence.read(sessions, completed);
				results = pilot.test.decide(testCase.testCase(), evidence);
				if (contract.judge().prompt().contains("{{evidence}}"))
					section = evidence.render();
			}
			catch (UndecidableToolException e)
			{
				pilot.failed(trial.position(), "The built-in tools could not be decided: " + Driver.describe(e));
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
			queue.addFirst(new JudgeJob(pilot, trial.position(), testCase, completed, judged, results, section, 1));
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
			switch (result)
			{
				case null -> sessionFailed(judging, "The judge session failed: " + Driver.describe(failure));
				case SessionResult.Completed completed ->
				{
					Optional<Judgment> judgment = judge.judgment(completed, judging.judged());
					if (judgment.isEmpty())
					{
						sessionFailed(judging, "The judge's output does not match its result schema");
						return;
					}
					decided(judging, judgment.get());
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
		 * Records a trial that its judge decided. A trial that passes every assertion has its effects and its copy of
		 * the initial project deleted; a trial that fails one keeps them as the evidence of its failure.
		 *
		 * @param judging the judged trial
		 * @param judgment the judge's verdict
		 */
		private void decided(JudgeJob judging, Judgment judgment)
		{
			Set<String> failed = new HashSet<>();
			for (FailedAssertion failure : judgment.failures())
				failed.add(failure.assertionId());
			Map<String, Boolean> assertions = new LinkedHashMap<>();
			for (BuiltInToolResult result : judging.results())
				assertions.put(result.assertion().id(), result.passed());
			for (JudgedAssertion assertion : judging.judged())
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
				case JudgeJob judging when judging.attempt() < Driver.JUDGE_ATTEMPTS ->
					queue.addFirst(new JudgeJob(judging.pilot(), judging.position(), judging.testCase(), judging.trial(),
						judging.judged(), judging.results(), judging.section(), judging.attempt() + 1));
				case JudgeJob judging -> infrastructureFailed(judging, "The judge failed " + Driver.JUDGE_ATTEMPTS +
					" times. Last failure: " + reason);
			}
		}

		/**
		 * Records a run of a logical trial that ended without an outcome. The logical trial runs again with the same
		 * test case, unless it has now ended without an outcome {@value Campaign#INFRASTRUCTURE_FAILURE_LIMIT} times:
		 * then the pilot pauses.
		 *
		 * @param job the session's work, which belongs to the logical trial
		 * @param reason why the run ended without an outcome
		 * @throws IOException if the checkpoint fails or the original launch has not settled
		 */
		private void infrastructureFailed(Job job, String reason) throws IOException
		{
			retire(job, new Discard(reason, true));
			if (job.pilot().recovery(job.position()).infrastructureAttempts().size() < Campaign.INFRASTRUCTURE_FAILURE_LIMIT)
				queue.addFirst(fresh(job));
		}

		/**
		 * Pauses once the trial has spent its infrastructure allowance.
		 *
		 * @param pilot the trial's pilot
		 * @param position the trial's draw position
		 * @param runs why its earlier attempts ended
		 */
		private void pauseIfExhausted(Pilot pilot, int position, List<String> runs)
		{
			if (runs.size() < Campaign.INFRASTRUCTURE_FAILURE_LIMIT)
				return;
			StringBuilder pause = new StringBuilder(128).append("Pilot trial ").append(position + 1).
				append(" of `").append(pilot.test.id()).append("` ended without an outcome ").append(runs.size()).
				append(" times:");
			for (int i = 0; i < runs.size(); ++i)
			{
				if (i > 0)
					pause.append(';');
				pause.append(" (").append(i + 1).append(") ").append(runs.get(i));
			}
			if (stopReason == null)
			{
				stopReason = pause.toString();
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
				discard(observe(pilot.recovery(job.position()).requestId().orElseThrow()));
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
			if (sessions.recoversTrials())
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
