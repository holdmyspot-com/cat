package io.github.cowwoc.cat.common.auth;

import io.github.cowwoc.cat.common.account.AccountDirectoryClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks whether a saved WorkOS login may supply CAT features for its selected account.
 */
public final class WorkOsAccountAccess
{
	private static final Duration OFFLINE_ALLOWANCE = Duration.ofHours(24);
	private final HttpClient http;
	private final URI workOsApiBase;
	private final WorkOsSessionStore sessions;
	private final Clock clock;

	/**
	 * One decision a product makes before supplying new features.
	 */
	public enum Decision
	{
		ONLINE,
		OFFLINE,
		SIGN_IN_REQUIRED,
		ACCOUNT_SELECTION_REQUIRED,
		ACCESS_DENIED,
		CONNECTIVITY_REQUIRED,
		VERIFICATION_FAILED
	}

	/**
	 * Identity bound to a successful account check.
	 *
	 * @param principalId provider-qualified login identity
	 * @param accountId selected HoldMySpot account checked by the server or offline allowance
	 */
	public record AuthorizedAccount(String principalId, long accountId)
	{
		/**
		 * Keeps the checked identity usable for a retained feature event.
		 *
		 * @param principalId provider-qualified login identity
		 * @param accountId selected HoldMySpot account
		 */
		public AuthorizedAccount
		{
			if (principalId == null || principalId.isBlank())
				throw new IllegalArgumentException("Authorized principal ID is invalid");
			if (accountId < 1)
				throw new IllegalArgumentException("Authorized account ID must be positive");
		}
	}

	/**
	 * Account-check decision and the identity that may receive features.
	 *
	 * @param decision access decision
	 * @param authorizedAccount checked identity, present only when features may be delivered
	 */
	public record CheckResult(Decision decision, Optional<AuthorizedAccount> authorizedAccount)
	{
		/**
		 * Requires a checked identity exactly when the decision permits feature delivery.
		 *
		 * @param decision access decision
		 * @param authorizedAccount checked identity for an enabled decision
		 */
		public CheckResult
		{
			Objects.requireNonNull(decision, "decision");
			Objects.requireNonNull(authorizedAccount, "authorizedAccount");
			boolean enabled = decision == Decision.ONLINE || decision == Decision.OFFLINE;
			if (enabled != authorizedAccount.isPresent())
				throw new IllegalArgumentException("Enabled access requires its checked account identity");
		}
	}

	/**
	 * Creates one account check using the saved login, provider, host, and local time.
	 *
	 * @param http transport for host and provider requests
	 * @param workOsApiBase provider API origin
	 * @param sessions private saved login and authorization state
	 * @param clock local time source for the offline window
	 */
	public WorkOsAccountAccess(HttpClient http, URI workOsApiBase, WorkOsSessionStore sessions, Clock clock)
	{
		this.http = Objects.requireNonNull(http, "http");
		this.workOsApiBase = Objects.requireNonNull(workOsApiBase, "workOsApiBase");
		this.sessions = Objects.requireNonNull(sessions, "sessions");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Checks the server before a product supplies features to the selected account.
	 *
	 * @return authorization or a reason new feature delivery must stop
	 * @throws IOException if private saved state cannot be used
	 * @throws InterruptedException if a host or provider request is interrupted
	 */
	public Decision check() throws IOException, InterruptedException
	{
		return checkForDelivery().decision();
	}

	/**
	 * Checks access and preserves the authorized identity for the ensuing feature delivery.
	 *
	 * @return decision and checked identity
	 * @throws IOException if private saved state cannot be used
	 * @throws InterruptedException if a host or provider request is interrupted
	 */
	public CheckResult checkForDelivery() throws IOException, InterruptedException
	{
		WorkOsSessionStore.Session session = sessions.load().orElse(null);
		if (session == null)
			return denied(Decision.SIGN_IN_REQUIRED);
		if (session.selectedAccountId().isEmpty())
			return denied(Decision.ACCOUNT_SELECTION_REQUIRED);
		AccountDirectoryClient directory = new AccountDirectoryClient(http, session.serverBase());
		List<AccountDirectoryClient.LinkedAccount> linked;
		try
		{
			linked = directory.list(session.tokens().accessToken());
		}
		catch (AccountDirectoryClient.UnavailableException _)
		{
			return offlineDecision(session);
		}
		catch (AccountDirectoryClient.AuthenticationRequiredException _)
		{
			return checkAfterRefresh(session, directory);
		}
		catch (AccountDirectoryClient.AccessDeniedException _)
		{
			return denyCurrentAccount(session);
		}
		catch (IOException _)
		{
			return denied(Decision.VERIFICATION_FAILED);
		}
		return verifyLinkedAccount(session, linked);
	}

	/**
	 * Refreshes one rejected token and asks the account server again.
	 *
	 * @param session saved login whose access token the server rejected
	 * @param directory server account-list transport
	 * @return the refreshed account check or its failure reason
	 * @throws IOException if private saved state cannot be used
	 * @throws InterruptedException if the refresh or second host request is interrupted
	 */
	private CheckResult checkAfterRefresh(WorkOsSessionStore.Session session, AccountDirectoryClient directory)
		throws IOException, InterruptedException
	{
		WorkOsDeviceAuthClient provider = new WorkOsDeviceAuthClient(http, workOsApiBase, session.clientId());
		WorkOsDeviceAuthClient.Tokens replacement;
		try
		{
			replacement = provider.refresh(session.tokens());
		}
		catch (IOException _)
		{
			sessions.clearAuthorizationIfCurrent(session);
			return denied(Decision.VERIFICATION_FAILED);
		}
		if (!sessions.replaceIfCurrent(session, replacement))
			return denied(Decision.VERIFICATION_FAILED);
		WorkOsSessionStore.Session refreshed = sessions.load().orElseThrow();
		try
		{
			return verifyLinkedAccount(refreshed, directory.list(replacement.accessToken()));
		}
		catch (AccountDirectoryClient.UnavailableException _)
		{
			return offlineDecision(refreshed);
		}
		catch (AccountDirectoryClient.AuthenticationRequiredException _)
		{
			if (!sessions.clearAuthorizationIfCurrent(refreshed))
				return denied(Decision.VERIFICATION_FAILED);
			return denied(Decision.SIGN_IN_REQUIRED);
		}
		catch (AccountDirectoryClient.AccessDeniedException _)
		{
			return denyCurrentAccount(refreshed);
		}
		catch (IOException _)
		{
			return denied(Decision.VERIFICATION_FAILED);
		}
	}

	/**
	 * Records authorization only when the host still lists the selected account.
	 *
	 * @param session login and account checked by the server
	 * @param linked accounts returned for that login
	 * @return online access or a rejected or changed account decision
	 * @throws IOException if the authorization record cannot be updated
	 */
	private CheckResult verifyLinkedAccount(WorkOsSessionStore.Session session,
		List<AccountDirectoryClient.LinkedAccount> linked) throws IOException
	{
		long accountId = session.selectedAccountId().orElseThrow();
		for (AccountDirectoryClient.LinkedAccount account: linked)
		{
			if (account.id() == accountId)
			{
				if (!sessions.recordAuthorizationIfCurrent(session, clock.instant()))
					return denied(Decision.VERIFICATION_FAILED);
				return authorized(Decision.ONLINE, session);
			}
		}
		return denyCurrentAccount(session);
	}

	/**
	 * Removes offline access after the server rejects the selected account.
	 *
	 * @param session login and account rejected by the server
	 * @return denied access, or a state-change failure if another account replaced it
	 * @throws IOException if private authorization state cannot be changed
	 */
	private CheckResult denyCurrentAccount(WorkOsSessionStore.Session session) throws IOException
	{
		if (!sessions.clearAuthorizationIfCurrent(session))
			return denied(Decision.VERIFICATION_FAILED);
		return denied(Decision.ACCESS_DENIED);
	}

	/**
	 * Selects the remaining offline allowance after the account server becomes unavailable.
	 *
	 * @param session saved login and most recent confirmed account check
	 * @return offline access while the selected account's 24-hour window remains, or a connectivity requirement
	 * @throws IOException if current private login state cannot be read
	 */
	private CheckResult offlineDecision(WorkOsSessionStore.Session session) throws IOException
	{
		WorkOsSessionStore.Session current = sessions.load().orElse(null);
		if (current == null || !WorkOsSessionStore.sameLogin(session, current) ||
			!session.selectedAccountId().equals(current.selectedAccountId()) || current.lastAuthorizedAt().isEmpty())
			return denied(Decision.CONNECTIVITY_REQUIRED);
		Instant authorizedAt = current.lastAuthorizedAt().orElseThrow();
		Instant now = clock.instant();
		if (now.isBefore(authorizedAt) || Duration.between(authorizedAt, now).compareTo(OFFLINE_ALLOWANCE) >= 0)
			return denied(Decision.CONNECTIVITY_REQUIRED);
		return authorized(Decision.OFFLINE, current);
	}

	/**
	 * Creates a disabled result without exposing a stale saved identity.
	 *
	 * @param decision reason feature delivery is disabled
	 * @return disabled result
	 */
	private static CheckResult denied(Decision decision)
	{
		return new CheckResult(decision, Optional.empty());
	}

	/**
	 * Carries the exact login and account whose access check succeeded.
	 *
	 * @param decision online or offline access
	 * @param session checked login and selection
	 * @return enabled result with original identity
	 */
	private static CheckResult authorized(Decision decision, WorkOsSessionStore.Session session)
	{
		return new CheckResult(decision, Optional.of(new AuthorizedAccount("workos:" + session.tokens().userId(),
			session.selectedAccountId().orElseThrow())));
	}
}
