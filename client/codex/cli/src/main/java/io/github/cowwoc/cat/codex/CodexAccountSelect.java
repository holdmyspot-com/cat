package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.auth.WorkOsAccountSelector;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;

/**
 * Selects a HoldMySpot account linked to the current Codex login.
 */
public final class CodexAccountSelect
{
	/**
	 * Selects an account and prints the selected account ID.
	 *
	 * @param args the positive HoldMySpot account ID to select
	 */
	public static void main(String[] args)
	{
		try
		{
			System.out.println(select(System.getenv(), args, HttpClient.newHttpClient()));
		}
		catch (IllegalArgumentException | IOException exception)
		{
			System.err.println(exception.getMessage());
			System.exit(1);
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			System.err.println("Account selection was interrupted; retry the command");
			System.exit(1);
		}
	}

	/**
	 * Checks the current login's linked accounts with the host before saving one choice.
	 *
	 * @param environment supplies the current Codex home directory
	 * @param args the positive HoldMySpot account ID to select
	 * @param http HTTP client used to ask the host for linked accounts
	 * @return confirmation of the selected account
	 * @throws IOException if the login is absent or the host does not list the account
	 * @throws InterruptedException if the host request is interrupted
	 */
	static String select(Map<String, String> environment, String[] args, HttpClient http)
		throws IOException, InterruptedException
	{
		if (args == null || args.length != 1)
			throw new IllegalArgumentException("Usage: account-select <account-id>");
		long accountId;
		try
		{
			accountId = Long.parseLong(args[0]);
		}
		catch (NumberFormatException exception)
		{
			throw new IllegalArgumentException("HoldMySpot account ID must be a positive integer", exception);
		}
		if (accountId < 1)
			throw new IllegalArgumentException("HoldMySpot account ID must be a positive integer");

		Path dataDirectory = CodexDataDirectory.auth(environment);
		new WorkOsAccountSelector(http, new WorkOsSessionStore(dataDirectory)).select(accountId);
		return "Selected HoldMySpot account " + accountId;
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexAccountSelect()
	{
	}
}
