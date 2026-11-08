package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * Hands a runner launch made inside a run to the run's own runner, which starts the session outside the run's sandbox.
 * <p>
 * A session's commands cannot start a harness: they run in a sandbox and hold no login. Every session that a runner
 * starts has {@link SessionRequestAccess}, so a runner launcher that the session runs packs the launch as a session
 * request instead, waits for the parent runner to finish it, and returns the requested session's transcript and receipt
 * as its own. With {@code --conversation}, the launch publishes the request without its records and sends each of the
 * conversation's messages as the request's next record while the session runs. When the parent's run may request no
 * further session, the parent refuses the request and the launch exits with status 2 and the reason. The parent
 * chooses the run root, the artifact, the denied reads and the limits of the sessions it requests, so a launch may not
 * choose them.
 */
public final class SessionRequestForwarder
{
	/**
	 * Options that the parent runner chooses for the sessions it requests.
	 */
	private static final Set<String> PARENT_OPTIONS = Set.of("--deny-read", "--workspace", "--session-requests",
		"--request-depth", "--max-concurrency", "--capacity-wait", "--agent", "--instruction-files",
		"--read-only-evidence", "--tool-output-token-limit");
	/**
	 * Options that a launch may name although the parent runner chooses their effect: the parent keeps every requested
	 * run and chooses its run root and artifact.
	 */
	private static final Set<String> IGNORED_OPTIONS = Set.of("--artifact", "--run-root");
	/**
	 * The options that take a value and that a launch may name.
	 */
	private static final Set<String> VALUE_OPTIONS = Set.of("--artifact", "--run-root", "--project", "--plugin",
		"--model", "--effort", "--input", "--output", "--receipt", ExtraPermissions.OPTION, Conversation.OPTION);

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
	 * @throws IOException              if the records are invalid, or the request or response cannot be read or
	 *                                  written
	 */
	public static int forward(String harness, String[] args, Reader input, Path requests, Pause pause,
		OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(requests, "requests").isDirectory();
		requireThat(pause, "pause").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(errors, "errors").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		ObjectNode request = json.createObjectNode().put("format_version", RequestArchive.VERSION).
			put("harness", harness).put("project", Path.of("").toAbsolutePath().toString());
		request.putArray("prompts");
		request.putArray("plugins");
		Path records = null;
		Path conversation = null;
		Path transcript = null;
		Path receipt = null;
		Set<String> scalars = new HashSet<>();
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			if (PARENT_OPTIONS.contains(option))
			{
				throw new InvalidArgumentsException("Inside a run, the parent runner chooses " + option +
					" for the sessions it starts");
			}
			if (!option.equals("--plugin") && !scalars.add(option))
				throw new InvalidArgumentsException("Repeated option: " + option);
			switch (option)
			{
				case "--keep-run" ->
				{
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
			if (IGNORED_OPTIONS.contains(option))
				continue;
			switch (option)
			{
				case "--project" -> request.put("project", Path.of(value).toAbsolutePath().toString());
				case "--plugin" -> request.withArray("plugins").add(Path.of(value).toAbsolutePath().toString());
				case "--model", "--effort" -> request.put(option.substring(2), value);
				case "--input" -> records = Path.of(value);
				case Conversation.OPTION -> conversation = Path.of(value).toAbsolutePath();
				case "--output" -> transcript = Path.of(value);
				case "--receipt" -> receipt = Path.of(value);
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
		String id = UUID.randomUUID().toString();
		JsonNode response;
		if (conversation == null)
		{
			List<String> prompts;
			if (records == null)
				prompts = prompts(json, input);
			else
			{
				try (Reader file = Files.newBufferedReader(records, StandardCharsets.UTF_8))
				{
					prompts = prompts(json, file);
				}
			}
			RequestArchive.pack(json, requests.toAbsolutePath().resolve(id + RequestArchive.EXTENSION), request,
				prompts, RequestArchive.NULL_DEVICE, RequestLimits.IN_RUN);
			response = awaitTranscript(json, responses(requests, id), pause, output, transcript, Function.identity());
		}
		else
		{
			response = converse(json, request, requests, id, conversation, RequestArchive.NULL_DEVICE,
				RequestLimits.IN_RUN, pause, output, transcript);
		}
		Path childReceipt = responses(requests, id).resolve("receipt.json");
		if (receipt != null && Files.isRegularFile(childReceipt))
		{
			Files.createDirectories(receipt.toAbsolutePath().getParent());
			Files.copy(childReceipt, receipt, StandardCopyOption.REPLACE_EXISTING);
		}
		String status = response.path("status").asString();
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
	 * ended.
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
			request.put(StreamedRequest.INPUT_FIELD, StreamedRequest.STREAM);
			RequestArchive.pack(json, requests.toAbsolutePath().resolve(id + RequestArchive.EXTENSION), request,
				List.of(), nullDevice, limits);
			Pause sending = () ->
			{
				for (Optional<String> next = messages.poll(); next.isPresent(); next = messages.poll())
					sender.send(next.get());
				pause.pause();
			};
			return awaitTranscript(json, responses(requests, id), sending, output, transcript, messages::childOutput);
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
	 * Prevents construction.
	 */
	private SessionRequestForwarder()
	{
	}
}
