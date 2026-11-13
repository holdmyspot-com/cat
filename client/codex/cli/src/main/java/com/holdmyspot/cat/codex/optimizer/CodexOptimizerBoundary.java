package com.holdmyspot.cat.codex.optimizer;

import com.holdmyspot.cat.common.optimizer.cli.Command;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Map;
import com.holdmyspot.cat.common.Platform;
import java.util.function.Supplier;

/**
 * Checks the optimizer's sandbox exception before it trusts a session's environment or paths. The same session
 * finder and policy reader that protect the request server validate the thread, configuration and parent process.
 */
public final class CodexOptimizerBoundary
{
	private final Path plugin;
	private final Path directory;
	private final RequestServer.SessionFinder sessions;
	private final Supplier<String> sandboxReason;
	private RequestServer.Session session;
	private final Map<String, String> environment;

	/**
	 * Creates the boundary.
	 *
	 * @param plugin the installed plugin, read-only to sessions
	 * @param directory the command's working directory
	 * @param sessions identifies the caller from protected host records
	 * @param sandboxReason explains why the host kept the launcher sandboxed
	 */
	public CodexOptimizerBoundary(Path plugin, Path directory, RequestServer.SessionFinder sessions,
		Supplier<String> sandboxReason)
	{
		this(plugin, directory, sessions, sandboxReason, Map.of());
	}

	/**
	 * Creates a boundary with the command's untrusted environment.
	 *
	 * @param plugin the installed plugin
	 * @param directory the command's working directory
	 * @param sessions identifies the caller from protected host records
	 * @param sandboxReason explains a sandbox refusal
	 * @param environment the inherited environment
	 */
	public CodexOptimizerBoundary(Path plugin, Path directory, RequestServer.SessionFinder sessions,
		Supplier<String> sandboxReason, Map<String, String> environment)
	{
		this.plugin = plugin;
		this.directory = directory;
		this.sessions = sessions;
		this.sandboxReason = sandboxReason;
		this.environment = Map.copyOf(environment);
	}

	/**
	 * Probes the protected installation, as the request server does.
	 *
	 * @param plugin the installed plugin
	 * @return whether the launcher can write outside the session sandbox
	 */
	public static boolean outside(Path plugin)
	{
		try
		{
			Path probe = Files.createTempFile(plugin, ".sandbox-probe-", "");
			Files.delete(probe);
			return true;
		}
		catch (IOException _)
		{
			return false;
		}
	}

	/**
	 * Refuses background execution when the host kept the launcher sandboxed.
	 *
	 * @return the reason, or empty when the exception applies
	 */
	public Optional<String> backgroundRefusal()
	{
		if (outside(plugin))
			return Optional.empty();
		return Optional.of("--background is unavailable: " + sandboxReason.get() + " Use foreground slices.");
	}

	/**
	 * Checks all externally supplied paths and establishes the session before any unsandboxed campaign work.
	 * Foreground commands inside the sandbox retain the host's enforcement.
	 *
	 * @param command the parsed command
	 * @throws IOException if validation fails
	 */
	public void validate(Command command) throws IOException
	{
		if (!outside(plugin))
			return;
		try
		{
			session = sessions.find();
			ParentPolicy policy = session.policy().read(session.askingThread());
			validateEnvironment(environment, policy);
			policy.requireTrusted(plugin);
			Path project = session.project().toRealPath();
			if (!directory.toRealPath().startsWith(project))
				throw new IOException("The optimizer's working directory is outside its host session's project.");
			if (command instanceof Command.Background background)
				command = background.command();
			if (command instanceof Command.Start start)
			{
				Path target = directory.resolve(start.target()).toRealPath();
				if (!target.startsWith(project) || !policy.mayRead(target))
					throw new IOException("The prompt target is outside the session's readable project: " + target);
				if (start.extraPermissions() != null &&
					!policy.mayRead(directory.resolve(start.extraPermissions()).toRealPath()))
					throw new IOException("The session cannot read the extra permissions file.");
			}
		}
		catch (PolicyRefusal e)
		{
			throw new IOException(e.messageFor("Codex"), e);
		}
	}

	/**
	 * Validates the environment inputs that can redirect the optimizer's files or executable programs. Other values
	 * are data forwarded through the request server, whose policy enforces their session permissions.
	 *
	 * @param environment the inherited environment
	 * @param policy the host policy read from protected records
	 * @throws IOException if an input is unsafe
	 * @throws PolicyRefusal if a program is writable by the session
	 */
	public static void validateEnvironment(Map<String, String> environment, ParentPolicy policy)
		throws IOException, PolicyRefusal
	{
		for (String name : environment.keySet())
			if (name.startsWith("GIT_"))
				throw new IOException("The optimizer refuses inherited Git override " + name + ".");
		for (String name : java.util.List.of(CodexOptimizerCommandScope.HOME_VARIABLE, "CODEX_HOME", "TMPDIR", "TMP",
			"TEMP"))
		{
			String value = environment.get(name);
			if (value == null || value.isEmpty())
				continue;
			try
			{
				if (!Path.of(value).isAbsolute() || value.contains("\n") || value.contains("\r"))
					throw new IOException("The optimizer requires an absolute directory in " + name + ".");
			}
			catch (java.nio.file.InvalidPathException e)
			{
				throw new IOException("The optimizer received an invalid directory in " + name + ".", e);
			}
		}
		if (environment.containsKey("PATH"))
		{
			for (String program : java.util.List.of("git", "codex"))
			{
				Path executable = Path.of(Platform.current().findProgram(program, environment));
				if (!executable.isAbsolute())
					throw new IOException("The optimizer requires an absolute " + program + " executable from PATH.");
				policy.requireTrusted(executable);
			}
		}
	}

	/**
	 * Returns the validated host session for queuing the exit notice.
	 *
	 * @return the session
	 * @throws IOException if no command established a trusted host
	 */
	public RequestServer.Session session() throws IOException
	{
		if (session == null)
			throw new IOException("The optimizer has no validated host session for its exit notice.");
		return session;
	}
}
