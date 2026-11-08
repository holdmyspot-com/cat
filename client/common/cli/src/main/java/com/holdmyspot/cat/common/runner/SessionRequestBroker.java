package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.runner.RequestArchive.RunRequest;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Validates session requests, snapshots their inputs before starting children, and publishes retained evidence.
 * A run whose options allow no session request gets each request refused with
 * {@link SessionRequestAccess#NO_REQUESTS_REASON}, and one that has accepted all that its options allow gets each
 * further request refused with {@code limit}. A request whose records follow it, as
 * {@link StreamedRequest} publishes them, gets each record on its child's standard input as it arrives, and its child
 * is stopped once the request's sender ends. Closing the broker stops child process trees and answers unprocessed
 * requests as stopped.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class SessionRequestBroker implements Closeable
{
	/**
	 * The most bytes of a requested runner's refusal that the broker reads.
	 */
	private static final int MAXIMUM_REFUSAL_BYTES = 65_536;
	private final HarnessRun run;
	private final HarnessRunnerOptions options;
	private final RequestedSessionStarter starter;
	private final RequestLimits limits;
	private final JsonMapper json = JsonMapper.builder().build();
	private final ExecutorService children = Executors.newVirtualThreadPerTaskExecutor();
	/**
	 * Live child processes, guarded with all other broker state by this instance's monitor.
	 */
	private final Map<String, Process> processes = new HashMap<>();
	private final Map<String, String> harnesses = new HashMap<>();
	private final Map<String, Path> childRoots = new HashMap<>();
	private final Set<String> cancelled = new HashSet<>();
	/**
	 * The records of each accepted request whose records follow it, by request identifier, until its child ends.
	 */
	private final Map<String, Streamed> streams = new HashMap<>();
	private Closeable monitor;
	private IOException failure;
	private int accepted;
	private boolean stopped;

	/**
	 * Creates a broker for an already prepared run. The runner creates its access directories before sandbox setup.
	 *
	 * @param run     the requesting run
	 * @param options the requesting runner's options
	 * @param starter resolves and starts child runners
	 */
	public SessionRequestBroker(HarnessRun run, HarnessRunnerOptions options, RequestedSessionStarter starter)
	{
		this(run, options, starter, RequestLimits.IN_RUN);
	}

	/**
	 * Creates a broker for an already prepared run whose request archives must stay within the given bounds.
	 *
	 * @param run     the requesting run
	 * @param options the requesting runner's options
	 * @param starter resolves and starts child runners
	 * @param limits  the bounds that each request's archive must stay within
	 */
	public SessionRequestBroker(HarnessRun run, HarnessRunnerOptions options, RequestedSessionStarter starter,
		RequestLimits limits)
	{
		requireThat(run, "run").isNotNull();
		requireThat(options, "options").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(limits, "limits").isNotNull();
		this.run = run;
		this.options = options;
		this.starter = starter;
		this.limits = limits;
	}

	/**
	 * Starts paced discovery using the session's ticker. Any discovery failure is reported by {@link #close()}.
	 *
	 * @param ticker the injected scan schedule
	 */
	public void start(HeartbeatTicker ticker)
	{
		synchronized (this)
		{
			requireThat(ticker, "ticker").isNotNull();
			if (monitor != null || stopped)
				throw new IllegalStateException("The session request broker has already started or stopped");
			monitor = ticker.start(() ->
			{
				try
				{
					scan();
				}
				catch (IOException e)
				{
					synchronized (this)
					{
						failure = e;
					}
				}
			});
		}
	}

	/**
	 * Discovers new non-temporary request archives once. Invalid requests receive a refusal without starting a child.
	 *
	 * @throws IOException if discovery or response publication fails
	 */
	public void scan() throws IOException
	{
		synchronized (this)
		{
			Path requests = SessionRequestAccess.requestsDirectory(run);
			for (String id : RequestArchive.pendingIds(requests, ""))
			{
				Path file = requests.resolve(id + RequestArchive.EXTENSION);
				Path directory = SessionRequestAccess.responsesDirectory(run).resolve(id);
				if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
					continue;
				Files.createDirectory(directory);
				if (stopped)
				{
					respond(id, "", "stopped", "The requesting run stopped", "", directory);
					continue;
				}
				if (options.sessionRequests() == 0)
				{
					respond(id, "", "refused", SessionRequestAccess.NO_REQUESTS_REASON, "", directory);
					continue;
				}
				if (accepted >= options.sessionRequests())
				{
					respond(id, "", "refused", "limit", "", directory);
					continue;
				}
				try
				{
					RunRequest request = read(file, directory);
					accepted += 1;
					harnesses.put(id, request.harness());
					childRoots.put(id, SessionRequestAccess.requestedRunsDirectory(run).
						resolve(String.valueOf(accepted)).resolve(request.harness()));
					List<String> arguments = arguments(request, request.project(), request.plugins(), directory, accepted);
					if (request.streamed())
						streams.put(id, new Streamed(directory));
					children.submit(() -> execute(id, request, arguments, directory));
				}
				catch (IOException | IllegalArgumentException | JacksonException e)
				{
					respond(id, "", "refused", e.getMessage(), "", directory);
				}
			}
			serveStreams(requests);
		}
	}

	/**
	 * Takes the records that each streamed request's sender has published since the last scan, in order, and stops the
	 * child of each request whose sender has ended or published an invalid record.
	 *
	 * @param requests the run's requests directory
	 * @throws IOException if a child cannot be stopped or its response cannot be published
	 */
	private void serveStreams(Path requests) throws IOException
	{
		if (stopped)
			return;
		for (Iterator<Map.Entry<String, Streamed>> entries = streams.entrySet().iterator(); entries.hasNext();)
		{
			Map.Entry<String, Streamed> entry = entries.next();
			String id = entry.getKey();
			Streamed stream = entry.getValue();
			if (cancelled.contains(id))
			{
				// The requesting turn stopped the child, and answered the request
				entries.remove();
				stream.records.add(Optional.empty());
				continue;
			}
			String stop = "";
			try
			{
				if (StreamedRequest.senderRuns(requests, id))
					stream.take(id, requests);
				else
					stop = "The launcher that requested the session ended";
			}
			catch (IOException | JacksonException e)
			{
				stop = "The launcher that requested the session sent an invalid record: " + e.getMessage();
			}
			if (stop.isEmpty())
				continue;
			entries.remove();
			cancelled.add(id);
			if (processes.containsKey(id))
				HarnessProcessTermination.terminate(processes.get(id), "requested runner");
			stream.records.add(Optional.empty());
			respond(id, harnesses.get(id), "stopped", stop, copyChildEvidence(id, stream.directory), stream.directory);
		}
	}

	/**
	 * Copies and extracts a request before parsing its bounded manifest and captured prompts.
	 *
	 * @param file      the request archive
	 * @param directory the broker-owned response directory
	 * @return the validated inputs, with prompt bytes already captured
	 * @throws IOException              if the document or any input is invalid
	 * @throws IllegalArgumentException if the request names no model or no effort
	 */
	private RunRequest read(Path file, Path directory) throws IOException
	{
		Path copy = directory.resolve("request.tar");
		// The broker has the user's permissions while the requesting session keeps running. Walking its input
		// directories would let it swap a link or junction into the path and make us read a file it may not read.
		// The launcher packs inside its sandbox, which refuses that read. We open only this single archive in
		// requests, whose path has no session-controlled directory, without following links and with one hard link.
		Path inputs = Files.createDirectory(directory.resolve("inputs"));
		try
		{
			RequestArchive.copy(file, copy, limits);
			RequestArchive.unpack(copy, inputs, limits);
		}
		finally
		{
			Files.deleteIfExists(copy);
		}
		return RequestArchive.readRun(json, inputs);
	}

	/**
	 * Builds a child's runner options from private copies, retaining the parent's denied reads and capacity ceiling.
	 * While the parent's request depth leaves a level below the child, the child may request as many sessions as the
	 * parent, one level less deep; otherwise it may request none.
	 *
	 * @param request   the validated request
	 * @param project   the project snapshot
	 * @param plugins   the plugin snapshots
	 * @param directory the response directory
	 * @param ordinal   the accepted request number
	 * @return the runner options
	 * @throws IOException if the request's extra permissions cannot be written for the runner
	 */
	private List<String> arguments(RunRequest request, Path project, List<Path> plugins, Path directory, int ordinal)
		throws IOException
	{
		List<String> arguments = new ArrayList<>(List.of("--project", project.toString(), "--run-root",
			SessionRequestAccess.requestedRunsDirectory(run).resolve(String.valueOf(ordinal)).toString(),
			"--keep-run", "--output", directory.resolve("transcript.jsonl").toString(), "--receipt",
			directory.resolve("receipt.json").toString(), "--capacity-wait", "600", "--max-concurrency",
			String.valueOf(options.maximumConcurrency())));
		for (Path denied : options.deniedReads())
			arguments.addAll(List.of("--deny-read", denied.toString()));
		arguments.addAll(List.of("--deny-read", options.runRoot().toString()));
		for (Path plugin : plugins)
			arguments.addAll(List.of("--plugin", plugin.toString()));
		arguments.addAll(List.of("--model", request.model(), "--effort", request.effort()));
		if (request.sessionTranscripts())
			arguments.add("--session-transcripts");
		if (request.installCat())
			arguments.add("--install-cat");
		if (!request.extraPermissions().isEmpty())
		{
			Path file = directory.resolve(ExtraPermissions.FILE_NAME);
			request.extraPermissions().write(json, file);
			arguments.addAll(List.of(ExtraPermissions.OPTION, file.toString()));
		}
		if (options.requestDepth() > 0)
		{
			arguments.addAll(List.of("--session-requests", String.valueOf(options.sessionRequests())));
			int childDepth = options.requestDepth() - 1;
			if (childDepth > 0)
				arguments.addAll(List.of("--request-depth", String.valueOf(childDepth)));
		}
		return arguments;
	}

	/**
	 * Starts and feeds a child, then classifies its exit and retains its native transcripts.
	 *
	 * @param id the request id
	 * @param request the captured request
	 * @param arguments the child runner options
	 * @param directory the private response directory
	 */
	private void execute(String id, RunRequest request, List<String> arguments, Path directory)
	{
		Process process = null;
		try
		{
			process = starter.start(request.harness(), arguments, directory);
			synchronized (this)
			{
				processes.put(id, process);
				if (stopped || cancelled.contains(id))
				{
					HarnessProcessTermination.terminate(process, "requested runner");
					return;
				}
			}
			try (OutputStream input = process.getOutputStream())
			{
				if (request.streamed())
					feed(id, process, input);
				else
				{
					for (String prompt : request.prompts())
					{
						ObjectNode turn = json.createObjectNode().put("type", "turn").put("prompt", prompt);
						input.write((json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(turn) +
							"\n").getBytes(StandardCharsets.UTF_8));
					}
					input.write("{\"type\":\"complete\"}\n".getBytes(StandardCharsets.UTF_8));
					input.flush();
				}
				int exit = process.waitFor();
				String status = "failed";
				String reason = "Requested runner exited with status " + exit;
				if (exit == 0)
				{
					status = "completed";
					reason = "";
				}
				else if (exit == HarnessCapacityWaitExpiredException.EXIT_STATUS)
				{
					status = "capacity";
					reason = "capacity";
				}
				else if (exit == HarnessRunnerOptions.REFUSED_LAUNCH_STATUS)
				{
					status = "refused";
					reason = refusal(directory, reason);
				}
				finish(id, request.harness(), status, reason, directory);
			}
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			publishFailure(id, request.harness(), "stopped", "Requested session interrupted", directory);
		}
		catch (IllegalArgumentException e)
		{
			publishFailure(id, request.harness(), "refused", e.getMessage(), directory);
		}
		catch (IOException e)
		{
			publishFailure(id, request.harness(), "failed", e.getMessage(), directory);
		}
		finally
		{
			if (process != null && process.isAlive())
				HarnessProcessTermination.terminateImmediately(process);
			synchronized (this)
			{
				processes.remove(id);
				streams.remove(id);
			}
		}
	}

	/**
	 * Writes each record of a streamed request to its child as the broker takes it, until the {@code complete} record,
	 * the child's exit, or the child's stop.
	 *
	 * @param id      the request identifier
	 * @param process the child
	 * @param input   the child's standard input
	 * @throws IOException          if a record cannot be written
	 * @throws InterruptedException if the thread is interrupted while it waits for a record
	 */
	private void feed(String id, Process process, OutputStream input) throws IOException, InterruptedException
	{
		BlockingQueue<Optional<String>> records;
		synchronized (this)
		{
			Streamed stream = streams.get(id);
			if (stream == null)
				return;
			records = stream.records;
		}
		process.onExit().thenRun(() -> records.add(Optional.empty()));
		while (true)
		{
			Optional<String> record = records.take();
			if (record.isEmpty())
				return;
			input.write((record.get() + "\n").getBytes(StandardCharsets.UTF_8));
			input.flush();
			if (StreamedRequest.isComplete(json, record.get()))
				return;
		}
	}

	/**
	 * Reads why a requested runner refused its launch, which it wrote to its standard error.
	 *
	 * @param directory the private response directory, which holds the runner's diagnostics
	 * @param fallback  the reason to give if the runner wrote none
	 * @return the runner's reason
	 */
	private static String refusal(Path directory, String fallback)
	{
		try
		{
			String reason = BoundedInput.readUtf8(directory.resolve("runner.stderr"), MAXIMUM_REFUSAL_BYTES,
				"requested runner refusal").strip();
			if (!reason.isEmpty())
				return reason;
		}
		catch (IOException _)
		{
			// The runner left no readable reason
		}
		return fallback;
	}

	/**
	 * Publishes a child's result after copying transcripts from its retained run.
	 *
	 * @param id the request id
	 * @param harness the requested harness
	 * @param status the terminal classification
	 * @param reason the diagnostic
	 * @param directory the private response directory
	 * @throws IOException if evidence cannot be read or copied
	 */
	private void finish(String id, String harness, String status, String reason, Path directory)
		throws IOException
	{
		synchronized (this)
		{
			// The child has ended, so the end of its request's sender no longer stops it
			streams.remove(id);
			if (stopped || cancelled.contains(id))
				return;
			String childRunId = copyChildEvidence(id, directory);
			if (status.equals("completed") && !Files.isRegularFile(directory.resolve("receipt.json")))
				throw new IOException("Requested runner completed without a retained-evidence receipt");
			respond(id, harness, status, reason, childRunId, directory);
		}
	}

	/**
	 * Copies native transcripts from the child's private run root, including when no success receipt was written.
	 *
	 * @param id the request id
	 * @param directory the private response directory
	 * @return the child's run id, or empty if capacity or startup prevented creation
	 * @throws IOException if retained evidence cannot be inspected or copied
	 */
	private String copyChildEvidence(String id, Path directory) throws IOException
	{
		Path root = childRoots.get(id);
		if (root == null || !Files.isDirectory(root))
			return "";
		try (DirectoryStream<Path> runs = Files.newDirectoryStream(root))
		{
			for (Path child : runs)
			{
				if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
					continue;
				Path transcripts = child.resolve("artifacts/session-transcripts");
				Path copies = directory.resolve("session-transcripts");
				if (Files.isDirectory(transcripts) && !Files.exists(copies))
					copyTranscripts(transcripts, copies);
				return child.getFileName().toString();
			}
		}
		return "";
	}

	/**
	 * Copies transcripts from the child-owned run tree, which the requesting session cannot modify.
	 *
	 * @param source      the child's retained transcripts
	 * @param destination the response's transcript copies
	 * @throws IOException if copying fails or the child tree contains a link or special file
	 */
	private static void copyTranscripts(Path source, Path destination) throws IOException
	{
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				Path target = destination.resolve(source.relativize(path));
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					Files.createDirectories(target);
				else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					Files.copy(path, target, LinkOption.NOFOLLOW_LINKS);
				else
					throw new IOException("Child transcript is a link or special file: " + path);
			}
		}
	}

	/**
	 * Records a child failure, retaining publication failures for the bridge to report on close.
	 *
	 * @param id the request id
	 * @param harness the requested harness
	 * @param status the terminal classification
	 * @param reason the diagnostic
	 * @param directory the private response directory
	 */
	private void publishFailure(String id, String harness, String status, String reason, Path directory)
	{
		synchronized (this)
		{
			streams.remove(id);
			if (stopped || cancelled.contains(id))
				return;
			try
			{
				respond(id, harness, status, reason, "", directory);
			}
			catch (IOException e)
			{
				failure = e;
			}
		}
	}

	/**
	 * Atomically publishes a terminal response that contains paths rather than evidence content.
	 *
	 * @param id the request id
	 * @param harness the requested harness, empty for an unparsed request
	 * @param status the terminal classification
	 * @param reason the diagnostic
	 * @param childRunId the child's run identifier, empty if unavailable
	 * @param directory the private response directory
	 * @throws IOException if publication fails
	 */
	private void respond(String id, String harness, String status, String reason, String childRunId, Path directory)
		throws IOException
	{
		ObjectNode response = json.createObjectNode().put("format_version", 1).put("id", id).put("harness", harness).
			put("status", status).put("reason", reason).put("child_run_id", childRunId);
		for (String name : List.of("transcript.jsonl", "receipt.json"))
			if (Files.isRegularFile(directory.resolve(name)))
				response.put(name.substring(0, name.indexOf('.')), directory.resolve(name).toString());
		if (Files.isDirectory(directory.resolve("session-transcripts")))
			response.put("session_transcripts", directory.resolve("session-transcripts").toString());
		SessionRequestCli.write(json, directory.resolve("response.json"), response);
	}

	/**
	 * Stops children and pending requests on a turn interruption. Later turns may submit new requests against the
	 * run's remaining count.
	 *
	 * @throws IOException if termination or response publication fails
	 */
	public void stopChildren() throws IOException
	{
		synchronized (this)
		{
			for (String id : processes.keySet())
				HarnessProcessTermination.terminate(processes.get(id), "requested runner");
			for (Map.Entry<String, String> entry : harnesses.entrySet())
			{
				Path directory = SessionRequestAccess.responsesDirectory(run).resolve(entry.getKey());
				if (!Files.exists(directory.resolve("response.json")))
				{
					cancelled.add(entry.getKey());
					respond(entry.getKey(), entry.getValue(), "stopped", "The requesting turn stopped",
						copyChildEvidence(entry.getKey(), directory), directory);
				}
			}
			boolean wasStopped = stopped;
			stopped = true;
			try
			{
				scan();
			}
			finally
			{
				stopped = wasStopped;
			}
		}
	}

	@Override
	public void close() throws IOException
	{
		synchronized (this)
		{
			if (stopped)
				return;
			stopped = true;
			try
			{
				if (monitor != null)
					monitor.close();
			}
			catch (IOException e)
			{
				recordFailure(e);
			}
			for (String id : processes.keySet())
			{
				try
				{
					HarnessProcessTermination.terminate(processes.get(id), "requested runner");
				}
				catch (IOException e)
				{
					recordFailure(e);
				}
			}
			for (Map.Entry<String, String> entry : harnesses.entrySet())
			{
				Path directory = SessionRequestAccess.responsesDirectory(run).resolve(entry.getKey());
				try
				{
					if (!Files.exists(directory.resolve("response.json")))
						respond(entry.getKey(), entry.getValue(), "stopped", "The requesting run stopped",
							copyChildEvidence(entry.getKey(), directory), directory);
				}
				catch (IOException e)
				{
					recordFailure(e);
				}
			}
			try
			{
				scan();
			}
			catch (IOException e)
			{
				recordFailure(e);
			}
			children.shutdownNow();
		}
		children.close();
		if (failure != null)
			throw failure;
	}

	/**
	 * Retains cleanup failures while allowing every remaining child to be stopped.
	 *
	 * @param exception the failure to report after cleanup
	 */
	private void recordFailure(IOException exception)
	{
		if (failure == null)
			failure = exception;
		else
			failure.addSuppressed(exception);
	}

	/**
	 * The records of a streamed request that the broker took and its child has not received yet.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe; the broker takes records while holding its own lock.
	 */
	private final class Streamed
	{
		/**
		 * The records in order; an empty element means that no record follows.
		 */
		private final BlockingQueue<Optional<String>> records = new LinkedBlockingQueue<>();
		/**
		 * The request's private response directory.
		 */
		private final Path directory;
		/**
		 * The number of the next record to take.
		 */
		private int next = 1;
		/**
		 * Indicates whether the broker took the {@code complete} record.
		 */
		private boolean complete;

		/**
		 * Creates the records of a request.
		 *
		 * @param directory the request's private response directory
		 */
		Streamed(Path directory)
		{
			this.directory = directory;
		}

		/**
		 * Takes each record that the sender has published since the last call, in order.
		 *
		 * @param id       the request identifier
		 * @param requests the run's requests directory
		 * @throws IOException if a record is invalid or cannot be read
		 */
		void take(String id, Path requests) throws IOException
		{
			while (!complete)
			{
				Optional<String> record = StreamedRequest.take(json, requests, id, next, directory, limits);
				if (record.isEmpty())
					return;
				next += 1;
				complete = StreamedRequest.isComplete(json, record.get());
				records.add(record);
			}
		}
	}
}
