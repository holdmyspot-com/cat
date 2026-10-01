package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexRunnerCli;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs nested sessions through the Codex runner.
 * <p>
 * Each session runs in a fresh, isolated run directory of the runner and holds one {@link HarnessCapacity} permit
 * for its lifetime. A session sends the request's turns one at a time through the runner's JSONL bridge. It parses
 * the harness's native events for the observed model, the token usage and the rate-limit windows, and keeps the
 * events as the transcript.
 * <p>
 * The Codex runner has no option for read-only evidence (design &sect;11, H23), so {@link SessionRequest#readOnly()}
 * is not passed to it. A judge reads the evidence by path, and only file permissions protect it.
 * <p>
 * The completed result's transcript is {@code <run>/artifacts/transcript.jsonl}, its effects directory is the run's
 * {@code project} copy of the request's source, and its prompt sources are the ones that the session was observed
 * loading (design &sect;5, H21). When a session ends, the run's isolated home, which holds a copy of the login, is
 * deleted.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexSessions implements SessionPort
{
	private final Settings settings;
	private final Bridge bridge;

	/**
	 * Creates a port that runs sessions through the real Codex runner.
	 *
	 * @param settings how the runner is configured
	 * @throws NullPointerException if {@code settings} is null
	 */
	public CodexSessions(Settings settings)
	{
		this(settings, productionBridge(settings));
	}

	/**
	 * Creates a port.
	 *
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @throws NullPointerException if any argument is null
	 */
	CodexSessions(Settings settings, Bridge bridge)
	{
		requireThat(settings, "settings").isNotNull();
		requireThat(bridge, "bridge").isNotNull();
		this.settings = settings;
		this.bridge = bridge;
	}

	@Override
	public SessionHandle start(SessionRequest request)
	{
		requireThat(request, "request").isNotNull();
		Session session = new Session(settings, request, bridge);
		session.begin();
		return session;
	}

	@Override
	public HostLogin hostLogin() throws IOException
	{
		return Credentials.classify(settings.configurationDirectory(), settings.environment(),
			JsonMapper.builder().build());
	}

	/**
	 * Creates the bridge that runs the real runner.
	 *
	 * @param settings how the runner is configured
	 * @return the bridge
	 */
	private static Bridge productionBridge(Settings settings)
	{
		requireThat(settings, "settings").isNotNull();
		return (arguments, input, output) -> CodexRunnerCli.serve(arguments.toArray(String[]::new), input, output,
			settings.environment(), _ -> HarnessCapacity.unlimited());
	}

	/**
	 * How the Codex runner is configured. Every session of a port shares these settings, because a session
	 * request names no model. The runner takes the reasoning effort from the Codex configuration.
	 *
	 * @param artifact the complete Codex artifact that the runner validates and installs
	 * @param runRoot the absolute directory that holds the runner's run directories
	 * @param executable the Codex executable, or blank for {@code codex}
	 * @param model the model to select, or blank for the harness default
	 * @param installCat whether the runner installs CAT into the session's isolated home
	 * @param environment the host environment that the runner scrubs for the session
	 * @param configurationDirectory the host's Codex configuration directory, which holds the login
	 * @param capacity the shared capacity from which every session takes one permit
	 * @param clock the clock for time limits
	 */
	public record Settings(Path artifact, Path runRoot, String executable, String model, boolean installCat,
		Map<String, String> environment, Path configurationDirectory, HarnessCapacity capacity,
		Clock clock)
	{
		/**
		 * Creates settings.
		 *
		 * @param artifact the complete Codex artifact that the runner validates and installs
		 * @param runRoot the absolute directory that holds the runner's run directories
		 * @param executable the Codex executable, or blank for {@code codex}
		 * @param model the model to select, or blank for the harness default
		 * @param installCat whether the runner installs CAT into the session's isolated home
		 * @param environment the host environment that the runner scrubs for the session
		 * @param configurationDirectory the host's Codex configuration directory, which holds the login
		 * @param capacity the shared capacity from which every session takes one permit
		 * @param clock the clock for time limits
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code runRoot} is not absolute
		 */
		public Settings
		{
			requireThat(artifact, "artifact").isNotNull();
			requireThat(runRoot, "runRoot").isNotNull();
			requireThat(runRoot.isAbsolute(), "runRoot.isAbsolute").isTrue();
			requireThat(executable, "executable").isNotNull();
			requireThat(model, "model").isNotNull();
			requireThat(environment, "environment").isNotNull();
			requireThat(configurationDirectory, "configurationDirectory").isNotNull();
			requireThat(capacity, "capacity").isNotNull();
			requireThat(clock, "clock").isNotNull();
			runRoot = runRoot.normalize();
			environment = Map.copyOf(environment);
		}
	}
}
