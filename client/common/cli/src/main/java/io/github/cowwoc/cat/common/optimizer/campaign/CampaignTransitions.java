package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.Comparison;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Derives a campaign's next state from an event. The function is pure: it reads no clock, performs no I/O and
 * draws randomness only from the campaign's seeded streams.
 */
public final class CampaignTransitions
{
	/**
	 * Prevents instantiation.
	 */
	private CampaignTransitions()
	{
	}

	/**
	 * Applies an event.
	 *
	 * @param campaign the current state
	 * @param event the event
	 * @return the next state
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the event names a trial, category, choice or attempt that the state does
	 *   not offer
	 * @throws IllegalStateException if the campaign's state does not permit the event
	 */
	public static Campaign apply(Campaign campaign, CampaignEvent event)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(event, "event").isNotNull();
		CampaignDraft draft = new CampaignDraft(campaign);
		switch (event)
		{
			case CampaignEvent.Confirm confirm -> confirm(draft, confirm);
			case CampaignEvent.Revise _ -> revise(draft);
			case CampaignEvent.Assign assign -> assign(draft, assign.admission());
			case CampaignEvent.Start start -> start(draft, start.key());
			case CampaignEvent.Complete complete -> complete(draft, complete);
			case CampaignEvent.Fail fail -> fail(draft, fail);
			case CampaignEvent.ObserveRateLimit observe ->
			{
				draft.throttle = draft.throttle.observe(observe.windows(), observe.at(),
					ConcurrencyPolicy.window(campaign));
				if (!draft.throttle.allowsAdmission(observe.at()))
					draft.slowStart = SlowStart.INITIAL;
			}
			case CampaignEvent.SubmitAuthorAttempt submit -> submitAuthorAttempt(draft, submit);
			case CampaignEvent.Commit commit -> commit(draft, campaign, commit);
			case CampaignEvent.CalibrationFailed failed ->
			{
				requireActive(draft);
				draft.raise(new PendingDecision.CalibrationFailure(failed.detail()));
			}
			case CampaignEvent.Choose choose -> UserChoices.apply(draft, campaign, choose);
			case CampaignEvent.Cancel _ ->
			{
				requireActive(draft);
				draft.header = draft.header.withLifecycle(Lifecycle.CANCELLED);
			}
			case CampaignEvent.Close _ ->
			{
				if (!draft.header.lifecycle().isResumable())
					throw new IllegalStateException("The campaign is already " + draft.header.lifecycle());
				draft.header = draft.header.withLifecycle(Lifecycle.CLOSED);
			}
			case CampaignEvent.ChangeMeasurement change -> changeMeasurement(draft, change.observed());
			case CampaignEvent.DetectUnownedChange change ->
			{
				requireActive(draft);
				draft.raise(new PendingDecision.UnownedChange(change.description()));
			}
			case CampaignEvent.ReportUnavailable report -> reportUnavailable(draft, report.outcome());
		}
		TrialAdoption.settle(draft);
		TrialAdoption.checkReviewPoints(draft);
		return draft.build();
	}

	/**
	 * Requires an active campaign.
	 *
	 * @param draft the state
	 * @throws IllegalStateException if the campaign is not active
	 */
	private static void requireActive(CampaignDraft draft)
	{
		if (draft.header.lifecycle() != Lifecycle.ACTIVE)
			throw new IllegalStateException("The campaign is " + draft.header.lifecycle() + ", not ACTIVE");
	}

	/**
	 * Requires an active campaign with a confirmed contract.
	 *
	 * @param draft the state
	 * @throws IllegalStateException if the campaign is not active or has no confirmed contract
	 */
	private static void requireConfirmed(CampaignDraft draft)
	{
		requireActive(draft);
		if (!draft.isConfirmed())
			throw new IllegalStateException("No contract has been confirmed");
	}

	/**
	 * Confirms a contract, which starts a lineage, an incumbent baseline and a development series.
	 *
	 * @param draft the state
	 * @param confirm the event
	 * @throws IllegalStateException if a contract is confirmed and no revision is requested, or a trial runs
	 */
	private static void confirm(CampaignDraft draft, CampaignEvent.Confirm confirm)
	{
		requireActive(draft);
		if (draft.isConfirmed() && !draft.revisionRequested)
			throw new IllegalStateException("A contract is confirmed and no revision was requested");
		for (LogicalTrial trial : draft.trials)
		{
			if (trial.state() == TrialState.RUNNING)
			{
				throw new IllegalStateException("Trial " + trial.key() + " is running; a new contract takes effect " +
					"at a safe checkpoint");
			}
		}
		draft.startLineage(new Lineage(draft.lineageNumber() + 1, confirm.contract(), confirm.contractHash(),
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, confirm.seed()).state(),
			DrawStream.create(DrawStream.Kind.BASELINE, confirm.seed()).state(), List.of(), Set.of()));
		Map<MeasurementCondition, String> contractValues = new EnumMap<>(MeasurementCondition.class);
		contractValues.put(MeasurementCondition.CONTRACT, confirm.contractHash().hex());
		contractValues.put(MeasurementCondition.GENERATOR, Generator.IDENTITY);
		draft.identity = draft.identity.replaceWith(new MeasurementIdentity(contractValues));
		draft.startBaseline();
		draft.startDevelopment(draft.working);
		draft.slowStart = SlowStart.INITIAL;
		draft.pendingDecisions.clear();
		draft.outcome = null;
		draft.revisionRequested = false;
	}

	/**
	 * Requests a contract revision. A running attempt stops; trials that have not started are discarded.
	 *
	 * @param draft the state
	 * @throws IllegalStateException if the campaign is not active or has no confirmed contract
	 */
	private static void revise(CampaignDraft draft)
	{
		requireConfirmed(draft);
		draft.endRunningAttempt(Outcome.STOPPED_FOR_CONTRACT_REVISION);
		draft.revisionRequested = true;
		draft.pendingDecisions.clear();
	}

	/**
	 * Assigns a logical trial.
	 *
	 * @param draft the state
	 * @param admission the admission
	 * @throws IllegalArgumentException if the state does not offer the admission
	 * @throws IllegalStateException if the campaign is not active, has no confirmed contract or waits for a
	 *   revision
	 */
	private static void assign(CampaignDraft draft, Admission.Assign admission)
	{
		requireConfirmed(draft);
		if (draft.revisionRequested)
			throw new IllegalStateException("The campaign waits for a contract revision");
		Contract contract = draft.contract();
		String category = admission.category();
		if (contract.findCategory(category).isEmpty())
			throw new IllegalArgumentException("The contract has no category " + category);
		TrialKey key = new TrialKey(admission.purpose(), admission.series(), admission.snapshot(), category,
			draft.nextSequence(new SequenceGroup(category, admission.purpose(), admission.snapshot())));
		TrialInstance instance = switch (admission.purpose())
		{
			case DEVELOPMENT ->
			{
				requireOffered(admission, draft.series, draft.working);
				DrawStream stream = DrawStream.resume(draft.developmentStream);
				TrialInstance drawn = draft.generator().draw(category, stream);
				draft.developmentStream = stream.state();
				yield drawn;
			}
			case BASELINE ->
			{
				requireOffered(admission, draft.baselineIndex, draft.incumbent);
				DrawStream stream = DrawStream.resume(draft.baselineStream);
				TrialInstance drawn = draft.generator().draw(category, stream);
				draft.baselineStream = stream.state();
				yield drawn;
			}
			case FORMAL -> assignFormal(draft, admission);
			case DIAGNOSTIC -> assignDiagnostic(draft, admission, key);
			case PROMOTION -> assignPromotion(draft, admission, key);
		};
		draft.trials.add(LogicalTrial.assigned(key, instance, draft.identity));
	}

	/**
	 * Requires an admission to name a series and a candidate's snapshot.
	 *
	 * @param admission the admission
	 * @param series the expected series
	 * @param candidate the expected candidate
	 * @throws IllegalArgumentException if the admission names another series or snapshot
	 */
	private static void requireOffered(Admission.Assign admission, int series, Candidate candidate)
	{
		if (admission.series() != series || !admission.snapshot().equals(candidate.snapshot()))
		{
			throw new IllegalArgumentException("The state does not offer " + admission + "; expected series " +
				series + " of " + candidate);
		}
	}

	/**
	 * Draws a formal trial of the running attempt.
	 *
	 * @param draft the state
	 * @param admission the admission
	 * @return the instance
	 * @throws IllegalArgumentException if no attempt runs or the admission names another attempt or candidate
	 */
	private static TrialInstance assignFormal(CampaignDraft draft, Admission.Assign admission)
	{
		Optional<CertificationAttempt> running = draft.runningAttempt();
		if (running.isEmpty())
			throw new IllegalArgumentException("No certification attempt runs: " + admission);
		CertificationAttempt attempt = running.get();
		requireOffered(admission, attempt.index(), attempt.candidate());
		DrawStream stream = DrawStream.resume(attempt.formalStream());
		TrialInstance drawn = draft.generator().draw(admission.category(), stream);
		draft.replaceAttempt(new CertificationAttempt(attempt.index(), attempt.lineage(), attempt.candidate(),
			attempt.identity(), attempt.procedure(), attempt.counts(), attempt.failures(), stream.state(), null));
		return drawn;
	}

	/**
	 * Assigns the diagnostic rerun of the first failing instance.
	 *
	 * @param draft the state
	 * @param admission the admission
	 * @param key the rerun's key
	 * @return the instance
	 * @throws IllegalArgumentException if no rerun is due or the admission names another instance
	 */
	private static TrialInstance assignDiagnostic(CampaignDraft draft, Admission.Assign admission, TrialKey key)
	{
		Diagnosis diagnosis = draft.diagnosis;
		requireOffered(admission, draft.series, draft.working);
		if (diagnosis == null || diagnosis.pending().isEmpty() || diagnosis.rerun() != null ||
			!Promotion.isSameContent(diagnosis.pending().getFirst(), admission.instance()))
		{
			throw new IllegalArgumentException("No diagnostic rerun of " + admission.instance() + " is due");
		}
		draft.diagnosis = new Diagnosis(diagnosis.pending(), diagnosis.passedReruns(), key, diagnosis.reproduced());
		return admission.instance();
	}

	/**
	 * Assigns an unassigned promotion run of the author attempt under validation.
	 *
	 * @param draft the state
	 * @param admission the admission
	 * @param key the run's key
	 * @return the instance
	 * @throws IllegalArgumentException if no unassigned run matches the admission
	 */
	private static TrialInstance assignPromotion(CampaignDraft draft, Admission.Assign admission, TrialKey key)
	{
		AuthorAttempt author = draft.author;
		if (author == null || admission.series() != author.number())
			throw new IllegalArgumentException("No author attempt " + admission.series() + " is under validation");
		List<PromotionRun> runs = new ArrayList<>(author.runs());
		for (int i = 0; i < runs.size(); ++i)
		{
			PromotionRun run = runs.get(i);
			Sha256 snapshot = Promotion.snapshot(author, run, draft.working.snapshot());
			if (run.trial() == null && snapshot.equals(admission.snapshot()) &&
				Promotion.isSameContent(run.instance(), admission.instance()))
			{
				runs.set(i, new PromotionRun(run.role(), run.instance(), key, null));
				draft.author = new AuthorAttempt(author.number(), author.category(), author.snapshot(), runs);
				return run.instance();
			}
		}
		throw new IllegalArgumentException("No unassigned promotion run matches " + admission);
	}

	/**
	 * Starts an assigned trial's session.
	 *
	 * @param draft the state
	 * @param key the trial's key
	 * @throws IllegalArgumentException if no assigned trial has the key
	 */
	private static void start(CampaignDraft draft, TrialKey key)
	{
		int index = draft.indexOf(key);
		LogicalTrial trial = draft.trials.get(index);
		if (trial.state() != TrialState.ASSIGNED)
			throw new IllegalArgumentException("Trial " + key + " is " + trial.state() + ", not ASSIGNED");
		draft.trials.set(index, trial.withState(TrialState.RUNNING));
	}

	/**
	 * Returns a running trial.
	 *
	 * @param draft the state
	 * @param key the trial's key
	 * @return the trial's index
	 * @throws IllegalArgumentException if no running trial has the key
	 */
	private static int running(CampaignDraft draft, TrialKey key)
	{
		int index = draft.indexOf(key);
		LogicalTrial trial = draft.trials.get(index);
		if (trial.state() != TrialState.RUNNING)
			throw new IllegalArgumentException("Trial " + key + " is " + trial.state() + ", not RUNNING");
		return index;
	}

	/**
	 * Records a judged session. An outcome observed under changed measurement conditions is not an outcome: the
	 * trial returns to the queue and the campaign asks the user.
	 *
	 * @param draft the state
	 * @param complete the event
	 * @throws IllegalArgumentException if no running trial has the key
	 */
	private static void complete(CampaignDraft draft, CampaignEvent.Complete complete)
	{
		int index = running(draft, complete.key());
		LogicalTrial trial = draft.trials.get(index);
		if (TrialAdoption.isStale(draft, trial))
		{
			draft.trials.remove(index);
			return;
		}
		MeasurementIdentity observed = complete.observed();
		List<MeasurementCondition> differences = draft.identity.differences(observed);
		if (!differences.isEmpty())
		{
			draft.trials.set(index, new LogicalTrial(trial.key(), trial.instance(), trial.identity(),
				TrialState.ASSIGNED, null, trial.infrastructureFailures(), trial.observations() + 1));
			draft.raise(new PendingDecision.MeasurementChanged(observed, differences));
			return;
		}
		extendIdentity(draft, observed);
		draft.throttle = draft.throttle.afterCompletion();
		draft.trials.set(index, new LogicalTrial(trial.key(), trial.instance(), trial.identity().extendWith(observed),
			TrialState.JUDGED, complete.verdict(), trial.infrastructureFailures(), trial.observations()));
	}

	/**
	 * Adds newly observed conditions to the campaign's and the running attempt's identities.
	 *
	 * @param draft the state
	 * @param observed the observed conditions, which do not differ from the recorded ones
	 */
	private static void extendIdentity(CampaignDraft draft, MeasurementIdentity observed)
	{
		draft.identity = draft.identity.extendWith(observed);
		Optional<CertificationAttempt> running = draft.runningAttempt();
		if (running.isPresent())
		{
			CertificationAttempt attempt = running.get();
			draft.replaceAttempt(new CertificationAttempt(attempt.index(), attempt.lineage(), attempt.candidate(),
				attempt.identity().extendWith(observed), attempt.procedure(), attempt.counts(), attempt.failures(),
				attempt.formalStream(), null));
		}
	}

	/**
	 * Records a session that ended without an outcome. The trial returns to the queue.
	 *
	 * @param draft the state
	 * @param fail the event
	 * @throws IllegalArgumentException if no running trial has the key
	 */
	private static void fail(CampaignDraft draft, CampaignEvent.Fail fail)
	{
		int index = running(draft, fail.key());
		LogicalTrial trial = draft.trials.get(index);
		if (TrialAdoption.isStale(draft, trial))
		{
			draft.trials.remove(index);
			return;
		}
		int infrastructureFailures = trial.infrastructureFailures();
		switch (fail.failure())
		{
			case INFRASTRUCTURE ->
			{
				++infrastructureFailures;
				if (infrastructureFailures >= Campaign.INFRASTRUCTURE_FAILURE_LIMIT)
					draft.raise(new PendingDecision.TrialFailures(trial.key()));
			}
			case RATE_LIMITED ->
			{
				draft.throttle = draft.throttle.reject(fail.at(), fail.resetsAt());
				draft.slowStart = SlowStart.INITIAL;
			}
			case INTERRUPTED ->
			{
				// The trial reruns unchanged
			}
		}
		draft.trials.set(index, new LogicalTrial(trial.key(), trial.instance(), trial.identity(),
			TrialState.ASSIGNED, null, infrastructureFailures, trial.observations()));
	}

	/**
	 * Validates an author attempt: an unchanged snapshot is rejected at once, otherwise its promotion runs are
	 * planned.
	 *
	 * @param draft the state
	 * @param submit the event
	 * @throws IllegalStateException if no round offers an author attempt for the category
	 */
	private static void submitAuthorAttempt(CampaignDraft draft, CampaignEvent.SubmitAuthorAttempt submit)
	{
		requireConfirmed(draft);
		String category = submit.category();
		Round round = draft.round;
		if (round == null || draft.author != null || !round.attempts().containsKey(category) ||
			round.attempts().get(category) >= Round.ATTEMPTS)
		{
			throw new IllegalStateException("No round offers an author attempt for " + category);
		}
		if (submit.snapshot().equals(draft.working.snapshot()))
		{
			TrialAdoption.rejectAuthorAttempt(draft, category);
			return;
		}
		++draft.authorAttempts;
		List<PromotionRun> runs = Promotion.plan(category, draft.developmentFailures, draft.regressionSet,
			draft.generator());
		draft.author = new AuthorAttempt(draft.authorAttempts, category, submit.snapshot(), runs);
		TrialAdoption.decidePromotion(draft);
	}

	/**
	 * Commits the working candidate: freezes the procedure, the comparisons and the formal stream, and assigns the
	 * next certification index.
	 *
	 * @param draft the state
	 * @param campaign the state before the event
	 * @param commit the event
	 * @throws IllegalStateException if no commit is due or the calibration is for another index
	 */
	private static void commit(CampaignDraft draft, Campaign campaign, CampaignEvent.Commit commit)
	{
		if (!ReadinessGate.isCommitDue(campaign))
			throw new IllegalStateException("No commit is due");
		int index = draft.attempts.size() + 1;
		SignificanceLevel level = ErrorBudget.certificationLevel(index);
		CapCalibration calibration = commit.calibration();
		if (!calibration.level().equals(level))
		{
			throw new IllegalStateException("The cap was calibrated at " + calibration.level() + ", but attempt " +
				index + " uses " + level);
		}
		List<String> categories = new ArrayList<>();
		for (Category category : draft.contract().categories())
			categories.add(category.id());
		int categoryCount = categories.size();
		SignificanceLevel improvementLevel = ErrorBudget.improvementLevel(index, categoryCount);
		SignificanceLevel incumbentLevel = ErrorBudget.incumbentLevel(draft.baselineIndex, categoryCount);
		List<Comparison> comparisons = new ArrayList<>();
		for (String category : categories)
		{
			CategoryCounts counts = draft.baselineCounts.getOrDefault(category, CategoryCounts.EMPTY);
			comparisons.add(Comparison.freeze(counts.passes(), counts.failures(), incumbentLevel, improvementLevel));
		}
		DecisionBoundaries boundaries = DecisionBoundaries.compute(level, calibration.cap(), comparisons);
		CertificationProcedure procedure = new CertificationProcedure(improvementLevel, draft.baselineIndex,
			categories, boundaries, CertificationProcedure.BETS, CertificationProcedure.ALLOCATION_POLICY);
		DrawStream formal = DrawStream.create(DrawStream.Kind.FORMAL, commit.formalSeed());
		formal.open();
		draft.attempts.add(new CertificationAttempt(index, draft.lineageNumber(), draft.working, draft.identity,
			procedure, Map.of(), List.of(), formal.state(), null));
		draft.outcome = null;
	}

	/**
	 * Records measurement conditions that the driver observed outside a trial.
	 *
	 * @param draft the state
	 * @param observed the observed conditions
	 * @throws IllegalStateException if the campaign is not active
	 */
	private static void changeMeasurement(CampaignDraft draft, MeasurementIdentity observed)
	{
		requireActive(draft);
		List<MeasurementCondition> differences = draft.identity.differences(observed);
		if (differences.isEmpty())
			extendIdentity(draft, observed);
		else
			draft.raise(new PendingDecision.MeasurementChanged(observed, differences));
	}

	/**
	 * Records a condition that makes certification unavailable under the current lineage.
	 *
	 * @param draft the state
	 * @param outcome the condition
	 * @throws IllegalStateException if the campaign is not active or has no confirmed contract
	 */
	private static void reportUnavailable(CampaignDraft draft, Outcome outcome)
	{
		requireConfirmed(draft);
		draft.endRunningAttempt(outcome);
		draft.unavailable.add(outcome);
		draft.outcome = outcome;
		draft.raise(new PendingDecision.CertificationUnavailable(outcome));
	}
}
