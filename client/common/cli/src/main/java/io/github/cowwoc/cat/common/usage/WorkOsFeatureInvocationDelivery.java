package io.github.cowwoc.cat.common.usage;

import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;

/**
 * Sends retained CAT rule deliveries for the saved WorkOS login.
 */
public final class WorkOsFeatureInvocationDelivery
{
	private final HttpClient http;
	private final URI workOsApiBase;
	private final WorkOsSessionStore sessions;
	private final FeatureInvocationOutbox outbox;

	/**
	 * Creates a sender for one plugin's saved login and feature outbox.
	 *
	 * @param http client used for host and provider requests
	 * @param workOsApiBase provider API origin
	 * @param sessions private saved login
	 * @param outbox retained feature invocations
	 */
	public WorkOsFeatureInvocationDelivery(HttpClient http, URI workOsApiBase, WorkOsSessionStore sessions,
		FeatureInvocationOutbox outbox)
	{
		this.http = Objects.requireNonNull(http, "http");
		this.workOsApiBase = Objects.requireNonNull(workOsApiBase, "workOsApiBase");
		this.sessions = Objects.requireNonNull(sessions, "sessions");
		this.outbox = Objects.requireNonNull(outbox, "outbox");
	}

	/**
	 * Sends invocations for the current WorkOS user and refreshes a rejected token once.
	 *
	 * @throws IOException if login, refresh, or host confirmation fails
	 * @throws InterruptedException if a provider or host request is interrupted
	 */
	public void flush() throws IOException, InterruptedException
	{
		WorkOsSessionStore.Session session = sessions.load().orElseThrow(() ->
			new IOException("No WorkOS login is saved; sign in before sending feature invocations"));
		WorkOsDeviceAuthClient provider = new WorkOsDeviceAuthClient(http, workOsApiBase, session.clientId());
		FeatureInvocationClient sender = new FeatureInvocationClient(http, session.serverBase());
		String principalId = "workos:" + session.tokens().userId();
		try
		{
			outbox.flush(principalId, session.tokens().accessToken(), sender);
		}
		catch (FeatureInvocationClient.AuthenticationRequiredException _)
		{
			WorkOsDeviceAuthClient.Tokens replacement = provider.refresh(session.tokens());
			if (!sessions.replaceIfCurrent(session, replacement))
				throw new IOException("WorkOS login changed during token refresh; retry pending feature delivery");
			outbox.flush(principalId, replacement.accessToken(), sender);
		}
	}
}
