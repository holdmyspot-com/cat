package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.runner.RequestArchive.RunRequest;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
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
 * further request refused with {@link SessionRequestAccess#limitReason(int)}. A request whose records follow it, as
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
	/**
	 * The name, in a response directory and in its response, of the copy of a kept run that the requester reads.
	 */
	static final String RUN_COPY = "run";
	private final Site site;
	private final RequestedSessionStarter starter;
	private final JsonMapper json = JsonMapper.builder().build();
	private final ExecutorService children = Executors.newVirtualThreadPerTaskExecutor();
	/**
	 * Live child processes, guarded with all other broker state by this instance's monitor.
	 */
	private final Map<String, Process> processes = new HashMap<>();
	private final Map<String, String> harnesses = new HashMap<>();
	/**
	 * The run root of each accepted request, which holds its child's run below the child's harness.
	 */
	private final Map<String, Path> runRoots = new HashMap<>();
	private final Map<String, Path> childRoots = new HashMap<>();
	private final Set<String> cancelled = new HashSet<>();
	/**
	 * The requests whose requester receives a copy of the requested session's kept run.
	 */
	private final Set<String> keptRuns = new HashSet<>();
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
		this(new RunSite(run, options, limits), starter);
	}

	/**
	 * Creates a broker that serves the requests of a site.
	 *
	 * @param site    where the requests arrive and what their children receive
	 * @param starter resolves and starts child runners
	 * @throws NullPointerException if any of the arguments are null
	 */
	SessionRequestBroker(Site site, RequestedSessionStarter starter)
	{
		requireThat(site, "site").isNotNull();
		requireThat(starter, "starter").isNotNull();
		this.site = site;
		this.starter = starter;
	}

	/**
	 * Starts paced discovery using the session's ticker, after stating the run's limits in its
	 * {@link SessionRequestAccess#LIMITS_FILE}. Any discovery failure is reported by {@link #close()}.
	 *
	 * @param ticker the injected scan schedule
	 * @throws IOException if the limits cannot be written
	 */
	public void start(HeartbeatTicker ticker) throws IOException
	{
		synchronized (this)
		{
			requireThat(ticker, "ticker").isNotNull();
			if (monitor != null || stopped)
				throw new IllegalStateException("The session request broker has already started or stopped");
			site.accepted(json, accepted);
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
			Path requests = site.requests();
			for (String id : RequestArchive.pendingIds(requests, ""))
			{
				Path file = requests.resolve(id + RequestArchive.EXTENSION);
				Path directory = site.responses().resolve(id);
				if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
					continue;
				Files.createDirectory(directory);
				if (stopped)
				{
					respond(id, "", site.closedStatus(), site.closedReason(), "", directory);
					continue;
				}
				Optional<String> refusal = site.refusal(accepted);
				if (refusal.isPresent())
				{
					respond(id, "", "refused", refusal.get(), "", directory);
					continue;
				}
				try
				{
					RunRequest request = read(file, directory);
					accepted += 1;
					site.accepted(json, accepted);
					harnesses.put(id, request.harness());
					Path runRoot = site.runRoot(id, accepted);
					runRoots.put(id, runRoot);
					childRoots.put(id, runRoot.resolve(request.harness()));
					List<String> arguments = arguments(request, runRoot, directory);
					if (request.streamed())
						streams.put(id, new Streamed(directory));
					if (request.keepRun())
						keptRuns.add(id);
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
			RequestArchive.copy(file, copy, site.limits());
			RequestArchive.unpack(copy, inputs, site.limits());
		}
		finally
		{
			Files.deleteIfExists(copy);
		}
		return site.read(json, inputs);
	}

	/**
	 * Builds the runner options that every child receives from its request's private copies: its project, run root,
	 * transcript, receipt and kept run, and the request's options, which only narrow the child or describe its own
	 * inputs. The site adds its own options before the child starts.
	 *
	 * @param request   the validated request
	 * @param runRoot   the request's run root
	 * @param directory the response directory
	 * @return the runner options
	 * @throws IOException if the request's extra permissions cannot be written for the runner
	 */
	private List<String> arguments(RunRequest request, Path runRoot, Path directory) throws IOException
	{
		List<String> arguments = new ArrayList<>(List.of("--project", request.project().toString(), "--run-root",
			runRoot.toString(), "--keep-run", "--output", directory.resolve("transcript.jsonl").toString(),
			"--receipt", directory.resolve("receipt.json").toString()));
		for (Path denied : request.deniedReads())
			arguments.addAll(List.of("--deny-read", denied.toString()));
		request.workspace().ifPresent(workspace -> arguments.addAll(List.of("--workspace", workspace.toString())));
		for (Path evidence : request.readOnlyEvidence())
			arguments.addAll(List.of("--read-only-evidence", evidence.toString()));
		request.instructionFiles().ifPresent(files -> arguments.addAll(List.of("--instruction-files", files)));
		request.toolOutputTokenLimit().ifPresent(limit ->
			arguments.addAll(List.of("--tool-output-token-limit", String.valueOf(limit))));
		for (Path plugin : request.plugins())
			arguments.addAll(List.of("--plugin", plugin.toString()));
		request.agent().ifPresent(agent -> arguments.addAll(List.of("--agent", agent)));
		if (!request.model().isEmpty())
			arguments.addAll(List.of("--model", request.model()));
		if (!request.effort().isEmpty())
			arguments.addAll(List.of("--effort", request.effort()));
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
			Path runRoot;
			synchronized (this)
			{
				runRoot = runRoots.get(id);
			}
			List<String> options = new ArrayList<>(arguments);
			options.addAll(site.options(request, runRoot));
			synchronized (this)
			{
				if (stopped || cancelled.contains(id))
					return;
			}
			process = starter.start(request.harness(), options, directory);
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
		catch (IllegalArgumentException | SessionRefusedException e)
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
				try
				{
					site.answered(runRoots.get(id));
				}
				catch (IOException e)
				{
					recordFailure(e);
				}
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
	 * Publishes a child's result after copying transcripts from its retained run and, when the request keeps its run,
	 * the run itself, without its home, into the response directory's {@link #RUN_COPY}. A run that cannot be copied
	 * in full makes a completed child fail, with the reason that names the file.
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
			String outcome = status;
			String diagnostic = reason;
			if (keptRuns.contains(id) && !childRunId.isEmpty())
			{
				// The requester cannot read the requested runs, whose homes hold their logins, so it reads a copy
				Optional<String> incomplete = KeptRunCopy.copy(childRoots.get(id).resolve(childRunId),
					directory.resolve(RUN_COPY)).incomplete();
				if (incomplete.isPresent())
				{
					if (diagnostic.isEmpty())
						diagnostic = incomplete.get();
					else
						diagnostic += "\n" + incomplete.get();
					if (outcome.equals("completed"))
						outcome = "failed";
				}
			}
			respond(id, harness, outcome, diagnostic, childRunId, directory);
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
		if (Files.isDirectory(directory.resolve(RUN_COPY), LinkOption.NOFOLLOW_LINKS))
			response.put(RUN_COPY, directory.resolve(RUN_COPY).toString());
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
				Path directory = site.responses().resolve(entry.getKey());
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
				Path directory = site.responses().resolve(entry.getKey());
				try
				{
					if (!Files.exists(directory.resolve("response.json")))
						respond(entry.getKey(), entry.getValue(), site.closedStatus(), site.closedReason(),
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
				Optional<String> record = StreamedRequest.take(json, requests, id, next, directory, site.limits());
				if (record.isEmpty())
					return;
				next += 1;
				complete = StreamedRequest.isComplete(json, record.get());
				records.add(record);
			}
		}
	}

	/**
	 * Where a broker's requests arrive, and what the children that it starts for them receive.
	 * <p>
	 * <b>Thread Safety:</b> Implementations must be thread-safe.
	 */
	interface Site
	{
		/**
		 * Returns the directory into which requesters write their requests.
		 *
		 * @return the directory
		 */
		Path requests();

		/**
		 * Returns the directory that holds one response directory per request.
		 *
		 * @return the directory
		 */
		Path responses();

		/**
		 * Returns the bounds that each request's archive must stay within.
		 *
		 * @return the bounds
		 */
		RequestLimits limits();

		/**
		 * Reads the manifest of an extracted request.
		 *
		 * @param json   the manifest mapper
		 * @param inputs the private directory that holds the extracted request
		 * @return the request
		 * @throws IOException              if the manifest is malformed or chooses a field that the site refuses
		 * @throws IllegalArgumentException if the request names no model or no effort
		 */
		RunRequest read(JsonMapper json, Path inputs) throws IOException;

		/**
		 * Returns why the site refuses the next request before it reads it.
		 *
		 * @param accepted how many requests the broker has accepted
		 * @return the reason, or empty if the site reads the request
		 */
		Optional<String> refusal(int accepted);

		/**
		 * Records how many requests the broker has accepted, once when it starts and again after each acceptance.
		 *
		 * @param json     writes the record
		 * @param accepted how many requests the broker has accepted
		 * @throws IOException if the record cannot be written
		 */
		void accepted(JsonMapper json, int accepted) throws IOException;

		/**
		 * Returns the run root of an accepted request.
		 *
		 * @param id      the request's identifier
		 * @param ordinal the request's number among the accepted requests, starting at {@code 1}
		 * @return the run root
		 */
		Path runRoot(String id, int ordinal);

		/**
		 * Returns the options that the site adds to an accepted request's child, on the request's own thread before
		 * the child starts.
		 *
		 * @param request the request
		 * @param runRoot the request's run root
		 * @return the options
		 * @throws SessionRefusedException if the site refuses the request; the message says why
		 * @throws IOException             if the options cannot be determined
		 */
		List<String> options(RunRequest request, Path runRoot) throws IOException;

		/**
		 * Returns the status of the requests that remain unanswered when the broker closes.
		 *
		 * @return the status
		 */
		String closedStatus();

		/**
		 * Returns the reason of the requests that remain unanswered when the broker closes.
		 *
		 * @return the reason
		 */
		String closedReason();

		/**
		 * Releases what an accepted request leaves in its run root once its child has ended and the request is
		 * answered.
		 *
		 * @param runRoot the request's run root
		 * @throws IOException if what the request leaves cannot be released
		 */
		void answered(Path runRoot) throws IOException;
	}

	/**
	 * The site of a run whose runner serves the requests of the run's commands.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param run     the requesting run
	 * @param options the requesting runner's options
	 * @param limits  the bounds that each request's archive must stay within
	 */
	private record RunSite(HarnessRun run, HarnessRunnerOptions options, RequestLimits limits) implements Site
	{
		/**
		 * Creates the site of a run.
		 *
		 * @param run     the requesting run
		 * @param options the requesting runner's options
		 * @param limits  the bounds that each request's archive must stay within
		 * @throws NullPointerException if any of the arguments are null
		 */
		private RunSite
		{
			requireThat(run, "run").isNotNull();
			requireThat(options, "options").isNotNull();
			requireThat(limits, "limits").isNotNull();
		}

		@Override
		public Path requests()
		{
			return SessionRequestAccess.requestsDirectory(run);
		}

		@Override
		public Path responses()
		{
			return SessionRequestAccess.responsesDirectory(run);
		}

		@Override
		public RunRequest read(JsonMapper json, Path inputs) throws IOException
		{
			return RequestArchive.readRun(json, inputs);
		}

		@Override
		public Optional<String> refusal(int accepted)
		{
			if (options.sessionRequests() == 0)
				return Optional.of(SessionRequestAccess.NO_REQUESTS_REASON);
			if (accepted >= options.sessionRequests())
				return Optional.of(SessionRequestAccess.limitReason(options.sessionRequests()));
			return Optional.empty();
		}

		/**
		 * Atomically states, in the run's {@link SessionRequestAccess#LIMITS_FILE}, how many sessions the run allows,
		 * how many it has accepted and how many levels below its requested sessions may request sessions in turn.
		 *
		 * @param json     writes the file
		 * @param accepted how many requests the broker has accepted
		 * @throws IOException if the file cannot be written
		 */
		@Override
		public void accepted(JsonMapper json, int accepted) throws IOException
		{
			ObjectNode limits = json.createObjectNode().put("format_version", 1).
				put("requests_allowed", options.sessionRequests()).put("requests_used", accepted).
				put("depth_remaining", options.requestDepth());
			SessionRequestCli.write(json, responses().resolve(SessionRequestAccess.LIMITS_FILE), limits);
		}

		@Override
		public Path runRoot(String id, int ordinal)
		{
			return SessionRequestAccess.requestedRunsDirectory(run).resolve(String.valueOf(ordinal));
		}

		/**
		 * Retains the parent's denied reads and capacity ceiling, denies the child the parent's run, and, while the
		 * parent's request depth leaves a level below the child, lets the child request as many sessions as the
		 * parent, one level less deep; otherwise it may request none.
		 *
		 * @param request the request
		 * @param runRoot the request's run root
		 * @return the options
		 */
		@Override
		public List<String> options(RunRequest request, Path runRoot)
		{
			List<String> arguments = new ArrayList<>(List.of("--capacity-wait", "600", "--max-concurrency",
				String.valueOf(options.maximumConcurrency())));
			for (Path denied : options.deniedReads())
				arguments.addAll(List.of("--deny-read", denied.toString()));
			arguments.addAll(List.of("--deny-read", options.runRoot().toString()));
			if (options.requestDepth() > 0)
			{
				arguments.addAll(List.of("--session-requests", String.valueOf(options.sessionRequests())));
				int childDepth = options.requestDepth() - 1;
				if (childDepth > 0)
					arguments.addAll(List.of("--request-depth", String.valueOf(childDepth)));
			}
			return arguments;
		}

		@Override
		public String closedStatus()
		{
			return "stopped";
		}

		@Override
		public String closedReason()
		{
			return "The requesting run stopped";
		}

		@Override
		public void answered(Path runRoot)
		{
			// The requested runs stay in the run, which the run's runner removes with it
		}
	}
}
