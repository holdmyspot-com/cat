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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Hands a runner launch to the runner that serves the session's requests outside its sandbox: inside a run, the run's
 * own runner, and outside a run, the session's request server.
 * <p>
 * A session's commands cannot start a sandboxed harness: they run in a sandbox, inside which no harness sandbox can
 * start. A runner launcher that the session runs packs the launch as a session request instead, waits for the runner
 * that serves it to finish it, and returns the requested session's transcript and receipt as its own. With
 * {@code --conversation}, the launch publishes the request without its records and sends each of the conversation's
 * messages as the request's next record while the session runs. When the parent's run may request no further
 * session, the parent refuses the request and the launch exits with status 2 and the reason. Inside a run, the parent
 * chooses the run root, the artifact, its own denied reads and the limits of the sessions it requests, so a launch may
 * not choose them; outside a run, a launch chooses the limits of the session it requests, and its kept run is copied
 * into its run root. A launch may name the options that only narrow the requested session or describe its own inputs:
 * further denied reads, the workspace files, the read-only evidence, the tool-output token limit, the instruction
 * files and {@code --keep-run}. The files and directories that it names travel inside the request, so each must be one
 * that the session's sandbox lets it read.
 */
public final class SessionRequestForwarder
{
	/**
	 * How often a served session's sender checks for its records and for the run's response.
	 */
	public static final Duration RESPONSE_POLL = Duration.ofMillis(100);
	/**
	 * Options that the parent runner chooses for the sessions it requests inside a run, and their manifest fields.
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
		 * The run's runner, which chooses the limits of the sessions that its run's session requests.
		 */
		RUN,
		/**
		 * The session's request server, outside a run, which applies the limits that the launch names.
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
	private static final Set<String> VALUE_OPTIONS = Set.of("--artifact", "--run-root", "--project", "--plugin",
		"--model", "--effort", "--input", "--output", "--receipt", ExtraPermissions.OPTION, Conversation.OPTION,
		"--deny-read", "--workspace", "--read-only-evidence", "--instruction-files", "--session-role",
		"--tool-output-token-limit");

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
	 * Forwards one launch and waits for its requested session to end. Outside a run, the launch's kept run is copied
	 * into its run root as {@code <run root>/<harness>/<run id>}, and the response is removed once the launch has
	 * copied what it needs.
	 *
	 * @param route          the runner that serves the request
	 * @param harness        the harness of the runner launcher
	 * @param args           the runner launcher's options
	 * @param input          the launch's {@code turn} and {@code complete} records when neither {@code --input} nor
	 *                       {@code --conversation} is given
	 * @param requests       the directory into which the launch writes its request
	 * @param defaultRunRoot the run root of a launch that names no {@code --run-root} outside a run, or {@code null}
	 *                       inside one
	 * @param pause          paces checks for the session's progress
	 * @param output         receives the session's transcript when {@code --output} is absent
	 * @param errors         receives the reason when the session does not complete
	 * @return {@code 0} if the session completed, {@code 75} if it found no capacity, {@code 2} if the session was
	 *         refused, or {@code 1} otherwise
	 * @throws NullPointerException     if any argument other than {@code defaultRunRoot} is null, or if
	 *                                  {@code defaultRunRoot} is null outside a run
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or, inside a run, chosen by the
	 *                                  parent runner, if {@code --input} and {@code --conversation} are both given,
	 *                                  or if the launch names no {@code --model} or no {@code --effort}
	 * @throws IOException              if the records are invalid, a file or directory that the launch names cannot be
	 *                                  read, the request or response cannot be read or written, or the kept run
	 *                                  cannot be copied
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
			RequestArchive.pack(json, requests.toAbsolutePath().resolve(id + RequestArchive.EXTENSION), request,
				prompts, RequestArchive.NULL_DEVICE, route.limits());
			response = awaitTranscript(json, responses(requests, id), pause, output, launch.transcript(),
				Function.identity());
		}
		else
		{
			response = converse(json, request, requests, id, launch.conversation(), RequestArchive.NULL_DEVICE,
				route.limits(), pause, output, launch.transcript());
		}
		Path childReceipt = responses(requests, id).resolve("receipt.json");
		if (launch.receipt() != null && Files.isRegularFile(childReceipt))
		{
			Files.createDirectories(launch.receipt().toAbsolutePath().getParent());
			Files.copy(childReceipt, launch.receipt(), StandardCopyOption.REPLACE_EXISTING);
		}
		String status = response.path("status").asString();
		if (route == Route.SERVER)
		{
			Path runRoot = launch.runRoot();
			if (runRoot == null)
				runRoot = defaultRunRoot;
			Optional<String> incomplete = copyKeptRun(response, runRoot.resolve(harness), "");
			TemporaryDirectories.deleteRecursively(responses(requests, id));
			if (incomplete.isPresent() && status.equals("completed"))
			{
				errors.println(incomplete.get());
				return 1;
			}
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
	 * Copies the kept run that a response names into a harness's runs. The session ran in a slot, which every session
	 * of the slot shares, so the copy gets an identifier of its own, which no other kept run has.
	 *
	 * @param response the response
	 * @param runs     the directory that holds the harness's runs in the launch's run root
	 * @param keptId   the identifier of the kept run that the command has been told of, or an empty string to give it
	 *                 a new one
	 * @return the reason why the copy is incomplete, or empty if the response names no kept run or the copy is
	 *   complete
	 * @throws IOException if the run cannot be copied
	 */
	private static Optional<String> copyKeptRun(JsonNode response, Path runs, String keptId)
		throws IOException
	{
		String run = response.path(SessionRequestBroker.RUN_COPY).asString("");
		String runId = response.path("child_run_id").asString("");
		if (run.isEmpty() || !RecordCopy.isRunId(runId))
			return Optional.empty();
		String kept = keptId;
		if (kept.isEmpty())
			kept = HarnessRunManager.newRunId(Clock.systemUTC());
		Optional<String> incomplete = KeptRunCopy.copy(Path.of(run), runs.resolve(kept)).incomplete();
		HarnessRunManager.renameCopy(runs.resolve(kept), kept);
		return incomplete;
	}

	/**
	 * Serves one session of a CAT command, such as the prompt optimizer, through the run's runner: streams the
	 * command's records to the requested session as it writes them, copies the session's records back as they arrive,
	 * and copies the session's kept run, which the run's runner names in its response, into the launch's
	 * {@code --run-root} as {@code <run root>/<harness>/<run id>}. The command creates that run's {@code artifacts}
	 * directory once the session reports its start, so that what the command writes into the run while it runs has a
	 * place. Before the command reads the record that reports the end of a completed turn, that run holds the
	 * session's {@code project} and {@code initial-project} as the turn left them, so that the command can read what
	 * the session has written so far, as it does in a session that it runs directly. A run that allows no further
	 * session refuses the session before it publishes anything. The run's runner installs its own CAT for
	 * {@code --install-cat}, so a launch that names another CAT as {@code --artifact} sends that CAT as a plugin, as a
	 * launch that goes to a request server does.
	 *
	 * @param command     names the command in a refusal, such as {@code "The prompt optimizer"}
	 * @param harness     the harness of the session
	 * @param ownArtifact the CAT artifact that the command runs from
	 * @param arguments   the runner's options, which name {@code --run-root} and {@code --keep-run}
	 * @param input       the {@code turn}, {@code interrupt} and {@code complete} records, one per line
	 * @param output      receives the session's records, one per line
	 * @param requests    the run's requests directory
	 * @param pause       paces checks for the session's progress, and throws once the calling thread is
	 *                    interrupted, which ends the request and stops the session
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or chosen by the parent runner, if
	 *                                  the launch names {@code --input} or {@code --conversation}, or no
	 *                                  {@code --run-root}, {@code --keep-run}, {@code --model} or {@code --effort}
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
	 * {@link #serve(String, String, Path, List, Reader, Writer, Path, Pause)} does inside a run. Outside a run, the
	 * response is removed once the command has copied the kept run.
	 *
	 * @param route       the runner that serves the request
	 * @param command     names the command in a refusal, such as {@code "The prompt optimizer"}
	 * @param harness     the harness of the session
	 * @param ownArtifact the CAT artifact that the command runs from
	 * @param arguments   the runner's options, which name {@code --run-root} and {@code --keep-run}
	 * @param input       the {@code turn}, {@code interrupt} and {@code complete} records, one per line
	 * @param output      receives the session's records, one per line
	 * @param requests    the directory into which the command writes its request
	 * @param pause       paces checks for the session's progress, and throws once the calling thread is
	 *                    interrupted, which ends the request and stops the session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or, inside a run, chosen by the
	 *                                  parent runner, if the launch names {@code --input} or {@code --conversation},
	 *                                  or no {@code --run-root}, {@code --keep-run}, {@code --model} or
	 *                                  {@code --effort}
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
		if (launch.runRoot() == null || !launch.keepRun())
			throw new InvalidArgumentsException("A served session names --run-root and --keep-run");
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
		String keptId;
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
			keptId = copy.keptRunId();
		}
		String status = response.path("status").asString("");
		String reason = response.path("reason").asString("");
		boolean kept = !response.path(SessionRequestBroker.RUN_COPY).asString("").isEmpty() &&
			RecordCopy.isRunId(response.path("child_run_id").asString(""));
		Optional<String> incomplete = copyKeptRun(response, launch.runRoot().resolve(harness), keptId);
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
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or, inside a run, chosen by the
	 *                                  parent runner, if
	 *                                  {@code --input} and {@code --conversation} are both given, or if the launch
	 *                                  names no {@code --model} or no {@code --effort}
	 * @throws IOException              if the file of {@code --extra-permissions} cannot be read
	 */
	private static Launch parse(JsonMapper json, Route route, String harness, String[] args) throws IOException
	{
		ObjectNode request = json.createObjectNode().put("format_version", RequestArchive.VERSION).
			put("harness", harness).put("project", Path.of("").toAbsolutePath().toString());
		request.putArray("prompts");
		request.putArray("plugins");
		Path records = null;
		Path conversation = null;
		Path transcript = null;
		Path receipt = null;
		Path runRoot = null;
		Path artifact = null;
		boolean keepRun = false;
		Set<String> scalars = new HashSet<>();
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			if (PARENT_OPTIONS.containsKey(option) && route == Route.RUN)
			{
				throw new InvalidArgumentsException("Inside a run, the parent runner chooses " + option +
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
				case "--keep-run" ->
				{
					keepRun = true;
					request.put("keep_run", true);
					continue;
				}
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
				// The parent runner chooses the run root; a served session's kept run is copied below it
				case "--run-root" -> runRoot = Path.of(value).toAbsolutePath();
				case "--project" -> request.put("project", Path.of(value).toAbsolutePath().toString());
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
		HarnessRunnerOptions.requireProfile(harness, request.path("model").asString(""),
			request.path("effort").asString(""));
		return new Launch(request, records, conversation, transcript, receipt, runRoot, artifact, keepRun);
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
	 * Reads the value of a limit that a launch outside a run names.
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
	 * @param records      the file of {@code --input}, or null
	 * @param conversation the folder of {@code --conversation}, or null
	 * @param transcript   the file of {@code --output}, or null
	 * @param receipt      the file of {@code --receipt}, or null
	 * @param runRoot      the folder of {@code --run-root}, or null
	 * @param artifact     the folder of {@code --artifact}, or null
	 * @param keepRun      indicates whether the launch names {@code --keep-run}
	 */
	private record Launch(ObjectNode request, Path records, Path conversation, Path transcript, Path receipt,
		Path runRoot, Path artifact, boolean keepRun)
	{
	}

	/**
	 * Returns the directory in which the run's runner answers a request.
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
	 * followed to its response.
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
	 * @return the response
	 * @throws IllegalArgumentException if {@code folder} is not a new or empty folder
	 * @throws IOException              if the request cannot be published, a record cannot be sent, or the wait fails
	 */
	static JsonNode converse(JsonMapper json, ObjectNode request, Path requests, String id, Path folder,
		Path nullDevice, RequestLimits limits, Pause pause, OutputStream output, Path transcript) throws IOException
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
				return response;
			}
			finally
			{
				messages.end(status);
			}
		}
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
				throw new IOException("Inside a run, a launch accepts only turn records with a prompt, followed by " +
					"complete: " + line);
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
	 * {@code artifacts} directory of the session's run below the command's run root once the session reports its
	 * start, and replacing the run's project and initial project there with the session's, which the runner copied,
	 * before the command reads each record that reports the end of a completed turn.
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
		 * The runner's copy of the session's project and initial project as of the end of its last completed turn.
		 */
		private final Path turnCopy;
		/**
		 * The bytes of a record whose line terminator has not arrived.
		 */
		private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
		/**
		 * The identifier of the session's run, or an empty string until the session reports its start.
		 */
		private String runId = "";
		/**
		 * Indicates whether the run holds a copy of a turn's project.
		 */
		private boolean turnCopied;

		/**
		 * Creates a copy.
		 *
		 * @param json     the record mapper
		 * @param output   receives the records
		 * @param runs     the folder of the command's runs of the session's harness
		 * @param turnCopy the runner's copy of the session's project and initial project as of the end of its last
		 *                 completed turn, which exists once a turn has completed
		 */
		RecordCopy(JsonMapper json, Writer output, Path runs, Path turnCopy)
		{
			this.json = json;
			this.output = output;
			this.runs = runs;
			this.turnCopy = turnCopy;
		}

		/**
		 * Removes the project and initial project that the copies of the turns left in the session's run.
		 *
		 * @throws IOException if they cannot be removed
		 */
		void deleteTurnCopies() throws IOException
		{
			if (turnCopied)
				KeptRunCopy.deleteProjects(runs.resolve(runId));
			turnCopied = false;
		}

		/**
		 * Returns the identifier under which the command knows the session's kept run.
		 *
		 * @return the identifier, or an empty string until the session reports its start
		 */
		String keptRunId()
		{
			return runId;
		}

		/**
		 * Indicates whether a text can name a run's directory.
		 *
		 * @param runId the text
		 * @return {@code true} if it is a run identifier, which names no other directory
		 */
		static boolean isRunId(String runId)
		{
			return runId.matches("[A-Za-z0-9._-]+") && !runId.startsWith(".");
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
			if (line.contains("\"started\"") || line.contains("\"turn_completed\""))
			{
				try
				{
					JsonNode record = json.readTree(line);
					String type = record.path("type").asString("");
					String id = record.path("run_id").asString("");
					if (type.equals("started") && isRunId(id))
					{
						// Every session of a slot runs under the slot's identifier, but each kept run needs its own
						runId = HarnessRunManager.newRunId(Clock.systemUTC());
						((ObjectNode) record).put("run_id", runId);
						line = json.writeValueAsString(record);
						Files.createDirectories(runs.resolve(runId).resolve("artifacts"));
					}
					else if (type.equals("turn_completed") && !runId.isEmpty() && Files.isDirectory(turnCopy))
					{
						KeptRunCopy.copyProjects(turnCopy, runs.resolve(runId));
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
