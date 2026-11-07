package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.installation.CapacityLocks;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The channel through which a sandboxed session asks the session launcher of its harness process, which runs outside
 * the sandbox, to start a child session. This class is the one declaration of that channel: its locations, file
 * names, request identifiers, requester proof, timings, statuses and the documents that cross it. The request's
 * manifest is {@link RequestArchive}'s session-level format, whose fields are {@link Request}'s.
 * <p>
 * <b>Locations.</b> Below the user's home directory:
 * <ul>
 *   <li>{@link #inbox()}, {@code .cat/locks/requests}, which every sandboxed session may write. A sender writes each
 *     message to a file named with {@link #TEMPORARY_PREFIX} and renames it into place as
 *     {@link #requestFileName(String)}, the request archive, or {@link #turnFileName(String, int)}, one streamed
 *     runner record. A launcher never writes below the inbox: it opens each entry without following links, accepts
 *     regular files only, copies the file into the request's directory before parsing it, and deletes it.</li>
 *   <li>{@link #stateRoot()}, {@link SessionDirectory#ROOT} below the user's home directory, beside the inbox on
 *     the same file system, which only launchers write and no parent session may write. It holds
 *     {@link #launcherDirectory(String, long, Instant)} for each harness process, with {@link #LAUNCHER_FILE},
 *     {@link #LOCK_FILE}, {@link #HEARTBEAT_FILE}, {@link #STOP_FILE} and {@link #LOG_FILE}, and
 *     {@link #requestDirectory(String)} for each accepted request, with
 *     {@link #ACCEPTED_FILE}, {@link #TURNS_FILE}, {@link #TRANSCRIPT_FILE}, {@link #RUNNER_STDERR_FILE},
 *     {@link #RESPONSE_FILE} and {@link #RUN_DIRECTORY}.</li>
 * </ul>
 * <p>
 * <b>Requester proof.</b> Before it publishes message {@code n} of request {@code id}, where {@code n} is
 * {@link #REQUEST_MESSAGE} for the request and the turn number for a streamed turn, the sender starts a holder process,
 * {@link #HOLDER_SHELL} with {@link #holderArguments(String, int, String)}, which lives until the sender closes its
 * standard input or ends. A launcher accepts the message only while a live process has exactly those arguments, the
 * nearest harness process that a live launcher has registered above it is the launcher's own, and the digest that the
 * holder names equals {@link #digest(Path)} of the launcher's private copy. A turn also needs its request to be
 * accepted already and {@code n} to be the next turn number. The end of the request's holder stops the child.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class SessionLauncherChannel
{
	/**
	 * How long a sender waits for a launcher to accept its request, which covers a launcher still starting.
	 */
	public static final Duration ACCEPTANCE_WAIT = Duration.ofSeconds(5);
	/**
	 * How often a launcher checks its inbox for new messages.
	 */
	public static final Duration INBOX_POLL = Duration.ofMillis(250);
	/**
	 * How often a live launcher refreshes the modification time of its {@link #HEARTBEAT_FILE}.
	 */
	public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(2);
	/**
	 * How long a heartbeat may stay unchanged before a sender treats its launcher as stopped.
	 */
	public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(15);
	/**
	 * How old an inbox file that no live holder names must be before a launcher's start-up sweep deletes it.
	 */
	public static final Duration ABANDONED_MESSAGE_AGE = Duration.ofSeconds(60);
	/**
	 * The start of the name of an inbox file that its sender is still writing.
	 */
	public static final String TEMPORARY_PREFIX = ".tmp-";
	/**
	 * The message number of a request, as opposed to one of its streamed turns, which are numbered from 1.
	 */
	public static final int REQUEST_MESSAGE = 0;
	/**
	 * The shell that runs a holder process.
	 */
	public static final Path HOLDER_SHELL = Path.of("/bin/sh");
	/**
	 * The hook data of a launcher's harness process, which the hook that starts the launcher writes.
	 */
	public static final String LAUNCHER_FILE = "launcher.json";
	/**
	 * The file that a live launcher holds an exclusive lock on.
	 */
	public static final String LOCK_FILE = "lock";
	/**
	 * The file whose modification time a live launcher refreshes every {@link #HEARTBEAT_INTERVAL}.
	 */
	public static final String HEARTBEAT_FILE = "heartbeat";
	/**
	 * The file whose appearance tells a launcher that its session ended.
	 */
	public static final String STOP_FILE = "stop";
	/**
	 * The launcher's standard output and standard error.
	 */
	public static final String LOG_FILE = "launcher.log";
	/**
	 * The file whose appearance tells a sender that a launcher accepted its request.
	 */
	public static final String ACCEPTED_FILE = "accepted.json";
	/**
	 * The streamed turns that the launcher has fed to the child, one line per turn.
	 */
	public static final String TURNS_FILE = "turns";
	/**
	 * The child runner's records, appended as they arrive.
	 */
	public static final String TRANSCRIPT_FILE = "transcript.jsonl";
	/**
	 * The child runner's standard error.
	 */
	public static final String RUNNER_STDERR_FILE = "runner.stderr";
	/**
	 * The request's final {@link Response}, published by an atomic rename.
	 */
	public static final String RESPONSE_FILE = "response.json";
	/**
	 * The child's run, deleted when the child ends.
	 */
	public static final String RUN_DIRECTORY = "run";
	/**
	 * The start of the name of a request archive in the inbox.
	 */
	public static final String REQUEST_PREFIX = "req-";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final HexFormat HEX = HexFormat.of();
	private final Path userHome;

	/**
	 * Creates the channel of one user.
	 *
	 * @param userHome the user's home directory
	 * @throws NullPointerException if {@code userHome} is null
	 */
	public SessionLauncherChannel(Path userHome)
	{
		requireThat(userHome, "userHome").isNotNull();
		this.userHome = userHome;
	}

	/**
	 * Returns the inbox, which every sandboxed session of the user may write.
	 *
	 * @return {@code .cat/locks/requests} below the user's home directory
	 */
	public Path inbox()
	{
		return CapacityLocks.root(userHome).resolve("requests");
	}

	/**
	 * Returns the directory that only launchers write.
	 *
	 * @return {@link SessionDirectory#ROOT} below the user's home directory
	 */
	public Path stateRoot()
	{
		return userHome.resolve(SessionDirectory.ROOT);
	}

	/**
	 * Returns the directory that holds one directory for each launcher. A sender that finds it empty or absent knows
	 * that no launcher serves its session.
	 *
	 * @return {@code launchers} below {@link #stateRoot()}
	 */
	public Path launchers()
	{
		return stateRoot().resolve("launchers");
	}

	/**
	 * Returns the directory of the launcher that serves one harness process.
	 *
	 * @param harness the harness identifier
	 * @param pid     the harness process's identifier
	 * @param start   the instant at which the harness process started, which tells it apart from a later process
	 *                with the same identifier
	 * @return {@code <harness>-<pid>-<start in milliseconds since the epoch>} below {@link #launchers()}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is not lower-case letters, digits and hyphens starting with
	 *                                  a letter, or {@code pid} is not positive
	 */
	public Path launcherDirectory(String harness, long pid, Instant start)
	{
		requireThat(harness, "harness").matches("[a-z][a-z0-9-]*");
		requireThat(pid, "pid").isPositive();
		requireThat(start, "start").isNotNull();
		return launchers().resolve(harness + "-" + pid + "-" + start.toEpochMilli());
	}

	/**
	 * Returns the directory that holds one directory for each accepted request.
	 *
	 * @return {@code requests} below {@link #stateRoot()}
	 */
	public Path requests()
	{
		return stateRoot().resolve("requests");
	}

	/**
	 * Returns the directory of one accepted request.
	 *
	 * @param id the request identifier
	 * @return {@code <id>} below {@link #requests()}
	 * @throws NullPointerException     if {@code id} is null
	 * @throws IllegalArgumentException if {@code id} is not a request identifier
	 */
	public Path requestDirectory(String id)
	{
		validateId(id);
		return requests().resolve(id);
	}

	/**
	 * Returns the message that a sender shows when it cannot read {@link #stateRoot()}.
	 *
	 * @return a message that names the directory and says that the session's sandbox must let it be read
	 */
	public String unreadableStateMessage()
	{
		return "CAT cannot read " + stateRoot() + ", where the session launcher answers requests. This session's " +
			"sandbox must let it be read.";
	}

	/**
	 * Returns a new request identifier.
	 *
	 * @return 32 lower-case hexadecimal digits from a secure random source
	 */
	public static String newId()
	{
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return HEX.formatHex(bytes);
	}

	/**
	 * Refuses a value that is not a request identifier.
	 *
	 * @param id the value
	 * @throws NullPointerException     if {@code id} is null
	 * @throws IllegalArgumentException if {@code id} is not 32 lower-case hexadecimal digits
	 */
	public static void validateId(String id)
	{
		requireThat(id, "id").matches("[0-9a-f]{32}");
	}

	/**
	 * Returns the inbox file name of a request archive.
	 *
	 * @param id the request identifier
	 * @return {@code req-<id>.tar}
	 * @throws NullPointerException     if {@code id} is null
	 * @throws IllegalArgumentException if {@code id} is not a request identifier
	 */
	public static String requestFileName(String id)
	{
		validateId(id);
		return REQUEST_PREFIX + id + RequestArchive.EXTENSION;
	}

	/**
	 * Returns the inbox file name of one streamed turn, which holds one runner record, the {@code complete} record
	 * included.
	 *
	 * @param id   the request identifier
	 * @param turn the turn number, from 1
	 * @return {@code turn-<id>-<turn>.json}
	 * @throws NullPointerException     if {@code id} is null
	 * @throws IllegalArgumentException if {@code id} is not a request identifier or {@code turn} is not positive
	 */
	public static String turnFileName(String id, int turn)
	{
		validateId(id);
		requireThat(turn, "turn").isPositive();
		return "turn-" + id + "-" + turn + ".json";
	}

	/**
	 * Returns the arguments that follow {@link #HOLDER_SHELL} in the command line of the holder of one message. The
	 * holder reads its standard input until its sender closes it or ends.
	 *
	 * @param id      the request identifier
	 * @param message {@link #REQUEST_MESSAGE} for the request, or the number of a streamed turn
	 * @param digest  the {@link #digest(Path)} of the published file
	 * @return {@code -c}, {@code read _}, {@code cat-request}, {@code id}, {@code message} and {@code digest}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code id} is not a request identifier, {@code message} is negative or
	 *                                  {@code digest} is not 64 lower-case hexadecimal digits
	 */
	public static List<String> holderArguments(String id, int message, String digest)
	{
		validateId(id);
		requireThat(message, "message").isNotNegative();
		requireThat(digest, "digest").matches("[0-9a-f]{64}");
		return List.of("-c", "read _", "cat-request", id, String.valueOf(message), digest);
	}

	/**
	 * Returns the digest that a holder names for a file.
	 *
	 * @param file the file
	 * @return the SHA-256 of its content, as 64 lower-case hexadecimal digits
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException          if the file cannot be read
	 */
	public static String digest(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java platform supports SHA-256", e);
		}
		try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest))
		{
			input.transferTo(OutputStream.nullOutputStream());
		}
		return HEX.formatHex(digest.digest());
	}

	/**
	 * Returns the message that a sender shows when no launcher serves its session.
	 *
	 * @param harness the name of the session's harness as its user knows it
	 * @return a message that says why and that asks the user to start a new session
	 * @throws NullPointerException     if {@code harness} is null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public static String noLauncherMessage(String harness)
	{
		requireThat(harness, "harness").isNotBlank();
		return "No CAT session launcher serves this session. CAT starts one when a session starts; sessions started " +
			"before CAT was installed or updated have none. Start a new " + harness + " session and run the command " +
			"again.";
	}

	/**
	 * Returns the message that a sender shows when its launcher's heartbeat stays unchanged for
	 * {@link #HEARTBEAT_TIMEOUT}.
	 *
	 * @return a message that asks the user to start a new session
	 */
	public static String stoppedLauncherMessage()
	{
		return "The session launcher stopped; start a new session to start sessions again.";
	}

	/**
	 * How a session-level request ended.
	 */
	public enum Status
	{
		/**
		 * The child session completed.
		 */
		COMPLETED(0),
		/**
		 * The child found no capacity within its wait.
		 */
		CAPACITY(HarnessCapacityWaitExpiredException.EXIT_STATUS),
		/**
		 * The launcher refused the request, starting no child or stopping it before it ran.
		 */
		REFUSED(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS),
		/**
		 * The child failed.
		 */
		FAILED(1),
		/**
		 * The child stopped because its sender or its launcher ended.
		 */
		STOPPED(1);

		private final int exitStatus;

		/**
		 * Creates a status.
		 *
		 * @param exitStatus the exit status of a sender whose request ends with this status
		 */
		Status(int exitStatus)
		{
			this.exitStatus = exitStatus;
		}

		/**
		 * Returns the exit status of a sender whose request ends with this status.
		 *
		 * @return 0 for {@link #COMPLETED}, 75 for {@link #CAPACITY}, 2 for {@link #REFUSED} and 1 otherwise
		 */
		public int exitStatus()
		{
			return exitStatus;
		}

		/**
		 * Returns this status as {@link Response} documents spell it.
		 *
		 * @return the lower-case name
		 */
		public String value()
		{
			return name().toLowerCase(Locale.ROOT);
		}

		/**
		 * Returns the status that a document spells.
		 *
		 * @param value the spelling
		 * @return the status
		 * @throws NullPointerException     if {@code value} is null
		 * @throws IllegalArgumentException if {@code value} spells no status
		 */
		public static Status fromValue(String value)
		{
			requireThat(value, "value").isNotNull();
			for (Status status : values())
			{
				if (status.value().equals(value))
					return status;
			}
			throw new IllegalArgumentException("Unknown request status: " + value);
		}
	}

	/**
	 * What a session-level request chooses for its child. A sender's {@link Request} names files and directories that
	 * its session can read, which {@link RequestArchive#packSession} packs; the {@link Request} that
	 * {@link RequestArchive#readSession} returns names their copies among the launcher's private inputs. A request
	 * cannot choose the artifact, the runner, the harness executable, the run location, environment variables, the
	 * child's sandbox policy or any host path that the launcher opens: the launcher decides them.
	 *
	 * @param harness              the child's harness identifier
	 * @param model                the child's model, empty when {@code agent} chooses it
	 * @param effort               the child's reasoning effort, empty when {@code agent} chooses it
	 * @param agent                the CAT agent definition that chooses the child's model and effort, or empty
	 * @param turns                the file of the child's runner records, the {@code complete} record included, or
	 *                             empty when the sender streams them as turn files
	 * @param project              the project directory
	 * @param plugins              the trial plugin directories, in order
	 * @param workspace            the directory whose files are copied into the project, or empty
	 * @param readOnlyEvidence     the evidence files and directories that the child may read but not change, in order
	 * @param installCat           indicates whether CAT is installed in the child's home
	 * @param sessionTranscripts   indicates whether the child keeps its native session transcripts
	 * @param keepRun              indicates whether the child's run is kept
	 * @param sessionRequests      how many sessions the child may request; {@code 0} allows none
	 * @param requestDepth         how many levels below the child may request sessions in turn
	 * @param capacityWait         the longest wait for a capacity slot, or empty to wait until one is free
	 * @param maximumConcurrency   the capacity ceiling, or empty for the runner's default
	 * @param capacityHeld         indicates whether the sender holds the child's capacity slot itself, so the child
	 *                             takes none
	 * @param instructionFiles     which instruction files the child's harness loads, or empty for the runner's
	 *                             default
	 * @param toolOutputTokenLimit the child's tool output token limit, or empty for the runner's default
	 * @param deniedReads          the absolute paths that the child may not read beyond what its policy denies
	 * @param extraPermissions     the writes and hosts that the child needs beyond what its harness needs, which join
	 *                             its needs
	 * @param gitName              the Git author name of the copied project, or empty
	 * @param gitEmail             the Git author email of the copied project, or empty
	 * @param askingThread         the identifier that the sender's harness gives the thread whose command sends the
	 *                             request, or empty if it gives none
	 */
	public record Request(String harness, Optional<String> model, Optional<String> effort, Optional<String> agent,
		Optional<Path> turns, Path project, List<Path> plugins, Optional<Path> workspace, List<Path> readOnlyEvidence,
		boolean installCat, boolean sessionTranscripts, boolean keepRun, int sessionRequests, int requestDepth,
		Optional<Duration> capacityWait, Optional<Integer> maximumConcurrency, boolean capacityHeld,
		Optional<String> instructionFiles, Optional<Integer> toolOutputTokenLimit, List<Path> deniedReads,
		ExtraPermissions extraPermissions, Optional<String> gitName, Optional<String> gitEmail,
		Optional<String> askingThread)
	{
		/**
		 * Creates a request.
		 *
		 * @param harness              the child's harness identifier
		 * @param model                the child's model, empty when {@code agent} chooses it
		 * @param effort               the child's reasoning effort, empty when {@code agent} chooses it
		 * @param agent                the CAT agent definition that chooses the child's model and effort, or empty
		 * @param turns                the file of the child's runner records, the {@code complete} record included,
		 *                             or empty when the sender streams them as turn files
		 * @param project              the project directory
		 * @param plugins              the trial plugin directories, in order
		 * @param workspace            the directory whose files are copied into the project, or empty
		 * @param readOnlyEvidence     the evidence files and directories that the child may read but not change, in
		 *                             order
		 * @param installCat           indicates whether CAT is installed in the child's home
		 * @param sessionTranscripts   indicates whether the child keeps its native session transcripts
		 * @param keepRun              indicates whether the child's run is kept
		 * @param sessionRequests      how many sessions the child may request; {@code 0} allows none
		 * @param requestDepth         how many levels below the child may request sessions in turn
		 * @param capacityWait         the longest wait for a capacity slot, or empty to wait until one is free
		 * @param maximumConcurrency   the capacity ceiling, or empty for the runner's default
		 * @param capacityHeld         indicates whether the sender holds the child's capacity slot itself
		 * @param instructionFiles     which instruction files the child's harness loads, or empty for the runner's
		 *                             default
		 * @param toolOutputTokenLimit the child's tool output token limit, or empty for the runner's default
		 * @param deniedReads          the absolute paths that the child may not read beyond what its policy denies
		 * @param extraPermissions     the writes and hosts that the child needs beyond what its harness needs, which join
		 *                             its needs
		 * @param gitName              the Git author name of the copied project, or empty
		 * @param gitEmail             the Git author email of the copied project, or empty
		 * @param askingThread         the identifier that the sender's harness gives the thread whose command sends
		 *                             the request, or empty if it gives none
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code harness} is not {@code claude} or {@code codex}; if {@code agent}
		 *                                  is present beside {@code model} or {@code effort}, or absent while either is
		 *                                  missing; if a text value is blank; if {@code sessionRequests} or
		 *                                  {@code requestDepth} is negative, or {@code requestDepth} is positive while
		 *                                  {@code sessionRequests} is {@code 0}; if {@code sessionTranscripts} is set
		 *                                  without {@code keepRun}; if {@code capacityWait} is shorter than one second
		 *                                  or not a whole number of seconds;
		 *                                  if {@code maximumConcurrency} is outside 1 to
		 *                                  {@link HarnessCapacity#MAXIMUM_CONCURRENCY}; if
		 *                                  {@code toolOutputTokenLimit} is not positive; or if a denied read is
		 *                                  relative
		 */
		public Request
		{
			requireThat(harness, "harness").isNotNull();
			requireThat(model, "model").isNotNull();
			requireThat(effort, "effort").isNotNull();
			requireThat(agent, "agent").isNotNull();
			requireThat(turns, "turns").isNotNull();
			requireThat(project, "project").isNotNull();
			requireThat(plugins, "plugins").isNotNull();
			requireThat(workspace, "workspace").isNotNull();
			requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
			requireThat(capacityWait, "capacityWait").isNotNull();
			requireThat(maximumConcurrency, "maximumConcurrency").isNotNull();
			requireThat(instructionFiles, "instructionFiles").isNotNull();
			requireThat(toolOutputTokenLimit, "toolOutputTokenLimit").isNotNull();
			requireThat(deniedReads, "deniedReads").isNotNull();
			requireThat(extraPermissions, "extraPermissions").isNotNull();
			requireThat(gitName, "gitName").isNotNull();
			requireThat(gitEmail, "gitEmail").isNotNull();
			requireThat(askingThread, "askingThread").isNotNull();
			if (!RequestArchive.HARNESSES.contains(harness))
				throw new IllegalArgumentException("Unsupported requested harness: " + harness);
			for (Optional<String> text : List.of(model, effort, agent, instructionFiles, gitName, gitEmail,
				askingThread))
				text.ifPresent(value -> requireThat(value, "value").isNotBlank());
			if (agent.isPresent())
			{
				if (model.isPresent() || effort.isPresent())
				{
					throw new IllegalArgumentException("agent cannot be combined with model or effort: the CAT agent " +
						"definition supplies the model and the effort.");
				}
			}
			else
				HarnessRunnerOptions.requireProfile(harness, model.orElse(""), effort.orElse(""));
			requireThat(sessionRequests, "sessionRequests").isNotNegative();
			requireThat(requestDepth, "requestDepth").isNotNegative();
			if (requestDepth > 0 && sessionRequests == 0)
				throw new IllegalArgumentException("request_depth requires session_requests");
			if (sessionTranscripts && !keepRun)
				throw new IllegalArgumentException("session_transcripts requires keep_run");
			capacityWait.ifPresent(value ->
			{
				requireThat(value, "capacityWait").isGreaterThanOrEqualTo(Duration.ofSeconds(1));
				if (value.getNano() != 0)
					throw new IllegalArgumentException("capacity_wait must be a whole number of seconds: " + value);
			});
			maximumConcurrency.ifPresent(value -> requireThat((int) value, "maximumConcurrency").
				isBetween(1, true, HarnessCapacity.MAXIMUM_CONCURRENCY, true));
			toolOutputTokenLimit.ifPresent(value -> requireThat((int) value, "toolOutputTokenLimit").isPositive());
			for (Path denied : deniedReads)
			{
				if (!denied.isAbsolute())
					throw new IllegalArgumentException("deny_read must name absolute paths: " + denied);
			}
			plugins = List.copyOf(plugins);
			readOnlyEvidence = List.copyOf(readOnlyEvidence);
			deniedReads = List.copyOf(deniedReads);
		}
	}

	/**
	 * The final document of a session-level request, which a launcher publishes as {@link #RESPONSE_FILE} by an atomic
	 * rename and its sender reads. Its paths lie in the request's directory below {@link #requests()}.
	 *
	 * @param id                 the request identifier
	 * @param harness            the child's harness identifier, or an empty string for a request that the launcher
	 *                           could not parse
	 * @param status             how the request ended
	 * @param reason             why it did not complete, or an empty string when it completed
	 * @param exitStatus         the child runner's exit status, or empty when no runner exited
	 * @param childRunId         the child's run identifier, or empty when no run was created
	 * @param transcript         the child runner's records, or empty when there are none
	 * @param receipt            the child's retained-evidence receipt, or empty when there is none
	 * @param sessionTranscripts the child's native session transcripts, or empty when there are none
	 * @param run                the child's kept run, or empty when it was not kept
	 */
	public record Response(String id, String harness, Status status, String reason, Optional<Integer> exitStatus,
		Optional<String> childRunId, Optional<Path> transcript, Optional<Path> receipt,
		Optional<Path> sessionTranscripts, Optional<Path> run)
	{
		/**
		 * The version of the response document.
		 */
		public static final int VERSION = 1;

		/**
		 * Creates a response.
		 *
		 * @param id                 the request identifier
		 * @param harness            the child's harness identifier, or an empty string for a request that the
		 *                           launcher could not parse
		 * @param status             how the request ended
		 * @param reason             why it did not complete, or an empty string when it completed
		 * @param exitStatus         the child runner's exit status, or empty when no runner exited
		 * @param childRunId         the child's run identifier, or empty when no run was created
		 * @param transcript         the child runner's records, or empty when there are none
		 * @param receipt            the child's retained-evidence receipt, or empty when there is none
		 * @param sessionTranscripts the child's native session transcripts, or empty when there are none
		 * @param run                the child's kept run, or empty when it was not kept
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code id} is not a request identifier, or a path is relative
		 */
		public Response
		{
			validateId(id);
			requireThat(harness, "harness").isNotNull();
			requireThat(status, "status").isNotNull();
			requireThat(reason, "reason").isNotNull();
			requireThat(exitStatus, "exitStatus").isNotNull();
			requireThat(childRunId, "childRunId").isNotNull();
			requireThat(transcript, "transcript").isNotNull();
			requireThat(receipt, "receipt").isNotNull();
			requireThat(sessionTranscripts, "sessionTranscripts").isNotNull();
			requireThat(run, "run").isNotNull();
			for (Optional<Path> path : List.of(transcript, receipt, sessionTranscripts, run))
			{
				if (path.isPresent() && !path.get().isAbsolute())
					throw new IllegalArgumentException("Response paths must be absolute: " + path.get());
			}
		}

		/**
		 * Publishes this response by writing a temporary file beside {@code destination} and renaming it into place.
		 *
		 * @param json        the document mapper
		 * @param destination the request directory's {@link #RESPONSE_FILE}
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IOException          if the document cannot be written or renamed
		 */
		public void write(JsonMapper json, Path destination) throws IOException
		{
			requireThat(json, "json").isNotNull();
			requireThat(destination, "destination").isNotNull();
			ObjectNode document = json.createObjectNode().put("format_version", VERSION).put("id", id).
				put("harness", harness).put("status", status.value()).put("reason", reason);
			exitStatus.ifPresent(value -> document.put("exit_code", value));
			childRunId.ifPresent(value -> document.put("child_run_id", value));
			transcript.ifPresent(value -> document.put("transcript", value.toString()));
			receipt.ifPresent(value -> document.put("receipt", value.toString()));
			sessionTranscripts.ifPresent(value -> document.put("session_transcripts", value.toString()));
			run.ifPresent(value -> document.put("run", value.toString()));
			SessionRequestCli.write(json, destination, document);
		}

		/**
		 * Reads a published response.
		 *
		 * @param json   the document mapper
		 * @param source the request directory's {@link #RESPONSE_FILE}
		 * @return the response
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IOException          if the file cannot be read, is not a version {@value #VERSION} response, or has
		 *                              a missing, malformed or unknown field
		 */
		public static Response read(JsonMapper json, Path source) throws IOException
		{
			requireThat(json, "json").isNotNull();
			requireThat(source, "source").isNotNull();
			JsonNode document;
			try
			{
				document = json.readTree(source.toFile());
			}
			catch (JacksonException e)
			{
				throw new IOException("Malformed response: " + source, e);
			}
			List<String> fields = List.of("format_version", "id", "harness", "status", "reason", "exit_code",
				"child_run_id", "transcript", "receipt", "session_transcripts", "run");
			if (!document.isObject())
				throw new IOException("Response must be a JSON object: " + source);
			for (String name : document.propertyNames())
			{
				if (!fields.contains(name))
					throw new IOException("Response has an unknown field: " + name);
			}
			if (!document.path("format_version").isInt() || document.path("format_version").intValue() != VERSION)
				throw new IOException("Response format_version must be " + VERSION + ": " + source);
			for (String name : List.of("id", "harness", "status", "reason"))
			{
				if (!document.path(name).isString())
					throw new IOException("Response requires text: " + name);
			}
			if (document.has("exit_code") && !document.path("exit_code").isInt())
				throw new IOException("Response exit_code must be an integer");
			try
			{
				return new Response(document.path("id").asString(), document.path("harness").asString(),
					Status.fromValue(document.path("status").asString()), document.path("reason").asString(),
					Optional.ofNullable(document.get("exit_code")).map(JsonNode::intValue),
					optionalText(document, "child_run_id"), optionalText(document, "transcript").map(Path::of),
					optionalText(document, "receipt").map(Path::of),
					optionalText(document, "session_transcripts").map(Path::of),
					optionalText(document, "run").map(Path::of));
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException("Invalid response: " + e.getMessage(), e);
			}
		}

		/**
		 * Reads an optional text field.
		 *
		 * @param document the document
		 * @param name     the field's name
		 * @return its text, or empty when it is absent
		 * @throws IOException if it is present but not text
		 */
		private static Optional<String> optionalText(JsonNode document, String name) throws IOException
		{
			if (!document.has(name))
				return Optional.empty();
			if (!document.path(name).isString())
				throw new IOException("Response field must be text: " + name);
			return Optional.of(document.path(name).asString());
		}
	}
}
