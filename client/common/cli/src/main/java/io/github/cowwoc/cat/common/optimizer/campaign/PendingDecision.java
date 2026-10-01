package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;

import java.util.List;

/**
 * A decision that the campaign waits for the user to make. While one is pending, nothing is admitted.
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
	 * A logical trial ended without an outcome {@value Campaign#INFRASTRUCTURE_FAILURE_LIMIT} times.
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
	 * A category reached its review point unresolved.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param category the category
	 * @param outcomes the category's adopted development outcomes
	 */
	record CheckpointReached(String category, int outcomes) implements PendingDecision
	{
		/**
		 * Creates the decision.
		 *
		 * @param category the category
		 * @param outcomes the category's adopted development outcomes
		 * @throws NullPointerException if {@code category} is null
		 * @throws IllegalArgumentException if {@code category} is blank or {@code outcomes} is negative
		 */
		public CheckpointReached
		{
			requireThat(category, "category").isNotBlank();
			requireThat(outcomes, "outcomes").isNotNegative();
		}
	}

	/**
	 * A certification attempt ended without certification, and its evidence meets the keep-improvement conditions.
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
	 * A certification attempt certified, but some category is proven worse than the incumbent.
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
	 * A complete development round produced no promotable attempt.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record PlateauReached() implements PendingDecision
	{
	}

	/**
	 * A condition makes certification unavailable under the current lineage.
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

		/**
		 * Indicates whether authorizing a target would permit some warned edit.
		 *
		 * @return {@code true} if a warning offers {@link AuthorityWarning.Choice#AUTHORIZE_TARGET}
		 */
		public boolean offersAuthorization()
		{
			for (AuthorityWarning warning : warnings)
			{
				if (warning.choices().contains(AuthorityWarning.Choice.AUTHORIZE_TARGET))
					return true;
			}
			return false;
		}
	}
}
