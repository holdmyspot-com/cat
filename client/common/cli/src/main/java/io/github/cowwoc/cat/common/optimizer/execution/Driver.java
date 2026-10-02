package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.ReadinessGate;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.RunFailure;
import io.github.cowwoc.cat.common.optimizer.campaign.Scheduler;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.UndecidableCheckException;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.execution.InFlight.Judging;
import io.github.cowwoc.cat.common.optimizer.execution.SessionEvents.Event;
import io.github.cowwoc.cat.common.optimizer.inventory.AttemptReview;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryDiff;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.ReportStatus;
import io.github.cowwoc.cat.common.optimizer.report.Status;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibrationException;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Advances one campaign during one bounded run, called a slice (design §3.7 and §3.8).
 * <p>
 * {@link #open(DriverContext, String) Opening} a driver takes the campaign's write lock and reconciles what an earlier
 * driver left behind. Each {@link #step()} then consumes the sessions that ended, judges and adopts their outcomes,
 * and starts the work that the campaign admits, in the write-ahead order of design §3.8: an assignment is persisted
 * before its session starts, and an outcome before it is adopted. A step never blocks; {@link SliceRunner} waits
 * between steps.
 */
public final class Driver implements AutoCloseable
{
	/**
	 * Count the trial's session as an infrastructure failure after this many failed judge attempts.
	 */
	static final int JUDGE_ATTEMPTS = 3;
	/**
	 * Stop the slice with an infrastructure failure after this many author sessions fail.
	 */
	static final int AUTHOR_FAILURE_LIMIT = 3;
	private final DriverContext context;
	private final CampaignLock lock;
	private final String campaignId;
	private final Instant sliceStart;
	private final Instant deadline;
	private final Instant graceEnd;
	private final SessionEvents events = new SessionEvents();
	private final Map<Long, InFlight> inFlight = new LinkedHashMap<>();
	private final List<Judging> judgingQueue = new ArrayList<>();
	private final SliceEvidence evidence = new SliceEvidence();
	/**
	 * The overlay identity of each snapshot that this slice staged.
	 */
	private final Map<Sha256, Sha256> staged = new HashMap<>();
	private Campaign campaign;
	private long nextToken;
	private int authorFailures;
	private boolean interrupted;
	private String infrastructureFailure;
	private DeliveryReport delivery;
	private boolean stopped;
	private boolean closed;

	/**
	 * Creates a driver that holds the campaign's lock.
	 *
	 * @param context the driver's context
	 * @param lock the campaign's write lock
	 * @param campaign the reconciled campaign
	 * @param sliceStart when the slice started
	 */
	private Driver(DriverContext context, CampaignLock lock, Campaign campaign, Instant sliceStart)
	{
		this.context = context;
		this.lock = lock;
		this.campaign = campaign;
		this.campaignId = campaign.header().id();
		this.sliceStart = sliceStart;
		this.deadline = sliceStart.plus(context.settings().budget());
		this.graceEnd = deadline.plus(context.settings().grace());
	}

	/**
	 * Takes a campaign's write lock and reconciles the campaign.
	 *
	 * @param context the driver's context
	 * @param campaignId the campaign's identifier
	 * @return the driver, or empty if another driver holds the campaign's lock
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code campaignId} is blank or the store holds no campaign with that
	 *   identifier
	 * @throws IOException if the store, the lock file, the worktree or the run records cannot be read or written
	 */
	public static Optional<Driver> open(DriverContext context, String campaignId) throws IOException
	{
		requireThat(context, "context").isNotNull();
		requireThat(campaignId, "campaignId").isNotBlank();
		Optional<CampaignLock> lock = CampaignLock.tryAcquire(context.lockFile());
		if (lock.isEmpty())
			return Optional.empty();
		try
		{
			Instant now = context.clock().instant();
			Campaign campaign = Reconciler.reconcile(context, load(context, campaignId), now);
			return Optional.of(new Driver(context, lock.get(), campaign, now));
		}
		catch (IOException | RuntimeException e)
		{
			lock.get().close();
			throw e;
		}
	}

	/**
	 * Returns the result of a slice that could not take the campaign's lock: the persisted campaign, reported as
	 * {@link Status#BUSY busy}. Nothing is written.
	 *
	 * @param context the driver's context
	 * @param campaignId the campaign's identifier
	 * @return the result
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code campaignId} is blank or the store holds no campaign with that
	 *   identifier
	 * @throws IOException if the store cannot be read
	 */
	public static SliceResult busy(DriverContext context, String campaignId) throws IOException
	{
		requireThat(context, "context").isNotNull();
		requireThat(campaignId, "campaignId").isNotBlank();
		StatusReport report = StatusReport.of(load(context, campaignId), ReportEvidence.NONE);
		return new SliceResult(SliceReports.awaiting(report, new ReportStatus.Active(Status.BUSY), report.pending()),
			null);
	}

	/**
	 * Loads a campaign.
	 *
	 * @param context the driver's context
	 * @param campaignId the campaign's identifier
	 * @return the campaign
	 * @throws IllegalArgumentException if the store holds no campaign with the identifier
	 * @throws IOException if the store cannot be read
	 */
	private static Campaign load(DriverContext context, String campaignId) throws IOException
	{
		Optional<Campaign> campaign = context.store().load(campaignId);
		if (campaign.isEmpty())
			throw new IllegalArgumentException("No campaign has the identifier " + campaignId);
		return campaign.get();
	}

	/**
	 * Applies a user's command and persists the result. Resuming after an unowned change restores the working
	 * candidate in the optimization worktree, so the change is never incorporated.
	 *
	 * @param event a {@link CampaignEvent.Confirm}, {@link CampaignEvent.Revise}, {@link CampaignEvent.Choose},
	 *   {@link CampaignEvent.Cancel}, {@link CampaignEvent.Close} or {@link CampaignEvent.ReportUnavailable}
	 * @throws NullPointerException if {@code event} is null
	 * @throws IllegalArgumentException if the event is not a user's command, or names a choice, trial or attempt that
	 *   the campaign does not offer
	 * @throws IllegalStateException if the driver is closed, or the campaign's state does not permit the event
	 * @throws IOException if the store or the worktree cannot be written
	 */
	public void apply(CampaignEvent event) throws IOException
	{
		requireThat(event, "event").isNotNull();
		requireOpen();
		switch (event)
		{
			// A user's command
			case CampaignEvent.Confirm _, CampaignEvent.Revise _, CampaignEvent.Choose _, CampaignEvent.Cancel _,
				CampaignEvent.Close _, CampaignEvent.ReportUnavailable _ ->
			{
			}
			default -> throw new IllegalArgumentException("Only the driver applies " + event);
		}
		Campaign before = campaign;
		Campaign after = CampaignTransitions.apply(before, event);
		if (event instanceof CampaignEvent.Choose choose && choose.choice() == Choice.RESUME &&
			before.pending() instanceof PendingDecision.UnownedChange && after.lifecycle() == Lifecycle.ACTIVE)
		{
			context.workspace().materialize(context.overlays().require(after.development().working().snapshot()));
		}
		campaign = after;
		context.store().replaceSnapshot(campaign);
	}

	/**
	 * Advances the campaign without blocking. A step consumes the sessions that ended, stops the slice on a host
	 * interrupt or at the end of the grace period, starts pending judges, and before the slice's deadline commits,
	 * starts an author session and admits trials.
	 *
	 * @return what follows
	 * @throws IllegalStateException if the driver is closed
	 * @throws IOException if the store, the workspace or a session cannot be read or written
	 */
	public Step step() throws IOException
	{
		requireOpen();
		if (stopped)
			return new Step.Stopped(result());
		Instant now = context.clock().instant();
		for (Event event : events.drain())
			handle(event, now);
		if (context.interrupts().isInterruptedAfter(sliceStart))
		{
			interrupted = true;
			cancel(now, "The host interrupted the run");
			return stop();
		}
		if (!now.isBefore(graceEnd))
		{
			cancel(now, "The slice's grace period ended before the session did");
			return stop();
		}
		if (infrastructureFailure != null)
		{
			cancel(now, infrastructureFailure);
			return stop();
		}
		if (!isDrained() && isPending(PendingDecision.MeasurementChanged.class))
			cancel(now, "The measurement conditions changed");

		boolean started = startJudges(now);
		if (now.isBefore(deadline))
		{
			started |= commitIfDue();
			started |= startAuthor(now);
			started |= admit(now);
		}
		if (!isDrained())
			return new Step.Continue(nextWake(now));
		if (deliver())
			return stop();
		if (started)
			return new Step.Continue(now);
		Instant holdUntil = campaign.throttle().holdUntil();
		if (isAdmissible() && holdUntil.isAfter(now) && holdUntil.isBefore(deadline))
			return new Step.Continue(holdUntil);
		return stop();
	}

	/**
	 * Indicates whether a session completion is waiting before the timeout elapses. Waits for a completion if none
	 * is available.
	 *
	 * @param timeout the longest time to wait
	 * @return {@code true} if a session ended
	 * @throws NullPointerException if {@code timeout} is null
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	public boolean awaitEvent(Duration timeout) throws InterruptedException
	{
		requireThat(timeout, "timeout").isNotNull();
		return events.await(timeout);
	}

	/**
	 * Cancels the sessions that still run, so that their trials rerun later as the same logical trials, persists the
	 * campaign and releases its lock.
	 *
	 * @throws IOException if a session cannot be interrupted, or the store or the lock cannot be written
	 */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		closed = true;
		try (lock)
		{
			if (!isDrained())
				cancel(context.clock().instant(), "The driver closed before the session ended");
		}
	}

	/**
	 * Requires the driver to be open.
	 *
	 * @throws IllegalStateException if the driver is closed
	 */
	private void requireOpen()
	{
		if (closed)
			throw new IllegalStateException("The driver is closed");
	}

	/**
	 * Indicates whether no session runs and no completed trial waits for its judge.
	 *
	 * @return {@code true} if nothing is in flight
	 */
	private boolean isDrained()
	{
		return inFlight.isEmpty() && judgingQueue.isEmpty();
	}

	/**
	 * Indicates whether the campaign waits for a decision of a kind.
	 *
	 * @param type the kind of decision
	 * @return {@code true} if a decision of that kind is pending
	 */
	private boolean isPending(Class<? extends PendingDecision> type)
	{
		for (PendingDecision decision : campaign.pendingDecisions())
		{
			if (type.isInstance(decision))
				return true;
		}
		return false;
	}

	/**
	 * Indicates whether the campaign may start new work: it is active, confirmed, and waits for no decision or
	 * revision.
	 *
	 * @return {@code true} if new work may start
	 */
	private boolean isAdmissible()
	{
		return campaign.lifecycle() == Lifecycle.ACTIVE && campaign.isConfirmed() && campaign.pending() == null &&
			!campaign.revisionRequested();
	}

	/**
	 * Returns the time by which the driver must step again while sessions run: the slice's deadline, then the end of
	 * its grace period, or an earlier end of a throttle hold.
	 *
	 * @param now the current time
	 * @return the time
	 */
	private Instant nextWake(Instant now)
	{
		Instant wake = graceEnd;
		if (now.isBefore(deadline))
			wake = deadline;
		Instant holdUntil = campaign.throttle().holdUntil();
		if (holdUntil.isAfter(now) && holdUntil.isBefore(wake))
			wake = holdUntil;
		return wake;
	}

	/**
	 * Returns the number of nested sessions that may start now.
	 *
	 * @return the free capacity under the campaign's ceiling
	 */
	private int freeCapacity()
	{
		return campaign.ceiling() - inFlight.size();
	}

	/**
	 * Applies an event that the driver observed and persists the result.
	 *
	 * @param event the event
	 * @throws IOException if the store cannot be written
	 */
	private void record(CampaignEvent event) throws IOException
	{
		campaign = CampaignTransitions.apply(campaign, event);
		context.store().replaceSnapshot(campaign);
	}

	/**
	 * Finds a trial with the given key in the campaign's persisted queue if its state is running.
	 *
	 * @param key the trial's key
	 * @return the trial, or empty if no running trial has the key
	 */
	private Optional<LogicalTrial> findRunningTrial(TrialKey key)
	{
		return Reconciler.find(campaign, key).filter(trial -> trial.state() == TrialState.RUNNING);
	}

	/**
	 * Records that a running trial's session ended without an outcome. The trial returns to the queue.
	 *
	 * @param key the trial's key
	 * @param failure the kind of failure
	 * @param detail what happened
	 * @param now the current time
	 * @param resetsAt when the provider accepts requests again, or {@code null} if unknown or not rate-limited
	 * @throws IOException if the store cannot be written
	 */
	private void fail(TrialKey key, RunFailure failure, String detail, Instant now, Instant resetsAt)
		throws IOException
	{
		if (findRunningTrial(key).isPresent())
			record(new CampaignEvent.Fail(key, failure, detail, now, resetsAt));
	}

	/**
	 * Consumes the end of a session.
	 *
	 * @param event the event
	 * @param now the current time
	 * @throws IOException if the store, the workspace or a session cannot be read or written
	 */
	private void handle(Event event, Instant now) throws IOException
	{
		InFlight session = inFlight.remove(event.token());
		// A cancelled session's late result is ignored
		if (session == null)
			return;
		SessionResult result = event.result();
		if (result != null && !result.windows().isEmpty())
			record(new CampaignEvent.ObserveRateLimit(result.windows(), now));
		switch (session)
		{
			case InFlight.Trial trial -> trialEnded(trial, result, event.failure(), now);
			case InFlight.Judge judge -> judgeEnded(judge.judging(), result, event.failure(), now);
			case InFlight.Author author -> authorEnded(author, result, event.failure(), now);
		}
	}

	/**
	 * Consumes the end of a trial session. A completed session's deterministic checks decide it or send it to its
	 * judge; any other result returns the trial to the queue.
	 *
	 * @param trial the session
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null}
	 * @param now the current time
	 * @throws IOException if the store cannot be written
	 */
	private void trialEnded(InFlight.Trial trial, SessionResult result, Throwable failure, Instant now)
		throws IOException
	{
		TrialKey key = trial.key();
		switch (result)
		{
			case null -> fail(key, RunFailure.INFRASTRUCTURE, "The trial session failed: " + describe(failure), now,
				null);
			case SessionResult.Completed completed ->
			{
				evidence.recordDuration(Duration.between(trial.startedAt(), now));
				evidence.addUsage(SessionRole.TRIAL, completed.usage());
				if (findRunningTrial(key).isPresent())
					decideChecks(trial, completed, now);
			}
			case SessionResult.Interrupted _ ->
				fail(key, RunFailure.INTERRUPTED, "The trial session was interrupted", now, null);
			case SessionResult.RateLimited limited -> fail(key, RunFailure.RATE_LIMITED,
				"The provider rejected the trial session because of a rate limit", now, limited.resetsAt());
			case SessionResult.Failed failed -> fail(key, RunFailure.INFRASTRUCTURE, failed.reason(), now, null);
		}
	}

	/**
	 * Runs a completed trial's deterministic checks first (design &sect;6.4). A failed check fails the trial with the
	 * record of each failed check, and a trial whose checks decide every requirement passes with the record of its
	 * checks; neither runs a judge. Otherwise the trial waits for a judge of its semantic requirements, which
	 * receives the check results as evidence. A check that the evidence cannot decide makes the run an
	 * infrastructure failure.
	 *
	 * @param trial the trial session
	 * @param completed the trial session's result
	 * @param now the current time
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void decideChecks(InFlight.Trial trial, SessionResult.Completed completed, Instant now)
		throws IOException
	{
		TrialInstance instance = trial.instance();
		TestDefinition test = campaign.contract().findTest(instance.testId()).orElseThrow();
		List<CheckResult> results;
		try
		{
			results = test.decide(instance.choices(), new TrialEvidence(completed));
		}
		catch (IOException | UndecidableCheckException e)
		{
			fail(trial.key(), RunFailure.INFRASTRUCTURE, "The deterministic checks could not be decided: " +
				describe(e), now, null);
			return;
		}
		List<CheckResult> failed = results.stream().filter(result -> !result.passed()).toList();
		if (!failed.isEmpty())
		{
			complete(trial.key(), instance, trial.concurrency(), completed, Verdict.FAIL,
				checkRecord("Failed deterministic checks:", failed), null);
			return;
		}
		List<String> semanticIds = test.semanticRequirementIds();
		if (semanticIds.isEmpty())
		{
			complete(trial.key(), instance, trial.concurrency(), completed, Verdict.PASS,
				checkRecord("Passed deterministic checks:", results), null);
			return;
		}
		Map<String, Requirement> requirementById = new HashMap<>();
		for (Requirement requirement : campaign.contract().requirements())
			requirementById.put(requirement.id(), requirement);
		List<Requirement> semantic = semanticIds.stream().map(requirementById::get).toList();
		judgingQueue.add(new Judging(trial.key(), instance, completed, semantic, results, 1, trial.concurrency()));
	}

	/**
	 * Returns the record of deterministic checks that labels a trial in place of a judge's rationale.
	 *
	 * @param heading the record's first line
	 * @param results the checks' results
	 * @return the record: the heading, then one line per check naming its requirement, kind, parameters and
	 *   observed value
	 */
	private static String checkRecord(String heading, List<CheckResult> results)
	{
		StringBuilder record = new StringBuilder(heading);
		for (CheckResult result : results)
			record.append("\n- ").append(result.describe());
		return record.toString();
	}

	/**
	 * Indicates whether a judge session was started or failed to start. Starts judges of completed trials while
	 * capacity is free and no throttle holds admissions. The grace
	 * period does not stop judges, since a trial without a verdict reruns.
	 *
	 * @param now the current time
	 * @return {@code true} if a judge session was started or failed to start
	 * @throws IOException if the store cannot be written
	 */
	private boolean startJudges(Instant now) throws IOException
	{
		boolean started = false;
		while (!judgingQueue.isEmpty() && freeCapacity() > 0 && campaign.throttle().allowsAdmission(now))
		{
			Judging judging = judgingQueue.removeFirst();
			if (findRunningTrial(judging.key()).isEmpty())
				continue;
			started = true;
			SessionRequest request = context.judge().request(campaign.contract().judge(), judging.instance(),
				judging.evidence(), judging.requirements(), judging.results());
			SessionHandle handle;
			try
			{
				handle = context.sessions().start(request);
			}
			catch (IOException e)
			{
				judgeFailed(judging, "The judge session could not start: " + describe(e), now);
				continue;
			}
			register(new InFlight.Judge(judging, handle));
		}
		return started;
	}

	/**
	 * Consumes the end of a judge session. A valid verdict completes the trial; anything else reruns the judge with
	 * the same evidence.
	 *
	 * @param judging the judged trial
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null}
	 * @param now the current time
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void judgeEnded(Judging judging, SessionResult result, Throwable failure, Instant now)
		throws IOException
	{
		if (findRunningTrial(judging.key()).isEmpty())
			return;
		String reason;
		switch (result)
		{
			case null -> reason = "The judge session failed: " + describe(failure);
			case SessionResult.Completed completed ->
			{
				evidence.addUsage(SessionRole.JUDGE, completed.usage());
				Optional<Judgment> judgment = context.judge().judgment(completed);
				if (judgment.isPresent())
				{
					complete(judging.key(), judging.instance(), judging.concurrency(), judging.evidence(),
						judgment.get().verdict(), judgment.get().rationale(), completed.model());
					return;
				}
				reason = "The judge's output does not match its result schema";
			}
			case SessionResult.RateLimited limited ->
			{
				// A rate limit is not a failure of the judge
				record(new CampaignEvent.ObserveRateLimit(List.of(new RateLimitWindow(1.0, limited.resetsAt())), now));
				judgingQueue.add(judging);
				return;
			}
			case SessionResult.Interrupted _ -> reason = "The judge session was interrupted";
			case SessionResult.Failed failed -> reason = failed.reason();
		}
		judgeFailed(judging, reason, now);
	}

	/**
	 * Reruns a judge with the same evidence, or returns the trial to the queue as an infrastructure failure once the
	 * judge has failed {@value #JUDGE_ATTEMPTS} times.
	 *
	 * @param judging the judged trial
	 * @param reason why the judge failed
	 * @param now the current time
	 * @throws IOException if the store cannot be written
	 */
	private void judgeFailed(Judging judging, String reason, Instant now) throws IOException
	{
		if (judging.attempt() < JUDGE_ATTEMPTS)
		{
			judgingQueue.add(judging.retry());
			return;
		}
		fail(judging.key(), RunFailure.INFRASTRUCTURE, "The judge failed " + JUDGE_ATTEMPTS + " times. Last " +
			"failure: " + reason, now, null);
	}

	/**
	 * Records a trial's verdict when its observed measurement conditions match the campaign's, persisting it before
	 * adoption. Different conditions pause the campaign without recording the verdict. A
	 * promotion replaces the working candidate in the optimization worktree once the worktree is verified.
	 *
	 * @param key the logical trial
	 * @param instance the trial's instance
	 * @param concurrency the number of trial sessions that ran once the trial's session started
	 * @param trial the trial session's result
	 * @param verdict the trial's label
	 * @param rationale the judge's rationale or, when no judge ran, the record of the trial's deterministic checks
	 * @param judgeModel the model that the judge session observed, or {@code null} if no judge ran or its events did
	 *   not name one
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void complete(TrialKey key, TrialInstance instance, int concurrency, SessionResult.Completed trial,
		Verdict verdict, String rationale, String judgeModel) throws IOException
	{
		MeasurementIdentity observed = observedIdentity(instance, trial, judgeModel);
		CampaignEvent.Complete complete = new CampaignEvent.Complete(key, verdict, rationale, observed);
		if (campaign.identity().differences(observed).isEmpty())
		{
			context.store().writeOutcome(campaignId, complete);
			evidence.observe(new AssumptionCheck.Observation(concurrency, verdict));
		}
		Candidate previous = campaign.development().working();
		Campaign next = CampaignTransitions.apply(campaign, complete);
		if (next.lifecycle() == Lifecycle.ACTIVE && !next.development().working().equals(previous))
			next = WorktreeCheck.verify(context, next, previous);
		campaign = next;
		context.store().replaceSnapshot(campaign);
	}

	/**
	 * Returns the measurement conditions under which a trial was observed: the driver's environment, the trial
	 * session's loaded prompt sources, the judge session's model if a judge ran, and the trial session's model and
	 * reasoning configuration, which are recorded for the trial's cell.
	 *
	 * @param instance the trial's instance
	 * @param trial the trial session's result
	 * @param judgeModel the model that the judge session observed, or {@code null} if no judge ran or its events did
	 *   not name one
	 * @return the conditions
	 */
	private MeasurementIdentity observedIdentity(TrialInstance instance, SessionResult.Completed trial,
		String judgeModel)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(context.environment().values());
		values.put(MeasurementCondition.PROMPT_SOURCES, trial.promptSources());
		if (judgeModel != null)
			values.put(MeasurementCondition.JUDGE, judgeModel);
		Map<MeasurementCondition, String> cell = new EnumMap<>(MeasurementCondition.class);
		if (trial.model() != null)
			cell.put(MeasurementCondition.MODEL, trial.model());
		if (trial.reasoning() != null)
			cell.put(MeasurementCondition.REASONING, trial.reasoning());
		return new MeasurementIdentity(values, Map.of(instance.cellId(), cell));
	}

	/**
	 * Indicates whether a candidate commitment or calibration failure was recorded. Commits the working candidate
	 * if the campaign is ready, calibrating the attempt's trial cap first.
	 *
	 * @return {@code true} if a commit or a calibration failure was recorded
	 * @throws IOException if the store cannot be written
	 */
	private boolean commitIfDue() throws IOException
	{
		if (!ReadinessGate.isCommitDue(campaign))
			return false;
		CampaignEvent event;
		try
		{
			event = new CampaignEvent.Commit(context.calibrator().calibrate(
				ErrorBudget.certificationLevel(campaign.nextCertificationIndex())), context.random().nextLong());
		}
		catch (CapCalibrationException e)
		{
			event = new CampaignEvent.CalibrationFailed(e.getMessage());
		}
		record(event);
		return true;
	}

	/**
	 * Indicates whether an author session was started or failed to start. Starts one if a development round offers an
	 * author attempt and none runs.
	 *
	 * @param now the current time
	 * @return {@code true} if an author session was started or failed to start
	 * @throws IOException if the workspace cannot be read or written
	 */
	private boolean startAuthor(Instant now) throws IOException
	{
		Development development = campaign.development();
		Round round = development.round();
		if (!isAdmissible() || round == null || development.author() != null || authorFailures >= AUTHOR_FAILURE_LIMIT ||
			freeCapacity() <= 0 || !campaign.throttle().allowsAdmission(now) || hasAuthorSession())
		{
			return false;
		}
		Optional<String> category = round.nextCategory();
		if (category.isEmpty())
			return false;
		Candidate working = development.working();
		Overlay overlay = context.overlays().require(working.snapshot());
		Path copy = context.workspace().prepareAuthoringCopy(overlay);
		try
		{
			FileInventory before = context.workspace().scanAuthoringCopy(copy);
			SessionRequest request = context.author().request(AuthorBrief.of(campaign, category.get(), overlay.fork()),
				copy);
			SessionHandle handle = context.sessions().start(request);
			register(new InFlight.Author(category.get(), working, overlay, copy, before, handle));
		}
		catch (IOException e)
		{
			context.workspace().deleteAuthoringCopy(copy);
			authorFailed("The author session could not start: " + describe(e));
		}
		return true;
	}

	/**
	 * Indicates whether an author session runs.
	 *
	 * @return {@code true} if one runs
	 */
	private boolean hasAuthorSession()
	{
		for (InFlight session : inFlight.values())
		{
			if (session instanceof InFlight.Author)
				return true;
		}
		return false;
	}

	/**
	 * Consumes the end of an author session and deletes the author's copy.
	 *
	 * @param author the session
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null}
	 * @param now the current time
	 * @throws IOException if the store or the workspace cannot be read or written
	 */
	private void authorEnded(InFlight.Author author, SessionResult result, Throwable failure, Instant now)
		throws IOException
	{
		try
		{
			switch (result)
			{
				case null -> authorFailed("The author session failed: " + describe(failure));
				case SessionResult.Completed completed ->
				{
					evidence.addUsage(SessionRole.AUTHOR, completed.usage());
					submit(author, completed);
				}
				case SessionResult.RateLimited limited -> record(new CampaignEvent.ObserveRateLimit(
					List.of(new RateLimitWindow(1.0, limited.resetsAt())), now));
				case SessionResult.Interrupted _ -> authorFailed("The author session was interrupted");
				case SessionResult.Failed failed -> authorFailed(failed.reason());
			}
		}
		finally
		{
			context.workspace().deleteAuthoringCopy(author.copy());
		}
	}

	/**
	 * Validates a completed author attempt against its starting snapshot and submits its candidate, then checks the
	 * edits that the author reported needing against the edit authority. An attempt with a forbidden operation or a
	 * malformed report contributes no candidate and counts as an infrastructure failure (design §4.3, §4.6). A needed
	 * edit outside the authority raises an {@code awaiting-user} decision that holds the warnings.
	 *
	 * @param author the session
	 * @param completed the session's result
	 * @throws IOException if the store, the overlays or the session's effects cannot be read or written
	 */
	private void submit(InFlight.Author author, SessionResult.Completed completed) throws IOException
	{
		Optional<List<NeededEdit>> neededEdits = context.author().neededEdits(completed);
		if (neededEdits.isEmpty() || !namesContractRequirements(neededEdits.get()))
		{
			authorFailed("The author's report of needed edits is malformed");
			return;
		}
		Authority authority = authority();
		FileInventory fork = author.overlay().fork();
		List<AuthorityWarning> warnings = new ArrayList<>();
		for (NeededEdit edit : neededEdits.get())
			authority.diagnose(edit.requirement(), edit.path(), edit.operation(), fork).ifPresent(warnings::add);

		// The session edits the runner's copy of the authoring copy, so its edits are in the effects directory
		FileInventory after = context.workspace().scanAuthorEffects(author.copy(), completed.effects());
		AttemptReview review = InventoryDiff.between(author.before(), after).review(fork, authority);
		if (!review.isPermitted())
		{
			authorFailed("The author attempt made forbidden changes: " + review.forbidden());
			return;
		}
		Optional<Overlay> candidate = AuthorDelta.apply(author.overlay(), completed.effects(), review.changes());
		if (candidate.isEmpty())
		{
			authorFailed("A file in the author's copy changed while CAT read it");
			return;
		}
		if (offersAuthorAttempt(author))
		{
			Sha256 snapshot = author.working().snapshot();
			if (!candidate.get().identity().equals(author.overlay().identity()))
			{
				context.overlays().save(candidate.get());
				snapshot = candidate.get().identity();
			}
			record(new CampaignEvent.SubmitAuthorAttempt(author.category(), snapshot));
		}
		if (!warnings.isEmpty() && campaign.lifecycle() == Lifecycle.ACTIVE && campaign.isConfirmed() &&
			!campaign.revisionRequested())
		{
			record(new CampaignEvent.WarnAuthority(warnings));
		}
	}

	/**
	 * Indicates whether each needed edit names a requirement of the confirmed contract.
	 *
	 * @param neededEdits the needed edits
	 * @return {@code true} if every requirement exists
	 */
	private boolean namesContractRequirements(List<NeededEdit> neededEdits)
	{
		Set<String> requirements = new HashSet<>();
		for (Requirement requirement : campaign.contract().requirements())
			requirements.add(requirement.id());
		for (NeededEdit edit : neededEdits)
		{
			if (!requirements.contains(edit.requirement()))
				return false;
		}
		return true;
	}

	/**
	 * Returns the campaign's edit authority: the confirmed authority with the targets that the user authorized.
	 *
	 * @return the authority
	 */
	private Authority authority()
	{
		return context.authority().withAuthorizedTargets(campaign.authorizedTargets());
	}

	/**
	 * Indicates whether the campaign still offers the author attempt that a session made.
	 *
	 * @param author the session
	 * @return {@code true} if the attempt may be submitted
	 */
	private boolean offersAuthorAttempt(InFlight.Author author)
	{
		if (campaign.lifecycle() != Lifecycle.ACTIVE || !campaign.isConfirmed() || campaign.revisionRequested())
			return false;
		Development development = campaign.development();
		Round round = development.round();
		return round != null && development.author() == null && development.working().equals(author.working()) &&
			round.attempts().getOrDefault(author.category(), Round.ATTEMPTS) < Round.ATTEMPTS;
	}

	/**
	 * Records a failed author session. The slice stops with an infrastructure failure once
	 * {@value #AUTHOR_FAILURE_LIMIT} author sessions failed.
	 *
	 * @param reason why the session failed
	 */
	private void authorFailed(String reason)
	{
		++authorFailures;
		if (authorFailures >= AUTHOR_FAILURE_LIMIT)
		{
			infrastructureFailure = "The author session failed " + AUTHOR_FAILURE_LIMIT + " times. Last failure: " +
				reason;
		}
	}

	/**
	 * Indicates whether a trial was admitted. Admits the trials that the scheduler plans, stopping before the first
	 * whose estimated duration would extend beyond the slice's deadline. Persists each assignment and the campaign
	 * snapshot recording the trials' starts before starting their sessions.
	 *
	 * @param now the current time
	 * @return {@code true} if a trial was admitted
	 * @throws IOException if the store, the overlays or the workspace cannot be read or written
	 */
	private boolean admit(Instant now) throws IOException
	{
		int free = freeCapacity();
		if (free <= 0)
			return false;
		List<LogicalTrial> admitted = new ArrayList<>();
		for (Admission admission : Scheduler.plan(campaign, now, free))
		{
			Campaign next = campaign;
			LogicalTrial trial = switch (admission)
			{
				case Admission.Rerun rerun -> Reconciler.find(campaign, rerun.key()).orElseThrow();
				case Admission.Assign assign ->
				{
					next = CampaignTransitions.apply(campaign, new CampaignEvent.Assign(assign));
					yield next.trials().getLast();
				}
			};
			Duration expected = evidence.expectedDuration(findTimeLimit(trial.instance()));
			if (now.plus(expected).isAfter(deadline))
				break;
			context.store().writeAssignment(campaignId, trial);
			campaign = CampaignTransitions.apply(next, new CampaignEvent.Start(trial.key()));
			admitted.add(trial);
		}
		if (admitted.isEmpty())
			return false;
		context.store().replaceSnapshot(campaign);
		for (LogicalTrial trial : admitted)
			startTrial(trial, now);
		return true;
	}

	/**
	 * Finds a trial test's time limit in its category of the confirmed contract.
	 *
	 * @param instance the trial's instance
	 * @return the time limit of the instance's test definition
	 */
	private Duration findTimeLimit(TrialInstance instance)
	{
		for (TestDefinition test : campaign.contract().findCategory(instance.categoryId()).orElseThrow().tests())
		{
			if (test.id().equals(instance.testId()))
				return test.timeLimit();
		}
		throw new AssertionError("The contract has no test " + instance.testId() + " in " + instance.categoryId());
	}

	/**
	 * Stages a started trial's candidate and starts its session. A failure to start is an infrastructure failure.
	 *
	 * @param trial the trial, whose start is persisted
	 * @param now the current time
	 * @throws IOException if the store cannot be written
	 */
	private void startTrial(LogicalTrial trial, Instant now) throws IOException
	{
		TrialKey key = trial.key();
		SessionHandle handle;
		try
		{
			Overlay candidate = context.overlays().require(key.snapshot());
			Path source = context.workspace().stage(candidate);
			staged.put(key.snapshot(), candidate.identity());
			TrialProfile profile = campaign.contract().findCell(trial.instance().cellId()).orElseThrow().profile();
			handle = context.sessions().start(new SessionRequest(SessionRole.TRIAL, source, trial.instance().turns(),
				List.of(), findTimeLimit(trial.instance()), profile));
		}
		catch (IOException e)
		{
			fail(key, RunFailure.INFRASTRUCTURE, "The trial session could not start: " + describe(e), now, null);
			return;
		}
		int concurrency = 1;
		for (InFlight session : inFlight.values())
		{
			if (session instanceof InFlight.Trial)
				++concurrency;
		}
		register(new InFlight.Trial(key, trial.instance(), now, concurrency, handle));
	}

	/**
	 * Tracks a started session and posts its end to the event queue.
	 *
	 * @param session the session
	 */
	private void register(InFlight session)
	{
		long token = nextToken;
		++nextToken;
		inFlight.put(token, session);
		session.handle().completion().whenComplete((result, failure) ->
			events.post(new Event(token, result, failure)));
	}

	/**
	 * Indicates whether delivery was attempted. Delivers the candidate that a finished campaign selected, once per
	 * slice.
	 *
	 * @return {@code true} if a delivery was attempted
	 * @throws IOException if the store, the overlays or a file cannot be read or written
	 */
	private boolean deliver() throws IOException
	{
		DeliveryRequest request = campaign.delivery();
		if (campaign.lifecycle() != Lifecycle.FINISHED || request == null || delivery != null)
			return false;
		Overlay selected = context.overlays().require(request.candidate().snapshot());
		Overlay working = context.overlays().require(campaign.development().working().snapshot());
		// An authorized external target participates like a prompt source, so its applicability is checked too
		Set<Path> promptSources = new HashSet<>(context.promptSources());
		promptSources.addAll(campaign.authorizedTargets());
		DeliveryResult result = context.deliverer().deliver(campaignId, request, selected, working, promptSources);
		Applicability applicability = null;
		if (result instanceof DeliveryResult.Delivered delivered)
		{
			MeasurementIdentity measured = campaign.identity();
			if (request.attempt() > 0)
				measured = campaign.attempt(request.attempt()).identity();
			Sha256 snapshot = request.candidate().snapshot();
			applicability = SliceReports.combine(delivered.applicability(), Applicability.of(snapshot, measured,
				snapshot, campaign.identity().replaceWith(context.environment())));
		}
		delivery = new DeliveryReport(result, applicability);
		return true;
	}

	/**
	 * Interrupts every session that still runs and returns its trial to the queue, to rerun later as the same
	 * logical trial. Author copies are deleted. Every session is interrupted even if interrupting one fails.
	 *
	 * @param now the current time
	 * @param reason why the sessions are cancelled
	 * @throws IOException if a session cannot be interrupted, or the store or the workspace cannot be written
	 */
	private void cancel(Instant now, String reason) throws IOException
	{
		IOException failure = null;
		List<TrialKey> abandoned = new ArrayList<>();
		for (InFlight session : inFlight.values())
		{
			switch (session)
			{
				case InFlight.Trial trial -> abandoned.add(trial.key());
				case InFlight.Judge judge -> abandoned.add(judge.judging().key());
				// The author copy is cleaned up by interrupt(); authors have no logical trial to queue.
				case InFlight.Author _ ->
				{
				}
			}

			try
			{
				interrupt(session);
			}
			catch (IOException e)
			{
				if (failure == null)
					failure = e;
				else
					failure.addSuppressed(e);
			}
		}
		for (Judging judging : judgingQueue)
			abandoned.add(judging.key());
		inFlight.clear();
		judgingQueue.clear();
		Campaign before = campaign;
		for (TrialKey key : abandoned)
		{
			if (findRunningTrial(key).isPresent())
				campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INTERRUPTED, reason,
					now, null));
		}
		if (campaign != before)
			context.store().replaceSnapshot(campaign);
		if (failure != null)
			throw failure;
	}

	/**
	 * Interrupts a session and deletes its author copy even if the interrupt fails. If both operations fail, the
	 * cleanup failure is suppressed on the interrupt failure.
	 *
	 * @param session the running session
	 * @throws IOException if the interrupt cannot be delivered or the author copy cannot be deleted
	 */
	private void interrupt(InFlight session) throws IOException
	{
		if (session instanceof InFlight.Author author)
		{
			Closeable cleanup = () -> context.workspace().deleteAuthoringCopy(author.copy());
			try (cleanup)
			{
				author.handle().interrupt();
			}
			return;
		}
		session.handle().interrupt();
	}

	/**
	 * Ends the slice: deletes the staged sources that no remaining work uses and reports the campaign.
	 *
	 * @return the step
	 * @throws IOException if the workspace or the overlays cannot be read or written
	 */
	private Step stop() throws IOException
	{
		stopped = true;
		Set<Sha256> used = new HashSet<>();
		used.add(campaign.incumbent().snapshot());
		used.add(campaign.development().working().snapshot());
		if (campaign.development().author() != null)
			used.add(campaign.development().author().snapshot());
		campaign.runningAttempt().ifPresent(attempt -> used.add(attempt.candidate().snapshot()));
		for (LogicalTrial trial : campaign.trials())
			used.add(trial.key().snapshot());
		for (Entry<Sha256, Sha256> entry : staged.entrySet())
		{
			if (!used.contains(entry.getKey()))
				context.workspace().deleteStaged(entry.getValue());
		}
		staged.keySet().retainAll(used);
		return new Step.Stopped(result());
	}

	/**
	 * Returns the slice's result.
	 *
	 * @return the result
	 * @throws IOException if the working candidate's overlay cannot be read
	 */
	private SliceResult result() throws IOException
	{
		Overlay working = context.overlays().require(campaign.development().working().snapshot());
		boolean symbolicLinks = !LinkReport.of(working.fork(), authority()).entries().isEmpty();
		StatusReport report = StatusReport.of(campaign,
			evidence.toReportEvidence(symbolicLinks, interrupted, infrastructureFailure));
		ReportStatus awaiting = new ReportStatus.Active(Status.AWAITING_USER);
		if (delivery != null && !(delivery.result() instanceof DeliveryResult.Delivered))
			report = SliceReports.awaiting(report, awaiting, SliceReports.describe(delivery.result()));
		else if (campaign.lifecycle() == Lifecycle.ACTIVE && campaign.revisionRequested() && isDrained())
			report = SliceReports.awaiting(report, awaiting, SliceReports.REVISION_PENDING);
		return new SliceResult(report, delivery);
	}

	/**
	 * Describes an exception.
	 *
	 * @param failure the exception
	 * @return its message, or its type if it has no message
	 */
	private static String describe(Throwable failure)
	{
		Throwable cause = failure;
		if (cause instanceof CompletionException && cause.getCause() != null)
			cause = cause.getCause();
		String message = cause.getMessage();
		if (message == null || message.isBlank())
			return cause.getClass().getName();
		return message;
	}
}
