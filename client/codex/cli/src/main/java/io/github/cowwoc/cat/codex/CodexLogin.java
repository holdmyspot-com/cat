package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.account.AccountDirectoryClient;
import io.github.cowwoc.cat.common.account.AccountEnrollmentClient;
import io.github.cowwoc.cat.common.auth.WorkOsClientConfigurationClient;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Signs a Codex user in through WorkOS and saves the account used for reporting.
 */
public final class CodexLogin
{
	private static final URI PRODUCTION_SERVER_BASE = URI.create("https://api.holdmyspot.com/cat/");
	private static final URI SANDBOX_SERVER_BASE = URI.create("https://api-sandbox.holdmyspot.com/cat/");
	private static final URI WORKOS_BASE = WorkOsDeviceAuthClient.API_BASE;

	/**
	 * Starts the device login flow for the installed Codex plugin.
	 *
	 * @param args optional {@code --sandbox} to sign in to the sandbox API; no option selects production
	 */
	public static void main(String[] args)
	{
		try
		{
			login(System.getenv(), args, HttpClient.newHttpClient(), PRODUCTION_SERVER_BASE, SANDBOX_SERVER_BASE,
				WORKOS_BASE, System.out);
		}
		catch (IllegalArgumentException | IOException exception)
		{
			System.err.println(exception.getMessage());
			System.exit(1);
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			System.err.println("Login was interrupted; retry the command");
			System.exit(1);
		}
	}

	/**
	 * Authorizes a user, enrolls the account, and saves the resulting session.
	 *
	 * @param environment supplies the current Codex home directory
	 * @param args optional {@code --sandbox} to sign in to the sandbox API; no option selects production
	 * @param http HTTP client used for the host and WorkOS requests
	 * @param productionServerBase production HoldMySpot API base
	 * @param sandboxServerBase sandbox HoldMySpot API base
	 * @param workOsBase WorkOS API origin
	 * @param output instructions and resulting account IDs shown to the user
	 * @throws IOException if authorization, enrollment, or private storage fails
	 * @throws InterruptedException if waiting or a request is interrupted
	 */
	static void login(Map<String, String> environment, String[] args, HttpClient http,
		URI productionServerBase, URI sandboxServerBase, URI workOsBase, PrintStream output)
		throws IOException, InterruptedException
	{
		if (args == null || args.length > 1 || (args.length == 1 && !"--sandbox".equals(args[0])))
			throw new IllegalArgumentException("Usage: login [--sandbox]");
		URI serverBase = productionServerBase;
		if (args.length == 1)
			serverBase = sandboxServerBase;
		Path authDirectory = CodexDataDirectory.auth(environment);
		String clientId = new WorkOsClientConfigurationClient(http, serverBase).fetchClientId();
		WorkOsDeviceAuthClient provider = new WorkOsDeviceAuthClient(http, workOsBase, clientId);
		WorkOsDeviceAuthClient.DeviceAuthorization authorization = provider.authorize();
		output.println("Open " + authorization.verificationUri() + " and enter code " + authorization.userCode());
		output.flush();
		long started = System.nanoTime();
		long lifetime = TimeUnit.SECONDS.toNanos(authorization.expiresInSeconds());
		long interval = authorization.intervalSeconds();
		WorkOsDeviceAuthClient.Tokens tokens;
		while (true)
		{
			TimeUnit.SECONDS.sleep(interval);
			if (System.nanoTime() - started >= lifetime)
				throw new IOException("WorkOS device authorization expired; retry login");
			WorkOsDeviceAuthClient.PollResult result = provider.poll(authorization.deviceCode());
			if (result instanceof WorkOsDeviceAuthClient.PollResult.Complete complete)
			{
				tokens = complete.tokens();
				break;
			}
			if (result instanceof WorkOsDeviceAuthClient.PollResult.SlowDown)
				interval = Math.addExact(interval, 5);
		}

		List<AccountDirectoryClient.LinkedAccount> accounts = new AccountEnrollmentClient(http, serverBase).
			enroll(tokens.accessToken());
		if (accounts.isEmpty())
			throw new IOException("The signed-in user has no HoldMySpot account");
		OptionalLong selected = OptionalLong.empty();
		if (accounts.size() == 1)
			selected = OptionalLong.of(accounts.getFirst().id());
		Files.createDirectories(authDirectory.getParent());
		new WorkOsSessionStore(authDirectory).save(new WorkOsSessionStore.Session(serverBase, clientId, tokens,
			selected));
		if (selected.isPresent())
			output.println("Signed in; selected HoldMySpot account " + selected.getAsLong());
		else
		{
			output.println("Signed in. Select an account with account-select <account-id>:");
			for (AccountDirectoryClient.LinkedAccount account: accounts)
				output.println(account.id() + " " + account.email());
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexLogin()
	{
	}
}
