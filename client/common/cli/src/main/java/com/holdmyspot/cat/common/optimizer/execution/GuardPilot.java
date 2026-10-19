package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

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
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class GuardPilot
{
	/**
	 * The number of control trials of each prompt test.
	 */
	public static final int TRIALS = 20;
	private final SessionPort sessions;
	private final WorkspacePort workspace;
	private final JudgeProtocol judge;
	private final ProposalSchema schema;
	private final JsonMapper json;
	private final int ceiling;

	/**
	 * Creates a guard pilot.
	 *
	 * @param sessions starts the trial and judge sessions
	 * @param workspace stages the control source that the trials work in
	 * @param judge writes the judge requests and reads their verdicts
	 * @param schema writes the definition of each prompt test, whose identity decides whether a stored pilot still
	 *   applies
	 * @param json reads and writes the hook configurations from which control trials remove the target's dependents
	 * @param ceiling the maximum number of sessions that run at the same time
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code ceiling} is not positive
	 */
	public GuardPilot(SessionPort sessions, WorkspacePort workspace, JudgeProtocol judge, ProposalSchema schema,
		JsonMapper json, int ceiling)
	{
		requireThat(sessions, "sessions").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(ceiling, "ceiling").isPositive();
		this.sessions = sessions;
		this.workspace = workspace;
		this.judge = judge;
		this.schema = schema;
		this.json = json;
		this.ceiling = ceiling;
	}

	/**
	 * Returns the cell that a prompt test's pilot runs in: the test's weakest cell when every model is allowed, since
	 * the user chooses the certification scope only when confirming.
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
	 * decides. A test whose cell is unknown, or that
	 * has no development test case, has no pilot. When the provider rejects or refuses a session, the pilot starts no
	 * further session and each unfinished test's result records why it stopped.
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
		List<Pilot> pending = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			Optional<Cell> cell = pilotCell(contract, test.id());
			if (cell.isEmpty() || contract.split().testCases(test, Part.DEVELOPMENT).isEmpty())
				continue;
			Sha256 definition = definition(contract, test);
			PilotResult reused = stored.get(test.id());
			if (reused != null && reused.appliesTo(definition, cell.get().id(), files))
			{
				pilots.put(test.id(), reused);
				continue;
			}
			Pilot pilot = new Pilot(test, cell.get(), definition, random.nextLong());
			pending.add(pilot);
			// Reserves the test's place in contract order
			pilots.put(test.id(), null);
		}
		if (!pending.isEmpty())
		{
			Path source = workspace.stage(control, excluded, selection);
			List<Path> directories = TrialPlugins.of(plugins, contract, workspace, source, true, json);
			new Run(contract, source, directories, pending).run();
		}
		for (Pilot pilot : pending)
			pilots.put(pilot.test.id(), pilot.result(files));
		return pilots;
	}

	/**
	 * The pilot of one prompt test while it runs.
	 */
	private static final class Pilot
	{
		private final PromptTest test;
		private final Cell cell;
		private final Sha256 definition;
		private final long seed;
		/**
		 * The record of each finished trial, by draw position; {@code null} for a trial that is unfinished or failed.
		 */
		private final PilotTrial[] trials = new PilotTrial[TRIALS];
		private final List<String> failedRuns = new ArrayList<>();
		private int finished;
		private String stopReason;

		/**
		 * Creates a pilot.
		 *
		 * @param test the prompt test
		 * @param cell the cell that the trials run in
		 * @param definition the identity of the test's definition
		 * @param seed the seed of the test's development stream
		 */
		Pilot(PromptTest test, Cell cell, Sha256 definition, long seed)
		{
			this.test = test;
			this.cell = cell;
			this.definition = definition;
			this.seed = seed;
		}

		/**
		 * Records a decided trial.
		 *
		 * @param position the trial's draw position
		 * @param trial the trial's record
		 */
		void decided(int position, PilotTrial trial)
		{
			trials[position] = trial;
			++finished;
		}

		/**
		 * Records a trial that ended without a decision.
		 *
		 * @param reason why the trial ended without a decision
		 */
		void failed(String reason)
		{
			failedRuns.add(reason);
			++finished;
		}

		/**
		 * Returns the pilot's result.
		 *
		 * @param control the identity of the conditions that the trials ran in
		 * @return the result, whose trials are in draw order
		 */
		PilotResult result(Sha256 control)
		{
			List<PilotTrial> decided = new ArrayList<>();
			for (PilotTrial trial : trials)
			{
				if (trial != null)
					decided.add(trial);
			}
			return new PilotResult(test.id(), definition, cell.id(), control, decided, failedRuns, stopReason);
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
	}

	/**
	 * A control trial.
	 *
	 * @param pilot the pilot that the trial belongs to
	 * @param position the trial's draw position
	 * @param testCase the trial's test case
	 */
	private record TrialJob(Pilot pilot, int position, DrawnTestCase testCase) implements Job
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
		private final Contract contract;
		private final Path source;
		private final List<Path> plugins;
		private final List<Pilot> pilots;
		private final Deque<Job> queue = new ArrayDeque<>();
		private final BlockingQueue<Ended> events = new LinkedBlockingQueue<>();
		private final Map<Job, SessionHandle> inFlight = new HashMap<>();
		private String stopReason;
		/**
		 * The failure to delete a passing trial's project copies, which ends the run once its running sessions end;
		 * {@code null} if every deletion succeeded.
		 */
		private IOException deletionFailure;

		/**
		 * Creates a run.
		 *
		 * @param contract the proposed contract
		 * @param source the staged control source
		 * @param plugins the plugin directories that the trials of {@code source} install
		 * @param pilots the pilots to run, in contract order
		 */
		Run(Contract contract, Path source, List<Path> plugins, List<Pilot> pilots)
		{
			this.contract = contract;
			this.source = source;
			this.plugins = plugins;
			this.pilots = pilots;
		}

		/**
		 * Draws each pilot's test cases, then runs their sessions until every trial ends or a session is rejected.
		 *
		 * @throws IOException if a passing trial's project copies cannot be deleted. No further session starts, and the
		 *   failure is thrown once the running sessions end.
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
					DrawnTestCase drawn = generator.markTargeted(generator.draw(pilot.cell.id(), stream));
					queue.add(new TrialJob(pilot, position, drawn));
				}
			}
			try
			{
				while (true)
				{
					startSessions();
					if (inFlight.isEmpty())
						break;
					Ended ended = events.take();
					inFlight.remove(ended.job());
					switch (ended.job())
					{
						case TrialJob trial -> trialEnded(trial, ended.result(), ended.failure());
						case JudgeJob judge -> judgeEnded(judge, ended.result(), ended.failure());
					}
				}
			}
			catch (InterruptedException e)
			{
				for (SessionHandle handle : inFlight.values())
				{
					try
					{
						handle.interrupt();
					}
					catch (IOException suppressed)
					{
						e.addSuppressed(suppressed);
					}
				}
				throw e;
			}
			if (deletionFailure != null)
				throw deletionFailure;
			if (stopReason == null)
				return;
			for (Pilot pilot : pilots)
			{
				if (pilot.finished < TRIALS)
					pilot.stopReason = stopReason;
			}
		}

		/**
		 * Starts queued sessions while capacity is free and no session was rejected.
		 */
		private void startSessions()
		{
			while (stopReason == null && inFlight.size() < ceiling && !queue.isEmpty())
			{
				Job job = queue.removeFirst();
				SessionRequest request = switch (job)
				{
					case TrialJob trial -> new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source,
						trial.testCase().turns(), List.of(), trial.pilot().test.timeLimit(), trial.pilot().cell.profile(),
						TestWorkspace.of(trial.pilot().test, trial.testCase().testCase()), plugins);
					case JudgeJob judging -> judge.request(contract.judge(), judging.testCase(), judging.trial(),
						judging.judged(), judging.results(), judging.section());
				};
				SessionHandle handle;
				try
				{
					handle = sessions.start(request);
				}
				catch (IOException e)
				{
					sessionFailed(job, "The " + roleName(job) + " session could not start: " + Driver.describe(e));
					continue;
				}
				inFlight.put(job, handle);
				handle.completion().whenComplete((result, failure) -> events.add(new Ended(job, result, failure)));
			}
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
		 */
		private void trialEnded(TrialJob trial, SessionResult result, Throwable failure)
		{
			switch (result)
			{
				case null -> trial.pilot().failed("The trial session failed: " + Driver.describe(failure));
				case SessionResult.Completed completed -> decideTools(trial, completed);
				case SessionResult.RateLimited _ ->
					stop("The provider rejected a pilot session because of a rate limit.");
				case SessionResult.Refused _ -> stop("The provider refused a pilot session.");
				case SessionResult.Interrupted _ -> trial.pilot().failed("The trial session was interrupted");
				case SessionResult.Failed failed -> trial.pilot().failed(failed.reason());
			}
		}

		/**
		 * Runs a completed trial's built-in tools. A failed tool whose {@code on_tool_failure} is {@code fail} decides
		 * the trial without a judge; otherwise the trial waits for a judge of the assertions without a {@code tool}, of
		 * the assertions whose failed tool leaves them to the judge, and of the assertion
		 * {@value TestCase#EXPECTED_OUTCOME}. A tool that the trial's record cannot decide, or evidence that cannot be
		 * rendered, makes the trial a failed run that records why.
		 *
		 * @param trial the trial
		 * @param completed the trial session's result
		 */
		private void decideTools(TrialJob trial, SessionResult.Completed completed)
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
			catch (IOException | UndecidableToolException e)
			{
				pilot.failed("The built-in tools could not be decided: " + Driver.describe(e));
				return;
			}
			if (results.stream().anyMatch(BuiltInToolResult::failsTrial))
			{
				// An assertion that a failed tool leaves to the judge stays undecided, because no judge runs
				Map<String, Boolean> assertions = new LinkedHashMap<>();
				for (BuiltInToolResult result : results)
				{
					if (!result.leftToJudge())
						assertions.put(result.assertion().id(), result.passed());
				}
				StringBuilder record = new StringBuilder("Failed built-in tools:");
				for (BuiltInToolResult result : results)
				{
					if (result.failsTrial())
						record.append("\n- ").append(result.describe());
				}
				pilot.decided(trial.position(), new PilotTrial(testCase.testCase().describedValues(),
					testCase.testCase().expectedOutcome(), assertions, record.toString()));
				return;
			}
			List<JudgedAssertion> judged = JudgedAssertion.of(pilot.test, contract.requirements(), results);
			queue.addFirst(new JudgeJob(pilot, trial.position(), testCase, completed, judged, results, section, 1));
		}

		/**
		 * Consumes the end of a judge session. A valid judgment decides the trial; any other result reruns the judge
		 * until it has failed {@value Driver#JUDGE_ATTEMPTS} times.
		 *
		 * @param judging the judged trial
		 * @param result the session's result, or {@code null} if the session failed with an exception
		 * @param failure the exception, or {@code null}
		 */
		private void judgeEnded(JudgeJob judging, SessionResult result, Throwable failure)
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
				case SessionResult.RateLimited _ ->
					stop("The provider rejected a pilot session because of a rate limit.");
				case SessionResult.Refused _ -> stop("The provider refused a pilot session.");
				case SessionResult.Interrupted _ -> sessionFailed(judging, "The judge session was interrupted");
				case SessionResult.Failed failed -> sessionFailed(judging, failed.reason());
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
			DrawnTestCase testCase = judging.testCase();
			judging.pilot().decided(judging.position(), new PilotTrial(testCase.testCase().describedValues(),
				testCase.testCase().expectedOutcome(), assertions, judgment.rationale()));
			if (assertions.containsValue(false))
				return;
			try
			{
				Driver.deleteProjects(judging.trial());
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
		 * failed {@value Driver#JUDGE_ATTEMPTS} times; then, like a failed trial, its trial is a failed run.
		 *
		 * @param job the session's work
		 * @param reason why the session failed
		 */
		private void sessionFailed(Job job, String reason)
		{
			switch (job)
			{
				case TrialJob trial -> trial.pilot().failed(reason);
				case JudgeJob judging when judging.attempt() < Driver.JUDGE_ATTEMPTS ->
					queue.addFirst(new JudgeJob(judging.pilot(), judging.position(), judging.testCase(), judging.trial(),
						judging.judged(), judging.results(), judging.section(), judging.attempt() + 1));
				case JudgeJob judging -> judging.pilot().failed("The judge failed " + Driver.JUDGE_ATTEMPTS +
					" times. Last failure: " + reason);
			}
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
