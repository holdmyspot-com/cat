package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorConflict;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;

import java.time.Instant;
import java.util.List;

/**
 * An input to {@link Transitions#apply(Campaign, CampaignEvent)} that requests a campaign state change.
 */
public sealed interface CampaignEvent
{
	/**
	 * Records token counts reported by a finished nested session.
	 *
	 * @param role the session's role
	 * @param usage the reported counts
	 */
	record ObserveTokenUsage(SessionRole role, TokenUsage usage) implements CampaignEvent
	{
		/**
		 * Creates the observation.
		 *
		 * @param role the session's role
		 * @param usage the reported counts
		 * @throws NullPointerException if {@code role} or {@code usage} are null
		 */
		public ObserveTokenUsage
		{
			requireThat(role, "role").isNotNull();
			requireThat(usage, "usage").isNotNull();
		}
	}

	/**
	 * The user confirmed a contract, which starts a new evidence history called a lineage.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param contract the confirmed contract, with the certification scope that the user chose
	 * @param contractHash the contract's identity
	 * @param seed the seed of the lineage's development and baseline draw streams
	 */
	record Confirm(Contract contract, Sha256 contractHash, long seed) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param contract the confirmed contract, with the certification scope that the user chose
		 * @param contractHash the contract's identity
		 * @param seed the seed of the lineage's development and baseline draw streams
		 * @throws NullPointerException if {@code contract} or {@code contractHash} are null
		 * @throws IllegalArgumentException if {@code contract} has no certification scope
		 */
		public Confirm
		{
			requireThat(contract, "contract").isNotNull();
			requireThat(contractHash, "contractHash").isNotNull();
			if (contract.profiles().scope() == null)
				throw new IllegalArgumentException("A confirmed contract needs a certification scope");
		}
	}

	/**
	 * The user asked to revise the contract. A running certification attempt stops and never resumes.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record Revise() implements CampaignEvent
	{
	}

	/**
	 * Requests assignment and recording of a trial before its session starts.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param admission the admission that the scheduler planned
	 */
	record Assign(Admission.Assign admission) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param admission the admission that the scheduler planned
		 * @throws NullPointerException if {@code admission} is null
		 */
		public Assign
		{
			requireThat(admission, "admission").isNotNull();
		}
	}

	/**
	 * A session started running an assigned logical trial.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param key the trial
	 */
	record Start(TrialKey key) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param key the trial
		 * @throws NullPointerException if {@code key} is null
		 */
		public Start
		{
			requireThat(key, "key").isNotNull();
		}
	}

	/**
	 * A running logical trial was labelled by its deterministic checks or by its judge.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param key the trial
	 * @param verdict the trial's label
	 * @param rationale the judge's explanation of the label or, when no judge ran, the record of the trial's
	 *   deterministic checks
	 * @param failedRequirements the requirements that a failed trial violated, each with its behavior: those of its
	 *   failed deterministic checks or, when its judge failed it, those that the judge named; empty for a pass
	 * @param triage the impact of a failed development trial, or {@code null} for any other trial
	 * @param observed the conditions that the trial session and, if one ran, the judge session observed
	 * @param inputTokens the trial session's input tokens, or {@code null} if unavailable
	 * @param responses each completed turn's assistant messages in order, ending with its final response
	 * @param files where the trial session's harness events and file changes were retained
	 */
	record Complete(TrialKey key, Verdict verdict, String rationale, List<FailedRequirement> failedRequirements,
		Triage triage, MeasurementIdentity observed, Long inputTokens, List<List<String>> responses, TrialFiles files)
		implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param key the trial
		 * @param verdict the trial's label
		 * @param rationale the judge's explanation of the label or, when no judge ran, the record of the trial's
		 *   deterministic checks
		 * @param failedRequirements the requirements that a failed trial violated, each with its behavior: those of its
		 *   failed deterministic checks or, when its judge failed it, those that the judge named; empty for a pass
		 * @param triage the impact of a failed development trial, or {@code null} for any other trial
		 * @param observed the conditions that the trial session and, if one ran, the judge session observed
		 * @param inputTokens the trial session's input tokens, or {@code null} if unavailable
		 * @param responses each completed turn's assistant messages in order, ending with its final response
		 * @param files where the trial session's harness events and file changes were retained
		 * @throws NullPointerException if any of {@code key}, {@code verdict}, {@code rationale},
		 *   {@code failedRequirements}, {@code observed}, {@code responses}, {@code files}, a failed requirement, a
		 *   turn's messages or an assistant message are null
		 * @throws IllegalArgumentException if {@code inputTokens} is negative, a pass has failed requirements, or a
		 *   {@code triage} exists for a trial that is not a failed {@link Purpose#DEVELOPMENT development} trial
		 */
		public Complete
		{
			requireThat(key, "key").isNotNull();
			requireThat(verdict, "verdict").isNotNull();
			requireThat(rationale, "rationale").isNotNull();
			requireThat(failedRequirements, "failedRequirements").isNotNull();
			if (verdict == Verdict.PASS)
				requireThat(failedRequirements, "failedRequirements").isEmpty();
			requireThat(observed, "observed").isNotNull();
			requireThat(responses, "responses").isNotNull();
			requireThat(files, "files").isNotNull();
			if (inputTokens != null)
				requireThat(inputTokens.longValue(), "inputTokens").isNotNegative();
			if (triage != null)
			{
				requireThat(verdict, "verdict").isEqualTo(Verdict.FAIL);
				requireThat(key.purpose(), "key.purpose()").isEqualTo(Purpose.DEVELOPMENT);
			}

			failedRequirements = List.copyOf(failedRequirements);
			responses = responses.stream().map(List::copyOf).toList();
		}
	}

	/**
	 * A run of a logical trial ended without an outcome. The trial records it as an infrastructure attempt.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param key the trial
	 * @param failure why the run ended
	 * @param detail what the harness or CAT reported about the failure
	 * @param at when the run ended
	 */
	record Fail(TrialKey key, RunFailure failure, String detail, Instant at) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param key the trial
		 * @param failure why the run ended
		 * @param detail what the harness or CAT reported about the failure
		 * @param at when the run ended
		 * @throws NullPointerException if {@code key}, {@code failure}, {@code detail} or {@code at} are null
		 * @throws IllegalArgumentException if {@code detail} is blank
		 */
		public Fail
		{
			requireThat(key, "key").isNotNull();
			requireThat(failure, "failure").isNotNull();
			requireThat(detail, "detail").isNotBlank();
			requireThat(at, "at").isNotNull();
		}
	}

	/**
	 * The harness's shared hold, set by a rate-limit rejection or a provider refusal, stops admissions until it ends
	 * and drops the concurrency window to 1.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param until when the hold ends
	 * @param backoffs the shared hold's number of doubled holds since a turn last completed
	 */
	record HoldAdmissions(Instant until, int backoffs) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param until when the hold ends
		 * @param backoffs the shared hold's number of doubled holds since a turn last completed
		 * @throws NullPointerException if {@code until} is null
		 * @throws IllegalArgumentException if {@code backoffs} is negative
		 */
		public HoldAdmissions
		{
			requireThat(until, "until").isNotNull();
			requireThat(backoffs, "backoffs").isNotNegative();
		}
	}

	/**
	 * The harness reported its rate-limit windows.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param windows the reported windows
	 * @param at when they were reported
	 */
	record ObserveRateLimit(List<RateLimitWindow> windows, Instant at) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param windows the reported windows
		 * @param at when they were reported
		 * @throws NullPointerException if any argument is null
		 */
		public ObserveRateLimit
		{
			requireThat(windows, "windows").isNotNull();
			requireThat(at, "at").isNotNull();
			windows = List.copyOf(windows);
		}
	}

	/**
	 * Reserves an author session's stable number before it starts.
	 *
	 * @param cell the optimization round's addressed cell
	 */
	record StartAuthorAttempt(String cell) implements CampaignEvent
	{
		/**
		 * Creates a reservation request.
		 *
		 * @param cell the addressed cell
		 * @throws NullPointerException if category is null
		 * @throws IllegalArgumentException if category is blank
		 */
		public StartAuthorAttempt
		{
			requireThat(cell, "category").isNotBlank();
		}
	}

	/**
	 * An author's proposed file changes passed inventory validation, so checks that decide whether to promote
	 * the snapshot can start.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param number the reserved attempt number
	 * @param cell the optimization round's cell identifier that the attempt addresses
	 * @param snapshot the attempt's snapshot
	 * @param changes the captured original file changes
	 */
	record SubmitAuthorAttempt(int number, String cell, Sha256 snapshot, List<FileDelta> changes)
		implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param number the reserved attempt number
		 * @param cell the optimization round's cell identifier that the attempt addresses
		 * @param snapshot the attempt's snapshot
		 * @param changes the captured original file changes
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if number is not positive or category is blank
		 */
		public SubmitAuthorAttempt
		{
			requireThat(number, "number").isPositive();
			requireThat(cell, "category").isNotBlank();
			requireThat(snapshot, "snapshot").isNotNull();
			changes = List.copyOf(changes);
		}
	}

	/**
	 * Advances a completed promotion decision to the next reduction or promotes its minimal passing snapshot.
	 *
	 * @param snapshot the saved overlay for the next reduction, ignored when the search has finished
	 */
	record AdvanceMinimality(Sha256 snapshot) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param snapshot the saved next reduction
		 * @throws NullPointerException if snapshot is null
		 */
		public AdvanceMinimality
		{
			requireThat(snapshot, "snapshot").isNotNull();
		}
	}

	/**
	 * Records a snapshot's observed prompt-file sizes.
	 *
	 * @param snapshot the measured snapshot
	 * @param size the measurements or the reason they are unavailable
	 */
	record MeasurePrompt(Sha256 snapshot, PromptSize size) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param snapshot the measured snapshot
		 * @param size the measurements
		 * @throws NullPointerException if either argument is null
		 */
		public MeasurePrompt
		{
			requireThat(snapshot, "snapshot").isNotNull();
			requireThat(size, "size").isNotNull();
		}
	}

	/**
	 * Retains a completed author session's diagnosis independently of whether it contributes a candidate.
	 *
	 * @param diagnosis the author's retained evidence
	 */
	record RetainAuthorDiagnosis(AuthorDiagnosis diagnosis) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param diagnosis the retained evidence
		 * @throws NullPointerException if the diagnosis is null
		 */
		public RetainAuthorDiagnosis
		{
			requireThat(diagnosis, "diagnosis").isNotNull();
		}
	}

	/**
	 * Starts the working candidate's next certification attempt by freezing its procedure and opening its trial stream.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param calibration the cap calibrated for the attempt's certification level
	 * @param formalSeed the seed of the attempt's formal draw stream
	 */
	record Commit(CapCalibration calibration, long formalSeed) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param calibration the cap calibrated for the attempt's certification level
		 * @param formalSeed the seed of the attempt's formal draw stream
		 * @throws NullPointerException if {@code calibration} is null
		 */
		public Commit
		{
			requireThat(calibration, "calibration").isNotNull();
		}
	}

	/**
	 * A deriver session wrote the key-value combinations of a formal failure group's current round.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param attempt the index of the attempt whose failure group the values address
	 * @param group the group's 1-based position in the attempt's groups
	 * @param combinations the new key-value combinations, each recording {@code attempt}
	 */
	record ReproductionValues(int attempt, int group, List<AddedCombination> combinations) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param attempt the index of the attempt whose failure group the values address
		 * @param group the group's 1-based position in the attempt's groups
		 * @param combinations the new key-value combinations, each recording {@code attempt}
		 * @throws NullPointerException if {@code combinations} is null or contains null
		 * @throws IllegalArgumentException if {@code group} is not positive, {@code combinations} is empty or a
		 *   combination records another attempt
		 */
		public ReproductionValues
		{
			requireThat(group, "group").isPositive();
			requireThat(combinations, "combinations").isNotEmpty();
			combinations = List.copyOf(combinations);
			for (AddedCombination combination : combinations)
				requireThat(combination.attempt(), "combination.attempt()").isEqualTo(attempt, "attempt");
		}
	}

	/**
	 * Cap calibration for the next certification attempt failed.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param detail why calibration failed
	 */
	record CalibrationFailed(String detail) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param detail why calibration failed
		 * @throws NullPointerException if {@code detail} is null
		 * @throws IllegalArgumentException if {@code detail} is blank
		 */
		public CalibrationFailed
		{
			requireThat(detail, "detail").isNotBlank();
		}
	}

	/**
	 * A valid author report found conflicts in confirmed requirements.
	 *
	 * @param conflicts the conflicts, in report order
	 */
	record FindConflict(List<AuthorConflict> conflicts) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param conflicts the ordered conflicts
		 * @throws NullPointerException if the list or an element is null
		 * @throws IllegalArgumentException if {@code conflicts} is empty
		 */
		public FindConflict
		{
			conflicts = List.copyOf(conflicts);
			requireThat(conflicts, "conflicts").isNotEmpty();
		}
	}

	/**
	 * The user selects a resolution of the first unanswered conflict.
	 *
	 * @param resolution the 1-based resolution position
	 */
	record ResolveConflict(int resolution) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param resolution the 1-based resolution position
		 * @throws IllegalArgumentException if {@code resolution} is not positive
		 */
		public ResolveConflict
		{
			requireThat(resolution, "resolution").isPositive();
		}
	}

	/**
	 * The user answered a pending decision.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param choice the answer
	 * @param attempt the certification index of the attempt to keep for {@link Choice#KEEP_IMPROVEMENT}, otherwise
	 *   0
	 * @param reviewPoint the next review point for {@link Choice#MORE_EVIDENCE}, otherwise 0
	 */
	record Choose(Choice choice, int attempt, int reviewPoint) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param choice the answer
		 * @param attempt the certification index of the attempt to keep for {@link Choice#KEEP_IMPROVEMENT},
		 *   otherwise 0
		 * @param reviewPoint the next review point for {@link Choice#MORE_EVIDENCE}, otherwise 0
		 * @throws NullPointerException if {@code choice} is null
		 * @throws IllegalArgumentException if {@code attempt} is not positive for {@link Choice#KEEP_IMPROVEMENT}
		 *   or is not zero for another choice, or {@code reviewPoint} is not positive for {@link Choice#MORE_EVIDENCE}
		 *   or is not zero for another choice
		 */
		public Choose
		{
			requireThat(choice, "choice").isNotNull();
			if (choice == Choice.KEEP_IMPROVEMENT)
				requireThat(attempt, "attempt").isPositive();
			else
				requireThat(attempt, "attempt").isZero();
			if (choice == Choice.MORE_EVIDENCE)
				requireThat(reviewPoint, "reviewPoint").isPositive();
			else
				requireThat(reviewPoint, "reviewPoint").isZero();
		}

		/**
		 * Returns a choice that needs no argument.
		 *
		 * @param choice the answer
		 * @return the event
		 * @throws NullPointerException if {@code choice} is null
		 * @throws IllegalArgumentException if the choice is {@link Choice#KEEP_IMPROVEMENT} or
		 *   {@link Choice#MORE_EVIDENCE}
		 */
		public static Choose of(Choice choice)
		{
			return new Choose(choice, 0, 0);
		}

		/**
		 * Returns a {@link Choice#KEEP_IMPROVEMENT} choice.
		 *
		 * @param attempt the certification index of the attempt to keep
		 * @return the event
		 * @throws IllegalArgumentException if {@code attempt} is not positive
		 */
		public static Choose keep(int attempt)
		{
			return new Choose(Choice.KEEP_IMPROVEMENT, attempt, 0);
		}

		/**
		 * Returns a {@link Choice#MORE_EVIDENCE} choice.
		 *
		 * @param reviewPoint the next review point
		 * @return the event
		 * @throws IllegalArgumentException if {@code reviewPoint} is not positive
		 */
		public static Choose moreEvidence(int reviewPoint)
		{
			return new Choose(Choice.MORE_EVIDENCE, 0, reviewPoint);
		}
	}

	/**
	 * Reports required edits outside the author's permitted files and asks the user how to continue. Only the
	 * driver applies this event.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param warnings the warnings, in report order
	 */
	record WarnAuthority(List<AuthorityWarning> warnings) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param warnings the warnings, in report order
		 * @throws NullPointerException if {@code warnings} is null or contains null
		 * @throws IllegalArgumentException if {@code warnings} is empty
		 */
		public WarnAuthority
		{
			requireThat(warnings, "warnings").isNotEmpty();
			warnings = List.copyOf(warnings);
		}
	}

	/**
	 * The user cancelled the campaign.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record Cancel() implements CampaignEvent
	{
	}

	/**
	 * The user closed the campaign, for example to start a new one with a fresh budget.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record Close() implements CampaignEvent
	{
	}

	/**
	 * CAT observed measurement conditions outside a trial's completion, before the next model call.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param observed the observed conditions
	 */
	record ChangeMeasurement(MeasurementIdentity observed) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param observed the observed conditions
		 * @throws NullPointerException if {@code observed} is null
		 */
		public ChangeMeasurement
		{
			requireThat(observed, "observed").isNotNull();
		}
	}

	/**
	 * Inventory validation found a change in the optimization worktree that CAT did not make.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param description what changed
	 */
	record DetectUnownedChange(String description) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param description what changed
		 * @throws NullPointerException if {@code description} is null
		 * @throws IllegalArgumentException if {@code description} is blank
		 */
		public DetectUnownedChange
		{
			requireThat(description, "description").isNotBlank();
		}
	}

	/**
	 * CAT found a condition that makes certification unavailable under the current lineage.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param outcome the matching {@code certification unavailable} outcome
	 */
	record ReportUnavailable(Outcome outcome) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param outcome the matching {@code certification unavailable} outcome
		 * @throws NullPointerException if {@code outcome} is null
		 * @throws IllegalArgumentException if {@code outcome} does not report unavailability
		 */
		public ReportUnavailable
		{
			requireThat(outcome, "outcome").isNotNull();
			requireThat(outcome.isUnavailable(), "outcome.isUnavailable()").isTrue();
		}
	}
}
