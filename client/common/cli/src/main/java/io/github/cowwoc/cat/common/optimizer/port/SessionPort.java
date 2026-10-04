package io.github.cowwoc.cat.common.optimizer.port;

import java.io.IOException;
import java.nio.file.Path;

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
	 * Indicates whether every session except the judge runs with its harness sandbox denying reads of the files and
	 * directories that hold held-out content.
	 *
	 * @return {@code true} if the harness denies those reads
	 */
	boolean deniesHeldOutReads();
}
