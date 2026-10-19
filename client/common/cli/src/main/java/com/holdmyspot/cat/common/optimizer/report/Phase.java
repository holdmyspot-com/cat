package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;

import java.util.Optional;

/**
 * The phase whose evidence a status report shows.
 */
public enum Phase
{
	/**
	 * No contract is confirmed, so nothing has been measured.
	 */
	UNCONFIRMED,
	/**
	 * The report shows the working candidate's development results, which are a development estimate and never
	 * certification evidence.
	 */
	DEVELOPMENT,
	/**
	 * The report shows one certification attempt's formal results.
	 */
	FORMAL;

	/**
	 * Returns the certification attempt that a report shows: the running attempt, or the attempt whose snapshot the
	 * campaign decided to deliver.
	 *
	 * @param campaign the campaign
	 * @return the attempt, or empty if the report shows development results or no results
	 * @throws NullPointerException if {@code campaign} is null
	 */
	static Optional<CertificationAttempt> reportedAttempt(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		Optional<CertificationAttempt> running = campaign.runningAttempt();
		if (running.isPresent())
			return running;
		if (campaign.delivery() != null && campaign.delivery().attempt() > 0)
			return Optional.of(campaign.attempt(campaign.delivery().attempt()));
		return Optional.empty();
	}

	/**
	 * Returns the phase that a campaign's report describes.
	 *
	 * @param campaign the campaign
	 * @return the phase
	 * @throws NullPointerException if {@code campaign} is null
	 */
	static Phase of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (!campaign.isConfirmed())
			return UNCONFIRMED;
		if (reportedAttempt(campaign).isPresent())
			return FORMAL;
		return DEVELOPMENT;
	}
}
