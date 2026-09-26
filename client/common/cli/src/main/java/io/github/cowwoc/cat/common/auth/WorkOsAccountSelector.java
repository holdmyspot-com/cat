package io.github.cowwoc.cat.common.auth;

import io.github.cowwoc.cat.common.account.AccountDirectoryClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Objects;

/**
 * Selects a HoldMySpot account only after the host confirms it belongs to the saved WorkOS login.
 */
public final class WorkOsAccountSelector
{
	private final HttpClient http;
	private final WorkOsSessionStore sessions;

	/**
	 * Creates an account selector for the saved login.
	 *
	 * @param http client used to ask the host for linked accounts
	 * @param sessions private store for the saved login and selected account
	 */
	public WorkOsAccountSelector(HttpClient http, WorkOsSessionStore sessions)
	{
		this.http = Objects.requireNonNull(http, "http");
		this.sessions = Objects.requireNonNull(sessions, "sessions");
	}

	/**
	 * Checks that the host lists an account for the saved login, then stores that choice.
	 *
	 * @param accountId positive HoldMySpot account ID requested by the user
	 * @throws IOException if the account is not linked or the login changes during selection
	 * @throws InterruptedException if the account-list request is interrupted
	 */
	public void select(long accountId) throws IOException, InterruptedException
	{
		if (accountId < 1)
			throw new IllegalArgumentException("Selected HoldMySpot account ID must be positive");
		WorkOsSessionStore.Session session = sessions.load().orElseThrow(() ->
			new IOException("No WorkOS login is saved; sign in before selecting an account"));
		AccountDirectoryClient directory = new AccountDirectoryClient(http, session.serverBase());
		boolean linked = false;
		for (AccountDirectoryClient.LinkedAccount account: directory.list(session.tokens().accessToken()))
		{
			if (account.id() == accountId)
			{
				linked = true;
				break;
			}
		}
		if (!linked)
			throw new IOException("HoldMySpot account " + accountId + " is not linked to the current WorkOS login");
		if (!sessions.selectIfCurrent(session, accountId))
			throw new IOException("WorkOS login changed while selecting an account; retry the selection");
	}
}
