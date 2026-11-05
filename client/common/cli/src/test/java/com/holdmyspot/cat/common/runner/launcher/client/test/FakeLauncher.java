package com.holdmyspot.cat.common.runner.launcher.client.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Request;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Response;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Status;
import com.holdmyspot.cat.common.runner.launcher.client.HolderStarter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays a session launcher on the channel's files, one poll at a time: the client's pause runs {@link #step()}, which
 * advances the clock by one poll interval. It accepts a message only while a live holder names its digest, records
 * each streamed turn it consumes, and answers each request as the test chooses.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
final class FakeLauncher implements HolderStarter
{
	/**
	 * The most polls that one test may take, which ends a test whose client never stops waiting.
	 */
	private static final int MAXIMUM_STEPS = 400;
	/**
	 * The launcher's directory name below {@link SessionLauncherChannel#launchers()}.
	 */
	static final String KEY = "claude-4242-1700000000000";
	/**
	 * The run identifier of every child.
	 */
	static final String RUN_ID = "run-1";
	/**
	 * The channel whose files the launcher plays on.
	 */
	private final SessionLauncherChannel channel;
	/**
	 * The client's clock.
	 */
	private final ManualClock clock;
	/**
	 * The mapper for the documents.
	 */
	private final JsonMapper json = JsonMapper.builder().build();
	/**
	 * Every holder started, in order.
	 */
	final List<FakeHolder> holders = new ArrayList<>();
	/**
	 * Every record that the launcher consumed, in order.
	 */
	final List<String> consumed = new ArrayList<>();
	/**
	 * Indicates whether the launcher accepts requests.
	 */
	boolean accepts = true;
	/**
	 * Indicates whether the launcher runs; a launcher that stopped neither refreshes its heartbeat nor answers.
	 */
	boolean alive = true;
	/**
	 * How the launcher answers a request whose input completed.
	 */
	Status status = Status.COMPLETED;
	/**
	 * Runs on each step, after the launcher's own work.
	 */
	Consumer<FakeLauncher> onStep = _ ->
	{
	};
	/**
	 * Runs before a turn is consumed; it sees the turn's record.
	 */
	Consumer<String> onConsume = _ ->
	{
	};
	/**
	 * The files, relative to the child's run directory, that the child writes besides its own, each holding its own
	 * path.
	 */
	List<String> runFiles = List.of();
	/**
	 * The request that the launcher accepted, or {@code null}.
	 */
	Request request;
	/**
	 * The identifier of the accepted request, or {@code null}.
	 */
	String id;
	/**
	 * The number of steps taken.
	 */
	int steps;

	/**
	 * Creates a launcher that has not registered.
	 *
	 * @param channel the channel whose files the launcher plays on
	 * @param clock   the client's clock
	 */
	FakeLauncher(SessionLauncherChannel channel, ManualClock clock)
	{
		this.channel = channel;
		this.clock = clock;
	}

	/**
	 * Registers the launcher, as a launcher does when it starts.
	 *
	 * @throws IOException if the launcher's directory cannot be written
	 */
	void register() throws IOException
	{
		Path directory = Files.createDirectories(channel.launchers().resolve(KEY));
		Files.writeString(directory.resolve(SessionLauncherChannel.HEARTBEAT_FILE), "");
		beat();
	}

	/**
	 * Refreshes the heartbeat.
	 *
	 * @throws IOException if the heartbeat cannot be written
	 */
	private void beat() throws IOException
	{
		Files.setLastModifiedTime(channel.launchers().resolve(KEY).resolve(SessionLauncherChannel.HEARTBEAT_FILE),
			FileTime.from(clock.instant()));
	}

	@Override
	public Holder start(List<String> arguments) throws IOException
	{
		// A holder starts before its message is published
		String name;
		if (arguments.get(4).equals("0"))
			name = SessionLauncherChannel.requestFileName(arguments.get(3));
		else
			name = SessionLauncherChannel.turnFileName(arguments.get(3), Integer.parseInt(arguments.get(4)));
		if (Files.exists(channel.inbox().resolve(name)))
			throw new AssertionError("The message was published before its holder started: " + name);
		FakeHolder holder = new FakeHolder(List.copyOf(arguments));
		holders.add(holder);
		return holder;
	}

	/**
	 * Takes one poll interval.
	 *
	 * @throws IOException if the channel's files cannot be read or written
	 */
	void step() throws IOException
	{
		steps += 1;
		if (steps > MAXIMUM_STEPS)
			throw new AssertionError("The client never stopped waiting");
		clock.advance(SessionLauncherChannel.INBOX_POLL);
		if (!alive)
			return;
		beat();
		if (accepts && id == null)
			acceptRequest();
		if (id != null)
		{
			consumeTurn();
			Path response = channel.requestDirectory(id).resolve(SessionLauncherChannel.RESPONSE_FILE);
			if (!Files.exists(response) && requestHolder().closed)
				respond(Status.STOPPED, "The requester ended");
		}
		onStep.accept(this);
	}

	/**
	 * Returns the holder of the accepted request.
	 *
	 * @return the holder
	 */
	FakeHolder requestHolder()
	{
		return holders.stream().filter(holder -> holder.arguments.get(4).equals("0")).findFirst().orElseThrow();
	}

	/**
	 * Indicates whether a live holder names a message's digest.
	 *
	 * @param id      the request identifier
	 * @param message the message number
	 * @param file    the message's file
	 * @return {@code true} if one does
	 * @throws IOException if the file cannot be read
	 */
	private boolean held(String id, int message, Path file) throws IOException
	{
		List<String> expected = SessionLauncherChannel.holderArguments(id, message,
			SessionLauncherChannel.digest(file));
		return holders.stream().anyMatch(holder -> !holder.closed && holder.arguments.equals(expected));
	}

	/**
	 * Accepts a published request.
	 *
	 * @throws IOException if the request cannot be read or the acceptance written
	 */
	private void acceptRequest() throws IOException
	{
		Path published;
		try (Stream<Path> entries = Files.list(channel.inbox()))
		{
			published = entries.filter(path -> path.getFileName().toString().startsWith(
				SessionLauncherChannel.REQUEST_PREFIX)).findFirst().orElse(null);
		}
		if (published == null)
			return;
		String name = published.getFileName().toString();
		String candidate = name.substring(SessionLauncherChannel.REQUEST_PREFIX.length(), name.length() - 4);
		if (!held(candidate, SessionLauncherChannel.REQUEST_MESSAGE, published))
			return;
		id = candidate;
		Path directory = Files.createDirectories(channel.requestDirectory(id));
		Path copy = directory.resolve("request.tar");
		Files.move(published, copy);
		Path inputs = Files.createDirectory(directory.resolve("inputs"));
		RequestArchive.unpack(copy, inputs, RequestLimits.UNBOUNDED);
		request = RequestArchive.readSession(json, inputs);
		Files.writeString(directory.resolve(SessionLauncherChannel.ACCEPTED_FILE), "{\"launcher\":\"" + KEY +
			"\",\"accepted\":\"" + clock.instant() + "\"}");
		Path run = runDirectory();
		Files.createDirectories(run.resolve("artifacts"));
		Files.createDirectories(run.resolve("home"));
		Files.writeString(run.resolve("home/credentials"), "secret");
		Files.writeString(run.resolve("run.json"), "{}");
		Files.createSymbolicLink(run.resolve("link"), run.resolve("run.json"));
		for (String relative : runFiles)
		{
			Path file = run.resolve(relative);
			Files.createDirectories(file.getParent());
			Files.writeString(file, relative);
		}
		record("{\"type\":\"started\",\"run_id\":\"" + RUN_ID + "\"}");
		if (request.turns().isPresent())
		{
			for (String line : Files.readAllLines(request.turns().get(), UTF_8))
			{
				if (!line.isBlank())
					answer(line);
			}
		}
	}

	/**
	 * Returns the child's run directory.
	 *
	 * @return the directory
	 */
	Path runDirectory()
	{
		return channel.requestDirectory(id).resolve(SessionLauncherChannel.RUN_DIRECTORY).resolve(
			request.harness()).resolve(RUN_ID);
	}

	/**
	 * Consumes the next streamed turn if a live holder names it.
	 *
	 * @throws IOException if the turn cannot be read or recorded
	 */
	private void consumeTurn() throws IOException
	{
		int next = consumed.size() + 1;
		Path turn = channel.inbox().resolve(SessionLauncherChannel.turnFileName(id, next));
		if (!Files.exists(turn) || !held(id, next, turn))
			return;
		String record = Files.readString(turn, UTF_8).strip();
		Files.delete(turn);
		onConsume.accept(record);
		Files.writeString(channel.requestDirectory(id).resolve(SessionLauncherChannel.TURNS_FILE), next + "\n",
			UTF_8, CREATE, APPEND);
		answer(record);
	}

	/**
	 * Answers one record as a runner would.
	 *
	 * @param record the record
	 * @throws IOException if the answer cannot be written
	 */
	private void answer(String record) throws IOException
	{
		consumed.add(record);
		String type = json.readTree(record).path("type").asString();
		if (type.equals("turn"))
		{
			record("{\"type\":\"turn_completed\",\"assistant_messages\":[\"reply " + consumed.size() + "\"]," +
				"\"path\":\"" + runDirectory().resolve("artifacts/turn.events") + "\"}");
		}
		else if (type.equals("complete"))
		{
			record("{\"type\":\"completed\",\"run_id\":\"" + RUN_ID + "\"}");
			Path artifacts = runDirectory().resolve("artifacts");
			Files.writeString(artifacts.resolve("result.txt"), "result");
			Path receipt = channel.requestDirectory(id).resolve("receipt.json");
			Files.writeString(receipt, "{\"retained_project\":\"" + runDirectory().resolve("project") + "\"}");
			String reason = "";
			if (status != Status.COMPLETED)
				reason = "the child's " + status.value() + " reason";
			respond(status, reason);
		}
	}

	/**
	 * Appends one record to the request's transcript.
	 *
	 * @param record the record
	 * @throws IOException if it cannot be written
	 */
	private void record(String record) throws IOException
	{
		Files.writeString(channel.requestDirectory(id).resolve(SessionLauncherChannel.TRANSCRIPT_FILE),
			record + "\n", UTF_8, CREATE, APPEND);
	}

	/**
	 * Publishes the request's response.
	 *
	 * @param answer how the request ended
	 * @param reason why it did not complete, or an empty string
	 * @throws IOException if the response cannot be written
	 */
	private void respond(Status answer, String reason) throws IOException
	{
		Path directory = channel.requestDirectory(id);
		Optional<Path> receipt = Optional.of(directory.resolve("receipt.json")).filter(Files::exists);
		new Response(id, request.harness(), answer, reason, Optional.of(answer.exitStatus()), Optional.of(RUN_ID),
			Optional.of(directory.resolve(SessionLauncherChannel.TRANSCRIPT_FILE)), receipt, Optional.empty(),
			Optional.of(runDirectory()).filter(_ -> request.keepRun())).
			write(json, directory.resolve(SessionLauncherChannel.RESPONSE_FILE));
	}

	/**
	 * A holder that the client started.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	static final class FakeHolder implements Holder
	{
		/**
		 * The holder's arguments.
		 */
		final List<String> arguments;
		/**
		 * Indicates whether the client ended the holder.
		 */
		boolean closed;

		/**
		 * Creates a holder.
		 *
		 * @param arguments the holder's arguments
		 */
		FakeHolder(List<String> arguments)
		{
			this.arguments = arguments;
		}

		@Override
		public void close()
		{
			closed = true;
		}
	}
}
