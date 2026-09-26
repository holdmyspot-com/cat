package io.github.cowwoc.cat.common.usage;

import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;

/**
 * Sends retained usage for the saved WorkOS login and refreshes a rejected access token once.
 */
public final class WorkOsUsageDelivery
{
	private final HttpClient http;
	private final URI workOsApiBase;
	private final WorkOsSessionStore sessions;
	private final UsageReportOutbox outbox;

	/**
	 * Creates a sender for one plugin's durable session and report outbox.
	 *
	 * @param http client used for the host and WorkOS requests
	 * @param workOsApiBase WorkOS API origin, or a loopback origin in local tests
	 * @param sessions private store containing the current WorkOS user session
	 * @param outbox reports retained across plugin processes
	 */
	public WorkOsUsageDelivery(HttpClient http, URI workOsApiBase, WorkOsSessionStore sessions,
		UsageReportOutbox outbox)
	{
		this.http = Objects.requireNonNull(http, "http");
		this.workOsApiBase = Objects.requireNonNull(workOsApiBase, "workOsApiBase");
		this.sessions = Objects.requireNonNull(sessions, "sessions");
		this.outbox = Objects.requireNonNull(outbox, "outbox");
	}

	/**
	 * Sends reports measured by the saved WorkOS user and keeps unconfirmed reports pending.
	 *
	 * @throws IOException if login, refresh, or server confirmation fails
	 * @throws InterruptedException if a provider or host request is interrupted
	 */
	public void flush() throws IOException, InterruptedException
	{
		WorkOsSessionStore.Session session = sessions.load().orElseThrow(() ->
			new IOException("No WorkOS login is saved; sign in before sending usage"));
		WorkOsDeviceAuthClient provider = new WorkOsDeviceAuthClient(http, workOsApiBase, session.clientId());
		UsageReportClient sender = new UsageReportClient(http, session.serverBase());
		String principalId = "workos:" + session.tokens().userId();
		try
		{
			outbox.flush(principalId, session.tokens().accessToken(), sender);
		}
		catch (UsageReportClient.AuthenticationRequiredException _)
		{
			WorkOsDeviceAuthClient.Tokens replacement = provider.refresh(session.tokens());
			if (!sessions.replaceIfCurrent(session, replacement))
				throw new IOException("WorkOS login changed during token refresh; retry pending usage delivery");
			outbox.flush(principalId, replacement.accessToken(), sender);
		}
	}
}
