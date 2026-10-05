package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorConflict;

import java.util.ArrayList;
import java.util.List;

/**
 * A decision that the campaign waits for the user to make. While one is pending, no new work starts.
 * <p>
 * {@link Campaign#choices()} lists the choices that the user may give.
 */
public sealed interface PendingDecision
{
	/**
	 * A run observed conditions that differ from the campaign's measurement identity.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param observed the observed conditions
	 * @param changed the conditions whose observed value differs
	 */
	record MeasurementChanged(MeasurementIdentity observed, List<MeasurementCondition> changed)
		implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param observed the observed conditions
		 * @param changed the conditions whose observed value differs
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code changed} is empty
		 */
		public MeasurementChanged
		{
			requireThat(observed, "observed").isNotNull();
			requireThat(changed, "changed").isNotEmpty();
			changed = List.copyOf(changed);
		}

		/**
		 * Indicates whether the loader configuration changed, which cannot start a new measurement identity.
		 *
		 * @return {@code true} if {@link MeasurementCondition#LOADER_CONFIGURATION} changed
		 */
		public boolean isLoaderChanged()
		{
			return changed.contains(MeasurementCondition.LOADER_CONFIGURATION);
		}
	}

	/**
	 * The optimization worktree holds a change that CAT did not make.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param description what changed
	 */
	record UnownedChange(String description) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param description what changed
		 * @throws NullPointerException if {@code description} is null
		 * @throws IllegalArgumentException if {@code description} is blank
		 */
		public UnownedChange
		{
			requireThat(description, "description").isNotBlank();
		}
	}

	/**
	 * A trial had {@value Campaign#INFRASTRUCTURE_FAILURE_LIMIT} infrastructure failures since assignment or the last
	 * retry choice; the user must choose whether to retry or stop. Rate limits and interruptions are excluded.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param key the trial
	 */
	record TrialFailures(TrialKey key) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param key the trial
		 * @throws NullPointerException if {@code key} is null
		 */
		public TrialFailures
		{
			requireThat(key, "key").isNotNull();
		}
	}

	/**
	 * Cap calibration failed, so the attempt stays uncommitted.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param detail why calibration failed
	 */
	record CalibrationFailure(String detail) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param detail why calibration failed
		 * @throws NullPointerException if {@code detail} is null
		 * @throws IllegalArgumentException if {@code detail} is blank
		 */
		public CalibrationFailure
		{
			requireThat(detail, "detail").isNotBlank();
		}
	}

	/**
	 * A cell (category and trial profile) reached the outcome count that triggers review without meeting readiness.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param cell the cell's identifier
	 * @param outcomes the cell's development outcomes included in evidence
	 */
	record CheckpointReached(String cell, int outcomes) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param cell the cell's identifier
		 * @param outcomes the cell's development outcomes included in evidence
		 * @throws NullPointerException if {@code cell} is null
		 * @throws IllegalArgumentException if {@code cell} is blank or {@code outcomes} is negative
		 */
		public CheckpointReached
		{
			requireThat(cell, "category").isNotBlank();
			requireThat(outcomes, "outcomes").isNotNegative();
		}
	}

	/**
	 * A certification attempt ended without certification and meets {@link KeepImprovement}'s conditions for offering
	 * its snapshot as an improvement over the original prompt system.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param attempt the attempt's certification index
	 */
	record ImprovementProven(int attempt) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param attempt the attempt's certification index
		 * @throws IllegalArgumentException if {@code attempt} is not positive
		 */
		public ImprovementProven
		{
			requireThat(attempt, "attempt").isPositive();
		}
	}

	/**
	 * Every failure group of an ended certification attempt either reproduced or ran its rounds without reproducing,
	 * and at least one did not reproduce. No author works on a group that did not reproduce.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param attempt the attempt's certification index
	 */
	record FailuresNotReproduced(int attempt) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param attempt the attempt's certification index
		 * @throws IllegalArgumentException if {@code attempt} is not positive
		 */
		public FailuresNotReproduced
		{
			requireThat(attempt, "attempt").isPositive();
		}
	}

	/**
	 * A certification attempt certified, but some cell is proven worse than the original prompt system.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param attempt the attempt's certification index
	 */
	record RegressionTradeOff(int attempt) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param attempt the attempt's certification index
		 * @throws IllegalArgumentException if {@code attempt} is not positive
		 */
		public RegressionTradeOff
		{
			requireThat(attempt, "attempt").isPositive();
		}
	}

	/**
	 * Every failing cell exhausted its author attempts without producing edits that passed promotion checks.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record PlateauReached() implements PendingDecision
	{
	}

	/**
	 * A condition makes certification unavailable under the current confirmed contract.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param outcome the {@code certification unavailable} outcome
	 */
	record CertificationUnavailable(Outcome outcome) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param outcome the {@code certification unavailable} outcome
		 * @throws NullPointerException if {@code outcome} is null
		 * @throws IllegalArgumentException if {@code outcome} does not report unavailability
		 */
		public CertificationUnavailable
		{
			requireThat(outcome, "outcome").isNotNull();
			requireThat(outcome.isUnavailable(), "outcome.isUnavailable()").isTrue();
		}
	}

	/**
	 * An author reported conflicts held by the confirmed prompt system. Answers follow report order.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param conflicts the conflicts, in report order
	 * @param resolutions the 1-based positions already selected, in conflict order
	 */
	record ConflictFound(List<AuthorConflict> conflicts, List<Integer> resolutions) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param conflicts the ordered conflicts
		 * @param resolutions the selected 1-based resolution positions
		 * @throws NullPointerException if an argument or collection element is null
		 * @throws IllegalArgumentException if no conflicts exist, every conflict is answered, or an answer is out of range
		 */
		public ConflictFound
		{
			conflicts = List.copyOf(conflicts);
			resolutions = List.copyOf(resolutions);
			requireThat(conflicts, "conflicts").isNotEmpty();
			requireThat(resolutions.size(), "resolutions.size()").isLessThan(conflicts.size());
			for (int i = 0; i < resolutions.size(); ++i)
			{
				requireThat(resolutions.get(i), "resolution").isPositive().
					isLessThanOrEqualTo(conflicts.get(i).resolutions().size());
			}
		}

		/**
		 * Returns the first unanswered conflict.
		 *
		 * @return the conflict
		 */
		public AuthorConflict current()
		{
			return conflicts.get(resolutions.size());
		}

		/**
		 * Adds the user's answer for the current conflict.
		 *
		 * @param resolution the 1-based position of its resolution
		 * @return all selected positions, including that answer
		 * @throws IllegalArgumentException if the position is out of range
		 */
		public List<Integer> choose(int resolution)
		{
			requireThat(resolution, "resolution").isPositive().isLessThanOrEqualTo(current().resolutions().size());
			List<Integer> chosen = new ArrayList<>(resolutions);
			chosen.add(resolution);
			return List.copyOf(chosen);
		}

		/**
		 * Builds revision feedback from each answered conflict's requirements, quotations and selected resolution.
		 *
		 * @param resolution the last conflict's 1-based resolution position
		 * @return the feedback for all conflicts
		 * @throws IllegalArgumentException if the position is out of range
		 * @throws IllegalStateException if more than one conflict remains unanswered
		 */
		public String feedback(int resolution)
		{
			List<Integer> chosen = choose(resolution);
			if (chosen.size() != conflicts.size())
				throw new IllegalStateException("More than one conflict still needs a resolution");
			StringBuilder feedback = new StringBuilder(256);
			for (int i = 0; i < chosen.size(); ++i)
			{
				AuthorConflict conflict = conflicts.get(i);
				feedback.append("Requirements: ").append(String.join(", ", conflict.requirementIds())).append('\n');
				for (AuthorConflict.Quotation quotation : conflict.quotations())
					feedback.append(quotation.path()).append(": ").append(quotation.text()).append('\n');
				feedback.append("Resolution: ").append(conflict.resolutions().get(chosen.get(i) - 1)).append('\n');
			}
			return feedback.toString();
		}
	}

	/**
	 * An author reported edits that its requirements need but that lie outside the edit authority.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param warnings the warnings, in report order
	 */
	record AuthorityWarnings(List<AuthorityWarning> warnings) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param warnings the warnings, in report order
		 * @throws NullPointerException if {@code warnings} is null or contains null
		 * @throws IllegalArgumentException if {@code warnings} is empty
		 */
		public AuthorityWarnings
		{
			requireThat(warnings, "warnings").isNotEmpty();
			warnings = List.copyOf(warnings);
		}
	}
}
