package io.github.cowwoc.cat.common.optimizer.port;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Starts nested sessions through the current harness's runner.
 * <p>
 * Each session runs in a fresh, isolated run directory. Implementations hold one shared-capacity permit for the
 * session's lifetime.
 */
public interface SessionPort
{
	/**
	 * Starts a session.
	 *
	 * @param request what the session needs
	 * @return a handle that awaits or interrupts the session
	 * @throws NullPointerException if {@code request} is null
	 * @throws IOException if the session cannot be started
	 */
	SessionHandle start(SessionRequest request) throws IOException;

	/**
	 * Returns the version of the environment in which this port's sessions run: the injected content, the hooks, the
	 * settings and limits that the runner applies, and the sandbox. A release of CAT changes it only when it changes
	 * what a session's model receives or how its actions take effect.
	 *
	 * @return the harness's session environment version
	 */
	int sessionEnvironmentVersion();

	/**
	 * Renders a completed trial's recorded activity and file changes for the judge.
	 *
	 * @param evidence the trial's retained transcript and effects
	 * @param source the staged source copied at the start of the trial
	 * @return the complete evidence section, without injected instructions, streaming fragments or telemetry
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the transcript, source or effects cannot be read
	 */
	String renderEvidence(SessionResult.Completed evidence, Path source) throws IOException;

	/**
	 * Classifies the host's login for this harness, so that the confirmation step can warn about a subscription before
	 * any session starts.
	 * <p>
	 * The classification uses the names and types of credential fields only, and never reads or returns a secret
	 * value.
	 *
	 * @return the login, or {@link HostLogin#UNKNOWN} if the credentials do not identify one
	 * @throws IOException if the credential file exists but cannot be read
	 */
	HostLogin hostLogin() throws IOException;

	/**
	 * Finds a host setting that keeps the harness sandbox from denying sessions of the development part reads of
	 * held-out content.
	 *
	 * @param project the project's root directory
	 * @return a message that names the setting and the file that holds it and asks the user to remove it, or empty if
	 *   every session of the development part is denied those reads
	 * @throws NullPointerException if {@code project} is null
	 * @throws IOException if a settings file exists but cannot be read
	 */
	Optional<String> findHeldOutExposure(Path project) throws IOException;

	/**
	 * Lists the directories that hold the runs of every session that this port starts, for the run of every campaign
	 * of the project. The run root holds the sessions of the development part and the held-out run root holds the
	 * sessions of the held-out part. Each run is recorded as {@code <directory>/<harness>/<run>/run.json}.
	 *
	 * @return the absolute directories, which may not exist yet
	 */
	List<Path> runRoots();
}
