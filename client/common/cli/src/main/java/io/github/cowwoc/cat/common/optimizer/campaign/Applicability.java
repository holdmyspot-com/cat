package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;

/**
 * Whether a result still describes the current prompt system and conditions.
 * <p>
 * A result is current only while CAT knows of no material change. A known change makes it historical without
 * deleting or rewriting it. Conditions are compared campaign-wide and for the same cell, the requirement category
 * under a runner profile. Missing data is not a known change. A certification is also historical when its recorded
 * parameters do not imply the parameters that the current version of CAT certifies under.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status whether the result is current
 * @param promptContentChanged {@code true} if the prompt-system snapshot differs
 * @param changed the conditions with different recorded values campaign-wide or for the same cell
 * @param parameters the certification parameters whose recorded value does not imply their current value
 */
public record Applicability(Status status, boolean promptContentChanged, List<MeasurementCondition> changed,
	List<CertificationParameter> parameters)
{
	/**
	 * Whether a result describes the current state.
	 */
	public enum Status
	{
		/**
		 * CAT knows of no material change.
		 */
		CURRENT,
		/**
		 * CAT knows of a material change; the result describes the conditions it was measured under.
		 */
		HISTORICAL
	}

	/**
	 * Creates an applicability result.
	 *
	 * @param status whether the result is current
	 * @param promptContentChanged {@code true} if the prompt-system snapshot differs
	 * @param changed the conditions with different recorded values campaign-wide or for the same cell
	 * @param parameters the certification parameters whose recorded value does not imply their current value
	 * @throws NullPointerException if any of the arguments are null, or {@code changed} or {@code parameters} contain
	 *   null
	 */
	public Applicability
	{
		requireThat(status, "status").isNotNull();
		requireThat(changed, "changed").isNotNull();
		requireThat(parameters, "parameters").isNotNull();
		changed = List.copyOf(changed);
		parameters = List.copyOf(parameters);
	}

	/**
	 * Creates an applicability result of a result that records no certification parameters.
	 *
	 * @param status whether the result is current
	 * @param promptContentChanged {@code true} if the prompt-system snapshot differs
	 * @param changed the conditions with different recorded values campaign-wide or for the same cell
	 * @throws NullPointerException if {@code status} or {@code changed} are null, or {@code changed} contains null
	 */
	public Applicability(Status status, boolean promptContentChanged, List<MeasurementCondition> changed)
	{
		this(status, promptContentChanged, changed, List.of());
	}

	/**
	 * Decides whether a result applies to the current state.
	 *
	 * @param resultSnapshot the snapshot that the result measured
	 * @param resultIdentity the conditions that the result was measured under
	 * @param currentSnapshot the current snapshot
	 * @param currentIdentity the current conditions
	 * @return the applicability, naming each known change
	 * @throws NullPointerException if any argument is null
	 */
	public static Applicability of(Sha256 resultSnapshot, MeasurementIdentity resultIdentity, Sha256 currentSnapshot,
		MeasurementIdentity currentIdentity)
	{
		requireThat(resultSnapshot, "resultSnapshot").isNotNull();
		requireThat(resultIdentity, "resultIdentity").isNotNull();
		requireThat(currentSnapshot, "currentSnapshot").isNotNull();
		requireThat(currentIdentity, "currentIdentity").isNotNull();
		boolean promptContentChanged = !resultSnapshot.equals(currentSnapshot);
		List<MeasurementCondition> changed = resultIdentity.differences(currentIdentity);
		if (promptContentChanged || !changed.isEmpty())
			return new Applicability(Status.HISTORICAL, promptContentChanged, changed);
		return new Applicability(Status.CURRENT, false, changed);
	}

	/**
	 * Decides whether a certification attempt applies to the current state.
	 *
	 * @param attempt the certification attempt
	 * @param currentSnapshot the current snapshot
	 * @param currentIdentity the current conditions
	 * @param currentParameters the parameters that the current version of CAT certifies the attempt under
	 * @return the applicability, naming each known change
	 * @throws NullPointerException if any argument is null
	 */
	public static Applicability of(CertificationAttempt attempt, Sha256 currentSnapshot,
		MeasurementIdentity currentIdentity, CertificationParameters currentParameters)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(currentParameters, "currentParameters").isNotNull();
		Applicability measurement = of(attempt.candidate().snapshot(), attempt.identity(), currentSnapshot,
			currentIdentity);
		List<CertificationParameter> parameters = attempt.parameters().notImplying(currentParameters);
		Status status = measurement.status;
		if (!parameters.isEmpty())
			status = Status.HISTORICAL;
		return new Applicability(status, measurement.promptContentChanged, measurement.changed, parameters);
	}
}
