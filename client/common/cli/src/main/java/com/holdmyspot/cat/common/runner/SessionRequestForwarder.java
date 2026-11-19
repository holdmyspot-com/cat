package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Hands a runner launch to the runner that serves the session's requests outside its sandbox: inside a nested session,
 * the run's own runner, and outside a nested session, the session's request server.
 * <p>
 * A session's commands cannot start a sandboxed harness: they run in a sandbox, inside which no harness sandbox can
 * start. A runner launcher that the session runs packs the launch as a session request instead, waits for the runner
 * that serves it to finish it, and returns the requested session's transcript and receipt as its own. With {@code
 * --conversation}, the launch publishes the request without its records and sends each of the conversation's messages
 * as the request's next record while the session runs. When the parent's run may request no further session, the parent
 * refuses the request and the launch exits with status 2 and the reason. Inside a nested session, the parent chooses
 * the results folder, the artifact, its own denied reads and the limits of the sessions it requests, so a launch may
 * not choose them; outside a nested session, a launch chooses the limits of the session it requests. The copy of the
 * session's run that its runner returns is copied into the launch's results folder, which inside a nested session only
 * a launch that names one has; otherwise the copy stays in the response that the nested session's runner keeps. A
 * launch may name the options that only narrow the requested session or describe its own inputs: further denied reads,
 * the workspace files, the read-only evidence, the tool-output token limit and the instruction files. The files and
 * directories that it names travel inside the request, so each must be one that the session's sandbox lets it read.
 */
public final class SessionRequestForwarder
{
	/**
	 * How often a served session's sender checks for its records and for the run's response.
	 */
	public static final Duration RESPONSE_POLL = Duration.ofMillis(100);
	/**
	 * Options that the parent runner chooses for the sessions it requests inside a nested session, and their manifest
	 * fields.
	 */
	private static final Map<String, String> PARENT_OPTIONS = Map.of("--session-requests", "session_requests",
		"--request-depth", "request_depth", "--max-concurrency", "max_concurrency", "--capacity-wait", "capacity_wait",
		"--agent", "agent");

	/**
	 * The runner that serves a launch's request.
	 */
	public enum Route
	{
		/**
		 * The nested session's runner, which chooses the limits of the sessions that its run's session requests.
		 */
		RUN,
		/**
		 * The session's request server, outside a nested session, which applies the limits that the launch names.
		 */
		SERVER;

		/**
		 * Returns the bounds that the route's requests stay within.
		 *
		 * @return the bounds
		 */
		private RequestLimits limits()
		{
			return switch (this)
			{
				case RUN -> RequestLimits.IN_RUN;
				case SERVER -> RequestLimits.UNBOUNDED;
			};
		}
	}
	/**
	 * Options that a launch may name more than once.
	 */
	private static final Set<String> REPEATABLE_OPTIONS = Set.of("--plugin", "--deny-read", "--read-only-evidence");
	/**
	 * The options that take a value and that a launch may name.
	 */
	private static final Set<String> VALUE_OPTIONS = Set.of("--artifact", "--results", "--project", "--plugin",
		"--model", "--effort", "--input", "--output", "--receipt", ExtraPermissions.OPTION, Conversation.OPTION,
		"--deny-read", "--workspace", "--read-only-evidence", "--instruction-files", "--session-role",
		"--tool-output-token-limit", RunnerArguments.RESUME_OPTION);

	/**
	 * Forwards one launch and waits for its requested session to end.
	 *
	 * @param harness  the harness of the runner launcher
	 * @param args     the runner launcher's options
	 * @param input    the launch's {@code turn} and {@code complete} records when neither {@code --input} nor
	 *                 {@code --conversation} is given
	 * @param requests the run's requests directory
	 * @param pause    paces checks for the session's progress
	 * @param output   receives the session's transcript when {@code --output} is absent
	 * @param errors   receives the reason when the session does not complete
	 * @return {@code 0} if the session completed, {@code 75} if it found no capacity, {@code 2} if the run allows no
	 *         further session, or {@code 1} otherwise
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or chosen by the parent runner, if
	 *                                  {@code --input} and {@code --conversation} are both given, or if the launch
	 *                                  names no {@code --model} or no {@code --effort}
	 * @throws IOException              if the records are invalid, a file or directory that the launch names cannot be
	 *                                  read, or the request or response cannot be read or written
	 */
	public static int forward(String harness, String[] args, Reader input, Path requests, Pause pause,
		OutputStream output, PrintWriter errors) throws IOException
	{
		return forward(Route.RUN, harness, args, input, requests, null, pause, output, errors);
	}

	/**
	 * Forwards one launch and waits for its requested session to end. The session's results are copied into the
	 * launch's results folder as {@code <results folder>/<harness>/<session ID>}, outside a nested session or when the
	 * launch names one inside a nested session. Outside a nested session, the response is removed once the launch has
	 * copied what it needs.
	 *
	 * @param route          the runner that serves the request
	 * @param harness        the harness of the runner launcher
	 * @param args           the runner launcher's options
	 * @param input          the launch's {@code turn} and {@code complete} records when neither {@code --input} nor
	 *                       {@code --conversation} is given
	 * @param requests       the directory into which the launch writes its request
	 * @param defaultRunRoot the results folder of a launch that names no {@code --results} outside a nested session, or
	 *                       {@code null} inside one
	 * @param pause          paces checks for the session's progress
	 * @param output         receives the session's transcript when {@code --output} is absent
	 * @param errors         receives the reason when the session does not complete
	 * @return {@code 0} if the session completed, {@code 75} if it found no capacity, {@code 2} if the session was
	 *         refused, or {@code 1} otherwise
	 * @throws NullPointerException     if any argument other than {@code defaultRunRoot} is null, or if
	 *                                  {@code defaultRunRoot} is null outside a nested session
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or, inside a nested session,
	 *                                  chosen by the parent runner, if {@code --input} and {@code --conversation} are
	 *                                  both given, or if the launch names no {@code --model} or no {@code --effort}
	 * @throws IOException              if the records are invalid, a file or directory that the launch names cannot be
	 *                                  read, the request or response cannot be read or written, or the session's
	 *                                  run cannot be copied
	 */
	public static int forward(Route route, String harness, String[] args, Reader input, Path requests,
		Path defaultRunRoot, Pause pause, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(route, "route").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(requests, "requests").isDirectory();
		if (route == Route.SERVER)
			requireThat(defaultRunRoot, "defaultRunRoot").isNotNull();
		requireThat(pause, "pause").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(errors, "errors").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		Launch launch = parse(json, route, harness, args);
		ObjectNode request = launch.request();
		String id = UUID.randomUUID().toString();
		JsonNode response;
		AtomicReference<Optional<String>> incomplete = new AtomicReference<>(Optional.empty());
		if (launch.conversation() == null)
		{
			List<String> prompts;
			if (launch.records() == null)
				prompts = prompts(json, input);
			else
			{
				try (Reader file = Files.newBufferedReader(launch.records(), StandardCharsets.UTF_8))
				{
					prompts = prompts(json, file);
				}
			}
			// The launch holds its lock from before it publishes the request until it ends, so that its server stops the
			// requested session if the launch ends first
			try (StreamedRequest.Sender _ = new StreamedRequest.Sender(requests.toAbsolutePath(), id))
			{
				RequestArchive.pack(json, requests.toAbsolutePath().resolve(id + RequestArchive.EXTENSION), request,
					prompts, RequestArchive.NULL_DEVICE, route.limits());
				response = awaitTranscript(json, responses(requests, id), pause, output, launch.transcript(),
					Function.identity());
				incomplete.set(collect(route, harness, launch, defaultRunRoot, requests, id, response));
			}
		}
		else
		{
			// The conversation ends after the launch has what it keeps, so the asking session that reads its last line
			// finds the copy of its run in place
			response = converse(json, request, requests, id, launch.conversation(), RequestArchive.NULL_DEVICE,
				route.limits(), pause, output, launch.transcript(),
				answer -> incomplete.set(collect(route, harness, launch, defaultRunRoot, requests, id, answer)));
		}
		String status = response.path("status").asString();
		if (incomplete.get().isPresent() && status.equals("completed"))
		{
			errors.println(incomplete.get().get());
			return 1;
		}
		if (status.equals("completed"))
			return 0;
		errors.println("The requested session ended with status " + status + ": " +
			response.path("reason").asString());
		if (status.equals("capacity"))
			return HarnessCapacityWaitExpiredException.EXIT_STATUS;
		if (status.equals("refused"))
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		return 1;
	}

	/**
	 * Keeps what a launch takes from its session's response: the receipt that {@code --receipt} names and the copy of
	 * the session's run, which is copied into the launch's results folder outside a nested session, and inside a nested
	 * session when the launch names one. The response is removed once the launch has copied what it needs, outside a
	 * nested session.
	 *
	 * @param route          the runner that serves the request
	 * @param harness        the harness of the runner launcher
	 * @param launch         the launch
	 * @param defaultRunRoot the results folder of a launch that names no {@code --results} outside a nested session, or
	 *                       {@code null} inside one
	 * @param requests       the directory into which the launch wrote its request
	 * @param id             the request identifier
	 * @param response       the response
	 * @return the reason why the copy of the session's run is incomplete, or empty if it is complete or the launch
	 *   copies none
	 * @throws IOException if the receipt or the session's run cannot be copied
	 */
	private static Optional<String> collect(Route route, String harness, Launch launch, Path defaultRunRoot,
		Path requests, String id, JsonNode response) throws IOException
	{
		Path childReceipt = responses(requests, id).resolve("receipt.json");
		if (launch.receipt() != null && Files.isRegularFile(childReceipt))
		{
			Files.createDirectories(launch.receipt().toAbsolutePath().getParent());
			Files.copy(childReceipt, launch.receipt(), StandardCopyOption.REPLACE_EXISTING);
		}
		Path runRoot = launch.runRoot();
		if (runRoot == null)
		{
			// Inside a nested session, the copy stays in the response, which the nested session's runner keeps while that
			// session lasts
			if (route != Route.SERVER)
				return Optional.empty();
			runRoot = defaultRunRoot;
		}
		Optional<String> incomplete = copyKeptRun(response, runRoot.resolve(harness), launch.project());
		if (route == Route.SERVER)
			TemporaryDirectories.deleteRecursively(responses(requests, id));
		return incomplete;
	}

	/**
	 * Copies the session's results that a response names into a harness's runs, under the identifier that the harness
	 * gave the session. The session ran in a slot, whose name every session of the slot shares, so the copy takes the
	 * session's own identifier, which no other session has.
	 *
	 * @param response the response
	 * @param runs     the directory that holds the harness's runs in the launch's results folder
	 * @param project  the project that the launch named, which the copy's manifest names
	 * @return the reason why the copy is incomplete, or empty if the response names no results or the copy is
	 *   complete
	 * @throws IOException if the run cannot be copied
	 */
	private static Optional<String> copyKeptRun(JsonNode response, Path runs, Path project)
		throws IOException
	{
		String run = response.path(SessionRequestBroker.RUN_COPY).asString("");
		String sessionId = response.path(SessionRequestBroker.SESSION_ID).asString("");
		if (run.isEmpty() || !RecordCopy.isSessionId(sessionId))
			return Optional.empty();
		return KeptRunCopy.copy(Path.of(run), runs.resolve(sessionId), sessionId, project).incomplete();
	}

	/**
	 * Serves one session of a CAT command, such as the prompt optimizer, through the nested session's runner: streams
	 * the command's records to the requested session as it writes them, copies the session's records back as they
	 * arrive, and copies the session's results, which the nested session's runner names in its response, into the
	 * launch's {@code --results} as {@code <results folder>/<harness>/<session id>}, where the session ID is the one
	 * that the harness gave the session. The command creates that directory's {@code artifacts} once the runner reports
	 * the ID, so that what the command writes into the session's results while it runs has a place. Before the command
	 * reads the record that reports the end of a completed turn, that run holds the session's {@code project} and
	 * {@code initial-project} as the turn left them, so that the command can read what the session has written so far,
	 * as it does in a session that it runs directly. A run that allows no further session refuses the session before it
	 * publishes anything. The nested session's runner installs its own CAT for {@code --install-cat}, so a launch that
	 * names another CAT as {@code --artifact} sends that CAT as a plugin, as a launch that goes to a request server
	 * does.
	 *
	 * @param command     names the command in a refusal, such as {@code "The prompt optimizer"}
	 * @param harness     the harness of the session
	 * @param ownArtifact the CAT artifact that the command runs from
	 * @param arguments   the runner's options, which name {@code --results}
	 * @param input       the {@code turn}, {@code interrupt} and {@code complete} records, one per line
	 * @param output      receives the session's records, one per line
	 * @param requests    the run's requests directory
	 * @param pause       paces checks for the session's progress, and throws once the calling thread is
	 *                    interrupted, which ends the request and stops the session
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or chosen by the parent runner, if
	 *                                  the launch names {@code --input} or {@code --conversation}, or no
	 *                                  {@code --results}, {@code --model} or {@code --effort}
	 * @throws LaunchRefusedException   if the run allows no further session or its runner refused the session
	 * @throws IOException              if the session did not complete, a file or directory that the launch names
	 *                                  cannot be read, the request or response cannot be read or written, or the kept
	 *                                  run cannot be copied in full
	 */
	public static void serve(String command, String harness, Path ownArtifact, List<String> arguments, Reader input,
		Writer output, Path requests, Pause pause) throws IOException
	{
		serve(Route.RUN, command, harness, ownArtifact, arguments, input, output, requests, pause);
	}

	/**
	 * Serves one session of a CAT command, such as the prompt optimizer, through the runner that a route names, as
	 * {@link #serve(String, String, Path, List, Reader, Writer, Path, Pause)} does inside a nested session. Outside a
	 * nested session, the response is removed once the command has copied the kept run.
	 *
	 * @param route       the runner that serves the request
	 * @param command     names the command in a refusal, such as {@code "The prompt optimizer"}
	 * @param harness     the harness of the session
	 * @param ownArtifact the CAT artifact that the command runs from
	 * @param arguments   the runner's options, which name {@code --results}
	 * @param input       the {@code turn}, {@code interrupt} and {@code complete} records, one per line
	 * @param output      receives the session's records, one per line
	 * @param requests    the directory into which the command writes its request
	 * @param pause       paces checks for the session's progress, and throws once the calling thread is
	 *                    interrupted, which ends the request and stops the session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or, inside a nested session,
	 *                                  chosen by the parent runner, if the launch names {@code --input} or {@code
	 *                                  --conversation}, or no {@code --results}, {@code --model} or {@code --effort}
	 * @throws LaunchRefusedException   if the run allows no further session or the runner refused the session
	 * @throws IOException              if the session did not complete, a file or directory that the launch names
	 *                                  cannot be read, the request or response cannot be read or written, or the kept
	 *                                  run cannot be copied in full
	 */
	public static void serve(Route route, String command, String harness, Path ownArtifact, List<String> arguments,
		Reader input, Writer output, Path requests, Pause pause) throws IOException
	{
		requireThat(route, "route").isNotNull();
		requireThat(command, "command").isNotBlank();
		requireThat(harness, "harness").isNotBlank();
		requireThat(ownArtifact, "ownArtifact").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(requests, "requests").isNotNull();
		requireThat(pause, "pause").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		Launch launch = parse(json, route, harness, arguments.toArray(String[]::new));
		if (launch.records() != null || launch.conversation() != null)
			throw new InvalidArgumentsException("A served session takes its records from its input");
		if (launch.runRoot() == null)
			throw new InvalidArgumentsException("A served session names --results");
		Optional<String> refusal = SessionRequestAccess.furtherRequestRefusal(requests);
		if (refusal.isPresent())
			throw new LaunchRefusedException(SessionRequestAccess.refusalMessage(command, refusal.get()));
		String id = UUID.randomUUID().toString();
		ObjectNode request = launch.request().put(StreamedRequest.INPUT_FIELD, StreamedRequest.STREAM).
			put("turn_copy", true);
		if (request.path("install_cat").asBoolean(false) && launch.artifact() != null &&
			!isSame(launch.artifact(), ownArtifact))
		{
			request.withArray("plugins").insert(0, launch.artifact().toString());
		}
		JsonNode response;
		Path responses = responses(requests, id);
		try (RecordCopy copy = new RecordCopy(json, output, launch.runRoot().resolve(harness),
			     responses.resolve(SessionRequestBroker.TURN_COPY));
		     TurnFeed records = new TurnFeed(input);
		     StreamedRequest.Sender sender = new StreamedRequest.Sender(requests.toAbsolutePath(), id))
		{
			RequestArchive.pack(json, requests.toAbsolutePath().resolve(id + RequestArchive.EXTENSION), request,
				List.of(), RequestArchive.NULL_DEVICE, route.limits());
			Pause sending = () ->
			{
				for (Optional<String> next = records.poll(); next.isPresent(); next = records.poll())
					sender.send(next.get());
				pause.pause();
			};
			response = await(json, responses, sending, copy);
			// The run's copy replaces the copies of the turns, which the copy of the run would otherwise collide with
			copy.deleteTurnCopies();
		}
		String status = response.path("status").asString("");
		String reason = response.path("reason").asString("");
		boolean kept = !response.path(SessionRequestBroker.RUN_COPY).asString("").isEmpty() &&
			RecordCopy.isSessionId(response.path(SessionRequestBroker.SESSION_ID).asString(""));
		Optional<String> incomplete = copyKeptRun(response, launch.runRoot().resolve(harness), launch.project());
		if (route == Route.SERVER)
			TemporaryDirectories.deleteRecursively(responses(requests, id));
		if (incomplete.isPresent() && status.equals("completed"))
			throw new IOException(incomplete.get());
		if (!kept && status.equals("completed"))
			throw new IOException("The runner kept no run of the requested session");
		if (status.equals("refused"))
		{
			if (SessionRequestAccess.isRequestLimit(reason))
				throw new LaunchRefusedException(SessionRequestAccess.refusalMessage(command, reason));
			throw new LaunchRefusedException(reason);
		}
		if (!status.equals("completed"))
			throw new IOException("The requested session ended with status " + status + ": " + reason);
	}

	/**
	 * Reads a launch's options.
	 *
	 * @param json    the manifest mapper
	 * @param route   the runner that serves the launch's request
	 * @param harness the harness of the runner launcher
	 * @param args    the launch's options
	 * @return the launch
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or, inside a nested session,
	 *                                  chosen by the parent runner, if {@code --input} and {@code --conversation} are
	 *                                  both given, or if the launch names no {@code --model} or no {@code --effort}
	 * @throws IOException              if the file of {@code --extra-permissions} cannot be read
	 */
	private static Launch parse(JsonMapper json, Route route, String harness, String[] args) throws IOException
	{
		Path project = Path.of("").toAbsolutePath();
		ObjectNode request = json.createObjectNode().put("format_version", RequestArchive.VERSION).
			put("harness", harness);
		request.putArray("prompts");
		request.putArray("plugins");
		Path records = null;
		Path conversation = null;
		Path transcript = null;
		Path receipt = null;
		Path runRoot = null;
		Path artifact = null;
		Set<String> scalars = new HashSet<>();
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			if (PARENT_OPTIONS.containsKey(option) && route == Route.RUN)
			{
				throw new InvalidArgumentsException("Inside a nested session, the parent runner chooses " + option +
					" for the sessions it starts");
			}
			if (!REPEATABLE_OPTIONS.contains(option) && !scalars.add(option))
				throw new InvalidArgumentsException("Repeated option: " + option);
			if (PARENT_OPTIONS.containsKey(option))
			{
				if (index + 1 == args.length)
					throw new InvalidArgumentsException("Missing value for " + option);
				index += 1;
				String field = PARENT_OPTIONS.get(option);
				if (option.equals("--agent"))
					request.put(field, args[index]);
				else
					request.put(field, count(option, args[index]));
				continue;
			}
			switch (option)
			{
				case RunnerArguments.HELD_OUT_OPTION ->
				{
					request.put("held_out", true);
					continue;
				}
				case "--install-cat" ->
				{
					request.put("install_cat", true);
					continue;
				}
				case "--session-transcripts" ->
				{
					request.put("session_transcripts", true);
					continue;
				}
				default ->
				{
				}
			}
			if (!VALUE_OPTIONS.contains(option))
				throw new InvalidArgumentsException("Unsupported option: " + option);
			if (index + 1 == args.length)
				throw new InvalidArgumentsException("Missing value for " + option);
			index += 1;
			String value = args[index];
			switch (option)
			{
				// The parent runner chooses the artifact; a served session may send it as a plugin
				case "--artifact" -> artifact = Path.of(value).toAbsolutePath();
				// The parent runner chooses the results folder; a served session's kept run is copied below it
				case "--results" -> runRoot = Path.of(value).toAbsolutePath();
				case "--project" -> project = Path.of(value).toAbsolutePath();
				case "--plugin" -> request.withArray("plugins").add(Path.of(value).toAbsolutePath().toString());
				case "--model", "--effort" -> request.put(option.substring(2), value);
				case "--input" -> records = Path.of(value);
				case Conversation.OPTION -> conversation = Path.of(value).toAbsolutePath();
				case "--output" -> transcript = Path.of(value);
				case "--receipt" -> receipt = Path.of(value);
				case "--deny-read" -> request.withArray("deny_read").add(Path.of(value).toAbsolutePath().toString());
				case "--read-only-evidence" ->
					request.withArray("read_only_evidence").add(Path.of(value).toAbsolutePath().toString());
				case "--workspace" -> request.put("workspace", Path.of(value).toAbsolutePath().toString());
				case "--instruction-files" -> request.put("instruction_files", value);
				case "--session-role" -> request.put("session_role", value);
				case "--tool-output-token-limit" -> request.put("tool_output_token_limit", tokenLimit(value));
				case RunnerArguments.RESUME_OPTION ->
				{
					if (!HarnessRunManager.HARNESS_SESSION_ID.matcher(value).matches())
					{
						throw new InvalidArgumentsException(RunnerArguments.RESUME_OPTION + " needs the ID of a harness " +
							"session: " + value);
					}
					request.put(RequestArchive.RESUME, value);
				}
				case ExtraPermissions.OPTION ->
				{
					ExtraPermissions extra = HarnessRunnerOptions.readExtraPermissions(json, Path.of(value));
					if (!extra.isEmpty())
						request.set(RequestArchive.EXTRA_PERMISSIONS, extra.toDocument(json));
				}
				default -> throw new InvalidArgumentsException("Unsupported option: " + option);
			}
		}
		if (conversation != null && records != null)
			throw new InvalidArgumentsException(Conversation.OPTION + " and --input cannot be used together");
		if (request.has(RequestArchive.RESUME))
		{
			List<String> fixed = RunLaunch.fixedArguments(args);
			if (!fixed.isEmpty())
				throw fixedAtStart(fixed.getFirst());
		}
		HarnessRunnerOptions.requireProfile(harness, request.path("model").asString(""),
			request.path("effort").asString(""));
		// A launch that resumes a session continues in the project that the session's run copied at its start
		if (!request.has(RequestArchive.RESUME))
			request.put("project", project.toString());
		return new Launch(request, project, records, conversation, transcript, receipt, runRoot, artifact);
	}

	/**
	 * Returns the refusal of a launch that resumes a session and names an option that the session's run fixed at its
	 * start.
	 *
	 * @param option the option
	 * @return the refusal
	 */
	private static InvalidArgumentsException fixedAtStart(String option)
	{
		return new InvalidArgumentsException(RunnerArguments.RESUME_OPTION + " continues a run that fixed " + option +
			" at its start, so a launch that resumes a session cannot name it");
	}

	/**
	 * Returns whether two paths name the same file or directory.
	 *
	 * @param first  a path
	 * @param second another path
	 * @return {@code true} if both exist and are the same file, or if neither exists and both are the same path
	 * @throws IOException if the files cannot be compared
	 */
	private static boolean isSame(Path first, Path second) throws IOException
	{
		if (Files.exists(first) && Files.exists(second))
			return Files.isSameFile(first, second);
		return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
	}

	/**
	 * Reads the value of a limit that a launch outside a nested session names.
	 *
	 * @param option the option
	 * @param value  the option's value
	 * @return the limit
	 * @throws IllegalArgumentException if the value is not a whole number
	 */
	private static int count(String option, String value)
	{
		try
		{
			int count = Integer.parseInt(value);
			if (count >= 0)
				return count;
		}
		catch (NumberFormatException _)
		{
			// Refused below
		}
		throw new InvalidArgumentsException(option + " must be a whole number: " + value);
	}

	/**
	 * Reads the value of {@code --tool-output-token-limit}.
	 *
	 * @param value the option's value
	 * @return the limit
	 * @throws IllegalArgumentException if the value is not a positive whole number
	 */
	private static int tokenLimit(String value)
	{
		try
		{
			int limit = Integer.parseInt(value);
			if (limit > 0)
				return limit;
		}
		catch (NumberFormatException _)
		{
			// Refused below
		}
		throw new InvalidArgumentsException("--tool-output-token-limit must be a positive whole number: " + value);
	}

	/**
	 * A launch's options.
	 *
	 * @param request      the request's manifest, naming the files and directories that the launch names
	 * @param project      the project that the launch names, or the working directory if it names none; the request
	 *                     names it too until it is packed, which names the project's place in the request instead
	 * @param records      the file of {@code --input}, or null
	 * @param conversation the folder of {@code --conversation}, or null
	 * @param transcript   the file of {@code --output}, or null
	 * @param receipt      the file of {@code --receipt}, or null
	 * @param runRoot      the folder of {@code --results}, or null
	 * @param artifact     the folder of {@code --artifact}, or null
	 */
	private record Launch(ObjectNode request, Path project, Path records, Path conversation, Path transcript,
		Path receipt, Path runRoot, Path artifact)
	{
	}

	/**
	 * Returns the directory in which the nested session's runner answers a request.
	 *
	 * @param requests the run's requests directory
	 * @param id       the request identifier
	 * @return the directory
	 */
	private static Path responses(Path requests, String id)
	{
		return requests.toAbsolutePath().getParent().resolve("responses").resolve(id);
	}

	/**
	 * Publishes a request whose records come from a conversation and follows it: sends each of the conversation's
	 * messages as the request's next record and copies the requested session's records to the conversation and to the
	 * transcript, until the parent runner publishes its response. The sender's lock is held from before the request
	 * appears until this method returns, however it returns, so the parent runner stops a session whose requester
	 * ended. The conversation's last line names the response's status, or {@code failed} when the request cannot be
	 * followed to its response, and follows {@code afterResponse}, so that what that action leaves behind is in place
	 * when the asking session reads it.
	 *
	 * @param json       the manifest mapper
	 * @param request    the request's manifest, without its records
	 * @param requests   the run's requests directory
	 * @param id         the request identifier
	 * @param folder     the conversation's folder
	 * @param nullDevice the null device, which a sandbox mounts over each file that the session may not read
	 * @param limits     the bounds that the request's archive must stay within
	 * @param pause      paces checks for progress
	 * @param output     receives the transcript when {@code transcript} is null
	 * @param transcript the file that receives the transcript, or null
	 * @param afterResponse acts on the response before the conversation's last line is written
	 * @return the response
	 * @throws IllegalArgumentException if {@code folder} is not a new or empty folder
	 * @throws IOException              if the request cannot be published, a record cannot be sent, the wait fails, or
	 *                                  {@code afterResponse} fails
	 */
	static JsonNode converse(JsonMapper json, ObjectNode request, Path requests, String id, Path folder,
		Path nullDevice, RequestLimits limits, Pause pause, OutputStream output, Path transcript,
		ResponseAction afterResponse) throws IOException
	{
		try (Conversation messages = Conversation.open(folder);
		     StreamedRequest.Sender sender = new StreamedRequest.Sender(requests.toAbsolutePath(), id))
		{
			// However the request ends, the asking session learns that no further turn_end comes
			String status = "failed";
			try
			{
				request.put(StreamedRequest.INPUT_FIELD, StreamedRequest.CONVERSATION);
				RequestArchive.pack(json, requests.toAbsolutePath().resolve(id + RequestArchive.EXTENSION), request,
					List.of(), nullDevice, limits);
				Pause sending = () ->
				{
					for (Optional<String> next = messages.poll(); next.isPresent(); next = messages.poll())
						sender.send(next.get());
					pause.pause();
				};
				JsonNode response = awaitTranscript(json, responses(requests, id), sending, output, transcript,
					messages::childOutput);
				status = response.path("status").asString(status);
				afterResponse.accept(response);
				return response;
			}
			finally
			{
				messages.end(status);
			}
		}
	}

	/**
	 * An action on a request's response.
	 */
	@FunctionalInterface
	interface ResponseAction
	{
		/**
		 * Acts on the response.
		 *
		 * @param response the response
		 * @throws IOException if the action fails
		 */
		void accept(JsonNode response) throws IOException;
	}

	/**
	 * Copies the requested session's transcript to {@code --output}, or to {@code output}, until the parent runner
	 * publishes its response.
	 *
	 * @param json         the response mapper
	 * @param directory    the request's response directory
	 * @param pause        paces checks for progress
	 * @param output       receives the transcript when {@code transcript} is null
	 * @param transcript   the file that {@code --output} names, or null
	 * @param wrap         wraps the transcript's stream, so that a conversation also receives the records
	 * @return the response
	 * @throws IOException if the transcript or response cannot be read, or the output cannot be written
	 */
	private static JsonNode awaitTranscript(JsonMapper json, Path directory, Pause pause, OutputStream output,
		Path transcript, Function<OutputStream, OutputStream> wrap) throws IOException
	{
		if (transcript == null)
			return await(json, directory, pause, wrap.apply(output));
		Path parent = transcript.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		try (OutputStream file = Files.newOutputStream(transcript))
		{
			return await(json, directory, pause, wrap.apply(file));
		}
	}

	/**
	 * Reads the prompts of a launch's records.
	 *
	 * @param json    the record mapper
	 * @param records the {@code turn} records followed by {@code complete}
	 * @return the prompts in order
	 * @throws IOException if a record is malformed or of another type, no turn precedes {@code complete}, or the
	 *                     records end without it
	 */
	private static List<String> prompts(JsonMapper json, Reader records) throws IOException
	{
		List<String> prompts = new ArrayList<>();
		BufferedReader lines = new BufferedReader(records);
		for (String line = lines.readLine(); line != null; line = lines.readLine())
		{
			if (line.isBlank())
				continue;
			JsonNode record;
			try
			{
				record = json.readTree(line);
			}
			catch (JacksonException e)
			{
				throw new IOException("Malformed record: " + line, e);
			}
			String type = record.path("type").asString();
			if (type.equals("complete"))
			{
				if (prompts.isEmpty())
					throw new IOException("A launch needs a turn record before complete");
				return prompts;
			}
			if (!type.equals("turn") || !record.path("prompt").isString())
			{
				throw new IOException("Inside a nested session, a launch accepts only turn records with a prompt, followed " +
					"by complete: " + line);
			}
			prompts.add(record.path("prompt").asString());
		}
		throw new IOException("The records ended without a complete record");
	}

	/**
	 * Copies the requested session's transcript as it grows, until the parent runner publishes its response.
	 *
	 * @param json      the response mapper
	 * @param directory the request's response directory
	 * @param pause     paces checks for progress
	 * @param output    receives the transcript
	 * @return the response
	 * @throws IOException if the transcript or response cannot be read, or the output cannot be written
	 */
	private static JsonNode await(JsonMapper json, Path directory, Pause pause, OutputStream output)
		throws IOException
	{
		Path transcript = directory.resolve("transcript.jsonl");
		Path response = directory.resolve("response.json");
		long copied = 0;
		while (true)
		{
			// The parent publishes the response after the session's last record, so a final copy after it appears
			// returns the whole transcript.
			boolean ended = Files.isRegularFile(response);
			if (Files.isRegularFile(transcript))
			{
				try (InputStream bytes = Files.newInputStream(transcript))
				{
					bytes.skipNBytes(copied);
					copied += bytes.transferTo(output);
				}
				output.flush();
			}
			if (ended)
				return json.readTree(response.toFile());
			pause.pause();
		}
	}

	/**
	 * Copies the requested session's records, one per line, to a served command's output, creating the
	 * {@code artifacts} directory of the session's run below the command's results folder once the session reports its
	 * start, and replacing its project, initial project and native transcript copies with the session's, which the runner
	 * copied, before the command reads each record that reports the end of a completed turn.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	private static final class RecordCopy extends OutputStream
	{
		/**
		 * The record mapper.
		 */
		private final JsonMapper json;
		/**
		 * Receives the records.
		 */
		private final Writer output;
		/**
		 * The folder of the command's runs of the session's harness.
		 */
		private final Path runs;
		/**
		 * The runner's copy of the session's project, initial project and native transcripts as of its last completed turn.
		 */
		private final Path turnCopy;
		/**
		 * The bytes of a record whose line terminator has not arrived.
		 */
		private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
		/**
		 * The identifier that the harness gave the session, or an empty string until the runner reports it.
		 */
		private String sessionId = "";
		/**
		 * Indicates whether the run holds a copy of turn evidence.
		 */
		private boolean turnCopied;

		/**
		 * Creates a copy.
		 *
		 * @param json     the record mapper
		 * @param output   receives the records
		 * @param runs     the folder of the command's runs of the session's harness
		 * @param turnCopy the runner's copy of the session's project, initial project and native transcripts as of the
		 *                 end of its last completed turn, which exists once a turn has completed
		 */
		RecordCopy(JsonMapper json, Writer output, Path runs, Path turnCopy)
		{
			this.json = json;
			this.output = output;
			this.runs = runs;
			this.turnCopy = turnCopy;
		}

		/**
		 * Removes the turn evidence that copies of turns left in the session's run.
		 *
		 * @throws IOException if they cannot be removed
		 */
		void deleteTurnCopies() throws IOException
		{
			if (turnCopied)
				KeptRunCopy.deleteTurnCopies(runs.resolve(sessionId));
			turnCopied = false;
		}

		/**
		 * Indicates whether a text can name the directory of a session's results.
		 *
		 * @param sessionId the text
		 * @return {@code true} if it is a session identifier, which names no other directory
		 */
		static boolean isSessionId(String sessionId)
		{
			return sessionId.matches("[A-Za-z0-9._-]+") && !sessionId.startsWith(".");
		}

		@Override
		public void write(int value) throws IOException
		{
			if (value != '\n')
			{
				partial.write(value);
				return;
			}
			String line = partial.toString(StandardCharsets.UTF_8);
			partial.reset();
			if (line.contains("\"session_id\"") || line.contains("\"turn_completed\""))
			{
				try
				{
					JsonNode record = json.readTree(line);
					String type = record.path("type").asString("");
					String id = record.path("session_id").asString("");
					if (type.equals("session_id") && sessionId.isEmpty() && isSessionId(id))
					{
						// The command writes into the session's results while the session runs
						sessionId = id;
						Files.createDirectories(runs.resolve(sessionId).resolve("artifacts"));
					}
					else if (type.equals("turn_completed") && !sessionId.isEmpty() && Files.isDirectory(turnCopy))
					{
						KeptRunCopy.copyTurn(turnCopy, runs.resolve(sessionId));
						turnCopied = true;
					}
				}
				catch (JacksonException _)
				{
					// The command reports the malformed record when it reads it
				}
			}
			output.write(line);
			output.write('\n');
		}

		@Override
		public void flush() throws IOException
		{
			output.flush();
		}
	}

	/**
	 * Prevents construction.
	 */
	private SessionRequestForwarder()
	{
	}
}
