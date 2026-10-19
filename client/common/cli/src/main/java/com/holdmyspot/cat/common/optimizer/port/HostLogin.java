package com.holdmyspot.cat.common.optimizer.port;

/**
 * How the host's harness account is billed, as far as its credentials show.
 * <p>
 * A value is derived from the names and types of credential fields only. It never carries a secret value.
 */
public enum HostLogin
{
	/**
	 * A subscription login. Nested sessions draw on the subscription's usage allowance, so CAT warns the user and
	 * states the minimum number of sessions that a campaign needs.
	 */
	SUBSCRIPTION,
	/**
	 * An API key. Nested sessions are billed per token.
	 */
	API_KEY,
	/**
	 * The credentials do not identify either kind: they are missing, unreadable, or ambiguous.
	 */
	UNKNOWN
}
