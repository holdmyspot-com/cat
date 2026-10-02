package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * An input to {@link CampaignTransitions#apply(Campaign, CampaignEvent)} that requests a campaign state change.
 */
public sealed interface CampaignEvent
{
	/**
	 * The user confirmed a contract, which starts a new evidence history called a lineage.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param contract the confirmed contract
	 * @param contractHash the contract's identity
	 * @param seed the seed of the lineage's development and baseline draw streams
	 */
	record Confirm(Contract contract, Sha256 contractHash, long seed) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param contract the confirmed contract
		 * @param contractHash the contract's identity
		 * @param seed the seed of the lineage's development and baseline draw streams
		 * @throws NullPointerException if {@code contract} or {@code contractHash} are null
		 */
		public Confirm
		{
			requireThat(contract, "contract").isNotNull();
			requireThat(contractHash, "contractHash").isNotNull();
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
	 * @param observed the conditions that the trial session and, if one ran, the judge session observed
	 */
	record Complete(TrialKey key, Verdict verdict, String rationale, MeasurementIdentity observed)
		implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param key the trial
		 * @param verdict the trial's label
		 * @param rationale the judge's explanation of the label or, when no judge ran, the record of the trial's
		 *   deterministic checks
		 * @param observed the conditions that the trial session and, if one ran, the judge session observed
		 * @throws NullPointerException if any argument is null
		 */
		public Complete
		{
			requireThat(key, "key").isNotNull();
			requireThat(verdict, "verdict").isNotNull();
			requireThat(rationale, "rationale").isNotNull();
			requireThat(observed, "observed").isNotNull();
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
	 * @param resetsAt when the provider accepts requests again, or {@code null} if it did not say or the run was not
	 *   rate-limited
	 */
	record Fail(TrialKey key, RunFailure failure, String detail, Instant at, Instant resetsAt)
		implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param key the trial
		 * @param failure why the run ended
		 * @param detail what the harness or CAT reported about the failure
		 * @param at when the run ended
		 * @param resetsAt when the provider accepts requests again, or {@code null} if it did not say or the run was
		 *   not rate-limited
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
	 * An author's proposed file changes passed inventory validation, so checks that decide whether to promote
	 * the snapshot can start.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param category the optimization round's cell identifier that the attempt addresses
	 * @param snapshot the attempt's snapshot
	 */
	record SubmitAuthorAttempt(String category, Sha256 snapshot) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param category the optimization round's cell identifier that the attempt addresses
		 * @param snapshot the attempt's snapshot
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code category} is blank
		 */
		public SubmitAuthorAttempt
		{
			requireThat(category, "category").isNotBlank();
			requireThat(snapshot, "snapshot").isNotNull();
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
	 * The user answered a pending decision.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param choice the answer
	 * @param attempt the certification index of the attempt to keep for {@link Choice#KEEP_IMPROVEMENT}, otherwise
	 *   0
	 * @param reviewPoint the next review point for {@link Choice#MORE_EVIDENCE}, otherwise 0
	 * @param target the normalized absolute path of the external target to authorize for
	 *   {@link Choice#AUTHORIZE_TARGET}, otherwise {@code null}
	 */
	record Choose(Choice choice, int attempt, int reviewPoint, Path target) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param choice the answer
		 * @param attempt the certification index of the attempt to keep for {@link Choice#KEEP_IMPROVEMENT},
		 *   otherwise 0
		 * @param reviewPoint the next review point for {@link Choice#MORE_EVIDENCE}, otherwise 0
		 * @param target the normalized absolute path of the external target to authorize for
		 *   {@link Choice#AUTHORIZE_TARGET}, otherwise {@code null}
		 * @throws NullPointerException if {@code choice} is null, or {@code target} is null and the choice is
		 *   {@link Choice#AUTHORIZE_TARGET}
		 * @throws IllegalArgumentException if {@code attempt} is not positive for {@link Choice#KEEP_IMPROVEMENT}
		 *   or is not zero for another choice, {@code reviewPoint} is not positive for {@link Choice#MORE_EVIDENCE}
		 *   or is not zero for another choice, or {@code target} is set for another choice or is not a normalized
		 *   absolute path
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
			if (choice == Choice.AUTHORIZE_TARGET)
			{
				requireThat(target, "target").isNotNull();
				FileInventory.requireInventoryPath(target, "target");
				requireThat(target, "target").isAbsolute();
			}
			else
				requireThat(target, "target").isNull();
		}

		/**
		 * Returns a choice that needs no argument.
		 *
		 * @param choice the answer
		 * @return the event
		 * @throws NullPointerException if {@code choice} is null or is {@link Choice#AUTHORIZE_TARGET}, which requires
		 *   a target argument
		 * @throws IllegalArgumentException if the choice is {@link Choice#KEEP_IMPROVEMENT} or
		 *   {@link Choice#MORE_EVIDENCE}
		 */
		public static Choose of(Choice choice)
		{
			return new Choose(choice, 0, 0, null);
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
			return new Choose(Choice.KEEP_IMPROVEMENT, attempt, 0, null);
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
			return new Choose(Choice.MORE_EVIDENCE, 0, reviewPoint, null);
		}

		/**
		 * Returns a {@link Choice#AUTHORIZE_TARGET} choice.
		 *
		 * @param target the normalized absolute path of the external target that a pending warning names
		 * @return the event
		 * @throws NullPointerException if {@code target} is null
		 * @throws IllegalArgumentException if {@code target} is not a normalized absolute path
		 */
		public static Choose authorize(Path target)
		{
			return new Choose(Choice.AUTHORIZE_TARGET, 0, 0, target);
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
