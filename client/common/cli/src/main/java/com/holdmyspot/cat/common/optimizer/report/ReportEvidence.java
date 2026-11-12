package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck;

import java.time.Instant;
import java.util.List;

/**
 * The observations that a status report needs and that the campaign state does not hold.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param observations the adopted outcomes of the reported phase, each with the concurrency level it ran at
 * @param symbolicLinks {@code true} if staging replaced symbolic links with copies of their targets
 * @param interrupted {@code true} if work stopped and can resume
 * @param infrastructureFailure the precise failure that stopped work and is not a pass or fail outcome, or
 *   {@code null} if none did
 * @param sharedHoldUntil the end of the hold on the harness's shared capacity, which stops the trials of every
 *   campaign of the harness, or {@code null} if none lasts
 * @param certifiedEnvironment the environment that the campaign's delivered certification was measured in, which the
 *   report shows, or {@code null} if the campaign delivered none
 * @param pilotUnfinished {@code true} if a command ran the campaign's guard pilot for one slice and left it with
 *   trials to run, so that the host agent repeats the command and the campaign is {@code running} even though it waits
 *   for its first confirmation
 */
public record ReportEvidence(List<AssumptionCheck.Observation> observations, boolean symbolicLinks,
	boolean interrupted, String infrastructureFailure, Instant sharedHoldUntil,
	CertifiedEnvironment certifiedEnvironment, boolean pilotUnfinished)
{
	/**
	 * No observations beyond the campaign state.
	 */
	public static final ReportEvidence NONE = new ReportEvidence(List.of(), false, false, null);

	/**
	 * Creates evidence.
	 *
	 * @param observations the adopted outcomes of the reported phase, each with the concurrency level it ran at
	 * @param symbolicLinks {@code true} if staging replaced symbolic links with copies of their targets
	 * @param interrupted {@code true} if work stopped and can resume
	 * @param infrastructureFailure the precise failure that stopped work, or {@code null} if none did
	 * @param sharedHoldUntil the end of the hold on the harness's shared capacity, or {@code null} if none lasts
	 * @param certifiedEnvironment the environment that the campaign's delivered certification was measured in, or
	 *   {@code null} if the campaign delivered none
	 * @param pilotUnfinished {@code true} if a command left the campaign's guard pilot with trials to run
	 * @throws NullPointerException if {@code observations} is null
	 * @throws IllegalArgumentException if {@code infrastructureFailure} is blank
	 */
	public ReportEvidence
	{
		requireThat(observations, "observations").isNotNull();
		if (infrastructureFailure != null)
			requireThat(infrastructureFailure, "infrastructureFailure").isNotBlank();
		observations = List.copyOf(observations);
	}

	/**
	 * Creates evidence without a hold on the harness's shared capacity or a delivered certification.
	 *
	 * @param observations the adopted outcomes of the reported phase, each with the concurrency level it ran at
	 * @param symbolicLinks {@code true} if staging replaced symbolic links with copies of their targets
	 * @param interrupted {@code true} if work stopped and can resume
	 * @param infrastructureFailure the precise failure that stopped work, or {@code null} if none did
	 * @throws NullPointerException if {@code observations} is null
	 * @throws IllegalArgumentException if {@code infrastructureFailure} is blank
	 */
	public ReportEvidence(List<AssumptionCheck.Observation> observations, boolean symbolicLinks, boolean interrupted,
		String infrastructureFailure)
	{
		this(observations, symbolicLinks, interrupted, infrastructureFailure, null, null, false);
	}

	/**
	 * Returns this evidence with a hold on the harness's shared capacity.
	 *
	 * @param sharedHoldUntil the end of the hold, or {@code null} if none lasts
	 * @return the evidence
	 */
	public ReportEvidence withSharedHoldUntil(Instant sharedHoldUntil)
	{
		return new ReportEvidence(observations, symbolicLinks, interrupted,
			infrastructureFailure, sharedHoldUntil, certifiedEnvironment, pilotUnfinished);
	}

	/**
	 * Returns this evidence with the campaign's delivered certification.
	 *
	 * @param certifiedEnvironment the environment that the delivered certification was measured in, or {@code null}
	 *   if the campaign delivered none
	 * @return the evidence
	 */
	public ReportEvidence withCertifiedEnvironment(CertifiedEnvironment certifiedEnvironment)
	{
		return new ReportEvidence(observations, symbolicLinks, interrupted,
			infrastructureFailure, sharedHoldUntil, certifiedEnvironment, pilotUnfinished);
	}

	/**
	 * Returns this evidence for a campaign whose guard pilot a command left with trials to run.
	 *
	 * @return the evidence
	 */
	public ReportEvidence withUnfinishedPilot()
	{
		return new ReportEvidence(observations, symbolicLinks, interrupted,
			infrastructureFailure, sharedHoldUntil, certifiedEnvironment, true);
	}
}
