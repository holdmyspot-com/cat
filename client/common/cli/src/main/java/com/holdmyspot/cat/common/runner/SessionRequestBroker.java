package com.holdmyspot.cat.common.runner;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Validates session requests, snapshots their inputs before starting children, and publishes retained evidence.
 * Closing the broker stops child process trees and answers unprocessed requests as stopped.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class SessionRequestBroker implements Closeable
{
	private final HarnessRun run;
	private final HarnessRunnerOptions options;
	private final RequestedSessionStarter starter;
	private final JsonMapper json = JsonMapper.builder().build();
	private final ExecutorService children = Executors.newVirtualThreadPerTaskExecutor();
	/**
	 * Live child processes, guarded with all other broker state by this instance's monitor.
	 */
	private final Map<String, Process> processes = new HashMap<>();
	private final Map<String, String> harnesses = new HashMap<>();
	private final Map<String, Path> childRoots = new HashMap<>();
	private final Set<String> cancelled = new HashSet<>();
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
		requireThat(run, "run").isNotNull();
		requireThat(options, "options").isNotNull();
		requireThat(starter, "starter").isNotNull();
		this.run = run;
		this.options = options;
		this.starter = starter;
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
			try (DirectoryStream<Path> files = Files.newDirectoryStream(SessionRequestAccess.requestsDirectory(run)))
			{
				for (Path file : files)
				{
					String name = file.getFileName().toString();
					if (name.startsWith(".") || !name.endsWith(RequestArchive.EXTENSION))
						continue;
					String id = name.substring(0, name.length() - RequestArchive.EXTENSION.length());
					try
					{
						SessionRequestCli.validateId(id);
					}
					catch (IllegalArgumentException _)
					{
						// Invalid filenames cannot name response directories. The launcher never generates them.
						continue;
					}
					Path directory = SessionRequestAccess.responsesDirectory(run).resolve(id);
					if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
						continue;
					Files.createDirectory(directory);
					if (stopped)
					{
						respond(id, "", "stopped", "The requesting run stopped", "", directory);
						continue;
					}
					if (accepted >= options.sessionRequests())
					{
						respond(id, "", "refused", "limit", "", directory);
						continue;
					}
					try
					{
						Request request = read(file, directory);
						accepted += 1;
						harnesses.put(id, request.harness());
						childRoots.put(id, SessionRequestAccess.requestedRunsDirectory(run).
							resolve(String.valueOf(accepted)).resolve(request.harness()));
						List<String> arguments = arguments(request, request.project(), request.plugins(), directory, accepted);
						children.submit(() -> execute(id, request, arguments, directory));
					}
					catch (IOException | IllegalArgumentException | JacksonException e)
					{
						respond(id, "", "refused", e.getMessage(), "", directory);
					}
				}
			}
		}
	}

	/**
	 * Copies and extracts a request before parsing its bounded manifest and captured prompts.
	 *
	 * @param file      the request archive
	 * @param directory the broker-owned response directory
	 * @return the validated inputs, with prompt bytes already captured
	 * @throws IOException if the document or any input is invalid
	 */
	private Request read(Path file, Path directory) throws IOException
	{
		Path copy = directory.resolve("request.tar");
		// The broker has the user's permissions while the requesting session keeps running. Walking its input
		// directories would let it swap a link or junction into the path and make us read a file it may not read.
		// The launcher packs inside its sandbox, which refuses that read. We open only this single archive in
		// requests, whose path has no session-controlled directory, without following links and with one hard link.
		Path inputs = Files.createDirectory(directory.resolve("inputs"));
		try
		{
			ArchiveSource.copy(file, copy);
			RequestArchive.unpack(copy, inputs);
		}
		finally
		{
			Files.deleteIfExists(copy);
		}
		JsonNode value = json.readTree(Files.readAllBytes(inputs.resolve(RequestArchive.MANIFEST)));
		Set<String> allowed = Set.of("format_version", "harness", "project", "prompts", "plugins", "model", "effort",
			"session_transcripts", "install_cat");
		if (!value.isObject() || !allowed.containsAll(value.propertyNames()) ||
			!value.path("format_version").isInt() || value.path("format_version").intValue() != RequestArchive.VERSION)
			throw new IOException("Request must be a version 2 object with supported fields");
		String harness = text(value, "harness");
		if (!Set.of("claude", "codex").contains(harness))
			throw new IOException("Unsupported requested harness: " + harness);
		if (!text(value, "project").equals("project"))
			throw new IOException("Request project must name the project archive entry");
		Path project = inputs.resolve("project");
		if (!Files.isDirectory(project))
			throw new IOException("Request archive requires a project directory");
		List<Path> plugins = paths(value, inputs, "plugins", false);
		List<String> prompts = new ArrayList<>();
		for (Path prompt : paths(value, inputs, "prompts", true))
			prompts.add(Files.readString(prompt, StandardCharsets.UTF_8));
		String model = optionalText(value, "model");
		String effort = optionalText(value, "effort");
		for (String flag : List.of("session_transcripts", "install_cat"))
		{
			if (value.has(flag) && !value.path(flag).isBoolean())
				throw new IOException(flag + " must be a boolean");
		}
		return new Request(harness, project, plugins, prompts, model, effort,
			value.path("session_transcripts").asBoolean(false), value.path("install_cat").asBoolean(false));
	}

	/**
	 * Reads one required text field.
	 *
	 * @param value the document
	 * @param field the field name
	 * @return the non-blank value
	 * @throws IOException if the field is missing or malformed
	 */
	private static String text(JsonNode value, String field) throws IOException
	{
		if (!value.path(field).isString() || value.path(field).asString().isBlank())
			throw new IOException("Request requires non-blank text: " + field);
		return value.path(field).asString();
	}

	/**
	 * Reads an optional runner profile field. Empty values preserve the runner's default profile.
	 *
	 * @param value the document
	 * @param field the field name
	 * @return its text, or empty when absent
	 * @throws IOException if present with the wrong type
	 */
	private static String optionalText(JsonNode value, String field) throws IOException
	{
		if (!value.has(field))
			return "";
		if (!value.path(field).isString())
			throw new IOException("Request field must be text: " + field);
		return value.path(field).asString();
	}

	/**
	 * Resolves an ordered list of canonical archive entries in the private extracted inputs.
	 *
	 * @param value the document
	 * @param inputs the private extraction directory
	 * @param field the array field
	 * @param required indicates whether at least one entry is required
	 * @return validated absolute paths
	 * @throws IOException if the array or a path is invalid
	 */
	private List<Path> paths(JsonNode value, Path inputs, String field, boolean required) throws IOException
	{
		JsonNode array = value.path(field);
		if (!array.isArray() || array.size() > 32 || (required && array.isEmpty()))
			throw new IOException("Request requires an array of at most 32 paths: " + field);
		List<Path> paths = new ArrayList<>();
		for (JsonNode element : array)
		{
			if (!element.isString())
				throw new IOException("Request paths must be text: " + field);
			String expected = field + "/" + paths.size();
			if (!element.asString().equals(expected))
				throw new IOException("Request input must name its ordered archive entry: " + expected);
			Path path = inputs.resolve(expected);
			boolean correctType = Files.isDirectory(path);
			if (field.equals("prompts"))
				correctType = Files.isRegularFile(path);
			if (!correctType)
				throw new IOException("Request archive input is missing or has the wrong type: " + expected);
			paths.add(path);
		}
		return paths;
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
	 */
	private List<String> arguments(Request request, Path project, List<Path> plugins, Path directory, int ordinal)
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
		if (!request.model().isBlank())
			arguments.addAll(List.of("--model", request.model()));
		if (!request.effort().isBlank())
			arguments.addAll(List.of("--effort", request.effort()));
		if (request.sessionTranscripts())
			arguments.add("--session-transcripts");
		if (request.installCat())
			arguments.add("--install-cat");
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
	private void execute(String id, Request request, List<String> arguments, Path directory)
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
				for (String prompt : request.prompts())
				{
					ObjectNode turn = json.createObjectNode().put("type", "turn").put("prompt", prompt);
					input.write((json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(turn) +
						"\n").getBytes(StandardCharsets.UTF_8));
				}
				input.write("{\"type\":\"complete\"}\n".getBytes(StandardCharsets.UTF_8));
				input.flush();
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
			}
		}
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
	 * Captured request inputs. Prompt text is private to the runner protocol rather than a response document.
	 *
	 * @param harness the selected harness
	 * @param project the validated source project
	 * @param plugins the validated source plugins
	 * @param prompts the captured prompts in turn order
	 * @param model the selected model, empty for the runner default
	 * @param effort the selected effort, empty for the runner default
	 * @param sessionTranscripts indicates whether native transcripts are requested
	 * @param installCat indicates whether CAT is installed in the requested session's home
	 */
	private record Request(String harness, Path project, List<Path> plugins, List<String> prompts, String model,
		String effort, boolean sessionTranscripts, boolean installCat)
	{
	}
}
