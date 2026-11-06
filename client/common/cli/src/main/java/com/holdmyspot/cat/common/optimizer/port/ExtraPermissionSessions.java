package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A {@link SessionPort} that starts every session with the same extra permissions, such as the permissions that a
 * campaign's user asked for. It answers every other question as the port it wraps answers it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe if the wrapped port is.
 */
public final class ExtraPermissionSessions implements SessionPort
{
	private final SessionPort delegate;
	private final ExtraPermissions extraPermissions;

	/**
	 * Creates a port.
	 *
	 * @param delegate the port that starts the sessions
	 * @param extraPermissions the writes and hosts that every session needs beyond its own
	 * @throws NullPointerException if any argument is null
	 */
	public ExtraPermissionSessions(SessionPort delegate, ExtraPermissions extraPermissions)
	{
		requireThat(delegate, "delegate").isNotNull();
		requireThat(extraPermissions, "extraPermissions").isNotNull();
		this.delegate = delegate;
		this.extraPermissions = extraPermissions;
	}

	@Override
	public SessionHandle start(SessionRequest request) throws IOException
	{
		return delegate.start(request.withExtraPermissions(extraPermissions));
	}

	@Override
	public Optional<Instant> sharedHoldUntil(Instant now) throws IOException
	{
		return delegate.sharedHoldUntil(now);
	}

	@Override
	public int sessionEnvironmentVersion()
	{
		return delegate.sessionEnvironmentVersion();
	}

	@Override
	public String sessionEnvironmentRelease()
	{
		return delegate.sessionEnvironmentRelease();
	}

	@Override
	public int judgeVersion()
	{
		return delegate.judgeVersion();
	}

	@Override
	public SessionEvidence readEvidence(Path transcript) throws IOException
	{
		return delegate.readEvidence(transcript);
	}

	@Override
	public boolean reportsExitStatus()
	{
		return delegate.reportsExitStatus();
	}

	@Override
	public HostLogin hostLogin() throws IOException
	{
		return delegate.hostLogin();
	}

	@Override
	public List<String> unapprovedHooks(Path plugin) throws IOException
	{
		return delegate.unapprovedHooks(plugin);
	}

	@Override
	public Optional<String> findHeldOutExposure(Path project) throws IOException
	{
		return delegate.findHeldOutExposure(project);
	}

	@Override
	public LoadedSources loadedSources(Path project, List<PluginBase> plugins) throws IOException
	{
		return delegate.loadedSources(project, plugins);
	}

	@Override
	public List<HookRegistration> hookRegistrations(Path project, List<PluginBase> plugins) throws IOException
	{
		return delegate.hookRegistrations(project, plugins);
	}

	@Override
	public List<HookInvocation> earlyHooks(Path project, List<PluginBase> plugins, Optional<Path> home, String prompt)
		throws IOException
	{
		return delegate.earlyHooks(project, plugins, home, prompt);
	}

	@Override
	public List<Path> installedPlugins(String name) throws IOException
	{
		return delegate.installedPlugins(name);
	}

	@Override
	public List<String> installedPluginNames() throws IOException
	{
		return delegate.installedPluginNames();
	}

	@Override
	public List<Path> runRoots()
	{
		return delegate.runRoots();
	}

	@Override
	public String toString()
	{
		return "ExtraPermissionSessions[" + delegate + "]";
	}
}
