package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.PromptSize;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Candidate;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameters;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Development;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorKind;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisionRequest;
import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.CommitGate;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.RunFailure;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.TrialFiles;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.Triage;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.evidence.EvidenceSection;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveredTest;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.execution.InFlight.Judging;
import com.holdmyspot.cat.common.optimizer.execution.InFlight.Triaging;
import com.holdmyspot.cat.common.optimizer.execution.SessionEvents.Event;
import com.holdmyspot.cat.common.optimizer.inventory.AttemptReview;
import com.holdmyspot.cat.common.optimizer.inventory.Authority;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.Hunk;
import com.holdmyspot.cat.common.optimizer.inventory.Hunks;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryDiff;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.port.UnreachableReference;
import com.holdmyspot.cat.common.optimizer.report.ReportEvidence;
import com.holdmyspot.cat.common.optimizer.report.ReportStatus;
import com.holdmyspot.cat.common.optimizer.report.Status;
import com.holdmyspot.cat.common.optimizer.report.StatusReport;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibrationException;
import com.holdmyspot.cat.common.optimizer.statistics.ErrorBudget;
import com.holdmyspot.cat.common.optimizer.store.TrialSession;
import com.holdmyspot.cat.common.optimizer.workspace.ControlSource;
import com.holdmyspot.cat.common.optimizer.workspace.LinkReport;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
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
	public static final int JUDGE_ATTEMPTS = 3;
	/**
	 * Count a development failure as blocking after this many failed triage attempts.
	 */
	public static final int TRIAGE_ATTEMPTS = 3;
	/**
	 * Stop the slice with an infrastructure failure after this many prompt author sessions fail.
	 */
	public static final int PROMPT_AUTHOR_FAILURE_LIMIT = 3;
	private final DriverContext context;
	private final CampaignLock lock;
	private final String campaignId;
	private final Instant sliceStart;
	private final Instant deadline;
	private final Instant graceEnd;
	/**
	 * How the harness authenticates, classified when the driver opened.
	 */
	private final HostLogin login;
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
	private int promptAuthorFailures;
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
	 * @param login how the harness authenticates
	 */
	private Driver(DriverContext context, CampaignLock lock, Campaign campaign, Instant sliceStart, HostLogin login)
	{
		this.context = context;
		this.lock = lock;
		this.campaign = campaign;
		this.campaignId = campaign.header().id();
		this.sliceStart = sliceStart;
		this.deadline = sliceStart.plus(context.settings().budget());
		this.graceEnd = deadline.plus(context.settings().grace());
		this.login = login;
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
	 * @throws IOException if the store, the lock file, the worktree or the run records cannot be read or written, or
	 *   the harness's credential file exists but cannot be read
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
	 * @throws IOException if the store, the worktree or the run records cannot be read or written, or the harness's
	 *   credential file exists but cannot be read
	 */
	public static Driver open(DriverContext context, String campaignId, CampaignLock lock) throws IOException
	{
		try
		{
			Instant now = context.clock().instant();
			HostLogin login = context.sessions().hostLogin();
			Campaign campaign = Reconciler.reconcile(context, load(context, campaignId), now);
			return new Driver(context, lock, campaign, now, login);
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
	 * Applies a user's command, or the result of a test revision that a command ran, and persists the result.
	 * Resuming after an unowned change restores the working candidate in the optimization worktree, so the change is
	 * never incorporated.
	 *
	 * @param event a {@link CampaignEvent.Confirm}, {@link CampaignEvent.ExtendFork}, {@link CampaignEvent.Revise},
	 *   {@link CampaignEvent.Choose},
	 *   {@link CampaignEvent.ResolveConflict}, {@link CampaignEvent.Cancel}, {@link CampaignEvent.Close},
	 *   {@link CampaignEvent.ReportUnavailable}, {@link CampaignEvent.ReviseTests},
	 *   {@link CampaignEvent.ReportTestSound} or {@link CampaignEvent.BlockTestRevision}
	 * @throws NullPointerException if {@code event} is null
	 * @throws IllegalArgumentException if the event is neither a user's command nor a test revision's result, or
	 *   names a choice, trial or attempt that the campaign does not offer
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
			case CampaignEvent.Confirm _, CampaignEvent.ExtendFork _, CampaignEvent.Revise _, CampaignEvent.Choose _,
				CampaignEvent.ResolveConflict _, CampaignEvent.Cancel _, CampaignEvent.Close _,
				CampaignEvent.ReportUnavailable _,
				// A test revision's result, which the run command records before the slice starts
				CampaignEvent.ReviseTests _, CampaignEvent.ReportTestSound _, CampaignEvent.BlockTestRevision _ ->
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
	 * starts the session that writes reproduction values after a failed certification attempt, commits, starts a prompt
	 * author session and admits trials. Work whose logical trial the campaign discarded ends without an outcome.
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
			cancel("The host interrupted the run", now);
			return stop();
		}
		if (!now.isBefore(graceEnd))
		{
			cancel("The slice's grace period ended before the session did", now);
			return stop();
		}
		if (infrastructureFailure != null)
		{
			cancel(infrastructureFailure, now);
			return stop();
		}
		if (!isDrained() && isPending(PendingDecision.MeasurementChanged.class))
			cancel("The measurement conditions changed", now);

		boolean started = startTriages(now);
		started |= startJudges(now);
		discardRemovedTrials();
		if (now.isBefore(deadline))
		{
			started |= advanceMinimality();
			started |= startValues(now);
			started |= commitIfDue();
			started |= startPromptAuthor(now);
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
				cancel("The driver closed before the session ended", context.clock().instant());
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
	 * Indicates whether the campaign may start new work: it is active, confirmed, and waits for no decision, contract
	 * revision or test revision.
	 *
	 * @return {@code true} if new work may start
	 */
	private boolean isAdmissible()
	{
		return campaign.lifecycle() == Lifecycle.ACTIVE && campaign.isConfirmed() && campaign.pending() == null &&
			!campaign.revisionRequested() && !campaign.awaitsTestRevision();
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
	 * @throws IOException if the store cannot be written
	 */
	private void fail(TrialKey key, RunFailure failure, String detail, Instant now) throws IOException
	{
		if (findRunningTrial(key).isPresent())
			record(new CampaignEvent.Fail(key, failure, detail, now));
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
		holdAdmissions(result);
		if (session instanceof InFlight.Trial trial)
			recordTrialSession(trial, result, now);
		switch (session)
		{
			case InFlight.Trial trial -> trialEnded(trial, result, event.failure(), now);
			case InFlight.Judge judge -> judgeEnded(judge.judging(), result, event.failure(), now);
			case InFlight.Triage triage -> triageEnded(triage.triaging(), result, event.failure());
			case InFlight.PromptAuthor promptAuthor -> promptAuthorEnded(promptAuthor, result, event.failure());
			case InFlight.Values values -> valuesEnded(values, result, event.failure());
		}
	}

	/**
	 * Holds admissions until the harness's shared hold ends after the provider rejected or refused a session. A refusal
	 * that followed a 30-minute hold stops the slice.
	 *
	 * @param result the session's result, or {@code null} if it failed with an exception
	 * @throws IOException if the store cannot be written
	 */
	private void holdAdmissions(SessionResult result) throws IOException
	{
		switch (result)
		{
			case null ->
			{
			}
			case SessionResult.Completed _, SessionResult.Interrupted _, SessionResult.Failed _ ->
			{
			}
			case SessionResult.RateLimited limited ->
				record(new CampaignEvent.HoldAdmissions(limited.hold().endsAt(), limited.hold().backoffs()));
			case SessionResult.Refused refused ->
			{
				record(new CampaignEvent.HoldAdmissions(refused.hold().endsAt(), refused.hold().backoffs()));
				if (refused.afterLongestHold() && infrastructureFailure == null)
				{
					infrastructureFailure = "The provider refused " + campaign.header().harness().commandName() +
						" requests after a 30-minute hold";
				}
			}
		}
	}

	/**
	 * Persists reported counts in the campaign's evidence before a finished session's output is validated or adopted.
	 *
	 * @param session the finished session
	 * @param result the session's result, or {@code null} if it failed with an exception
	 * @throws IOException if campaign evidence cannot be written
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
			case InFlight.PromptAuthor _ -> SessionRole.PROMPT_AUTHOR;
			case InFlight.Values _ -> SessionRole.CONTRACT_AUTHOR;
		};

		record(new CampaignEvent.ObserveTokenUsage(role, completed.usage()));
	}

	/**
	 * Appends the record of an ended trial session to its trial's record before the session's output is validated or
	 * adopted. The record holds the throttle state after the session's reported rate-limit windows were applied.
	 *
	 * @param trial the session
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param now the current time
	 * @throws IOException if the trial record cannot be written
	 */
	private void recordTrialSession(InFlight.Trial trial, SessionResult result, Instant now) throws IOException
	{
		List<RateLimitWindow> windows = List.of();
		if (result != null)
			windows = result.windows();
		TokenUsage usage = null;
		if (result instanceof SessionResult.Completed completed)
			usage = completed.usage();
		context.store().writeTrialSession(campaignId, trial.key(), new TrialSession(trial.startedAt(), now,
			trial.concurrency(), campaign.throttle(), windows, login, usage));
	}

	/**
	 * Consumes the end of a trial session. A completed session's built-in tools decide it or send it to its
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
			case null -> fail(key, RunFailure.INFRASTRUCTURE, "The trial session failed: " + describe(failure), now);
			case SessionResult.Completed completed ->
			{
				evidence.recordDuration(Duration.between(trial.startedAt(), now));
				if (findRunningTrial(key).isPresent())
					decideTools(trial, completed, now);
			}
			case SessionResult.Interrupted _ ->
				fail(key, RunFailure.INTERRUPTED, "The trial session was interrupted", now);
			// The trial reruns once the harness's shared hold ends
			case SessionResult.RateLimited _ -> fail(key, RunFailure.RATE_LIMITED,
				"The provider rejected the trial session because of a rate limit", now);
			case SessionResult.Refused _ -> fail(key, RunFailure.RATE_LIMITED,
				"The provider refused the trial session", now);
			case SessionResult.Failed failed -> fail(key, RunFailure.INFRASTRUCTURE, failed.reason(), now);
		}
	}

	/**
	 * Runs a completed trial's built-in tools first. A failed tool whose {@code on_tool_failure} is {@code fail}
	 * fails the trial with the record of each such tool and no judge runs; a failed development trial waits for its
	 * triage before the failure is recorded. Otherwise the trial waits for a judge of the assertions without a
	 * {@code tool}, of the assertions whose failed tool leaves them to the judge, and of the assertion
	 * {@value TestCase#EXPECTED_OUTCOME}, which receives the tool results as evidence. A tool that the record cannot
	 * decide, or evidence that cannot be rendered, makes the run an infrastructure failure.
	 *
	 * @param trial the trial session
	 * @param completed the trial session's result
	 * @param now the current time
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void decideTools(InFlight.Trial trial, SessionResult.Completed completed, Instant now)
		throws IOException
	{
		DrawnTestCase testCase = trial.testCase();
		PromptTest test = campaign.contract().findTest(testCase.testId()).orElseThrow();
		List<BuiltInToolResult> results;
		try
		{
			new Generator(campaign.contract(), campaign.developmentTestCases()).validate(testCase);
			results = test.decide(testCase.testCase(), TrialEvidence.read(context.sessions(), completed));
		}
		catch (IOException | UndecidableToolException e)
		{
			fail(trial.key(), RunFailure.INFRASTRUCTURE, "The built-in tools could not be decided: " +
				describe(e), now);
			return;
		}
		List<BuiltInToolResult> failed = results.stream().filter(BuiltInToolResult::failsTrial).toList();
		if (!failed.isEmpty())
		{
			String record = toolRecord("Failed built-in tools:", failed);
			List<FailedAssertion> failures = failed.stream().
				map(result -> new FailedAssertion(result.assertion().id(), result.behavior())).
				toList();
			if (trial.key().purpose() != Purpose.DEVELOPMENT)
			{
				complete(trial.key(), testCase, trial.concurrency(), completed, Verdict.FAIL, record, failures, null);
				return;
			}
			Optional<String> section = renderEvidence(trial.key(), completed, now);
			if (section.isPresent())
			{
				triageQueue.add(new Triaging(trial.key(), testCase, completed, section.get(), results, record, failures,
					1, trial.concurrency()));
			}
			return;
		}
		List<JudgedAssertion> judged = JudgedAssertion.of(test, campaign.contract().requirements(), results);
		String section = "";
		if (judgeRendersEvidence())
		{
			Optional<String> rendered = renderEvidence(trial.key(), completed, now);
			if (rendered.isEmpty())
				return;
			section = rendered.get();
		}
		judgingQueue.add(new Judging(trial.key(), testCase, completed, section, judged, results, 1,
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
	 * @param now the current time
	 * @return the rendered evidence, or empty if it could not be rendered
	 * @throws IOException if the store cannot be written
	 */
	private Optional<String> renderEvidence(TrialKey key, SessionResult.Completed completed, Instant now)
		throws IOException
	{
		try
		{
			return Optional.of(TrialEvidence.read(context.sessions(), completed).render());
		}
		catch (IOException e)
		{
			fail(key, RunFailure.INFRASTRUCTURE, "The trial evidence could not be rendered: " + describe(e), now);
			return Optional.empty();
		}
	}

	/**
	 * Returns the record of built-in tools that labels a trial in place of a judge's rationale.
	 *
	 * @param heading the record's first line
	 * @param results the tools' results
	 * @return the record: the heading, then one line per tool naming its assertion, its {@code tool}, its
	 *   behavior and its observed value
	 */
	private static String toolRecord(String heading, List<BuiltInToolResult> results)
	{
		StringBuilder record = new StringBuilder(heading);
		for (BuiltInToolResult result : results)
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
			SessionRequest request = context.judge().request(campaign.contract().judge(), judging.testCase(),
				judging.evidence(), judging.assertions(), judging.results(), judging.evidenceSection());
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
				Optional<Judgment> judgment = context.judge().judgment(completed, judging.assertions());
				if (judgment.isPresent())
				{
					judged(judging, judgment.get(), now);
					return;
				}
				reason = "The judge's output does not match its result schema";
			}
			case SessionResult.RateLimited _, SessionResult.Refused _ ->
			{
				// A rate limit or a refusal is not a failure of the judge, which reruns once the shared hold ends
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
	 * @param now the current time
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void judged(Judging judging, Judgment judgment, Instant now) throws IOException
	{
		if (judgment.verdict() != Verdict.FAIL || judging.key().purpose() != Purpose.DEVELOPMENT)
		{
			complete(judging.key(), judging.testCase(), judging.concurrency(), judging.evidence(), judgment.verdict(),
				judgment.rationale(), judgment.failures(), null);
			return;
		}
		String section = judging.evidenceSection();
		if (!judgeRendersEvidence())
		{
			Optional<String> rendered = renderEvidence(judging.key(), judging.evidence(), now);
			if (rendered.isEmpty())
				return;
			section = rendered.get();
		}
		triageQueue.add(new Triaging(judging.key(), judging.testCase(), judging.evidence(), section, judging.results(),
			judgment.rationale(), judgment.failures(), 1, judging.concurrency()));
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
			"failure: " + reason, now);
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
				triaging.testCase(), triaging.evidence(), triaging.results(), triaging.evidenceSection(),
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
	 * @throws IOException if the store or the worktree cannot be read or written
	 */
	private void triageEnded(Triaging triaging, SessionResult result, Throwable failure)
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
			case SessionResult.RateLimited _, SessionResult.Refused _ ->
			{
				// A rate limit or a refusal is not a failure of the triage, which reruns once the shared hold ends
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
		complete(triaging.key(), triaging.testCase(), triaging.concurrency(), triaging.evidence(), Verdict.FAIL,
			triaging.rationale(), triaging.failures(), triage);
	}

	/**
	 * Records a trial's verdict when its observed measurement conditions match the campaign's, persisting it before
	 * adoption. Different conditions pause the campaign without recording the verdict. A
	 * promotion replaces the working candidate in the optimization worktree once the worktree is verified. Once a
	 * passing verdict is recorded, the trial's effects and its copy of the initial project are deleted; a failed trial
	 * keeps them as the evidence of its failure.
	 *
	 * @param key the logical trial
	 * @param testCase the trial's test case
	 * @param concurrency the number of trial sessions that ran once the trial's session started
	 * @param trial the trial session's result
	 * @param verdict the trial's label
	 * @param rationale the judge's rationale or, when a built-in tool failed, the record of the failed tools
	 * @param failures the assertions that a failed trial failed, each with its behavior; empty for a pass
	 * @param triage the impact of a failed development trial, or {@code null} for any other trial
	 * @throws IOException if the store or the worktree cannot be read or written, or a passing trial's project copies
	 *   cannot be deleted
	 */
	private void complete(TrialKey key, DrawnTestCase testCase, int concurrency, SessionResult.Completed trial,
		Verdict verdict, String rationale, List<FailedAssertion> failures, Triage triage)
		throws IOException
	{
		MeasurementIdentity observed = observedIdentity(testCase, trial);
		TrialFiles files = new TrialFiles(trial.transcript(), trial.effects(), trial.initialProject());
		CampaignEvent.Complete complete = new CampaignEvent.Complete(key, verdict, rationale, failures,
			triage, observed, trialInputTokens(trial), trial.responses(), files);
		boolean recorded = campaign.identity().differences(observed).isEmpty();
		if (recorded)
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
		if (recorded && verdict == Verdict.PASS)
			deleteProjects(trial);
	}

	/**
	 * Deletes a trial's effects and its copy of the initial project. Nothing reads a passing trial's project copies,
	 * and each holds a full copy of the trial's project.
	 *
	 * @param trial the trial session's result
	 * @throws IOException if either directory cannot be deleted
	 */
	static void deleteProjects(SessionResult.Completed trial) throws IOException
	{
		TemporaryDirectories.deleteRecursively(trial.effects());
		TemporaryDirectories.deleteRecursively(trial.initialProject());
	}

	/**
	 * Returns the measurement conditions under which a trial was observed: the driver's environment, the trial
	 * session's loaded prompt sources, and the trial session's model and reasoning configuration and, in a cell that
	 * limits tool output, the tool-output token limit that the session started under, which are recorded for the
	 * trial's cell.
	 *
	 * @param testCase the trial's test case
	 * @param trial the trial session's result
	 * @return the conditions
	 */
	private MeasurementIdentity observedIdentity(DrawnTestCase testCase, SessionResult.Completed trial)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(context.environment().values());
		values.put(MeasurementCondition.PROMPT_SOURCES, trial.promptSources());
		Map<MeasurementCondition, String> cell = new EnumMap<>(MeasurementCondition.class);
		if (trial.model() != null)
			cell.put(MeasurementCondition.MODEL, trial.model());
		if (trial.reasoning() != null)
			cell.put(MeasurementCondition.REASONING, trial.reasoning());
		int toolOutputTokenLimit = campaign.contract().findCell(testCase.cellId()).orElseThrow().profile().
			toolOutputTokenLimit();
		if (toolOutputTokenLimit > 0)
			cell.put(MeasurementCondition.TOOL_OUTPUT_LIMIT, String.valueOf(toolOutputTokenLimit));
		return new MeasurementIdentity(values, Map.of(testCase.cellId(), cell));
	}

	/**
	 * Indicates whether a candidate commitment or calibration failure was recorded. Commits the working candidate if the
	 * campaign meets the commit gate, calibrating the attempt's trial cap first and saving the control source that the
	 * attempt's control trials run on.
	 *
	 * @return {@code true} if a commit or a calibration failure was recorded
	 * @throws IOException if the store or the control source cannot be written
	 */
	private boolean commitIfDue() throws IOException
	{
		if (!CommitGate.isCommitDue(campaign))
			return false;
		CampaignEvent event;
		try
		{
			FileInventory fork = context.overlays().require(campaign.incumbent().snapshot()).fork();
			Overlay control = ControlSource.of(context.workspace(), fork,
				campaign.header().root().relativize(campaign.header().target()), campaign.contract().projectRemovals(),
				context.json());
			context.overlays().save(control);
			event = new CampaignEvent.Commit(context.calibrator().calibrate(
				ErrorBudget.certificationLevel(campaign.nextCertificationIndex()),
				campaign.contract().minimumPassRate()), context.random().nextLong(), control.identity());
		}
		catch (CapCalibrationException e)
		{
			event = new CampaignEvent.CalibrationFailed(e.getMessage());
		}
		record(event);
		return true;
	}

	/**
	 * Starts the session that writes the test cases of the first failure group whose reproduction round awaits
	 * values. One such session runs at a time, so each one knows every test case written before it.
	 *
	 * @param now the current time
	 * @return whether the session was started or failed to start
	 */
	private boolean startValues(Instant now)
	{
		if (!isAdmissible() || hasValuesSession() || freeCapacity() <= 0 || !campaign.throttle().allowsAdmission(now))
			return false;
		Optional<CertificationAttempt> reproducing = campaign.reproducing();
		if (reproducing.isEmpty())
			return false;
		CertificationAttempt attempt = reproducing.get();
		List<FailureGroup> groups = attempt.groups();
		int number = 0;
		for (int i = 0; i < groups.size() && number == 0; ++i)
		{
			if (groups.get(i).awaitsValues())
				number = i + 1;
		}
		if (number == 0)
			return false;
		SessionRequest request = context.values().request(campaign.contract(), campaign.developmentTestCases(),
			attempt.group(number));
		SessionHandle handle;
		try
		{
			handle = context.sessions().start(request);
		}
		catch (IOException e)
		{
			infrastructureFailure = "The session that writes reproduction values could not start: " + describe(e);
			return true;
		}
		register(new InFlight.Values(attempt.index(), number, handle));
		return true;
	}

	/**
	 * Indicates whether a session that writes reproduction values is running.
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
	 * Consumes the end of a session that writes reproduction values. Valid test cases become the failure group's
	 * current round, whose reproduction trials then run; a rejected response or a failed session is an infrastructure
	 * failure. The result is ignored if its group's round does not await values when the session ends.
	 *
	 * @param values the session
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null}
	 * @throws IOException if the store cannot be written
	 */
	private void valuesEnded(InFlight.Values values, SessionResult result, Throwable failure)
		throws IOException
	{
		Optional<CertificationAttempt> reproducing = campaign.reproducing();
		if (reproducing.isEmpty() || reproducing.get().index() != values.attempt() ||
			!reproducing.get().group(values.group()).awaitsValues())
		{
			return;
		}
		FailureGroup group = reproducing.get().group(values.group());
		switch (result)
		{
			case null -> infrastructureFailure = "The session that writes reproduction values failed: " +
				describe(failure);
			case SessionResult.Completed completed ->
			{
				List<AddedTestCase> testCases;
				try
				{
					testCases = context.values().testCases(completed, campaign.contract(),
						campaign.developmentTestCases(), values.attempt(), group);
				}
				catch (InvalidProposalException e)
				{
					infrastructureFailure = "The reproduction values were rejected: " + String.join(" ", e.problems());
					return;
				}
				record(new CampaignEvent.ReproductionValues(values.attempt(), values.group(), testCases));
			}
			// The session starts again once the harness's shared hold ends
			case SessionResult.RateLimited _, SessionResult.Refused _ ->
			{
			}
			case SessionResult.Interrupted _ -> infrastructureFailure =
				"The session that writes reproduction values was interrupted";
			case SessionResult.Failed failed -> infrastructureFailure =
				"The session that writes reproduction values failed: " + failed.reason();
		}
	}

	/**
	 * Starts the round's prompt authors within the available session capacity, concurrently when the frozen policy is
	 * weak. Weak prompt authors wait for already assigned development trials to settle so every attempt receives the
	 * same brief.
	 *
	 * @param now the current time
	 * @return whether a prompt author session was started or failed to start
	 * @throws IOException if the workspace or reservation cannot be read or written
	 */
	private boolean startPromptAuthor(Instant now) throws IOException
	{
		boolean started = false;
		while (isAdmissible() && campaign.development().round() != null && campaign.development().
			promptAuthor() == null && promptAuthorFailures < PROMPT_AUTHOR_FAILURE_LIMIT && freeCapacity() > 0 &&
			campaign.throttle(). allowsAdmission(now))
		{
			Round round = campaign.development().round();
			if (round.weak() && campaign.trials().stream().anyMatch(trial -> trial.key().purpose() == Purpose.DEVELOPMENT))
				break;
			if (!round.weak() && hasPromptAuthorSession())
				break;
			Optional<Entry<Integer, String>> reservation = reservePromptAuthor();
			if (reservation.isEmpty())
				break;

			startPromptAuthorSession(reservation.get().getKey(), reservation.get().getValue());
			started = true;
			if (!round.weak())
				break;
		}
		return started;
	}

	/**
	 * Finds an unfinished prompt author reservation whose session is absent, or reserves the next unstarted attempt.
	 *
	 * @return the reservation in launch order, or empty when every slot has a session or has been submitted
	 * @throws IOException if a new reservation cannot be persisted
	 */
	private Optional<Entry<Integer, String>> reservePromptAuthor() throws IOException
	{
		Round round = campaign.development().round();
		for (Entry<Integer, String> entry : round.pendingPromptAuthorAttempts().entrySet())
		{
			if (!isPromptAuthorRunning(entry.getKey()))
				return Optional.of(entry);
		}
		Optional<String> cell = round.nextCell();
		if (cell.isEmpty())
			return Optional.empty();

		record(new CampaignEvent.StartPromptAuthorAttempt(cell.get()));
		return campaign.development().round().pendingPromptAuthorAttempts().entrySet().stream().
			filter(entry -> !isPromptAuthorRunning(entry.getKey())).findFirst();
	}

	/**
	 * Indicates whether a prompt author reservation has a running session in this slice.
	 *
	 * @param number the reserved attempt number
	 * @return whether that session is running
	 */
	private boolean isPromptAuthorRunning(int number)
	{
		return inFlight.values().stream().anyMatch(session -> session instanceof InFlight.PromptAuthor promptAuthor &&
			promptAuthor.number() == number);
	}

	/**
	 * Launches an already persisted prompt author reservation from the unchanged working candidate and its frozen
	 * evidence.
	 *
	 * @param number the reserved attempt number
	 * @param cell the addressed cell
	 * @throws IOException if the workspace or overlay cannot be read or written
	 */
	private void startPromptAuthorSession(int number, String cell) throws IOException
	{
		Candidate working = campaign.development().working();
		Overlay overlay = context.overlays().require(working.snapshot());
		Path copy = context.workspace().prepareAuthoringCopy(overlay);
		try
		{
			FileInventory before = context.workspace().scanAuthoringCopy(copy);
			Overlay incumbent = context.overlays().require(campaign.incumbent().snapshot());
			String edits = CampaignEdits.of(context.workspace(), incumbent, overlay, context.promptSources());
			Map<Integer, String> sections = renderFailureSections();
			PromptAuthorBrief brief = PromptAuthorBrief.of(campaign, number, overlay.fork(), edits, sections);
			SessionRequest request = context.promptAuthor().request(brief, copy);
			SessionHandle handle = context.sessions().start(request);
			register(new InFlight.PromptAuthor(number, campaign.lineage().number(), cell, working, overlay, copy,
				before, brief, handle));
		}
		catch (IOException e)
		{
			context.workspace().deleteAuthoringCopy(copy);
			promptAuthorFailed("The prompt author session could not start: " + describe(e));
		}
	}

	/**
	 * Returns the paths that a staged source leaves out so that a trial cannot read the prompt tests.
	 *
	 * @return the confirmed contract's prompt test directories, cited regression scenarios and the shared workspace file
	 *   directories that the cited scenarios name, relative to the project
	 * @throws IOException if a cited scenario cannot be read
	 */
	private Set<Path> excludedTests() throws IOException
	{
		if (!campaign.isConfirmed())
			return Set.of();
		Path root = campaign.header().root();
		Path target = root.relativize(campaign.header().target());
		return PromptTestLocations.excluded(campaign.contract(), target, root);
	}

	/**
	 * Returns the files of a candidate that a staged source holds, so that a trial's project holds only the files that
	 * load the prompt system.
	 *
	 * @return the campaign's base selection, which holds the contract's in-project dependents once a contract is
	 *   confirmed
	 */
	private SourceSelection trialSelection()
	{
		Harness harness = campaign.header().harness();
		Set<Path> loaderConfiguration = context.authority().loaderConfiguration();
		List<TrialPlugin> plugins = campaign.plugins().orElse(List.of());
		if (!campaign.isConfirmed())
			return TrialSelection.base(harness, context.promptSources(), loaderConfiguration, plugins);
		return TrialSelection.base(harness, context.promptSources(), loaderConfiguration, campaign.contract(), plugins);
	}

	/**
	 * Renders the evidence section of each of the working candidate's development failures, as a judge receives it,
	 * against the project as the failure's trial started. A failure whose trial's transcript, effects or initial
	 * project are no longer retained has no section.
	 *
	 * @return each rendered section, keyed by the failure's index in the working candidate's development failures
	 * @throws IOException if a retained trial's evidence cannot be read
	 */
	private Map<Integer, String> renderFailureSections() throws IOException
	{
		List<Failure> failures = campaign.development().failures();
		Map<Integer, String> sections = new HashMap<>();
		for (int index = 0; index < failures.size(); ++index)
		{
			TrialFiles files = failures.get(index).files();
			if (Files.isRegularFile(files.transcript()) && Files.isDirectory(files.effects()) &&
				Files.isDirectory(files.initialProject()))
			{
				SessionEvidence activity = context.sessions().readEvidence(files.transcript());
				sections.put(index, EvidenceSection.render(activity, files.transcript(), files.effects(),
					files.initialProject()));
			}
		}
		return sections;
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
	 * Indicates whether a prompt author session runs.
	 *
	 * @return {@code true} if one runs
	 */
	private boolean hasPromptAuthorSession()
	{
		for (InFlight session : inFlight.values())
		{
			if (session instanceof InFlight.PromptAuthor)
				return true;
		}
		return false;
	}

	/**
	 * Consumes the end of a prompt author session and deletes the prompt author's copy.
	 *
	 * @param promptAuthor the session
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null}
	 * @throws IOException if the store or the workspace cannot be read or written
	 */
	private void promptAuthorEnded(InFlight.PromptAuthor promptAuthor, SessionResult result, Throwable failure)
		throws IOException
	{
		try
		{
			switch (result)
			{
				case null -> promptAuthorFailed("The prompt author session failed: " + describe(failure));
				case SessionResult.Completed completed ->
				{
					submit(promptAuthor, completed);
				}
				// The prompt author starts again with the same brief once the harness's shared hold ends
				case SessionResult.RateLimited _, SessionResult.Refused _ ->
				{
				}
				case SessionResult.Interrupted _ -> promptAuthorFailed("The prompt author session was interrupted");
				case SessionResult.Failed failed -> promptAuthorFailed(failed.reason());
			}
		}
		finally
		{
			context.workspace().deleteAuthoringCopy(promptAuthor.copy());
		}
	}

	/**
	 * Validates a completed prompt author attempt against its starting snapshot and submits its candidate, then checks
	 * the edits that the prompt author reported needing against the edit authority. An attempt with a forbidden
	 * operation or a malformed report contributes no candidate and counts as an infrastructure failure. A rejected
	 * manifest is retained and contributes no candidate. An attempt that classifies no kind routing or guidance offers
	 * its working snapshot, which is no candidate. A needed
	 * edit outside the authority raises an {@code awaiting_user} decision that holds the warnings. A valid conflict
	 * report discards the candidate and pauses for the user's resolution.
	 *
	 * @param promptAuthor the session
	 * @param completed the session's result
	 * @throws IOException if the store, the overlays or the session's effects cannot be read or written
	 */
	private void submit(InFlight.PromptAuthor promptAuthor, SessionResult.Completed completed) throws IOException
	{
		Optional<PromptAuthorReport> report = context.promptAuthor().report(completed);
		if (report.isEmpty() || !namesContractRequirements(report.get().neededEdits()))
		{
			promptAuthorFailed("The prompt author's report of needed edits is malformed");
			return;
		}
		Authority authority = context.authority();
		FileInventory fork = promptAuthor.overlay().fork();
		List<AuthorityWarning> warnings = new ArrayList<>();
		for (NeededEdit edit : report.get().neededEdits())
			authority.diagnose(edit.requirement(), edit.path(), edit.operation(), fork).ifPresent(warnings::add);

		// The session edits the runner's copy of the authoring copy, so its edits are in the effects directory
		FileInventory after = context.workspace().scanPromptAuthorEffects(promptAuthor.copy(), completed.effects());
		AttemptReview review = InventoryDiff.between(promptAuthor.before(), after).review(fork, authority);
		if (!review.isPermitted())
		{
			promptAuthorFailed("The prompt author attempt made forbidden changes: " + review.forbidden());
			return;
		}
		List<String> unreceived = unreceivedFiles(completed.effects());
		if (!unreceived.isEmpty())
		{
			promptAuthorFailed("The prompt author attempt loads files that its trials do not receive: " +
				String.join(" ", unreceived));
			return;
		}
		Optional<Overlay> candidate = PromptAuthorDelta.apply(promptAuthor.overlay(), completed.effects(),
			review.changes());
		if (candidate.isEmpty())
		{
			promptAuthorFailed("A file in the prompt author's copy changed while CAT read it");
			return;
		}
		Optional<List<FileDelta>> changes = PromptAuthorDelta.changes(promptAuthor.overlay(), promptAuthor.copy(),
			completed.effects(), review.changes());
		if (changes.isEmpty())
		{
			promptAuthorFailed("A file in the prompt author's effects changed while CAT read it");
			return;
		}
		List<Hunk> hunks = changes.get().stream().flatMap(change -> Hunks.between(change).stream()).toList();
		Set<String> received = promptAuthor.brief().failureIds();
		Overlay incumbent = context.overlays().require(campaign.incumbent().snapshot());
		List<String> violations = report.get().manifest().violations(received, hunks, campaign.contract(),
			incumbent.snapshot(), confirmedContents(incumbent, report.get().conflicts()), report.get().conflicts(),
			promptAuthor.brief().soundFailures());
		PromptAuthorManifest manifest = report.get().manifest();
		record(new CampaignEvent.RetainPromptAuthorDiagnosis(new PromptAuthorDiagnosis(promptAuthor.number(),
			promptAuthor.lineage(), promptAuthor.cell(), promptAuthor.working().snapshot(), manifest.kinds(),
			manifest.hunks(), violations, completed.transcript(), completed.effects())));
		if (!violations.isEmpty())
		{
			promptAuthorFailed("The prompt author manifest is invalid: " + violations);
			return;
		}
		if (!report.get().conflicts().isEmpty())
		{
			if (campaign.lifecycle() == Lifecycle.ACTIVE && campaign.isConfirmed() && !campaign.revisionRequested())
				record(new CampaignEvent.FindConflict(report.get().conflicts()));
			return;
		}
		if (offersPromptAuthorAttempt(promptAuthor))
		{
			// An attempt that classified no kind routing or guidance offers its working snapshot, which is no candidate
			Sha256 snapshot = promptAuthor.working().snapshot();
			List<FileDelta> submitted = List.of();
			if (manifest.producesCandidate(hunks) &&
				!candidate.get().identity().equals(promptAuthor.overlay().identity()))
			{
				context.overlays().save(candidate.get());
				snapshot = candidate.get().identity();
				submitted = changes.get();
			}
			record(new CampaignEvent.SubmitPromptAuthorAttempt(promptAuthor.number(), promptAuthor.cell(), snapshot,
				submitted));
		}
		if (campaign.lifecycle() == Lifecycle.ACTIVE && campaign.isConfirmed() && !campaign.revisionRequested())
		{
			for (TestRevisionRequest request : testDefects(manifest, promptAuthor.brief()))
				record(new CampaignEvent.RequestTestRevision(request));
		}
		if (!warnings.isEmpty() && campaign.lifecycle() == Lifecycle.ACTIVE && campaign.isConfirmed() &&
			!campaign.revisionRequested())
		{
			record(new CampaignEvent.WarnAuthority(warnings));
		}
	}

	/**
	 * Returns the files that load or run in a prompt author attempt's trials but that its trials do not receive: each
	 * project file outside the campaign's trial selection, and each reference to a file that trials cannot receive.
	 *
	 * @param project the project as the prompt author session left it
	 * @return one description of each file, empty if trials receive every file that loads
	 * @throws IOException if the project cannot be read
	 */
	private List<String> unreceivedFiles(Path project) throws IOException
	{
		LoadedSources loaded = context.sessions().loadedSources(project, List.of());
		SourceSelection selection = trialSelection();
		List<String> unreceived = new ArrayList<>();
		for (DerivedSource source : loaded.trials())
		{
			Path path = Path.of(source.path());
			if (!path.isAbsolute() && !selection.selects(path))
				unreceived.add(source.path() + " is not among the files that trials receive.");
		}
		for (UnreachableReference reference : loaded.unreachable())
			unreceived.add(reference.describe());
		return unreceived;
	}

	/**
	 * Returns a test revision request for each test that a test defect kind of a valid manifest names.
	 *
	 * @param manifest the manifest
	 * @param brief the prompt author's brief
	 * @return the requests, in the order that their tests first appear among the test defect kinds
	 */
	private static List<TestRevisionRequest> testDefects(PromptAuthorManifest manifest, PromptAuthorBrief brief)
	{
		Map<String, Failure> failures = brief.failuresById();
		Map<String, List<PromptAuthorKind>> kindsByTest = new LinkedHashMap<>();
		Map<String, Map<String, Failure>> failuresByTest = new LinkedHashMap<>();
		for (PromptAuthorKind kind : manifest.kinds())
		{
			if (kind.classification() != FailureKind.TEST_DEFECT)
				continue;
			for (String id : kind.failures())
			{
				Failure failure = failures.get(id);
				String test = failure.testCase().testId();
				List<PromptAuthorKind> kinds = kindsByTest.computeIfAbsent(test, _ -> new ArrayList<>());
				if (!kinds.contains(kind))
					kinds.add(kind);
				failuresByTest.computeIfAbsent(test, _ -> new LinkedHashMap<>()).put(id, failure);
			}
		}
		List<TestRevisionRequest> requests = new ArrayList<>();
		for (Map.Entry<String, List<PromptAuthorKind>> entry : kindsByTest.entrySet())
		{
			requests.add(new TestRevisionRequest(entry.getKey(), TestRevisionRequest.Trigger.TEST_DEFECT,
				entry.getValue(), failuresByTest.get(entry.getKey())));
		}
		return requests;
	}

	/**
	 * Reads quotation sources from the saved confirmed prompt system rather than the working candidate.
	 *
	 * @param incumbent the confirmed overlay
	 * @param conflicts the reports whose quotations need validation
	 * @return recorded file bytes keyed by resolved path; absent files remain absent for manifest validation
	 * @throws IOException if recorded content cannot be read
	 */
	private Map<Path, FileContent> confirmedContents(Overlay incumbent, List<PromptAuthorConflict> conflicts)
		throws IOException
	{
		Map<Path, FileContent> contents = new HashMap<>();
		for (PromptAuthorConflict conflict : conflicts)
		{
			for (PromptAuthorConflict.Quotation quotation : conflict.quotations())
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
	 * Indicates whether the campaign still offers the prompt author attempt that a session made.
	 *
	 * @param promptAuthor the session
	 * @return {@code true} if the attempt may be submitted
	 */
	private boolean offersPromptAuthorAttempt(InFlight.PromptAuthor promptAuthor)
	{
		if (campaign.lifecycle() != Lifecycle.ACTIVE || !campaign.isConfirmed() || campaign.revisionRequested())
			return false;
		Development development = campaign.development();
		Round round = development.round();
		return round != null && development.promptAuthor() == null && development.working().
			equals(promptAuthor.working()) && promptAuthor.cell().equals(round.pendingPromptAuthorAttempts().
			get(promptAuthor.number()));
	}

	/**
	 * Records a failed prompt author session. The slice stops with an infrastructure failure once
	 * {@value #PROMPT_AUTHOR_FAILURE_LIMIT} prompt author sessions failed.
	 *
	 * @param reason why the session failed
	 */
	private void promptAuthorFailed(String reason)
	{
		++promptAuthorFailures;
		if (promptAuthorFailures >= PROMPT_AUTHOR_FAILURE_LIMIT)
		{
			infrastructureFailure = "The prompt author session failed " + PROMPT_AUTHOR_FAILURE_LIMIT +
				" times. Last failure: " + reason;
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
			Duration expected = evidence.expectedDuration(findTimeLimit(trial.testCase()));
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
	 * Finds a trial's time limit in the confirmed contract.
	 *
	 * @param testCase the trial's test case
	 * @return the time limit of the test case's prompt test
	 */
	private Duration findTimeLimit(DrawnTestCase testCase)
	{
		return campaign.contract().findTest(testCase.testId()).orElseThrow(() ->
			new AssertionError("The contract has no test " + testCase.testId())).timeLimit();
	}

	/**
	 * Stages a started trial's candidate and its copies of the trial plugins, then starts its session. A failure to
	 * start is an infrastructure failure.
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
			source = context.workspace().stage(candidate, excludedTests(), trialSelection());
			staged.put(key.snapshot(), candidate.identity());
			TrialProfile profile = campaign.contract().findCell(trial.testCase().cellId()).orElseThrow().profile();
			PromptTest test = campaign.contract().findTest(trial.testCase().testId()).orElseThrow();
			List<Path> plugins = TrialPlugins.of(campaign.plugins().orElse(List.of()), campaign.contract(),
				context.workspace(), source, key.purpose() == Purpose.CONTROL, context.json());
			handle = context.sessions().start(new SessionRequest(SessionRole.TRIAL, key.purpose().part(), source,
				trial.testCase().turns(), List.of(), test.timeLimit(), profile,
				TestWorkspace.of(test, trial.testCase().testCase()), plugins));
		}
		catch (IOException e)
		{
			fail(key, RunFailure.INFRASTRUCTURE, "The trial session could not start: " + describe(e), now);
			return;
		}
		int concurrency = 1;
		for (InFlight session : inFlight.values())
		{
			if (session instanceof InFlight.Trial)
				++concurrency;
		}
		register(new InFlight.Trial(key, trial.testCase(), now, concurrency, handle));
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
	 * Indicates whether delivery was attempted. Delivers the candidate that a finished campaign selected, or the prompt
	 * tests of an incumbent that the user kept, once per slice. A certified delivery and a kept incumbent write the
	 * confirmed prompt tests.
	 *
	 * @return {@code true} if a delivery was attempted
	 * @throws IOException if the store, the overlays or a file cannot be read or written
	 */
	private boolean deliver() throws IOException
	{
		DeliveryRequest request = campaign.delivery();
		boolean incumbentKept = campaign.isIncumbentKept();
		if (campaign.lifecycle() != Lifecycle.FINISHED || (request == null && !incumbentKept) || delivery != null)
			return false;
		Overlay selected;
		if (incumbentKept)
			selected = context.overlays().require(campaign.incumbent().snapshot());
		else
			selected = context.overlays().require(request.candidate().snapshot());
		Overlay working = context.overlays().require(campaign.development().working().snapshot());
		// A read-only external participant that changed after the fork makes the delivered system a different snapshot
		Set<Path> promptSources = new HashSet<>(context.promptSources());
		for (Path path : selected.fork().entries().keySet())
		{
			if (path.isAbsolute())
				promptSources.add(path);
		}
		CertificationRecord record = null;
		List<DeliveredTest> tests = List.of();
		if (incumbentKept || request.label() == Outcome.CERTIFIED)
		{
			Path target = campaign.header().root().relativize(campaign.header().target());
			tests = DeliveredTest.of(campaign.contract(), campaign.developmentTestCases(), target);
		}
		if (!incumbentKept && request.label() == Outcome.CERTIFIED)
		{
			record = CertificationRecord.of(campaign, campaign.attempt(request.attempt()), selected.snapshot(),
				context.promptSources());
		}
		DeliveryResult result = context.deliverer().deliver(campaignId, request, selected, working,
			promptSources, record, tests);
		Applicability applicability = null;
		if (result instanceof DeliveryResult.Delivered delivered && incumbentKept)
			applicability = delivered.applicability();
		else if (result instanceof DeliveryResult.Delivered delivered)
		{
			Sha256 snapshot = request.candidate().snapshot();
			MeasurementIdentity current = campaign.identity().replaceWith(context.environment());
			Applicability measurement;
			if (request.attempt() > 0)
			{
				CertificationAttempt attempt = campaign.attempt(request.attempt());
				measurement = Applicability.of(attempt, snapshot, current,
					CertificationParameters.current(attempt.index(), campaign.contract().minimumPassRate()));
			}
			else
				measurement = Applicability.of(snapshot, campaign.identity(), snapshot, current);
			applicability = SliceReports.combine(delivered.applicability(), measurement);
		}
		delivery = new DeliveryReport(result, applicability);
		return true;
	}

	/**
	 * Interrupts every session that still runs and returns its trial to the queue, to rerun later as the same
	 * logical trial. Prompt author copies are deleted. Every session is interrupted even if interrupting one fails.
	 *
	 * @param reason why the sessions are cancelled
	 * @param now the current time
	 * @throws IOException if a session cannot be interrupted, or the store or the workspace cannot be written
	 */
	private void cancel(String reason, Instant now) throws IOException
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
				// The prompt author copy is cleaned up by interrupt(); prompt authors have no logical trial to queue.
				case InFlight.PromptAuthor _, InFlight.Values _ ->
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
					now));
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
				case InFlight.PromptAuthor _, InFlight.Values _ -> null;
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
	 * Interrupts a session and deletes its prompt author copy even if the interrupt fails. If both operations fail, the
	 * cleanup failure is suppressed on the interrupt failure.
	 *
	 * @param session the running session
	 * @throws IOException if the interrupt cannot be delivered or the prompt author copy cannot be deleted
	 */
	private void interrupt(InFlight session) throws IOException
	{
		if (session instanceof InFlight.PromptAuthor promptAuthor)
		{
			Closeable cleanup = () -> context.workspace().deleteAuthoringCopy(promptAuthor.copy());
			try (cleanup)
			{
				promptAuthor.handle().interrupt();
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
		if (campaign.development().promptAuthor() != null)
			used.add(campaign.development().promptAuthor().snapshot());
		campaign.runningAttempt().ifPresent(attempt ->
		{
			used.add(attempt.candidate().snapshot());
			used.add(attempt.control());
		});
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
			Path source = context.workspace().stage(overlay, excludedTests(), trialSelection());
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
		boolean symbolicLinks = !LinkReport.of(working.fork(), context.authority()).entries().isEmpty();
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
	static String describe(Throwable failure)
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
