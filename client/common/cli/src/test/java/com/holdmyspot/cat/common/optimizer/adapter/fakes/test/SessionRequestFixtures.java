package com.holdmyspot.cat.common.optimizer.adapter.fakes.test;

import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;

/**
 * Values that tests use to build a {@link SessionRequest}.
 */
public final class SessionRequestFixtures
{
	/**
	 * Prevents instantiation.
	 */
	private SessionRequestFixtures()
	{
	}

	/**
	 * Returns the part of the contract that a session of a role reads, taking a trial to be a development trial.
	 *
	 * @param role the session's role
	 * @return {@link Part#HELD_OUT} for the judge, otherwise {@link Part#DEVELOPMENT}
	 */
	public static Part partOf(SessionRole role)
	{
		return switch (role)
		{
			case JUDGE -> Part.HELD_OUT;
			case TRIAL, TRIAGE, PROMPT_AUTHOR, CONTRACT_AUTHOR -> Part.DEVELOPMENT;
		};
	}
}
