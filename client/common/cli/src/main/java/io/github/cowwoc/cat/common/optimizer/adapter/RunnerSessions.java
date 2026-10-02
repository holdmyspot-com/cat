package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs nested sessions through a harness runner.
 * <p>
 * Each session runs in a fresh, isolated run directory of the runner and holds one {@link HarnessCapacity} permit
 * for its lifetime. A session sends the request's turns one at a time through the runner's JSONL bridge. It parses
 * the harness's events for the observed model, the token usage and the rate-limit windows, and keeps the events as
 * the transcript. The runner selects the request's profile: {@code --model} and {@code --effort} for each bound
 * dimension, and nothing for an unbound one, so the harness applies its default.
 * <p>
 * The completed result's transcript is {@code <run>/artifacts/transcript.jsonl}, its effects directory is the run's
 * {@code project} copy of the request's source, and its prompt sources are the ones that the session was observed
 * loading (design &sect;5, H21). When a session ends, the run's isolated home, which holds a copy of the login, is
 * deleted.
 * <p>
 * A subclass supplies its harness's event parsers, its read-only evidence options and its login classification.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public abstract class RunnerSessions implements SessionPort
{
	private final String harness;
	private final Settings settings;
	private final Bridge bridge;

	/**
	 * Creates a port.
	 *
	 * @param harness the harness's identifier, which names its directory below the run root
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	protected RunnerSessions(String harness, Settings settings, Bridge bridge)
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(settings, "settings").isNotNull();
		requireThat(bridge, "bridge").isNotNull();
		this.harness = harness;
		this.settings = settings;
		this.bridge = bridge;
	}

	@Override
	public final SessionHandle start(SessionRequest request)
	{
		requireThat(request, "request").isNotNull();
		Session session = new Session(this, request);
		session.begin();
		return session;
	}

	/**
	 * Returns the harness's identifier.
	 *
	 * @return the identifier
	 */
	final String harness()
	{
		return harness;
	}

	/**
	 * Returns how the runner is configured.
	 *
	 * @return the settings
	 */
	protected final Settings settings()
	{
		return settings;
	}

	/**
	 * Returns the runner's bridge.
	 *
	 * @return the bridge
	 */
	final Bridge bridge()
	{
		return bridge;
	}

	/**
	 * Creates the parser of one session's harness events.
	 *
	 * @param json the JSON mapper
	 * @return a parser that has seen no events
	 */
	protected abstract HarnessEvents newEvents(JsonMapper json);

	/**
	 * Creates the prompt-source observation of one run.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @return an observation that has seen no events
	 */
	protected abstract PromptSources newPromptSources(JsonMapper json, Map<String, Path> directories);

	/**
	 * Returns the runner options that pass a request's read-only evidence.
	 *
	 * @param evidence the paths that the session may read but not change
	 * @return the options, empty if the runner has no option for read-only evidence
	 */
	protected abstract List<String> readOnlyArguments(List<Path> evidence);

	/**
	 * How a harness runner is configured. Every session of a port shares these settings; the model and reasoning
	 * effort come from each request's profile.
	 *
	 * @param artifact the complete harness artifact that the runner validates and installs
	 * @param runRoot the absolute directory that holds the runner's run directories
	 * @param executable the harness executable, or blank for the harness's default command
	 * @param installCat whether the runner installs CAT into the session's isolated home
	 * @param environment the host environment that the runner scrubs for the session
	 * @param configurationDirectory the host's harness configuration directory, which holds the login
	 * @param capacity the shared capacity from which every session takes one permit
	 * @param clock the clock for time limits
	 */
	public record Settings(Path artifact, Path runRoot, String executable, boolean installCat,
		Map<String, String> environment, Path configurationDirectory, HarnessCapacity capacity, Clock clock)
	{
		/**
		 * Creates settings.
		 *
		 * @param artifact the complete harness artifact that the runner validates and installs
		 * @param runRoot the absolute directory that holds the runner's run directories
		 * @param executable the harness executable, or blank for the harness's default command
		 * @param installCat whether the runner installs CAT into the session's isolated home
		 * @param environment the host environment that the runner scrubs for the session
		 * @param configurationDirectory the host's harness configuration directory, which holds the login
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
			requireThat(environment, "environment").isNotNull();
			requireThat(configurationDirectory, "configurationDirectory").isNotNull();
			requireThat(capacity, "capacity").isNotNull();
			requireThat(clock, "clock").isNotNull();
			runRoot = runRoot.normalize();
			environment = Map.copyOf(environment);
		}
	}
}
