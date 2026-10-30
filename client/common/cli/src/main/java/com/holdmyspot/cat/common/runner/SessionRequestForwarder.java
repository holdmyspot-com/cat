package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
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
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Hands a runner launch made inside a run to the run's own runner, which starts the session outside the run's sandbox.
 * <p>
 * A session's commands cannot start a harness: they run in a sandbox and hold no login. When its runner lets it
 * request sessions, a runner launcher that the session runs packs the launch as a session request instead, waits for
 * the parent runner to finish it, and returns the requested session's transcript and receipt as its own. The parent
 * chooses the run root, the artifact, the denied reads and the limits of the sessions it requests, so a launch may not
 * choose them.
 */
public final class SessionRequestForwarder
{
	/**
	 * Options that the parent runner chooses for the sessions it requests.
	 */
	private static final Set<String> PARENT_OPTIONS = Set.of("--deny-read", "--workspace", "--session-requests",
		"--request-depth", "--max-concurrency", "--capacity-wait", "--exclude-user-rules", "--agent",
		"--instruction-files", "--read-only-evidence", "--tool-output-token-limit");
	/**
	 * Options that a launch may name although the parent runner chooses their effect: the parent keeps every requested
	 * run and chooses its run root, artifact and harness executable.
	 */
	private static final Set<String> IGNORED_OPTIONS = Set.of("--artifact", "--run-root", "--executable");

	/**
	 * Forwards one launch and waits for its requested session to end.
	 *
	 * @param harness  the harness of the runner launcher
	 * @param args     the runner launcher's options
	 * @param input    the launch's {@code turn} and {@code complete} records when {@code --input} is absent
	 * @param requests the run's requests directory
	 * @param pause    paces checks for the session's progress
	 * @param output   receives the session's transcript when {@code --output} is absent
	 * @param errors   receives the reason when the session does not complete
	 * @return {@code 0} if the session completed, {@code 75} if it found no capacity, or {@code 1} otherwise
	 * @throws IllegalArgumentException if an option is unknown, incomplete, repeated or chosen by the parent runner, or
	 *                                  if the launch names no {@code --model} or no {@code --effort}
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
		Path transcript = null;
		Path receipt = null;
		Set<String> scalars = new HashSet<>();
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			if (PARENT_OPTIONS.contains(option))
			{
				throw new IllegalArgumentException("Inside a run, the parent runner chooses " + option +
					" for the sessions it starts");
			}
			if (!option.equals("--plugin") && !scalars.add(option))
				throw new IllegalArgumentException("Repeated option: " + option);
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
			if (index + 1 == args.length)
				throw new IllegalArgumentException("Missing value for " + option);
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
				case "--output" -> transcript = Path.of(value);
				case "--receipt" -> receipt = Path.of(value);
				default -> throw new IllegalArgumentException("Unsupported option: " + option);
			}
		}
		HarnessRunnerOptions.requireProfile(harness, request.path("model").asString(""),
			request.path("effort").asString(""));
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

		String id = UUID.randomUUID().toString();
		RequestArchive.pack(json, requests.toAbsolutePath().resolve(id + RequestArchive.EXTENSION), request, prompts,
			RequestArchive.NULL_DEVICE, RequestLimits.DEFAULT);
		Path directory = requests.toAbsolutePath().getParent().resolve("responses").resolve(id);
		JsonNode response;
		if (transcript == null)
			response = await(json, directory, pause, output);
		else
		{
			Path parent = transcript.toAbsolutePath().getParent();
			Files.createDirectories(parent);
			try (OutputStream file = Files.newOutputStream(transcript))
			{
				response = await(json, directory, pause, file);
			}
		}
		Path childReceipt = directory.resolve("receipt.json");
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
		return 1;
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
