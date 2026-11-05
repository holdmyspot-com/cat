package com.holdmyspot.cat.common.runner.launcher.client;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Request;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Response;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Status;
import com.holdmyspot.cat.common.runner.launcher.client.HolderStarter.Holder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
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
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
	 * @param exitStatus the runner launcher's exit status
	 * @param message    why the request did not complete, or an empty string when it completed
	 */
	record Outcome(int exitStatus, String message)
	{
		/**
		 * Creates an outcome.
		 *
		 * @param exitStatus the runner launcher's exit status
		 * @param message    why the request did not complete, or an empty string when it completed
		 * @throws NullPointerException if {@code message} is null
		 */
		Outcome
		{
			requireThat(message, "message").isNotNull();
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
			return Optional.of(new Outcome(SessionLauncherChannel.Status.REFUSED.exitStatus(),
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
		return new Outcome(SessionLauncherChannel.Status.REFUSED.exitStatus(),
			SessionLauncherChannel.noLauncherMessage(sessionProduct));
	}

	/**
	 * Sends a request and follows it until its response, copying the child's records as they arrive.
	 *
	 * @param request      the request, whose {@code turns} is empty when the records come from {@code turns}
	 * @param turns        the records to stream as turns, one per line, or empty when the request carries them
	 * @param sink         receives the child runner's records
	 * @param destinations where the request's results go
	 * @return how the request ended
	 * @throws InterruptedIOException if the calling thread is interrupted, which ends the request
	 * @throws IOException            if the request cannot be sent, or its state or results cannot be read or
	 *                                written
	 */
	Outcome exchange(Request request, Optional<Reader> turns, Sink sink, Destinations destinations)
		throws IOException
	{
		String id = SessionLauncherChannel.newId();
		Path inbox = Files.createDirectories(channel.inbox());
		Path published = inbox.resolve(SessionLauncherChannel.requestFileName(id));
		Holder requestHolder = publish(id, SessionLauncherChannel.REQUEST_MESSAGE, published,
			staging -> RequestArchive.packSession(json, staging, request, NULL_DEVICE, RequestLimits.UNBOUNDED));
		try (requestHolder;
			TurnFeed feed = turns.map(TurnFeed::new).orElse(null))
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
			Streamer streamer = new Streamer(id, directory, feed, requestHolder);
			Path responseFile = directory.resolve(SessionLauncherChannel.RESPONSE_FILE);
			try (streamer)
			{
				while (true)
				{
					requireNotInterrupted();
					// The launcher publishes the response after the child's last record, so a final copy after it
					// appears returns every record.
					boolean ended = Files.isRegularFile(responseFile);
					follower.copy(ended);
					if (ended)
						break;
					streamer.step();
					if (heartbeat.isPresent() && heartbeat.get().stale())
					{
						return new Outcome(SessionLauncherChannel.Status.STOPPED.exitStatus(),
							SessionLauncherChannel.stoppedLauncherMessage());
					}
					pause.pause();
				}
			}
			Response response = Response.read(json, responseFile);
			copyReceipt(response, destinations, relocation);
			copyRun(response, destinations, request.harness());
			if (response.status() == Status.COMPLETED)
				return new Outcome(0, "");
			return new Outcome(response.status().exitStatus(), "The requested session ended with status " +
				response.status().value() + ": " + response.reason());
		}
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
	 * Waits for a launcher to accept the request.
	 *
	 * @param directory the request's directory
	 * @return the accepting launcher's heartbeat, empty if the launcher answered without accepting, or {@code null}
	 *   if no launcher claimed the request within {@link SessionLauncherChannel#ACCEPTANCE_WAIT}
	 * @throws IOException if the wait is interrupted or the acceptance cannot be read
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
				return Optional.of(new HeartbeatWatch(heartbeatOf(accepted)));
			if (Files.isRegularFile(response))
				return Optional.empty();
			if (!clock.instant().isBefore(deadline))
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
		JsonNode document;
		try
		{
			document = json.readTree(accepted.toFile());
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
	 * Copies the child's receipt, naming the sender's copy of the kept run in place of the launcher's.
	 *
	 * @param response     the request's response
	 * @param destinations where the request's results go
	 * @param relocation   maps the launcher's run root to the sender's
	 * @throws IOException if the receipt cannot be read or written
	 */
	private static void copyReceipt(Response response, Destinations destinations, Relocation relocation)
		throws IOException
	{
		if (destinations.receipt().isEmpty() || response.receipt().isEmpty())
			return;
		Path receipt = destinations.receipt().get();
		Path parent = receipt.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);
		Files.writeString(receipt, relocation.apply(Files.readString(response.receipt().get(), UTF_8)), UTF_8);
	}

	/**
	 * Copies the child's kept run into the sender's run root, as {@code <run root>/<harness>/<run id>}, leaving out
	 * links and the child's isolated home.
	 *
	 * @param response     the request's response
	 * @param destinations where the request's results go
	 * @param harness      the child's harness
	 * @throws IOException if the run cannot be copied, or a copied file already exists
	 */
	private static void copyRun(Response response, Destinations destinations, String harness) throws IOException
	{
		if (destinations.runRoot().isEmpty() || response.run().isEmpty())
			return;
		if (response.childRunId().isEmpty())
			throw new IOException("The response names a kept run but no run identifier");
		Path source = response.run().get();
		Path target = destinations.runRoot().get().resolve(harness).resolve(response.childRunId().get());
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				Path relative = source.relativize(directory);
				if (relative.equals(Path.of(RUN_HOME)))
					return FileVisitResult.SKIP_SUBTREE;
				Files.createDirectories(target.resolve(relative.toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (attributes.isRegularFile())
					Files.copy(file, target.resolve(source.relativize(file).toString()), NOFOLLOW_LINKS);
				return FileVisitResult.CONTINUE;
			}
		});
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
		private final TurnFeed feed;
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
		Streamer(String id, Path directory, TurnFeed feed, Holder requestHolder)
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
