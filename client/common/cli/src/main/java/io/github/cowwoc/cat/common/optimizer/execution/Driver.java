package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorDiagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptSize;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedRequirement;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureSummary;
import io.github.cowwoc.cat.common.optimizer.campaign.Impact;
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
import io.github.cowwoc.cat.common.optimizer.campaign.Triage;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.UndecidableCheckException;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.execution.InFlight.Judging;
import io.github.cowwoc.cat.common.optimizer.execution.InFlight.Triaging;
import io.github.cowwoc.cat.common.optimizer.execution.SessionEvents.Event;
import io.github.cowwoc.cat.common.optimizer.inventory.AttemptReview;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunks;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Advances one campaign during one bounded run, called a slice.
 * <p>
 * {@link #open(DriverContext, String) Opening} a driver takes the campaign's write lock and reconciles what an earlier
 * driver left behind. Each {@link #step()} then consumes the sessions that ended, judges and adopts their outcomes,
 * and starts the work that the campaign admits, in write-ahead order: an assignment is persisted
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
	 * Count a development failure as blocking after this many failed triage attempts.
	 */
	static final int TRIAGE_ATTEMPTS = 3;
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
	private final List<Triaging> triageQueue = new ArrayList<>();
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
		return Optional.of(open(context, campaignId, lock.get()));
	}

	/**
	 * Reconciles a campaign under a write lock that the caller has taken.
	 * <p>
	 * Takes ownership of the lock, releasing it if the driver cannot be created.
	 *
	 * @param context the driver's context
	 * @param campaignId the campaign's identifier
	 * @param lock the campaign's write lock
	 * @return the driver
	 * @throws IllegalArgumentException if the store holds no campaign with that identifier
	 * @throws IOException if the store, the worktree or the run records cannot be read or written
	 */
	static Driver open(DriverContext context, String campaignId, CampaignLock lock) throws IOException
	{
		try
		{
			Instant now = context.clock().instant();
			Campaign campaign = Reconciler.reconcile(context, load(context, campaignId), now);
			return new Driver(context, lock, campaign, now);
		}
		catch (IOException | RuntimeException e)
		{
			try (lock)
			{
				throw e;
			}
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
	 *   {@link CampaignEvent.ResolveConflict}, {@link CampaignEvent.Cancel}, {@link CampaignEvent.Close} or
	 *   {@link CampaignEvent.ReportUnavailable}
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
			case CampaignEvent.Confirm _, CampaignEvent.Revise _, CampaignEvent.Choose _,
				CampaignEvent.ResolveConflict _, CampaignEvent.Cancel _, CampaignEvent.Close _,
				CampaignEvent.ReportUnavailable _ ->
			{
			}
			default -> throw new IllegalArgumentException("Only the driver applies " + event);
		}
		Campaign before = campaign;
		Campaign after = Transitions.apply(before, event);
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
	 * interrupt or at the end of the grace period, starts pending triages and judges, and before the slice's deadline
	 * starts the session that adds development values after a failed certification attempt, commits, starts an author
	 * session and admits trials. Work whose logical trial the campaign discarded ends without an outcome.
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
		discardRemovedTrials();
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

		boolean started = startTriages(now);
		started |= startJudges(now);
		discardRemovedTrials();
		if (now.isBefore(deadline))
		{
			started |= advanceMinimality();
			started |= startValues(now);
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
	 * Indicates whether no session runs and no completed trial waits for its judge or triage.
	 *
	 * @return {@code true} if nothing is in flight
	 */
	private boolean isDrained()
	{
		return inFlight.isEmpty() && judgingQueue.isEmpty() && triageQueue.isEmpty();
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
		campaign = Transitions.apply(campaign, event);
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
		observeUsage(session, result);

		if (result != null && !result.windows().isEmpty())
			record(new CampaignEvent.ObserveRateLimit(result.windows(), now));
		switch (session)
		{
			case InFlight.Trial trial -> trialEnded(trial, result, event.failure(), now);
			case InFlight.Judge judge -> judgeEnded(judge.judging(), result, event.failure(), now);
			case InFlight.Triage triage -> triageEnded(triage.triaging(), result, event.failure(), now);
			case InFlight.Author author -> authorEnded(author, result, event.failure(), now);
			case InFlight.Values values -> valuesEnded(values, result, event.failure(), now);
		}
	}

	/**
	 * Persists reported counts before a finished session's output is validated or adopted.
	 *
	 * @param session the finished session
	 * @param result the session's result, or {@code null} if it failed with an exception
	 * @throws IOException if campaign evidence or the trial record cannot be written
	 */
	private void observeUsage(InFlight session, SessionResult result) throws IOException
	{
		if (!(result instanceof SessionResult.Completed completed) || completed.usage() == null)
			return;
		SessionRole role = switch (session)
		{
			case InFlight.Trial _ -> SessionRole.TRIAL;
			case InFlight.Judge _ -> SessionRole.JUDGE;
			case InFlight.Triage _ -> SessionRole.TRIAGE;
			case InFlight.Author _ -> SessionRole.AUTHOR;
			case InFlight.Values _ -> SessionRole.DERIVER;
		};

		record(new CampaignEvent.ObserveTokenUsage(role, completed.usage()));
		if (session instanceof InFlight.Trial trial)
			context.store().writeTrialUsage(campaign.header().id(), trial.key(), completed.usage());
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
	 * Runs a completed trial's deterministic checks first. A failed check fails the trial with the
	 * record of each failed check, and a trial whose checks decide every requirement passes with the record of its
	 * checks; neither runs a judge. A failed development trial waits for its triage before the failure is recorded.
	 * Otherwise the trial waits for a judge of its semantic requirements, which receives the check results as
	 * evidence. A check that the evidence cannot decide, or evidence that cannot be rendered, makes the run an
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
			results = test.decide(new Generator(campaign.contract(), campaign.lineage().added()).texts(instance),
				new TrialEvidence(completed));
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
			String record = checkRecord("Failed deterministic checks:", failed);
			List<FailedRequirement> failures = failed.stream().
				map(result -> new FailedRequirement(result.check().requirementId(), result.check().behavior())).
				toList();
			if (trial.key().purpose() != Purpose.DEVELOPMENT)
			{
				complete(trial.key(), instance, trial.concurrency(), completed, Verdict.FAIL, record, failures, null,
					null);
				return;
			}
			Optional<String> section = renderEvidence(trial.key(), completed, trial.source(), now);
			if (section.isPresent())
			{
				triageQueue.add(new Triaging(trial.key(), instance, completed, section.get(), results, record, failures,
					null, 1, trial.concurrency()));
			}
			return;
		}
		List<String> semanticIds = test.semanticRequirementIds();
		if (semanticIds.isEmpty())
		{
			complete(trial.key(), instance, trial.concurrency(), completed, Verdict.PASS,
				checkRecord("Passed deterministic checks:", results), List.of(), null, null);
			return;
		}
		Map<String, Requirement> requirementById = new HashMap<>();
		for (Requirement requirement : campaign.contract().requirements())
			requirementById.put(requirement.id(), requirement);
		List<Requirement> semantic = semanticIds.stream().map(requirementById::get).toList();
		String section = "";
		if (judgeRendersEvidence())
		{
			Optional<String> rendered = renderEvidence(trial.key(), completed, trial.source(), now);
			if (rendered.isEmpty())
				return;
			section = rendered.get();
		}
		judgingQueue.add(new Judging(trial.key(), instance, trial.source(), completed, section, semantic, results, 1,
			trial.concurrency()));
	}

	/**
	 * Indicates whether the frozen judge template names the rendered trial evidence.
	 *
	 * @return {@code true} if judges receive the evidence section
	 */
	private boolean judgeRendersEvidence()
	{
		return campaign.contract().judge().prompt().contains("{{evidence}}");
	}

	/**
	 * Renders a completed trial's activity and file changes. A failure to render returns the trial to the queue as an
	 * infrastructure failure.
	 *
	 * @param key the logical trial
	 * @param completed the trial session's result
	 * @param source the staged source copied at the start of the trial
	 * @param now the current time
	 * @return the rendered evidence, or empty if it could not be rendered
	 * @throws IOException if the store cannot be written
	 */
	private Optional<String> renderEvidence(TrialKey key, SessionResult.Completed completed, Path source,
		Instant now) throws IOException
	{
		try
		{
			return Optional.of(context.sessions().renderEvidence(completed, source));
		}
		catch (IOException e)
		{
			fail(key, RunFailure.INFRASTRUCTURE, "The trial evidence could not be rendered: " + describe(e), now,
				null);
			return Optional.empty();
		}
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
				judging.evidence(), judging.requirements(), judging.results(), judging.evidenceSection());
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
	 * Consumes the end of a judge session. A valid verdict completes the trial, after the triage of a failed
	 * development trial; anything else reruns the judge with the same evidence.
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
				Optional<Judgment> judgment = context.judge().judgment(completed, judging.requirements());
				if (judgment.isPresent())
				{
					judged(judging, judgment.get(), completed.model(), now);
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
	 * Completes a judged trial, or queues the triage of a failed development trial.
	 *
	 * @param judging the judged trial
	 * @param judgment the judge's verdict and rationale
	 * @param judgeModel the model that the judge session observed, or {@code null} if its events did not name one
	 * @param now the current time
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void judged(Judging judging, Judgment judgment, String judgeModel, Instant now) throws IOException
	{
		if (judgment.verdict() != Verdict.FAIL || judging.key().purpose() != Purpose.DEVELOPMENT)
		{
			complete(judging.key(), judging.instance(), judging.concurrency(), judging.evidence(), judgment.verdict(),
				judgment.rationale(), judgment.failures(), judgeModel, null);
			return;
		}
		String section = judging.evidenceSection();
		if (!judgeRendersEvidence())
		{
			Optional<String> rendered = renderEvidence(judging.key(), judging.evidence(), judging.source(), now);
			if (rendered.isEmpty())
				return;
			section = rendered.get();
		}
		triageQueue.add(new Triaging(judging.key(), judging.instance(), judging.evidence(), section, judging.results(),
			judgment.rationale(), judgment.failures(), judgeModel, 1, judging.concurrency()));
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
	 * Indicates whether a triage session was started or failed to start. Starts triages of failed development trials
	 * while capacity is free and no throttle holds admissions. The grace period does not stop triages, since a trial
	 * without a recorded outcome reruns.
	 *
	 * @param now the current time
	 * @return {@code true} if a triage session was started or failed to start
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private boolean startTriages(Instant now) throws IOException
	{
		boolean started = false;
		while (!triageQueue.isEmpty() && freeCapacity() > 0 && campaign.throttle().allowsAdmission(now))
		{
			Triaging triaging = triageQueue.removeFirst();
			if (findRunningTrial(triaging.key()).isEmpty())
				continue;
			started = true;
			SessionRequest request = context.triage().request(campaign.contract().judge().profile(),
				triaging.instance(), triaging.evidence(), triaging.results(), triaging.evidenceSection(),
				triaging.rationale());
			SessionHandle handle;
			try
			{
				handle = context.sessions().start(request);
			}
			catch (IOException e)
			{
				triageFailed(triaging, "The triage session could not start: " + describe(e));
				continue;
			}
			register(new InFlight.Triage(triaging, handle));
		}
		return started;
	}

	/**
	 * Consumes the end of a triage session. A valid impact records the trial's failure; anything else reruns the
	 * triage with the same evidence.
	 *
	 * @param triaging the failed trial
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null}
	 * @param now the current time
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void triageEnded(Triaging triaging, SessionResult result, Throwable failure, Instant now)
		throws IOException
	{
		if (findRunningTrial(triaging.key()).isEmpty())
			return;
		String reason;
		switch (result)
		{
			case null -> reason = "The triage session failed: " + describe(failure);
			case SessionResult.Completed completed ->
			{
				Optional<Triage> triage = context.triage().triage(completed);
				if (triage.isPresent())
				{
					completeTriaged(triaging, triage.get());
					return;
				}
				reason = "The triage's output does not match its result schema";
			}
			case SessionResult.RateLimited limited ->
			{
				// A rate limit is not a failure of the triage
				record(new CampaignEvent.ObserveRateLimit(List.of(new RateLimitWindow(1.0, limited.resetsAt())), now));
				triageQueue.add(triaging);
				return;
			}
			case SessionResult.Interrupted _ -> reason = "The triage session was interrupted";
			case SessionResult.Failed failed -> reason = failed.reason();
		}
		triageFailed(triaging, reason);
	}

	/**
	 * Reruns a triage with the same evidence, or records the failure as blocking once the triage has failed
	 * {@value #TRIAGE_ATTEMPTS} times.
	 *
	 * @param triaging the failed trial
	 * @param reason why the triage failed
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void triageFailed(Triaging triaging, String reason) throws IOException
	{
		if (triaging.attempt() < TRIAGE_ATTEMPTS)
		{
			triageQueue.add(triaging.retry());
			return;
		}
		completeTriaged(triaging, new Triage(Impact.BLOCKING, "The triage failed " + TRIAGE_ATTEMPTS + " times, " +
			"so the failure counts as blocking. Last failure: " + reason));
	}

	/**
	 * Records a triaged trial's failure with its impact.
	 *
	 * @param triaging the failed trial
	 * @param triage the failure's impact and its rationale
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void completeTriaged(Triaging triaging, Triage triage) throws IOException
	{
		complete(triaging.key(), triaging.instance(), triaging.concurrency(), triaging.evidence(), Verdict.FAIL,
			triaging.rationale(), triaging.failures(), triaging.judgeModel(), triage);
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
	 * @param failures the requirements that a failed trial failed, each with its behavior; empty for a pass
	 * @param judgeModel the model that the judge session observed, or {@code null} if no judge ran or its events did
	 *   not name one
	 * @param triage the impact of a failed development trial, or {@code null} for any other trial
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void complete(TrialKey key, TrialInstance instance, int concurrency, SessionResult.Completed trial,
		Verdict verdict, String rationale, List<FailedRequirement> failures, String judgeModel, Triage triage)
		throws IOException
	{
		MeasurementIdentity observed = observedIdentity(instance, trial, judgeModel);
		CampaignEvent.Complete complete = new CampaignEvent.Complete(key, verdict, rationale, failures,
			triage, observed, trialInputTokens(trial), trial.responses());
		if (campaign.identity().differences(observed).isEmpty())
		{
			context.store().writeOutcome(campaignId, complete);
			evidence.observe(new AssumptionCheck.Observation(concurrency, verdict));
		}
		Candidate previous = campaign.development().working();
		Campaign next = Transitions.apply(campaign, complete);
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
	 * Starts the session that adds development values once the last certification attempt ended with formal failures
	 * and none were added for it. Development draws nothing and the readiness gate stays unmet until they are added.
	 *
	 * @param now the current time
	 * @return whether the session was started or failed to start
	 */
	private boolean startValues(Instant now)
	{
		if (!isAdmissible() || hasValuesSession() || freeCapacity() <= 0 || !campaign.throttle().allowsAdmission(now))
			return false;
		Optional<CertificationAttempt> attempt = campaign.awaitingValues();
		if (attempt.isEmpty())
			return false;
		List<FailureSummary> failures = attempt.get().failures().stream().map(Failure::summary).toList();
		SessionRequest request = context.values().request(campaign.contract(), campaign.lineage().added(), failures);
		SessionHandle handle;
		try
		{
			handle = context.sessions().start(request);
		}
		catch (IOException e)
		{
			infrastructureFailure = "The session that adds development values could not start: " + describe(e);
			return true;
		}
		register(new InFlight.Values(attempt.get().index(), failures, handle));
		return true;
	}

	/**
	 * Indicates whether a session that adds development values is running.
	 *
	 * @return {@code true} if the session is running
	 */
	private boolean hasValuesSession()
	{
		for (InFlight session : inFlight.values())
		{
			if (session instanceof InFlight.Values)
				return true;
		}
		return false;
	}

	/**
	 * Consumes the end of a session that adds development values. Valid combinations join the development part and
	 * restart the development counts; a rejected response or a failed session is an infrastructure failure. The
	 * result is ignored if its attempt does not await values when the session ends.
	 *
	 * @param values the session
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null}
	 * @param now the current time
	 * @throws IOException if the store cannot be written
	 */
	private void valuesEnded(InFlight.Values values, SessionResult result, Throwable failure, Instant now)
		throws IOException
	{
		Optional<CertificationAttempt> awaiting = campaign.awaitingValues();
		if (awaiting.isEmpty() || awaiting.get().index() != values.attempt())
			return;
		switch (result)
		{
			case null -> infrastructureFailure = "The session that adds development values failed: " +
				describe(failure);
			case SessionResult.Completed completed ->
			{
				List<AddedCombination> combinations;
				try
				{
					combinations = context.values().combinations(completed, campaign.contract(),
						campaign.lineage().added(), values.attempt(), values.failures());
				}
				catch (InvalidProposalException e)
				{
					infrastructureFailure = "The development values were rejected: " + String.join(" ", e.problems());
					return;
				}
				record(new CampaignEvent.AddValues(values.attempt(), combinations));
			}
			// The session starts again once the rate limit allows it
			case SessionResult.RateLimited limited -> record(new CampaignEvent.ObserveRateLimit(
				List.of(new RateLimitWindow(1.0, limited.resetsAt())), now));
			case SessionResult.Interrupted _ -> infrastructureFailure =
				"The session that adds development values was interrupted";
			case SessionResult.Failed failed -> infrastructureFailure =
				"The session that adds development values failed: " + failed.reason();
		}
	}

	/**
	 * Starts the round's authors within the available session capacity, concurrently when the frozen policy is weak.
	 * Weak authors wait for already assigned development trials to settle so every attempt receives the same brief.
	 *
	 * @param now the current time
	 * @return whether an author session was started or failed to start
	 * @throws IOException if the workspace or reservation cannot be read or written
	 */
	private boolean startAuthor(Instant now) throws IOException
	{
		boolean started = false;
		while (isAdmissible() && campaign.development().round() != null && campaign.development().author() == null &&
			authorFailures < AUTHOR_FAILURE_LIMIT && freeCapacity() > 0 && campaign.throttle().allowsAdmission(now))
		{
			Round round = campaign.development().round();
			if (round.weak() && campaign.trials().stream().anyMatch(trial -> trial.key().purpose() == Purpose.DEVELOPMENT))
				break;
			if (!round.weak() && hasAuthorSession())
				break;
			Optional<Entry<Integer, String>> reservation = reserveAuthor();
			if (reservation.isEmpty())
				break;

			startAuthorSession(reservation.get().getKey(), reservation.get().getValue());
			started = true;
			if (!round.weak())
				break;
		}
		return started;
	}

	/**
	 * Finds an unfinished author reservation whose session is absent, or reserves the next unstarted attempt.
	 *
	 * @return the reservation in launch order, or empty when every slot has a session or has been submitted
	 * @throws IOException if a new reservation cannot be persisted
	 */
	private Optional<Entry<Integer, String>> reserveAuthor() throws IOException
	{
		Round round = campaign.development().round();
		for (Entry<Integer, String> entry : round.pendingAuthorAttempts().entrySet())
		{
			if (!isAuthorRunning(entry.getKey()))
				return Optional.of(entry);
		}
		Optional<String> cell = round.nextCell();
		if (cell.isEmpty())
			return Optional.empty();

		record(new CampaignEvent.StartAuthorAttempt(cell.get()));
		return campaign.development().round().pendingAuthorAttempts().entrySet().stream().
			filter(entry -> !isAuthorRunning(entry.getKey())).findFirst();
	}

	/**
	 * Indicates whether an author reservation has a running session in this slice.
	 *
	 * @param number the reserved attempt number
	 * @return whether that session is running
	 */
	private boolean isAuthorRunning(int number)
	{
		return inFlight.values().stream().anyMatch(session -> session instanceof InFlight.Author author &&
			author.number() == number);
	}

	/**
	 * Launches an already persisted author reservation from the unchanged working candidate and its frozen evidence.
	 *
	 * @param number the reserved attempt number
	 * @param cell the addressed cell
	 * @throws IOException if the workspace or overlay cannot be read or written
	 */
	private void startAuthorSession(int number, String cell) throws IOException
	{
		Candidate working = campaign.development().working();
		Overlay overlay = context.overlays().require(working.snapshot());
		Path copy = context.workspace().prepareAuthoringCopy(overlay);
		try
		{
			FileInventory before = context.workspace().scanAuthoringCopy(copy);
			Overlay incumbent = context.overlays().require(campaign.incumbent().snapshot());
			String edits = CampaignEdits.of(context.workspace(), incumbent, overlay, context.promptSources());
			AuthorBrief brief = AuthorBrief.of(campaign, number, overlay.fork(), edits);
			SessionRequest request = context.author().request(brief, copy);
			SessionHandle handle = context.sessions().start(request);
			register(new InFlight.Author(number, cell, working, overlay, copy, before, brief, handle));
		}
		catch (IOException e)
		{
			context.workspace().deleteAuthoringCopy(copy);
			authorFailed("The author session could not start: " + describe(e));
		}
	}

	/**
	 * Saves and starts the next reduction, or promotes the last passing reduction after all groups fail.
	 *
	 * @return whether the search advanced
	 * @throws IOException if an overlay, snapshot or worktree cannot be read or written
	 */
	private boolean advanceMinimality() throws IOException
	{
		if (!isDrained())
			return false;
		Campaign updated = MinimalityAdvancement.advance(context, campaign);
		if (updated == campaign)
			return false;
		Candidate previous = campaign.development().working();
		if (!updated.development().working().equals(previous))
			updated = WorktreeCheck.verify(context, updated, previous);
		campaign = updated;
		context.store().replaceSnapshot(campaign);
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
	 * malformed report contributes no candidate and counts as an infrastructure failure. A needed
	 * edit outside the authority raises an {@code awaiting-user} decision that holds the warnings. A valid conflict
	 * report discards the candidate and pauses for the user's resolution.
	 *
	 * @param author the session
	 * @param completed the session's result
	 * @throws IOException if the store, the overlays or the session's effects cannot be read or written
	 */
	private void submit(InFlight.Author author, SessionResult.Completed completed) throws IOException
	{
		Optional<AuthorReport> report = context.author().report(completed);
		if (report.isEmpty() || !namesContractRequirements(report.get().neededEdits()))
		{
			authorFailed("The author's report of needed edits is malformed");
			return;
		}
		Authority authority = authority();
		FileInventory fork = author.overlay().fork();
		List<AuthorityWarning> warnings = new ArrayList<>();
		for (NeededEdit edit : report.get().neededEdits())
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
		Optional<List<FileDelta>> changes = AuthorDelta.changes(author.overlay(), author.copy(), completed.effects(),
			review.changes());
		if (changes.isEmpty())
		{
			authorFailed("A file in the author's effects changed while CAT read it");
			return;
		}
		List<Hunk> hunks = changes.get().stream().flatMap(change -> Hunks.between(change).stream()).toList();
		Set<String> received = author.brief().failureIds();
		Overlay incumbent = context.overlays().require(campaign.incumbent().snapshot());
		List<String> violations = report.get().manifest().violations(received, hunks, campaign.contract(),
			incumbent.snapshot(), confirmedContents(incumbent, report.get().conflicts()), report.get().conflicts());
		record(new CampaignEvent.RetainAuthorDiagnosis(new AuthorDiagnosis(author.cell(),
			author.working().snapshot(), report.get().manifest().classifications(), violations, completed.transcript(),
			completed.effects())));
		if (!violations.isEmpty())
		{
			authorFailed("The author manifest is invalid: " + violations);
			return;
		}
		if (!report.get().conflicts().isEmpty())
		{
			if (campaign.lifecycle() == Lifecycle.ACTIVE && campaign.isConfirmed() && !campaign.revisionRequested())
				record(new CampaignEvent.FindConflict(report.get().conflicts()));
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
			record(new CampaignEvent.SubmitAuthorAttempt(author.number(), author.cell(), snapshot, changes.get()));
		}
		if (!warnings.isEmpty() && campaign.lifecycle() == Lifecycle.ACTIVE && campaign.isConfirmed() &&
			!campaign.revisionRequested())
		{
			record(new CampaignEvent.WarnAuthority(warnings));
		}
	}

	/**
	 * Reads quotation sources from the saved confirmed prompt system rather than the working candidate.
	 *
	 * @param incumbent the confirmed overlay
	 * @param conflicts the reports whose quotations need validation
	 * @return recorded file bytes keyed by resolved path; absent files remain absent for manifest validation
	 * @throws IOException if recorded content cannot be read
	 */
	private Map<Path, FileContent> confirmedContents(Overlay incumbent, List<AuthorConflict> conflicts)
		throws IOException
	{
		Map<Path, FileContent> contents = new HashMap<>();
		for (AuthorConflict conflict : conflicts)
		{
			for (AuthorConflict.Quotation quotation : conflict.quotations())
			{
				Path target = incumbent.snapshot().resolve(quotation.path()).orElse(quotation.path());
				if (!contents.containsKey(target))
					context.workspace().readFile(incumbent, target).ifPresent(content -> contents.put(target, content));
			}
		}
		return Map.copyOf(contents);
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
			author.cell().equals(round.pendingAuthorAttempts().get(author.number()));
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
					next = Transitions.apply(campaign, new CampaignEvent.Assign(assign));
					yield next.trials().getLast();
				}
			};
			Duration expected = evidence.expectedDuration(findTimeLimit(trial.instance()));
			if (now.plus(expected).isAfter(deadline))
				break;
			context.store().writeAssignment(campaignId, trial);
			campaign = Transitions.apply(next, new CampaignEvent.Start(trial.key()));
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
		Path source;
		try
		{
			Overlay candidate = context.overlays().require(key.snapshot());
			source = context.workspace().stage(candidate);
			staged.put(key.snapshot(), candidate.identity());
			TrialProfile profile = campaign.contract().findCell(trial.instance().cellId()).orElseThrow().profile();
			handle = context.sessions().start(new SessionRequest(SessionRole.TRIAL, key.purpose().part(), source,
				trial.instance().turns(), List.of(), findTimeLimit(trial.instance()), profile));
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
		register(new InFlight.Trial(key, trial.instance(), source, now, concurrency, handle));
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
				case InFlight.Triage triage -> abandoned.add(triage.triaging().key());
				// The author copy is cleaned up by interrupt(); authors have no logical trial to queue.
				case InFlight.Author _, InFlight.Values _ ->
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
		for (Triaging triaging : triageQueue)
			abandoned.add(triaging.key());
		inFlight.clear();
		judgingQueue.clear();
		triageQueue.clear();
		Campaign before = campaign;
		for (TrialKey key : abandoned)
		{
			if (findRunningTrial(key).isPresent())
				campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INTERRUPTED, reason,
					now, null));
		}
		if (campaign != before)
			context.store().replaceSnapshot(campaign);
		if (failure != null)
			throw failure;
	}

	/**
	 * Interrupts the trial, judge and triage sessions whose logical trial the campaign discarded, and drops the
	 * judges and triages that wait for such a trial. Their trials have no outcome to record, so no failure is
	 * recorded either. Every session is interrupted even if interrupting one fails.
	 *
	 * @throws IOException if a session cannot be interrupted
	 */
	private void discardRemovedTrials() throws IOException
	{
		judgingQueue.removeIf(judging -> isRemoved(judging.key()));
		triageQueue.removeIf(triaging -> isRemoved(triaging.key()));
		IOException failure = null;
		for (Iterator<InFlight> sessions = inFlight.values().iterator(); sessions.hasNext();)
		{
			InFlight session = sessions.next();
			TrialKey key = switch (session)
			{
				case InFlight.Trial trial -> trial.key();
				case InFlight.Judge judge -> judge.judging().key();
				case InFlight.Triage triage -> triage.triaging().key();
				case InFlight.Author _, InFlight.Values _ -> null;
			};
			if (key == null || !isRemoved(key))
				continue;
			// The session's late result is ignored
			sessions.remove();
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
		if (failure != null)
			throw failure;
	}

	/**
	 * Indicates whether the campaign no longer holds a logical trial.
	 *
	 * @param key the trial's key
	 * @return {@code true} if the campaign discarded the trial
	 */
	private boolean isRemoved(TrialKey key)
	{
		return Reconciler.find(campaign, key).isEmpty();
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
	 * Records the working and incumbent snapshots' observed prompt-file sizes.
	 *
	 * @param snapshot the snapshot
	 * @throws IOException if the descriptive evidence cannot be persisted
	 */
	private void measurePrompt(Sha256 snapshot) throws IOException
	{
		PromptSize previous = campaign.optimization().promptSizes().get(snapshot);
		if (previous != null && previous.unavailable() == null)
			return;
		PromptSize size;
		try
		{
			Overlay overlay = context.overlays().require(snapshot);
			Path source = context.workspace().stage(overlay);
			staged.put(snapshot, overlay.identity());
			size = PromptMeasurements.measure(source, overlay.snapshot(), context.promptSources());
		}
		catch (IOException e)
		{
			size = PromptSize.unavailable("Prompt files could not be staged for measurement: " + e.getMessage());
		}
		record(new CampaignEvent.MeasurePrompt(snapshot, size));
	}

	/**
	 * Returns the trial session's reported input tokens, preserving absent usage.
	 *
	 * @param trial the completed trial session
	 * @return input tokens including cached input, or {@code null}
	 */
	private static Long trialInputTokens(SessionResult.Completed trial)
	{
		if (trial.usage() == null)
			return null;
		return trial.usage().input();
	}

	/**
	 * Returns the slice's result.
	 *
	 * @return the result
	 * @throws IOException if the working candidate's overlay cannot be read
	 */
	private SliceResult result() throws IOException
	{
		measurePrompt(campaign.development().working().snapshot());
		measurePrompt(campaign.incumbent().snapshot());
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
