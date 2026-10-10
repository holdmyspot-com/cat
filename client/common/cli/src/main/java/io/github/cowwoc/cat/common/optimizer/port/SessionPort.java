package io.github.cowwoc.cat.common.optimizer.port;

import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;

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
	 * Returns the version of the judge that this port's sessions run: the judge's role profile, its prompt, rubric,
	 * decision rule and result schema, and the evidence section that
	 * {@link io.github.cowwoc.cat.common.optimizer.evidence.EvidenceSection} renders from what
	 * {@link #readEvidence(Path)} reads for the same harness events. A release of CAT changes it whenever any of them
	 * changes.
	 *
	 * @return the harness's judge version
	 */
	int judgeVersion();

	/**
	 * Reads a completed trial's activity from its harness events: every tool call of the main agent and its children
	 * with its complete input and output, hook outcomes and assistant positions.
	 *
	 * @param transcript the trial's retained harness events
	 * @return the activity, without injected instructions, streaming fragments or telemetry
	 * @throws NullPointerException if {@code transcript} is null
	 * @throws IOException if the transcript cannot be read
	 */
	SessionEvidence readEvidence(Path transcript) throws IOException;

	/**
	 * Indicates whether the harness reports the exit status of the commands that a session runs, which the built-in tool
	 * {@code exit-status} needs.
	 *
	 * @return {@code true} if it does
	 */
	boolean reportsExitStatus();

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
