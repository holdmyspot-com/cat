package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Cell;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Derives a campaign's next state from an event. The function is pure: it reads no clock, performs no I/O and
 * draws randomness only from the campaign's seeded streams.
 */
public final class Transitions
{
	/**
	 * Prevents instantiation.
	 */
	private Transitions()
	{
	}

	/**
	 * Applies an event.
	 *
	 * @param campaign the current state
	 * @param event the event
	 * @return the next state
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the event names a trial, cell, choice or attempt that the state does
	 *   not offer
	 * @throws IllegalStateException if the campaign's state does not permit the event
	 */
	public static Campaign apply(Campaign campaign, CampaignEvent event)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(event, "event").isNotNull();
		Draft draft = new Draft(campaign);
		switch (event)
		{
			case CampaignEvent.ObserveTokenUsage observed ->
				draft.optimization = draft.optimization.addUsage(observed.role(), observed.usage());
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
			case CampaignEvent.StartAuthorAttempt start -> startAuthorAttempt(draft, start.cell());
			case CampaignEvent.SubmitAuthorAttempt submit -> submitAuthorAttempt(draft, submit);
			case CampaignEvent.AdvanceMinimality advance -> advanceMinimality(draft, advance.snapshot());
			case CampaignEvent.MeasurePrompt measured ->
			{
				Map<Sha256, PromptSize> sizes = new HashMap<>(draft.optimization.promptSizes());
				sizes.put(measured.snapshot(), measured.size());
				draft.optimization = new OptimizationEvidence(draft.optimization.authors(), draft.optimization.removed(),
					sizes, draft.optimization.trialInputs(), draft.optimization.attempts(),
					draft.optimization.tokenUsage());
			}
			case CampaignEvent.RetainAuthorDiagnosis retain ->
			{
				List<AuthorDiagnosis> authors = new ArrayList<>(draft.optimization.authors());
				authors.add(retain.diagnosis());
				draft.optimization = new OptimizationEvidence(authors, draft.optimization.removed(),
					draft.optimization.promptSizes(), draft.optimization.trialInputs(), draft.optimization.attempts(),
					draft.optimization.tokenUsage());
			}
			case CampaignEvent.Commit commit -> commit(draft, campaign, commit);
			case CampaignEvent.CalibrationFailed failed ->
			{
				requireActive(draft);
				draft.raise(new PendingDecision.CalibrationFailure(failed.detail()));
			}
			case CampaignEvent.FindConflict found ->
			{
				requireConfirmed(draft);
				if (draft.revisionRequested)
					throw new IllegalStateException("The campaign waits for a contract revision");
				draft.raise(new PendingDecision.ConflictFound(found.conflicts(), List.of()));
			}
			case CampaignEvent.ResolveConflict resolve -> resolveConflict(draft, resolve.resolution());
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
			case CampaignEvent.WarnAuthority warn ->
			{
				requireConfirmed(draft);
				if (draft.revisionRequested)
					throw new IllegalStateException("The campaign waits for a contract revision");
				draft.raise(new PendingDecision.AuthorityWarnings(warn.warnings()));
			}
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
	private static void requireActive(Draft draft)
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
	private static void requireConfirmed(Draft draft)
	{
		requireActive(draft);
		if (!draft.isConfirmed())
			throw new IllegalStateException("No contract has been confirmed");
	}

	/**
	 * Confirms a contract and starts its evidence history, the original prompt's baseline measurement and a new
	 * development series with no counted outcomes.
	 *
	 * @param draft the state
	 * @param confirm the event
	 * @throws IllegalStateException if a contract is confirmed and no revision is requested, or a trial runs
	 */
	private static void confirm(Draft draft, CampaignEvent.Confirm confirm)
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
	 * Requests a contract revision. A running attempt stops; trials that have not started are discarded when
	 * {@link TrialAdoption#settle(Draft)} runs.
	 *
	 * @param draft the state
	 * @throws IllegalStateException if the campaign is not active or has no confirmed contract
	 */
	private static void revise(Draft draft)
	{
		requireConfirmed(draft);
		draft.endRunningAttempt(Outcome.STOPPED_FOR_CONTRACT_REVISION);
		draft.revisionRequested = true;
		draft.pendingDecisions.clear();
	}

	/**
	 * Records one resolution, retaining the next conflict or requesting a revision after the last choice.
	 *
	 * @param draft the state
	 * @param resolution the 1-based resolution position
	 * @throws IllegalStateException if the campaign has no active conflict pause
	 * @throws IllegalArgumentException if the position is out of range
	 */
	private static void resolveConflict(Draft draft, int resolution)
	{
		requireConfirmed(draft);
		if (!(draft.pending() instanceof PendingDecision.ConflictFound conflicts))
			throw new IllegalStateException("No conflict awaits a resolution");
		List<Integer> chosen = conflicts.choose(resolution);
		if (chosen.size() == conflicts.conflicts().size())
			revise(draft);
		else
		{
			draft.resolve();
			draft.pendingDecisions.addFirst(new PendingDecision.ConflictFound(conflicts.conflicts(), chosen));
		}
	}

	/**
	 * Assigns a logical trial, which keeps the admission's allocation decision.
	 *
	 * @param draft the state
	 * @param admission the admission
	 * @throws IllegalArgumentException if the state does not offer the admission, or the allocation decision used
	 *   an evidence revision that the campaign has not reached
	 * @throws IllegalStateException if the campaign is not active, has no confirmed contract or waits for a
	 *   revision
	 */
	private static void assign(Draft draft, Admission.Assign admission)
	{
		requireConfirmed(draft);
		if (draft.revisionRequested)
			throw new IllegalStateException("The campaign waits for a contract revision");
		long evidenceRevision = admission.allocation().evidenceRevision();
		if (evidenceRevision > draft.evidenceRevision)
		{
			throw new IllegalArgumentException("The allocation decision used evidence revision " + evidenceRevision +
				", but the campaign has adopted " + draft.evidenceRevision + " outcomes: " + admission);
		}
		Contract contract = draft.contract();
		String cell = admission.cell();
		if (contract.findCell(cell).isEmpty())
			throw new IllegalArgumentException("The contract has no cell " + cell);
		TrialKey key = new TrialKey(admission.purpose(), admission.series(), admission.snapshot(), cell,
			draft.nextSequence(new SequenceGroup(cell, admission.purpose(), admission.snapshot())));
		TrialInstance instance = switch (admission.purpose())
		{
			case DEVELOPMENT ->
			{
				requireSeriesAndSnapshot(admission, draft.series, draft.working);
				DrawStream stream = DrawStream.resume(draft.developmentStream);
				TrialInstance drawn = draft.generator().draw(cell, stream);
				draft.developmentStream = stream.state();
				yield drawn;
			}
			case BASELINE ->
			{
				requireSeriesAndSnapshot(admission, draft.baselineIndex, draft.incumbent);
				DrawStream stream = DrawStream.resume(draft.baselineStream);
				TrialInstance drawn = draft.generator().draw(cell, stream);
				draft.baselineStream = stream.state();
				yield drawn;
			}
			case FORMAL -> assignFormal(draft, admission);
			case DIAGNOSTIC -> assignDiagnostic(draft, admission, key);
			case PROMOTION -> assignPromotion(draft, admission, key);
		};
		draft.trials.add(LogicalTrial.assigned(key, instance, draft.identity, admission.allocation()));
	}

	/**
	 * Requires an admission to name a series and a candidate's snapshot.
	 *
	 * @param admission the admission
	 * @param series the expected series
	 * @param candidate the expected candidate
	 * @throws IllegalArgumentException if the admission names another series or snapshot
	 */
	private static void requireSeriesAndSnapshot(Admission.Assign admission, int series, Candidate candidate)
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
	private static TrialInstance assignFormal(Draft draft, Admission.Assign admission)
	{
		Optional<CertificationAttempt> running = draft.runningAttempt();
		if (running.isEmpty())
			throw new IllegalArgumentException("No certification attempt runs: " + admission);
		CertificationAttempt attempt = running.get();
		requireSeriesAndSnapshot(admission, attempt.index(), attempt.candidate());
		DrawStream stream = DrawStream.resume(attempt.formalStream());
		TrialInstance drawn = draft.generator().draw(admission.cell(), stream);
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
	private static TrialInstance assignDiagnostic(Draft draft, Admission.Assign admission, TrialKey key)
	{
		Diagnosis diagnosis = draft.diagnosis;
		requireSeriesAndSnapshot(admission, draft.series, draft.working);
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
	private static TrialInstance assignPromotion(Draft draft, Admission.Assign admission, TrialKey key)
	{
		AuthorAttempt author = draft.findAuthor(admission.series());
		if (author == null)
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
				draft.updateAuthor(new AuthorAttempt(author.number(), author.cell(), author.snapshot(), runs,
					author.minimality()));
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
	private static void start(Draft draft, TrialKey key)
	{
		int index = draft.indexOf(key);
		LogicalTrial trial = draft.trials.get(index);
		if (trial.state() != TrialState.ASSIGNED)
			throw new IllegalArgumentException("Trial " + key + " is " + trial.state() + ", not ASSIGNED");
		draft.trials.set(index, trial.withState(TrialState.RUNNING));
	}

	/**
	 * Finds the index of the running trial with a key.
	 *
	 * @param draft the state
	 * @param key the trial's key
	 * @return the trial's index
	 * @throws IllegalArgumentException if no running trial has the key
	 */
	private static int indexOfRunningTrial(Draft draft, TrialKey key)
	{
		int index = draft.indexOf(key);
		LogicalTrial trial = draft.trials.get(index);
		if (trial.state() != TrialState.RUNNING)
			throw new IllegalArgumentException("Trial " + key + " is " + trial.state() + ", not RUNNING");
		return index;
	}

	/**
	 * Records a judged session. When its observed measurement conditions differ, discards its verdict, queues
	 * the same trial again and asks the user how to proceed.
	 *
	 * @param draft the state
	 * @param complete the event
	 * @throws IllegalArgumentException if no running trial has the key
	 */
	private static void complete(Draft draft, CampaignEvent.Complete complete)
	{
		int index = indexOfRunningTrial(draft, complete.key());
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
				trial.allocation(), TrialState.ASSIGNED, null, null, null, trial.infrastructureFailures(),
				trial.infrastructureAttempts(), trial.observations() + 1, List.of()));
			draft.raise(new PendingDecision.MeasurementChanged(observed, differences));
			return;
		}
		MeasurementIdentity measured = trial.identity().extendWith(observed);
		draft.optimization = draft.optimization.addTrial(measured,
			complete.key().snapshot(), complete.key().cell(), complete.inputTokens());
		boolean measuresIncumbent = switch (trial.key().purpose())
		{
			case BASELINE -> true;
			case DEVELOPMENT -> trial.key().snapshot().equals(draft.incumbent.snapshot());
			default -> false;
		};
		if (measuresIncumbent)
			draft.baselineIdentity = draft.baselineIdentity.extendWith(measured);
		extendIdentity(draft, observed);
		draft.throttle = draft.throttle.afterCompletion();
		draft.trials.set(index, new LogicalTrial(trial.key(), trial.instance(), measured,
			trial.allocation(), TrialState.JUDGED, complete.verdict(), complete.rationale(), complete.triage(),
			trial.infrastructureFailures(), trial.infrastructureAttempts(), trial.observations(), complete.responses()));
	}

	/**
	 * Adds newly observed conditions to the campaign's and the running attempt's identities.
	 *
	 * @param draft the state
	 * @param observed the observed conditions, which do not differ from the recorded ones
	 */
	private static void extendIdentity(Draft draft, MeasurementIdentity observed)
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
	 * Records a session that ended without an outcome as an infrastructure attempt. The trial returns to the queue.
	 *
	 * @param draft the state
	 * @param fail the event
	 * @throws IllegalArgumentException if no running trial has the key
	 */
	private static void fail(Draft draft, CampaignEvent.Fail fail)
	{
		int index = indexOfRunningTrial(draft, fail.key());
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
			// An interrupted trial is queued unchanged after this switch.
			case INTERRUPTED ->
			{
			}
		}
		List<InfrastructureAttempt> attempts = new ArrayList<>(trial.infrastructureAttempts());
		attempts.add(new InfrastructureAttempt(fail.at(), fail.failure(), fail.detail()));
		draft.trials.set(index, new LogicalTrial(trial.key(), trial.instance(), trial.identity(), trial.allocation(),
			TrialState.ASSIGNED, null, null, null, infrastructureFailures, attempts, trial.observations(), List.of()));
	}

	/**
	 * Reserves a stable launch-order number without counting an unvalidated author session.
	 *
	 * @param draft the campaign state
	 * @param cell the addressed cell
	 * @throws IllegalStateException if the round has no available slot for that cell
	 */
	private static void startAuthorAttempt(Draft draft, String cell)
	{
		requireConfirmed(draft);
		Round round = draft.round;
		if (round == null || draft.author != null || !round.nextCell().orElse("").equals(cell))
			throw new IllegalStateException("No round offers an author attempt for " + cell);
		int latest = draft.authorAttempts;
		for (int number : round.reservations().keySet())
			latest = Math.max(latest, number);
		for (AttemptResult result : draft.optimization.attempts())
			latest = Math.max(latest, result.number());
		draft.round = round.reserve(latest + 1, cell);
	}

	/**
	 * Receives a validated attempt into its reserved slot and plans the shared promotion runs.
	 *
	 * @param draft the state
	 * @param submit the received attempt
	 * @throws IllegalStateException if no pending reservation matches the attempt
	 */
	private static void submitAuthorAttempt(Draft draft, CampaignEvent.SubmitAuthorAttempt submit)
	{
		requireConfirmed(draft);
		String cell = submit.cell();
		int number = submit.number();
		Round round = draft.round;
		if (round == null || draft.author != null || !cell.equals(round.pendingAuthorAttempts().get(number)))
			throw new IllegalStateException("No pending author attempt " + number + " addresses " + cell);
		++draft.authorAttempts;
		List<PromotionRun> runs = List.of();
		if (!submit.snapshot().equals(draft.working.snapshot()))
		{
			List<TrialInstance> failures = draft.developmentFailures.stream().map(Failure::instance).toList();
			List<String> cells = List.of(cell);
			if (round.weak())
			{
				String categoryId = draft.contract().findCell(cell).orElseThrow().categoryId();
				cells = draft.developmentCells().stream().filter(other -> other.categoryId().equals(categoryId)).
					map(Cell::id).toList();
			}
			runs = planPromotionRuns(draft, cells, failures);
		}
		AuthorAttempt attempt = new AuthorAttempt(number, cell, submit.snapshot(), runs,
			Minimality.start(submit.changes(), submit.snapshot()));
		draft.round = round.withAttempt(attempt);
		if (round.weak())
		{
			TrialAdoption.decideWeakRound(draft);
			return;
		}
		draft.author = attempt;
		if (submit.snapshot().equals(draft.working.snapshot()))
		{
			TrialAdoption.retainAttempt(draft, attempt, false, false);
			draft.author = null;
			TrialAdoption.rejectAuthorAttempt(draft, cell);
			return;
		}
		TrialAdoption.decidePromotion(draft);
	}

	/**
	 * Plans an attempt's runs, sharing references already owned by a weak contender or an unadopted trial.
	 * Adopted working verdicts are supplied by the regression set.
	 *
	 * @param draft the campaign state
	 * @param cells the triggering cells
	 * @param failures the instances that the author received
	 * @return the attempt's own runs and references that have no owner yet
	 */
	private static List<PromotionRun> planPromotionRuns(Draft draft, List<String> cells,
		List<TrialInstance> failures)
	{
		List<TrialInstance> references = new ArrayList<>();
		for (LogicalTrial trial : draft.trials)
		{
			if (draft.isWorkingReference(trial))
				references.add(trial.instance());
		}
		if (draft.round.weak())
		{
			for (AuthorAttempt attempt : draft.round.validationAttempts())
			{
				for (PromotionRun run : attempt.runs())
				{
					if (run.role() == PromotionRun.Role.WORKING_REFERENCE)
						references.add(run.instance());
				}
			}
		}

		return Promotion.plan(cells, failures, draft.regressionSet, draft.generator()).stream().filter(run ->
			run.role() != PromotionRun.Role.WORKING_REFERENCE ||
				!Promotion.containsContent(references, run.instance())).toList();
	}

	/**
	 * Advances the shared reduction policy after the current attempt's runs have settled.
	 *
	 * @param draft the state
	 * @param snapshot the saved overlay of the next reduction
	 * @throws IllegalStateException if there is no completed promotion decision or targeted runs remain
	 */
	private static void advanceMinimality(Draft draft, Sha256 snapshot)
	{
		AuthorAttempt author = draft.author;
		if (author == null || draft.trials.stream().anyMatch(trial -> trial.key().purpose() == Purpose.PROMOTION))
			throw new IllegalStateException("Minimality needs a completed attempt with no remaining promotion trials");
		Minimality next = author.minimality().advance(author.snapshot(), author.runs(),
			Promotion.decide(author, draft.regressionSet, draft.instanceOutcomes));
		if (next.testing() == null)
		{
			List<String> removed = new ArrayList<>(draft.optimization.removed());
			removed.addAll(next.removed());
			draft.optimization = new OptimizationEvidence(draft.optimization.authors(), removed,
				draft.optimization.promptSizes(), draft.optimization.trialInputs(), draft.optimization.attempts(),
				draft.optimization.tokenUsage());
			TrialAdoption.promote(draft, new AuthorAttempt(author.number(), author.cell(), next.accepted(),
				next.acceptedRuns(), next));
			return;
		}
		List<PromotionRun> runs = Promotion.resetRuns(author.runs());
		draft.author = new AuthorAttempt(author.number(), author.cell(), snapshot, runs, next);
	}

	/**
	 * Commits the working candidate: freezes the procedure over the certification scope's cells, the comparisons and
	 * the formal stream, and assigns the next certification index.
	 *
	 * @param draft the state
	 * @param campaign the state before the event
	 * @param commit the event
	 * @throws IllegalStateException if no commit is due or the calibration is for another index
	 */
	private static void commit(Draft draft, Campaign campaign, CampaignEvent.Commit commit)
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
		List<String> cells = CellTier.scopeCellIds(draft.contract());
		int cellCount = cells.size();
		SignificanceLevel improvementLevel = ErrorBudget.improvementLevel(index, cellCount);
		SignificanceLevel incumbentLevel = ErrorBudget.incumbentLevel(draft.baselineIndex, cellCount);
		boolean comparable = Comparability.of(draft.identity, draft.baselineIdentity).isComparable();
		List<Comparison> comparisons = new ArrayList<>();
		for (String cell : cells)
		{
			if (comparable)
			{
				CellCounts counts = draft.baselineCounts.getOrDefault(cell, CellCounts.EMPTY);
				comparisons.add(Comparison.freeze(counts.passes(), counts.failures(), incumbentLevel, improvementLevel));
			}
			else
				comparisons.add(new Comparison(BigDecimal.ONE, BigDecimal.ZERO, improvementLevel));
		}
		DecisionBoundaries boundaries = DecisionBoundaries.compute(level, calibration.cap(), comparisons);
		CertificationProcedure procedure = new CertificationProcedure(improvementLevel, draft.baselineIndex,
			draft.baselineIdentity, cells, boundaries, CertificationProcedure.BETS,
			CertificationProcedure.ALLOCATION_POLICY);
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
	private static void changeMeasurement(Draft draft, MeasurementIdentity observed)
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
	private static void reportUnavailable(Draft draft, Outcome outcome)
	{
		requireConfirmed(draft);
		draft.endRunningAttempt(outcome);
		draft.unavailable.add(outcome);
		draft.outcome = outcome;
		draft.raise(new PendingDecision.CertificationUnavailable(outcome));
	}
}
