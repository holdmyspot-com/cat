package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.AddedTestCases;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;
import com.holdmyspot.cat.common.optimizer.statistics.Comparison;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.ErrorBudget;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
			case CampaignEvent.ExtendFork extend -> extendFork(draft, extend);
			case CampaignEvent.Revise _ -> revise(draft);
			case CampaignEvent.Assign assign -> assign(draft, assign.admission());
			case CampaignEvent.Start start -> start(draft, start.key());
			case CampaignEvent.Complete complete -> complete(draft, complete);
			case CampaignEvent.Fail fail -> fail(draft, fail);
			case CampaignEvent.ObserveRateLimit observe ->
			{
				draft.throttle = draft.throttle.observe(observe.windows(), ConcurrencyPolicy.window(campaign));
				if (!draft.throttle.allowsAdmission(observe.at()))
					draft.slowStart = SlowStart.INITIAL;
			}
			case CampaignEvent.HoldAdmissions hold ->
			{
				draft.throttle = draft.throttle.hold(hold.until(), hold.backoffs());
				draft.slowStart = SlowStart.INITIAL;
			}
			case CampaignEvent.RankApproaches ranked ->
				draft.round = draft.round.withApproachOrder(ranked.cell(), ranked.order());
			case CampaignEvent.StartPromptAuthorAttempt start -> startPromptAuthorAttempt(draft, start.cell());
			case CampaignEvent.SubmitPromptAuthorAttempt submit -> submitPromptAuthorAttempt(draft, submit);
			case CampaignEvent.AdvanceMinimality advance -> advanceMinimality(draft, advance.snapshot());
			case CampaignEvent.MeasurePrompt measured ->
			{
				Map<Sha256, PromptSize> sizes = new HashMap<>(draft.optimization.promptSizes());
				sizes.put(measured.snapshot(), measured.size());
				draft.optimization = new OptimizationEvidence(draft.optimization.promptAuthors(), draft.optimization.removed(),
					sizes, draft.optimization.trialInputs(), draft.optimization.attempts(),
					draft.optimization.tokenUsage(), draft.optimization.approaches());
			}
			case CampaignEvent.RetainPromptAuthorDiagnosis retain ->
			{
				List<PromptAuthorDiagnosis> promptAuthors = new ArrayList<>(draft.optimization.promptAuthors());
				promptAuthors.add(retain.diagnosis());
				draft.optimization = new OptimizationEvidence(promptAuthors, draft.optimization.removed(),
					draft.optimization.promptSizes(), draft.optimization.trialInputs(), draft.optimization.attempts(),
					draft.optimization.tokenUsage(), draft.optimization.approaches());
			}
			case CampaignEvent.ReproductionValues values -> reproductionValues(draft, values);
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
			case CampaignEvent.RequestTestRevision request -> requestTestRevision(draft, request.request());
			case CampaignEvent.ReviseTests revise -> reviseTests(draft, revise.revisions());
			case CampaignEvent.ReportTestSound sound ->
			{
				requireConfirmed(draft);
				draft.testRevisions = draft.testRevisions.withSound(new SoundTest(draft.lineageNumber(), sound.test(),
					sound.reasons()));
			}
			case CampaignEvent.BlockTestRevision block ->
			{
				requireConfirmed(draft);
				draft.testRevisions = draft.testRevisions.withoutRequest(block.test());
				draft.raise(new PendingDecision.TestRevisionBlocked(block.test(), block.reason(), block.reasons(),
					block.proposedChange()));
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
			case CampaignEvent.WarnAuthority warn -> warnAuthority(draft, warn.warnings());
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
			DrawStream.create(DrawStream.Kind.BASELINE, confirm.seed()).state(), List.of(), Set.of(), AddedTestCases.NONE));
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
		draft.testRevisions = new TestRevisions(List.of(), draft.testRevisions.revisions(),
			draft.testRevisions.sound());
	}

	/**
	 * Records the contract author's test cases for a failure group's current round, whose reproduction trials then
	 * run.
	 *
	 * @param draft the state
	 * @param values the event
	 * @throws IllegalStateException if the campaign is not active, has no confirmed contract, no attempt is
	 *   reproducing, or the group's current round does not await values
	 * @throws IllegalArgumentException if another attempt is reproducing or the attempt has no such group
	 */
	private static void reproductionValues(Draft draft, CampaignEvent.ReproductionValues values)
	{
		requireConfirmed(draft);
		CertificationAttempt attempt = draft.reproducing().orElseThrow(() ->
			new IllegalStateException("No certification attempt is reproducing its formal failures"));
		if (attempt.index() != values.attempt())
		{
			throw new IllegalArgumentException("Attempt " + attempt.index() + " is reproducing its formal failures, " +
				"not attempt " + values.attempt());
		}
		if (values.group() > attempt.groups().size())
		{
			throw new IllegalArgumentException("Attempt " + attempt.index() + " has no failure group " +
				values.group());
		}
		FailureGroup group = attempt.group(values.group());
		draft.replaceAttempt(attempt.withGroup(values.group(), group.withValues(values.testCases())));
	}

	/**
	 * Moves a campaign whose revision waits for confirmation onto a larger fork. The confirmation that follows starts
	 * the new lineage on it.
	 *
	 * @param draft the state
	 * @param extend the event
	 * @throws IllegalStateException if the campaign is not active, has no confirmed contract or no revision was
	 *   requested
	 * @throws IllegalArgumentException if the incumbent is the working candidate and the event gives them different
	 *   snapshots
	 */
	private static void extendFork(Draft draft, CampaignEvent.ExtendFork extend)
	{
		requireConfirmed(draft);
		if (!draft.revisionRequested)
			throw new IllegalStateException("Only a revision that waits for confirmation moves the campaign's fork");
		if (draft.working.isIncumbent() && !extend.working().equals(extend.incumbent()))
		{
			throw new IllegalArgumentException("The incumbent is the working candidate, so both need the same " +
				"snapshot");
		}
		draft.incumbent = new Candidate(0, extend.incumbent());
		draft.working = new Candidate(draft.working.number(), extend.working());
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
	 * Records a request to revise a prompt test, merged into any pending request for the same test.
	 *
	 * @param draft the state
	 * @param request the request
	 * @throws IllegalStateException if the campaign is not active, has no confirmed contract or waits for a contract
	 *   revision
	 */
	private static void requestTestRevision(Draft draft, TestRevisionRequest request)
	{
		requireConfirmed(draft);
		if (draft.revisionRequested)
			throw new IllegalStateException("The campaign waits for a contract revision");
		draft.testRevisions = draft.testRevisions.request(request);
	}

	/**
	 * Applies a test revision. A running attempt stops, keeping its certification index, and the revised contract
	 * waits for confirmation. The reports that a test is sound carry over to the next lineage for the tests that the
	 * revision left unchanged.
	 *
	 * @param draft the state
	 * @param revisions the changes, one per revised test
	 * @throws IllegalStateException if the campaign is not active or has no confirmed contract
	 */
	private static void reviseTests(Draft draft, List<TestRevision> revisions)
	{
		requireConfirmed(draft);
		draft.endRunningAttempt(Outcome.STOPPED_FOR_TEST_REVISION);
		draft.revisionRequested = true;
		draft.pendingDecisions.clear();
		int lineage = draft.lineageNumber();
		Set<String> revised = new HashSet<>();
		for (TestRevision revision : revisions)
			revised.add(revision.test());
		List<TestRevision> applied = new ArrayList<>(draft.testRevisions.revisions());
		applied.addAll(revisions);
		List<SoundTest> sound = new ArrayList<>(draft.testRevisions.sound());
		for (SoundTest report : draft.testRevisions.sound())
		{
			if (report.lineage() == lineage && !revised.contains(report.test()))
				sound.add(new SoundTest(lineage + 1, report.test(), report.reasons()));
		}
		draft.testRevisions = new TestRevisions(List.of(), applied, sound);
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
		DrawnTestCase testCase = switch (admission.purpose())
		{
			case DEVELOPMENT ->
			{
				requireSeriesAndSnapshot(admission, draft.series, draft.working);
				DrawStream stream = DrawStream.resume(draft.developmentStream);
				DrawnTestCase drawn = draft.generator().draw(cell, stream);
				draft.developmentStream = stream.state();
				yield drawn;
			}
			case BASELINE ->
			{
				requireSeriesAndSnapshot(admission, draft.baselineIndex, draft.incumbent);
				DrawStream stream = DrawStream.resume(draft.baselineStream);
				DrawnTestCase drawn = draft.generator().draw(cell, stream);
				draft.baselineStream = stream.state();
				yield drawn;
			}
			case FORMAL -> assignFormal(draft, admission);
			case CONTROL -> assignReference(draft, admission, Reference.CONTROL);
			case INCUMBENT -> assignReference(draft, admission, Reference.INCUMBENT);
			case DIAGNOSTIC -> assignDiagnostic(draft, admission, key);
			case PROMOTION -> assignPromotion(draft, admission, key);
			case REPRODUCTION -> assignReproduction(draft, admission);
		};
		draft.trials.add(LogicalTrial.assigned(key, testCase, draft.identity, admission.allocation()));
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
	 * @return the test case
	 * @throws IllegalArgumentException if no attempt runs or the admission names another attempt or candidate
	 */
	private static DrawnTestCase assignFormal(Draft draft, Admission.Assign admission)
	{
		Optional<CertificationAttempt> running = draft.runningAttempt();
		if (running.isEmpty())
			throw new IllegalArgumentException("No certification attempt runs: " + admission);
		CertificationAttempt attempt = running.get();
		requireSeriesAndSnapshot(admission, attempt.index(), attempt.candidate());
		DrawStream stream = DrawStream.resume(attempt.formalStream());
		DrawnTestCase drawn = draft.generator().draw(admission.cell(), stream);
		draft.replaceAttempt(attempt.withEvidence(attempt.identity(), attempt.counts(), attempt.controlPairings(),
			attempt.incumbentPairings(), attempt.failures(), stream.state()));
		return drawn;
	}

	/**
	 * Assigns the control or incumbent trial that pairs with the running attempt's next candidate formal trial of a
	 * cell that has no trial of that kind yet.
	 *
	 * @param draft the state
	 * @param admission the admission
	 * @param reference the kind of reference trial
	 * @return the test case
	 * @throws IllegalArgumentException if no attempt runs, the admission names another attempt or another snapshot
	 *   than the attempt's control source or the incumbent, the cell does not compare its assertions with that kind of
	 *   reference trial, or every waiting candidate formal trial of the cell already has a trial of that kind in
	 *   flight, or the next one ran another test case
	 */
	private static DrawnTestCase assignReference(Draft draft, Admission.Assign admission, Reference reference)
	{
		Optional<CertificationAttempt> running = draft.runningAttempt();
		if (running.isEmpty())
			throw new IllegalArgumentException("No certification attempt runs: " + admission);
		CertificationAttempt attempt = running.get();
		Sha256 snapshot = switch (reference)
		{
			case CONTROL -> attempt.control();
			case INCUMBENT -> draft.incumbent.snapshot();
		};
		if (admission.series() != attempt.index() || !admission.snapshot().equals(snapshot))
		{
			throw new IllegalArgumentException("The state does not offer " + admission + "; expected snapshot " +
				snapshot + " of attempt " + attempt.index());
		}
		Pairing pairing = attempt.pairing(reference, admission.cell());
		if (!pairing.isComparing())
			throw new IllegalArgumentException("The cell does not compare its assertions yet: " + admission);
		int inFlight = 0;
		for (LogicalTrial trial : draft.trials)
		{
			TrialKey key = trial.key();
			if (key.purpose() == reference.purpose() && key.series() == attempt.index() &&
				key.cell().equals(admission.cell()))
			{
				++inFlight;
			}
		}
		List<Pairing.UnpairedTrial> unpaired = pairing.unpaired();
		if (inFlight >= unpaired.size() ||
			!Promotion.isSameTestCase(unpaired.get(inFlight).testCase(), admission.testCase()))
		{
			String next = "none";
			if (inFlight < unpaired.size())
				next = unpaired.get(inFlight).testCase().toString();
			throw new IllegalArgumentException("The state does not offer " + admission + "; the next waiting " +
				"candidate formal trial is " + next);
		}
		return admission.testCase();
	}

	/**
	 * Draws a reproduction trial of a failure group's current round from the development stream.
	 *
	 * @param draft the state
	 * @param admission the admission, whose series is the group's 1-based position
	 * @return the test case, marked targeted
	 * @throws IllegalArgumentException if no attempt is reproducing, or the admission names a group that is not
	 *   reproducing, a round that awaits values, another cell or another snapshot than the working candidate's
	 */
	private static DrawnTestCase assignReproduction(Draft draft, Admission.Assign admission)
	{
		CertificationAttempt attempt = draft.reproducing().orElseThrow(() ->
			new IllegalArgumentException("No certification attempt is reproducing its formal failures: " + admission));
		if (admission.series() < 1 || admission.series() > attempt.groups().size())
			throw new IllegalArgumentException("Attempt " + attempt.index() + " has no failure group: " + admission);
		FailureGroup group = attempt.group(admission.series());
		ReproductionRound round = group.round();
		if (group.status() != FailureGroup.Status.REPRODUCING || round.awaitsValues() ||
			!round.cellId().equals(admission.cell()) || !admission.snapshot().equals(draft.working.snapshot()))
		{
			throw new IllegalArgumentException("The state does not offer " + admission + "; failure group " +
				admission.series() + " of attempt " + attempt.index() + " is " + group);
		}
		Generator generator = draft.reproductionGenerator();
		DrawStream stream = DrawStream.resume(draft.developmentStream);
		DrawnTestCase drawn = generator.drawAdded(round.cellId(), group.testId(), round.added(), stream);
		draft.developmentStream = stream.state();
		return generator.markTargeted(drawn);
	}

	/**
	 * Assigns the diagnostic rerun of the first failing test case.
	 *
	 * @param draft the state
	 * @param admission the admission
	 * @param key the rerun's key
	 * @return the test case
	 * @throws IllegalArgumentException if no rerun is due or the admission names another test case
	 */
	private static DrawnTestCase assignDiagnostic(Draft draft, Admission.Assign admission, TrialKey key)
	{
		Diagnosis diagnosis = draft.diagnosis;
		requireSeriesAndSnapshot(admission, draft.series, draft.working);
		if (diagnosis == null || diagnosis.pending().isEmpty() || diagnosis.rerun() != null ||
			!Promotion.isSameTestCase(diagnosis.pending().getFirst(), admission.testCase()))
		{
			throw new IllegalArgumentException("No diagnostic rerun of " + admission.testCase() + " is due");
		}
		draft.diagnosis = new Diagnosis(diagnosis.pending(), diagnosis.passedReruns(), key, diagnosis.reproduced());
		return admission.testCase();
	}

	/**
	 * Assigns an unassigned promotion run of the prompt author attempt under validation.
	 *
	 * @param draft the state
	 * @param admission the admission
	 * @param key the run's key
	 * @return the test case
	 * @throws IllegalArgumentException if no unassigned run matches the admission
	 */
	private static DrawnTestCase assignPromotion(Draft draft, Admission.Assign admission, TrialKey key)
	{
		PromptAuthorAttempt promptAuthor = draft.findPromptAuthor(admission.series());
		if (promptAuthor == null)
			throw new IllegalArgumentException("No prompt author attempt " + admission.series() +
			" is under validation");
		List<PromotionRun> runs = new ArrayList<>(promptAuthor.runs());
		for (int i = 0; i < runs.size(); ++i)
		{
			PromotionRun run = runs.get(i);
			Sha256 snapshot = Promotion.snapshot(promptAuthor, run, draft.working.snapshot());
			if (run.trial() == null && snapshot.equals(admission.snapshot()) &&
				Promotion.isSameTestCase(run.testCase(), admission.testCase()))
			{
				runs.set(i, new PromotionRun(run.role(), run.testCase(), key, null));
				draft.updatePromptAuthor(new PromptAuthorAttempt(promptAuthor.number(), promptAuthor.cell(),
					promptAuthor.snapshot(), runs, promptAuthor.minimality()));
				return run.testCase();
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
			draft.trials.set(index, new LogicalTrial(trial.key(), trial.testCase(), trial.identity(),
				trial.allocation(), TrialState.ASSIGNED, null, null, List.of(), null, trial.infrastructureFailures(),
				trial.infrastructureAttempts(), trial.observations() + 1, List.of(), null));
			draft.raise(new PendingDecision.MeasurementChanged(observed, differences));
			return;
		}
		MeasurementIdentity measured = trial.identity().replaceWith(observed);
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
		draft.trials.set(index, new LogicalTrial(trial.key(), trial.testCase(), measured,
			trial.allocation(), TrialState.JUDGED, complete.verdict(), complete.rationale(), complete.failedAssertions(),
			complete.triage(), trial.infrastructureFailures(), trial.infrastructureAttempts(), trial.observations(),
			complete.responses(), complete.files()));
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
			draft.replaceAttempt(attempt.withEvidence(attempt.identity().extendWith(observed), attempt.counts(),
				attempt.controlPairings(), attempt.incumbentPairings(), attempt.failures(), attempt.formalStream()));
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
			// A rate-limited, refused or interrupted trial is queued unchanged after this switch. The harness's shared
			// hold reaches the throttle through HoldAdmissions.
			case RATE_LIMITED, INTERRUPTED ->
			{
			}
		}
		List<InfrastructureAttempt> attempts = new ArrayList<>(trial.infrastructureAttempts());
		attempts.add(new InfrastructureAttempt(fail.at(), fail.failure(), fail.detail()));
		draft.trials.set(index, new LogicalTrial(trial.key(), trial.testCase(), trial.identity(), trial.allocation(),
			TrialState.ASSIGNED, null, null, List.of(), null, infrastructureFailures, attempts, trial.observations(),
			List.of(), null));
	}

	/**
	 * Reserves a stable launch-order number without counting an unvalidated prompt author session.
	 *
	 * @param draft the campaign state
	 * @param cell the addressed cell
	 * @throws IllegalStateException if the round has no available slot for that cell
	 */
	private static void startPromptAuthorAttempt(Draft draft, String cell)
	{
		requireConfirmed(draft);
		Round round = draft.round;
		if (round == null || draft.promptAuthor != null || !round.nextCell().orElse("").equals(cell))
			throw new IllegalStateException("No round offers a prompt author attempt for " + cell);
		int latest = draft.promptAuthorAttempts;
		for (int number : round.reservations().keySet())
			latest = Math.max(latest, number);
		for (AttemptResult result : draft.optimization.attempts())
			latest = Math.max(latest, result.number());
		draft.round = round.reserve(latest + 1, cell);
		int number = latest + 1;
		PromptAuthorApproach approach = draft.round.approach(number,
			draft.optimization.promptAuthorAttempts(cell, draft.round.number()));
		ApproachAssignment.Source source = ApproachAssignment.Source.DECLARATION;
		ApproachOrder order = round.approachOrders().get(cell);
		if (order != null)
		{
			if (order.fallback())
				source = ApproachAssignment.Source.FALLBACK;
			else
				source = ApproachAssignment.Source.RANKING;
		}
		draft.optimization = draft.optimization.withApproach(number, new ApproachAssignment(approach, source));
	}

	/**
	 * Receives a validated attempt into its reserved slot and plans its triggering runs.
	 *
	 * @param draft the state
	 * @param submit the received attempt
	 * @throws IllegalStateException if no pending reservation matches the attempt
	 */
	private static void submitPromptAuthorAttempt(Draft draft, CampaignEvent.SubmitPromptAuthorAttempt submit)
	{
		requireConfirmed(draft);
		String cell = submit.cell();
		int number = submit.number();
		Round round = draft.round;
		if (round == null || draft.promptAuthor != null ||
			!cell.equals(round.pendingPromptAuthorAttempts().get(number)))
		{
			throw new IllegalStateException("No pending prompt author attempt " + number + " addresses " + cell);
		}
		++draft.promptAuthorAttempts;
		List<PromotionRun> runs = List.of();
		if (!submit.snapshot().equals(draft.working.snapshot()))
		{
			List<DrawnTestCase> failures = draft.developmentFailures.stream().map(Failure::testCase).toList();
			List<String> cells = List.of(cell);
			if (round.weak())
			{
				String testId = draft.contract().findCell(cell).orElseThrow().testId();
				cells = draft.developmentCells().stream().filter(other -> other.testId().equals(testId)).
					map(Cell::id).toList();
			}
			runs = Promotion.plan(cells, failures, draft.generator());
		}
		PromptAuthorAttempt attempt = new PromptAuthorAttempt(number, cell, submit.snapshot(), runs,
			Minimality.start(submit.changes(), submit.snapshot()));
		draft.round = round.withAttempt(attempt);
		if (round.weak())
		{
			TrialAdoption.decideWeakRound(draft);
			return;
		}
		draft.promptAuthor = attempt;
		if (submit.snapshot().equals(draft.working.snapshot()))
		{
			TrialAdoption.retainAttempt(draft, attempt, false, false);
			draft.promptAuthor = null;
			TrialAdoption.rejectPromptAuthorAttempt(draft, cell);
			return;
		}
		TrialAdoption.decidePromotion(draft);
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
		PromptAuthorAttempt promptAuthor = draft.promptAuthor;
		if (promptAuthor == null || draft.trials.stream().anyMatch(trial -> trial.key().purpose() == Purpose.PROMOTION))
			throw new IllegalStateException("Minimality needs a completed attempt with no remaining promotion trials");
		Minimality next = promptAuthor.minimality().advance(promptAuthor.snapshot(), promptAuthor.runs(),
			TrialAdoption.decision(draft, promptAuthor));
		if (next.testing() == null)
		{
			List<String> removed = new ArrayList<>(draft.optimization.removed());
			removed.addAll(next.removed());
			draft.optimization = new OptimizationEvidence(draft.optimization.promptAuthors(), removed,
				draft.optimization.promptSizes(), draft.optimization.trialInputs(), draft.optimization.attempts(),
				draft.optimization.tokenUsage(), draft.optimization.approaches());
			TrialAdoption.promote(draft, new PromptAuthorAttempt(promptAuthor.number(), promptAuthor.cell(),
				next.accepted(), next.acceptedRuns(), next));
			return;
		}
		List<PromotionRun> runs = Promotion.resetRuns(promptAuthor.runs());
		draft.promptAuthor = new PromptAuthorAttempt(promptAuthor.number(), promptAuthor.cell(), snapshot, runs, next);
	}

	/**
	 * Commits the working candidate: freezes the procedure over the certification scope's cells, the comparisons and
	 * the formal stream, and assigns the next certification index.
	 *
	 * @param draft the state
	 * @param campaign the state before the event
	 * @param commit the event
	 * @throws IllegalStateException if no commit is due or the calibration is for another index or minimum pass rate
	 */
	private static void commit(Draft draft, Campaign campaign, CampaignEvent.Commit commit)
	{
		if (!CommitGate.isCommitDue(campaign))
			throw new IllegalStateException("No commit is due");
		int index = draft.attempts.size() + 1;
		SignificanceLevel level = ErrorBudget.certificationLevel(index);
		CapCalibration calibration = commit.calibration();
		if (!calibration.level().equals(level))
		{
			throw new IllegalStateException("The cap was calibrated at " + calibration.level() + ", but attempt " +
				index + " uses " + level);
		}
		BigDecimal minimumPassRate = draft.contract().minimumPassRate();
		if (calibration.minimumPassRate().compareTo(minimumPassRate) != 0)
		{
			throw new IllegalStateException("The cap was calibrated for the minimum pass rate " +
				calibration.minimumPassRate() + ", but the contract requires " + minimumPassRate);
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
				comparisons.add(Comparison.freeze(counts.passes(), counts.failures(), incumbentLevel, improvementLevel,
					minimumPassRate));
			}
			else
				comparisons.add(new Comparison(BigDecimal.ONE, BigDecimal.ZERO, improvementLevel, minimumPassRate));
		}
		DecisionBoundaries boundaries = DecisionBoundaries.compute(level, minimumPassRate, calibration.cap(),
			comparisons);
		CertificationProcedure procedure = new CertificationProcedure(improvementLevel, draft.baselineIndex,
			draft.baselineIdentity, cells, boundaries, CertificationProcedure.BETS,
			CertificationProcedure.ALLOCATION_POLICY);
		DrawStream formal = DrawStream.create(DrawStream.Kind.FORMAL, commit.formalSeed());
		formal.open();
		draft.attempts.add(new CertificationAttempt(index, draft.lineageNumber(), draft.working, draft.identity,
			procedure, commit.control(), Map.of(), Map.of(), Map.of(), List.of(), formal.state(), null, List.of()));
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

	/**
	 * Asks the user about reported edits outside the edit authority. Warnings that the user already answered by
	 * choosing to use an authorized source are dropped; if none remain, nothing is raised. The remaining warnings join
	 * the pending warning decision in its place, after the warnings it already lists, or else form one new decision.
	 * Each warning is listed once, in report order.
	 *
	 * @param draft the state
	 * @param warnings the reported warnings, in report order
	 * @throws IllegalStateException if the campaign is not active, has no confirmed contract or waits for a
	 *   revision
	 */
	private static void warnAuthority(Draft draft, List<AuthorityWarning> warnings)
	{
		requireConfirmed(draft);
		if (draft.revisionRequested)
			throw new IllegalStateException("The campaign waits for a contract revision");
		Set<AuthorityWarning> remaining = new LinkedHashSet<>(warnings);
		draft.answeredWarnings.forEach(remaining::remove);
		if (remaining.isEmpty())
			return;

		for (int i = 0; i < draft.pendingDecisions.size(); ++i)
		{
			if (draft.pendingDecisions.get(i) instanceof PendingDecision.AuthorityWarnings pending)
			{
				List<AuthorityWarning> merged = new ArrayList<>(pending.warnings());
				for (AuthorityWarning warning : remaining)
				{
					if (!merged.contains(warning))
						merged.add(warning);
				}
				draft.pendingDecisions.set(i, new PendingDecision.AuthorityWarnings(merged));
				return;
			}
		}
		draft.raise(new PendingDecision.AuthorityWarnings(List.copyOf(remaining)));
	}
}
