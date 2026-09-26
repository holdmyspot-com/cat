package io.github.cowwoc.cat.common.account;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.OptionalLong;

/**
 * Registers a verified login and discovers the accounts it can select.
 */
public final class AccountEnrollmentClient
{
	private final AccountRegistrationClient registration;
	private final AccountDirectoryClient directory;

	/**
	 * Creates the account-enrollment client.
	 *
	 * @param http HTTP client owned by the caller
	 * @param serverBase HoldMySpot server origin
	 */
	public AccountEnrollmentClient(HttpClient http, URI serverBase)
	{
		registration = new AccountRegistrationClient(http, serverBase);
		directory = new AccountDirectoryClient(http, serverBase);
	}

	/**
	 * Registers the login if needed, then returns its linked accounts.
	 *
	 * @param accessToken authenticated provider session token
	 * @return accounts available for selection
	 * @throws IOException if registration and discovery cannot be reconciled
	 * @throws InterruptedException if a request is interrupted
	 */
	public List<AccountDirectoryClient.LinkedAccount> enroll(String accessToken)
		throws IOException, InterruptedException
	{
		OptionalLong created = registration.register(accessToken);
		List<AccountDirectoryClient.LinkedAccount> linked = directory.list(accessToken);
		if (created.isPresent())
		{
			for (AccountDirectoryClient.LinkedAccount account: linked)
			{
				if (account.id() == created.getAsLong())
					return linked;
			}
			throw new IOException("New account is missing from the linked account list");
		}
		return linked;
	}
}
