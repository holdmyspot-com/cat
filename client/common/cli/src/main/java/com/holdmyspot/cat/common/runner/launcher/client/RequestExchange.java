package com.holdmyspot.cat.common.runner.launcher.client;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.ProtectedProjectFiles;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Request;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Response;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Status;
import com.holdmyspot.cat.common.runner.TurnSource;
import com.holdmyspot.cat.common.runner.launcher.client.HolderStarter.Holder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends session-level requests to the session launcher of the harness process that runs the sender, and follows each
 * request until its response.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe; each call to {@link #exchange} owns its request.
 */
final class RequestExchange
{
	/**
	 * The null device, which a sandbox mounts over each file that its session may not read.
	 */
	private static final Path NULL_DEVICE = Path.of("/dev/null");
	/**
	 * The field of {@link SessionLauncherChannel#ACCEPTED_FILE} that names the accepting launcher's directory below
	 * {@link SessionLauncherChannel#launchers()}.
	 */
	static final String ACCEPTED_LAUNCHER_FIELD = "launcher";
	/**
	 * The directory of a kept run that holds the child's isolated home, which is never copied.
	 */
	private static final String RUN_HOME = "home";
	/**
	 * The directory of a kept run that holds the copy of the project that the child worked in.
	 */
	private static final String RUN_PROJECT = "project";
	/**
	 * The channel to the launcher.
	 */
	private final SessionLauncherChannel channel;
	/**
	 * The product name of the harness that runs the sender, as its users know it.
	 */
	private final String sessionProduct;
	/**
	 * Starts the holder of each message.
	 */
	private final HolderStarter holders;
	/**
	 * The time source for the waits.
	 */
	private final Clock clock;
	/**
	 * Paces the checks for progress.
	 */
	private final Pause pause;
	/**
	 * The document mapper.
	 */
	private final JsonMapper json;

	/**
	 * How one request ended for its sender.
	 *
	 * @param status     the request's status: its response's, or the status of a request that no response ended
	 * @param exitStatus the runner launcher's exit status
	 * @param message    why the request did not complete, or an empty string when it completed
	 */
	record Outcome(Status status, int exitStatus, String message)
	{
		/**
		 * Creates an outcome.
		 *
		 * @param status     the request's status: its response's, or the status of a request that no response ended
		 * @param exitStatus the runner launcher's exit status
		 * @param message    why the request did not complete, or an empty string when it completed
		 * @throws NullPointerException if {@code status} or {@code message} are null
		 */
		Outcome
		{
			requireThat(status, "status").isNotNull();
			requireThat(message, "message").isNotNull();
		}

		/**
		 * Creates the outcome of a request that ended with a status, with the exit status that the status implies.
		 *
		 * @param status  the request's status
		 * @param message why the request did not complete, or an empty string when it completed
		 * @throws NullPointerException if any of the arguments are null
		 */
		Outcome(Status status, String message)
		{
			this(status, status.exitStatus(), message);
		}
	}

	/**
	 * Receives the child runner's records.
	 */
	@FunctionalInterface
	interface Sink
	{
		/**
		 * Receives one record.
		 *
		 * @param line the record, without its line terminator
		 * @throws IOException if the record cannot be written
		 */
		void line(String line) throws IOException;
	}

	/**
	 * Where the request's results go on the sender's side.
	 *
	 * @param runRoot the run root that receives the child's kept run, or empty when the run is not kept
	 * @param receipt the file that receives the child's receipt, or empty
	 */
	record Destinations(Optional<Path> runRoot, Optional<Path> receipt)
	{
		/**
		 * Creates the destinations.
		 *
		 * @param runRoot the run root that receives the child's kept run, or empty when the run is not kept
		 * @param receipt the file that receives the child's receipt, or empty
		 * @throws NullPointerException if any of the arguments are null
		 */
		Destinations
		{
			requireThat(runRoot, "runRoot").isNotNull();
			requireThat(receipt, "receipt").isNotNull();
		}
	}

	/**
	 * Creates an exchange.
	 *
	 * @param channel        the channel to the launcher
	 * @param sessionProduct the product name of the harness that runs the sender, as its users know it
	 * @param holders        starts the holder of each message
	 * @param clock          the time source for the waits
	 * @param pause          paces the checks for progress
	 * @param json           the document mapper
	 */
	RequestExchange(SessionLauncherChannel channel, String sessionProduct, HolderStarter holders, Clock clock,
		Pause pause, JsonMapper json)
	{
		this.channel = channel;
		this.sessionProduct = sessionProduct;
		this.holders = holders;
		this.clock = clock;
		this.pause = pause;
		this.json = json;
	}

	/**
	 * Returns why no request can be sent: the launchers' state cannot be read, or no launcher has registered.
	 *
	 * @return the outcome that refuses the request, or empty if a launcher may answer it
	 * @throws IOException if the state cannot be listed for another reason
	 */
	Optional<Outcome> refusal() throws IOException
	{
		try (DirectoryStream<Path> launchers = Files.newDirectoryStream(channel.launchers()))
		{
			if (launchers.iterator().hasNext())
				return Optional.empty();
		}
		catch (AccessDeniedException _)
		{
			return Optional.of(new Outcome(Status.REFUSED,
				channel.unreadableStateMessage()));
		}
		catch (NoSuchFileException _)
		{
			// No launcher has registered yet
		}
		return Optional.of(noLauncher());
	}

	/**
	 * Returns the outcome of a request that no launcher answers.
	 *
	 * @return the outcome
	 */
	private Outcome noLauncher()
	{
		return new Outcome(Status.REFUSED,
			SessionLauncherChannel.noLauncherMessage(sessionProduct));
	}

	/**
	 * Sends a request and follows it until its response, copying the child's records as they arrive.
	 *
	 * @param request      the request, whose {@code turns} is empty when the records come from {@code turns}
	 * @param turns        the records to stream as turns, or empty when the request carries them; the caller closes
	 *                     them
	 * @param sink         receives the child runner's records
	 * @param destinations where the request's results go
	 * @return how the request ended, including a failure to copy the kept run in full
	 * @throws InterruptedIOException if the calling thread is interrupted, which ends the request
	 * @throws IOException            if the request cannot be sent, or its state, records or receipt cannot be read
	 *                                or written
	 */
	Outcome exchange(Request request, Optional<TurnSource> turns, Sink sink, Destinations destinations)
		throws IOException
	{
		String id = SessionLauncherChannel.newId();
		Path inbox = Files.createDirectories(channel.inbox());
		Path published = inbox.resolve(SessionLauncherChannel.requestFileName(id));
		Holder requestHolder = publish(id, SessionLauncherChannel.REQUEST_MESSAGE, published,
			staging -> RequestArchive.packSession(json, staging, request, NULL_DEVICE, RequestLimits.UNBOUNDED));
		try (requestHolder)
		{
			Path directory = channel.requestDirectory(id);
			Optional<HeartbeatWatch> heartbeat = awaitAcceptance(directory);
			if (heartbeat == null)
			{
				Files.deleteIfExists(published);
				return noLauncher();
			}
			Relocation relocation = new Relocation(json, remoteRunRoot(id), destinations.runRoot(), request.harness());
			Follower follower = new Follower(directory.resolve(SessionLauncherChannel.TRANSCRIPT_FILE), sink,
				relocation);
			Streamer streamer = new Streamer(id, directory, turns.orElse(null), requestHolder);
			PublishedResponse publishedResponse = new PublishedResponse(
				directory.resolve(SessionLauncherChannel.RESPONSE_FILE));
			Response response;
			try (streamer)
			{
				while (true)
				{
					requireNotInterrupted();
					// The launcher publishes the response after the child's last record, so a final copy after it
					// appears returns every record.
					Optional<Response> ended = publishedResponse.read();
					follower.copy(ended.isPresent());
					if (ended.isPresent())
					{
						response = ended.get();
						break;
					}
					streamer.step();
					if (heartbeat.isPresent() && heartbeat.get().stale())
					{
						return new Outcome(Status.STOPPED,
							SessionLauncherChannel.stoppedLauncherMessage());
					}
					pause.pause();
				}
			}
			RunCopy copy = copyRun(response, destinations, request.harness());
			copyReceipt(response, destinations, relocation, copy.skipped());
			return outcomeOf(response, copy.incomplete());
		}
	}

	/**
	 * Returns how a request ended for its sender. A failed copy of the kept run does not hide the session's own
	 * outcome: its message follows the session's, and it makes a completed session fail.
	 *
	 * @param response      the request's response
	 * @param incompleteRun why the kept run was copied only in part, or empty if it was copied in full or not kept
	 * @return the outcome
	 */
	private static Outcome outcomeOf(Response response, Optional<String> incompleteRun)
	{
		if (response.status() == Status.COMPLETED)
		{
			if (incompleteRun.isEmpty())
				return new Outcome(Status.COMPLETED, "");
			return new Outcome(Status.COMPLETED, Status.FAILED.exitStatus(), incompleteRun.get());
		}
		String ended = "The requested session ended with status " + response.status().value() + ": " +
			response.reason();
		if (incompleteRun.isEmpty())
			return new Outcome(response.status(), ended);
		return new Outcome(response.status(), ended + "\n" + incompleteRun.get());
	}

	/**
	 * Returns where the launcher keeps the child's runs. The launcher names this directory as the child runner's
	 * {@code --run-root}.
	 *
	 * @param id the request identifier
	 * @return the directory
	 */
	private Path remoteRunRoot(String id)
	{
		return channel.requestDirectory(id).resolve(SessionLauncherChannel.RUN_DIRECTORY);
	}

	/**
	 * Waits for a launcher to accept the request. An acceptance that the sender finds but cannot read yet does not
	 * end the wait.
	 *
	 * @param directory the request's directory
	 * @return the accepting launcher's heartbeat, empty if the launcher answered without accepting, or {@code null}
	 *   if no launcher claimed the request within {@link SessionLauncherChannel#ACCEPTANCE_WAIT}
	 * @throws IOException if the wait is interrupted, or the acceptance still cannot be read, or does not name a
	 *                     launcher, when the wait ends
	 */
	private Optional<HeartbeatWatch> awaitAcceptance(Path directory) throws IOException
	{
		Path accepted = directory.resolve(SessionLauncherChannel.ACCEPTED_FILE);
		Path response = directory.resolve(SessionLauncherChannel.RESPONSE_FILE);
		Instant deadline = clock.instant().plus(SessionLauncherChannel.ACCEPTANCE_WAIT);
		while (true)
		{
			requireNotInterrupted();
			if (Files.isRegularFile(accepted))
			{
				try
				{
					return Optional.of(new HeartbeatWatch(heartbeatOf(accepted)));
				}
				catch (IOException e)
				{
					// Windows can refuse to open the acceptance for a moment while another process still has it open.
					// Ending the launch then would abandon a request that a launcher accepted, so the sender tries
					// again until the acceptance wait ends.
					if (!clock.instant().isBefore(deadline))
						throw e;
				}
			}
			else if (Files.isRegularFile(response))
				return Optional.empty();
			else if (!clock.instant().isBefore(deadline))
				return null;
			pause.pause();
		}
	}

	/**
	 * Returns the heartbeat file of the launcher that accepted a request.
	 *
	 * @param accepted the request's {@link SessionLauncherChannel#ACCEPTED_FILE}
	 * @return the heartbeat file
	 * @throws IOException if the file cannot be read or does not name a launcher
	 */
	private Path heartbeatOf(Path accepted) throws IOException
	{
		byte[] content = Files.readAllBytes(accepted);
		JsonNode document;
		try
		{
			document = json.readTree(content);
		}
		catch (JacksonException e)
		{
			throw new IOException("Malformed acceptance: " + accepted, e);
		}
		String launcher = document.path(ACCEPTED_LAUNCHER_FIELD).asString("");
		if (!launcher.matches("[a-z][a-z0-9-]*"))
			throw new IOException("The acceptance names no launcher: " + accepted);
		return channel.launchers().resolve(launcher).resolve(SessionLauncherChannel.HEARTBEAT_FILE);
	}

	/**
	 * Throws if the calling thread is interrupted.
	 *
	 * @throws InterruptedIOException if it is
	 */
	private static void requireNotInterrupted() throws InterruptedIOException
	{
		if (Thread.currentThread().isInterrupted())
			throw new InterruptedIOException("Interrupted while waiting for the requested session");
	}

	/**
	 * Writes a message's file.
	 */
	@FunctionalInterface
	private interface MessageWriter
	{
		/**
		 * Writes the message.
		 *
		 * @param file the file that receives it
		 * @throws IOException if it cannot be written
		 */
		void write(Path file) throws IOException;
	}

	/**
	 * Publishes one message under its own holder: it writes the message beside the inbox entry under a temporary
	 * name, starts the holder that names its digest, and then renames it into place.
	 *
	 * @param id          the request identifier
	 * @param message     {@link SessionLauncherChannel#REQUEST_MESSAGE} or the turn number
	 * @param destination the message's inbox entry
	 * @param writer      writes the message
	 * @return the message's holder
	 * @throws IOException if the message cannot be written or published, or its holder cannot be started
	 */
	private Holder publish(String id, int message, Path destination, MessageWriter writer) throws IOException
	{
		Path staging = destination.resolveSibling(SessionLauncherChannel.TEMPORARY_PREFIX + id + "-" + message);
		try
		{
			writer.write(staging);
			String digest = SessionLauncherChannel.digest(staging);
			Holder holder = holders.start(SessionLauncherChannel.holderArguments(id, message, digest));
			try
			{
				Files.move(staging, destination, ATOMIC_MOVE);
			}
			catch (IOException | RuntimeException e)
			{
				holder.close();
				throw e;
			}
			return holder;
		}
		finally
		{
			Files.deleteIfExists(staging);
		}
	}

	/**
	 * Copies the child's receipt, naming the sender's copy of the kept run in place of the launcher's and listing the
	 * paths that the copy of the kept run skipped.
	 *
	 * @param response     the request's response
	 * @param destinations where the request's results go
	 * @param relocation   maps the launcher's run root to the sender's
	 * @param skipped      the paths of the kept run, relative to the run, that the copy skipped
	 * @throws IOException if the receipt cannot be read or written
	 */
	private void copyReceipt(Response response, Destinations destinations, Relocation relocation,
		List<String> skipped) throws IOException
	{
		if (destinations.receipt().isEmpty() || response.receipt().isEmpty())
			return;
		Path receipt = destinations.receipt().get();
		Path parent = receipt.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);
		String text = relocation.apply(Files.readString(response.receipt().get(), UTF_8));
		if (!skipped.isEmpty())
			text = withSkippedPaths(text, skipped);
		Files.writeString(receipt, text, UTF_8);
	}

	/**
	 * Adds the list of skipped paths to a receipt.
	 *
	 * @param text    the receipt
	 * @param skipped the paths of the kept run, relative to the run, that the copy skipped
	 * @return the receipt with the list
	 * @throws IOException if the receipt is not a JSON object
	 */
	private String withSkippedPaths(String text, List<String> skipped) throws IOException
	{
		JsonNode document;
		try
		{
			document = json.readTree(text);
		}
		catch (JacksonException e)
		{
			throw new IOException("Malformed receipt", e);
		}
		if (!(document instanceof ObjectNode receipt))
			throw new IOException("The receipt is not a JSON object");
		ArrayNode paths = receipt.putArray(HarnessRunReceipt.SKIPPED_COPY_PATHS);
		for (String path : skipped)
			paths.add(path);
		return json.writeValueAsString(receipt);
	}

	/**
	 * How the copy of a kept run ended.
	 *
	 * @param incomplete the message that names the file that could not be copied and says that the kept run is
	 *                   incomplete, or empty if the run was copied in full, apart from what the sender's sandbox
	 *                   protects, or is not kept
	 * @param skipped    the paths, relative to the run, that the sender's sandbox refused to let the copy write
	 */
	private record RunCopy(Optional<String> incomplete, List<String> skipped)
	{
	}

	/**
	 * Copies the child's kept run into the sender's run root, as {@code <run root>/<harness>/<run id>}, leaving out
	 * links, the child's isolated home and the project files that the child's sandbox kept it from changing. A file
	 * or folder that the sender's sandbox refuses to write is skipped without a message and listed in the result.
	 * Any other file that the sender cannot write ends the copy and is reported, not thrown, so that it does not hide
	 * the session's outcome.
	 *
	 * @param response     the request's response
	 * @param destinations where the request's results go
	 * @param harness      the child's harness
	 * @return how the copy ended
	 * @throws IOException if the response names a kept run but no run identifier
	 */
	private static RunCopy copyRun(Response response, Destinations destinations, String harness) throws IOException
	{
		if (destinations.runRoot().isEmpty() || response.run().isEmpty())
			return new RunCopy(Optional.empty(), List.of());
		if (response.childRunId().isEmpty())
			throw new IOException("The response names a kept run but no run identifier");
		Path source = response.run().get();
		Path target = destinations.runRoot().get().resolve(harness).resolve(response.childRunId().get());
		RunCopier copier = new RunCopier(source, target);
		try
		{
			Files.walkFileTree(source, copier);
		}
		catch (FileSystemException e)
		{
			String reason = e.getReason();
			if (reason == null)
				reason = e.getClass().getSimpleName();
			return new RunCopy(Optional.of(incompleteRun(copier.attempted, reason, target)), copier.skipped);
		}
		catch (IOException e)
		{
			return new RunCopy(Optional.of(incompleteRun(copier.attempted, e.getClass().getSimpleName(), target)),
				copier.skipped);
		}
		return new RunCopy(Optional.empty(), copier.skipped);
	}

	/**
	 * Indicates whether a failure is the sandbox of the process that runs the sender refusing to let it write.
	 *
	 * @param failure the failure
	 * @return {@code true} if the operating system refused the operation for lack of permission
	 */
	private static boolean isPermissionRefusal(IOException failure)
	{
		return failure instanceof AccessDeniedException ||
			failure instanceof FileSystemException e && "Operation not permitted".equals(e.getReason());
	}

	/**
	 * Returns the message that says a kept run is incomplete.
	 *
	 * @param file   the file that could not be copied
	 * @param reason why it could not be copied
	 * @param target the sender's copy of the kept run
	 * @return the message
	 */
	private static String incompleteRun(Path file, String reason, Path target)
	{
		return "The file " + file + " could not be copied: " + reason + ". The kept run at " + target +
			" is incomplete.";
	}

	/**
	 * Copies a kept run's directories and regular files, skips what the sender's sandbox refuses to write, and
	 * remembers which copy it attempted last.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	private static final class RunCopier extends SimpleFileVisitor<Path>
	{
		/**
		 * The files and folders of the run, relative to it, that the child's sandbox kept it from changing, so that
		 * they equal the initial project's and nothing reads them from a kept run. The copy leaves them out because
		 * the sender's own sandbox can refuse to write them, as Claude Code's does for its settings and a project's
		 * Git configuration and hooks, and a child is never allowed more than its parent: it would otherwise
		 * change a file that the parent cannot. The launcher must not write them into the sender's run root in the
		 * sender's place either, which would let the child write where the sender's sandbox forbids it.
		 */
		private static final Set<Path> UNCHANGED = ProtectedProjectFiles.paths().stream().
			map(path -> Path.of(RUN_PROJECT).resolve(path)).collect(Collectors.toUnmodifiableSet());
		/**
		 * The kept run in the launcher's run root.
		 */
		private final Path source;
		/**
		 * The sender's copy of the kept run.
		 */
		private final Path target;
		/**
		 * The paths, relative to the run, that the sender's sandbox refused to let the copy write.
		 */
		private final List<String> skipped = new ArrayList<>();
		/**
		 * The file or directory of the sender's copy that the visitor last tried to write.
		 */
		private Path attempted;

		/**
		 * Creates a copier.
		 *
		 * @param source the kept run in the launcher's run root
		 * @param target the sender's copy of the kept run
		 */
		RunCopier(Path source, Path target)
		{
			this.source = source;
			this.target = target;
			this.attempted = target;
		}

		/**
		 * Returns where a file of the kept run goes in the sender's copy.
		 *
		 * @param file the file in the launcher's run root
		 * @return its place in the sender's copy
		 */
		private Path copyOf(Path file)
		{
			return target.resolve(source.relativize(file).toString());
		}

		/**
		 * Indicates whether a failure to write a file or folder of the sender's copy is the sender's sandbox refusing
		 * it, and if so, remembers that the copy skips it. A refusal to read the launcher's file is not one.
		 *
		 * @param file    the file or folder in the launcher's run root
		 * @param failure the failure
		 * @return {@code true} if the copy skips the file or folder
		 */
		private boolean skips(Path file, IOException failure)
		{
			if (!isPermissionRefusal(failure) || !Files.isReadable(file))
				return false;
			skipped.add(PortablePaths.text(source.relativize(file)));
			return true;
		}

		@Override
		public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
		{
			Path relative = source.relativize(directory);
			if (relative.equals(Path.of(RUN_HOME)) || UNCHANGED.contains(relative))
				return FileVisitResult.SKIP_SUBTREE;
			attempted = copyOf(directory);
			try
			{
				Files.createDirectories(attempted);
			}
			catch (IOException e)
			{
				if (relative.toString().isEmpty() || !skips(directory, e))
					throw e;
				return FileVisitResult.SKIP_SUBTREE;
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
		{
			if (attributes.isRegularFile() && !UNCHANGED.contains(source.relativize(file)))
			{
				attempted = copyOf(file);
				try
				{
					Files.copy(file, attempted, NOFOLLOW_LINKS);
				}
				catch (IOException e)
				{
					if (!skips(file, e))
						throw e;
				}
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
		{
			attempted = copyOf(file);
			throw failure;
		}
	}

	/**
	 * Maps the launcher's run root to the sender's in the records and the receipt, so that they name the sender's
	 * copy of the kept run.
	 */
	private static final class Relocation
	{
		/**
		 * The launcher's run root, as JSON text without its quotes.
		 */
		private final String remote;
		/**
		 * The sender's run root, as JSON text without its quotes, or empty when the run is not kept.
		 */
		private final Optional<String> local;
		/**
		 * The sender's run root, or empty when the run is not kept.
		 */
		private final Optional<Path> localRoot;
		/**
		 * The child's harness.
		 */
		private final String harness;
		/**
		 * The record mapper.
		 */
		private final JsonMapper json;

		/**
		 * Creates a relocation.
		 *
		 * @param json    the record mapper
		 * @param remote  the launcher's run root
		 * @param local   the sender's run root, or empty when the run is not kept
		 * @param harness the child's harness
		 */
		Relocation(JsonMapper json, Path remote, Optional<Path> local, String harness)
		{
			this.json = json;
			this.remote = jsonText(json, remote);
			this.local = local.map(path -> jsonText(json, path));
			this.localRoot = local;
			this.harness = harness;
		}

		/**
		 * Returns a path as the text inside a JSON string.
		 *
		 * @param json the record mapper
		 * @param path the path
		 * @return its JSON-escaped text
		 */
		private static String jsonText(JsonMapper json, Path path)
		{
			String quoted = json.writeValueAsString(path.toString());
			return quoted.substring(1, quoted.length() - 1);
		}

		/**
		 * Rewrites JSON text.
		 *
		 * @param text the text
		 * @return the text, naming the sender's run root in place of the launcher's when the run is kept
		 */
		String apply(String text)
		{
			if (local.isEmpty())
				return text;
			return text.replace(remote, local.get());
		}

		/**
		 * Creates the sender's directory of a run whose runner reported its start, so that what the sender writes into
		 * the run while it runs has a place.
		 *
		 * @param record a record of the child runner
		 * @throws IOException if the directory cannot be created
		 */
		void observe(String record) throws IOException
		{
			if (localRoot.isEmpty() || !record.contains("\"started\""))
				return;
			JsonNode node;
			try
			{
				node = json.readTree(record);
			}
			catch (JacksonException _)
			{
				return;
			}
			String runId = node.path("run_id").asString("");
			if (!node.path("type").asString("").equals("started") || !runId.matches("[A-Za-z0-9._-]+") ||
				runId.startsWith("."))
				return;
			Files.createDirectories(localRoot.get().resolve(harness).resolve(runId).resolve("artifacts"));
		}
	}

	/**
	 * Copies the child runner's records from the request's transcript as they arrive.
	 */
	private final class Follower
	{
		/**
		 * The request's transcript.
		 */
		private final Path transcript;
		/**
		 * Receives the records.
		 */
		private final Sink sink;
		/**
		 * Maps the launcher's run root to the sender's.
		 */
		private final Relocation relocation;
		/**
		 * The bytes of a record whose line terminator has not arrived.
		 */
		private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
		/**
		 * How many bytes of the transcript were read.
		 */
		private long read;

		/**
		 * Creates a follower.
		 *
		 * @param transcript the request's transcript
		 * @param sink       receives the records
		 * @param relocation maps the launcher's run root to the sender's
		 */
		Follower(Path transcript, Sink sink, Relocation relocation)
		{
			this.transcript = transcript;
			this.sink = sink;
			this.relocation = relocation;
		}

		/**
		 * Copies the records that arrived since the last copy.
		 *
		 * @param last {@code true} if no record follows, so that a record without a line terminator is copied too
		 * @throws IOException if the transcript cannot be read or a record cannot be written
		 */
		void copy(boolean last) throws IOException
		{
			if (Files.isRegularFile(transcript))
			{
				byte[] bytes;
				try (InputStream input = Files.newInputStream(transcript))
				{
					input.skipNBytes(read);
					bytes = input.readAllBytes();
				}
				read += bytes.length;
				for (byte value : bytes)
				{
					if (value == '\n')
						emit();
					else
						partial.write(value);
				}
			}
			if (last && partial.size() > 0)
				emit();
		}

		/**
		 * Copies the record that the partial bytes hold.
		 *
		 * @throws IOException if the record cannot be written
		 */
		private void emit() throws IOException
		{
			String line = partial.toString(UTF_8);
			partial.reset();
			relocation.observe(line);
			sink.line(relocation.apply(line));
		}
	}

	/**
	 * Publishes the streamed turns one at a time: each one after the launcher consumed the one before, ending each
	 * turn's holder once the launcher consumed it.
	 */
	private final class Streamer implements AutoCloseable
	{
		/**
		 * The request identifier.
		 */
		private final String id;
		/**
		 * The file in which the launcher records each consumed turn.
		 */
		private final Path consumed;
		/**
		 * The records to stream, or {@code null} when the request carries them.
		 */
		private final TurnSource feed;
		/**
		 * The request's holder, which ends the request when it ends.
		 */
		private final Holder requestHolder;
		/**
		 * The holder of the turn that the launcher has not consumed yet, or {@code null}.
		 */
		private Holder pending;
		/**
		 * The number of the last published turn.
		 */
		private int published;
		/**
		 * Indicates whether no turn follows.
		 */
		private boolean finished;

		/**
		 * Creates a streamer.
		 *
		 * @param id            the request identifier
		 * @param directory     the request's directory
		 * @param feed          the records to stream, or {@code null} when the request carries them
		 * @param requestHolder the request's holder
		 */
		Streamer(String id, Path directory, TurnSource feed, Holder requestHolder)
		{
			this.id = id;
			this.consumed = directory.resolve(SessionLauncherChannel.TURNS_FILE);
			this.feed = feed;
			this.requestHolder = requestHolder;
			this.finished = feed == null;
		}

		/**
		 * Ends the holder of a consumed turn and publishes the next turn that is available. Input that ends without
		 * a {@code complete} record ends the request.
		 *
		 * @throws IOException if the records cannot be read, or a turn cannot be published
		 */
		void step() throws IOException
		{
			if (pending != null)
			{
				if (consumedTurns() < published)
					return;
				pending.close();
				pending = null;
			}
			if (finished)
				return;
			Optional<String> next = feed.poll();
			if (next.isPresent())
			{
				published += 1;
				String record = next.get();
				pending = publish(id, published, channel.inbox().resolve(SessionLauncherChannel.turnFileName(id,
					published)), file -> Files.writeString(file, record + "\n", UTF_8));
				finished = isComplete(record);
				return;
			}
			if (feed.ended())
			{
				// The runner fails a session whose input ends without a complete record; the launcher stops it
				finished = true;
				requestHolder.close();
			}
		}

		/**
		 * Returns how many turns the launcher consumed.
		 *
		 * @return the count
		 * @throws IOException if the record of consumed turns cannot be read
		 */
		private int consumedTurns() throws IOException
		{
			List<String> lines;
			try
			{
				lines = Files.readAllLines(consumed, UTF_8);
			}
			catch (NoSuchFileException _)
			{
				return 0;
			}
			return (int) lines.stream().filter(line -> !line.isBlank()).count();
		}

		/**
		 * Indicates whether a record ends the session's input.
		 *
		 * @param record the record
		 * @return {@code true} if it is a {@code complete} record
		 */
		private boolean isComplete(String record)
		{
			try
			{
				return json.readTree(record).path("type").asString("").equals("complete");
			}
			catch (JacksonException _)
			{
				return false;
			}
		}

		@Override
		public void close()
		{
			if (pending != null)
				pending.close();
		}
	}

	/**
	 * Reads a request's response once the launcher has published it. Right after the launcher renames the response
	 * into place, Windows can refuse to open it for a moment while another process still has it open, and a file
	 * system can show a file before its content. Ending the wait at that first failed read would report a request that
	 * ended as one whose response is malformed, so the sender keeps trying until reads have failed for
	 * {@link SessionLauncherChannel#RESPONSE_READ_WAIT}.
	 */
	private final class PublishedResponse
	{
		/**
		 * The request directory's {@link SessionLauncherChannel#RESPONSE_FILE}.
		 */
		private final Path file;
		/**
		 * When a read of the file first failed, or null if no read has failed.
		 */
		private Instant firstFailure;

		/**
		 * Creates a reader of a response that is not published yet.
		 *
		 * @param file the request directory's {@link SessionLauncherChannel#RESPONSE_FILE}
		 */
		PublishedResponse(Path file)
		{
			this.file = file;
		}

		/**
		 * Reads the response if the launcher has published it.
		 *
		 * @return the response, or empty if the file does not exist yet, or exists but could not be read for less than
		 *   {@link SessionLauncherChannel#RESPONSE_READ_WAIT}
		 * @throws IOException if reads of the file have failed for {@link SessionLauncherChannel#RESPONSE_READ_WAIT},
		 *                     because it cannot be read or does not hold a valid response
		 */
		Optional<Response> read() throws IOException
		{
			if (!Files.isRegularFile(file))
				return Optional.empty();
			try
			{
				return Optional.of(Response.read(json, file));
			}
			catch (IOException e)
			{
				Instant now = clock.instant();
				if (firstFailure == null)
					firstFailure = now;
				boolean failedTooLong = Duration.between(firstFailure, now).
					compareTo(SessionLauncherChannel.RESPONSE_READ_WAIT) >= 0;
				if (failedTooLong)
					throw e;
				return Optional.empty();
			}
		}
	}

	/**
	 * Watches a launcher's heartbeat, which the launcher refreshes every
	 * {@link SessionLauncherChannel#HEARTBEAT_INTERVAL}.
	 */
	private final class HeartbeatWatch
	{
		/**
		 * The heartbeat file.
		 */
		private final Path file;
		/**
		 * The last modification time seen, or empty if the file was missing.
		 */
		private Optional<FileTime> last;
		/**
		 * When the heartbeat last changed.
		 */
		private Instant changed;

		/**
		 * Creates a watch.
		 *
		 * @param file the heartbeat file
		 * @throws IOException if the file cannot be read
		 */
		HeartbeatWatch(Path file) throws IOException
		{
			this.file = file;
			this.last = modified();
			this.changed = clock.instant();
		}

		/**
		 * Indicates whether the heartbeat stayed unchanged for {@link SessionLauncherChannel#HEARTBEAT_TIMEOUT}.
		 *
		 * @return {@code true} if the launcher stopped
		 * @throws IOException if the file cannot be read
		 */
		boolean stale() throws IOException
		{
			Optional<FileTime> current = modified();
			Instant now = clock.instant();
			if (!current.equals(last))
			{
				last = current;
				changed = now;
			}
			return Duration.between(changed, now).compareTo(SessionLauncherChannel.HEARTBEAT_TIMEOUT) > 0;
		}

		/**
		 * Returns the heartbeat's modification time.
		 *
		 * @return the time, or empty if the file is missing
		 * @throws IOException if the file cannot be read
		 */
		private Optional<FileTime> modified() throws IOException
		{
			try
			{
				return Optional.of(Files.getLastModifiedTime(file));
			}
			catch (NoSuchFileException _)
			{
				return Optional.empty();
			}
		}
	}
}
