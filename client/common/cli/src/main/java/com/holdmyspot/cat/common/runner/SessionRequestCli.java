package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Pause;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Submits a file-based session request and waits for its response, or resumes waiting for an earlier request.
 */
public final class SessionRequestCli
{
	/**
	 * Runs the request launcher using the directories supplied by the owning runner.
	 *
	 * @param args submission options or {@code --await <id>}, with optional {@code --wait <seconds>}
	 * @throws IOException if the request or response cannot be read or written
	 */
	public static void main(String[] args) throws IOException
	{
		args = CommandLineArguments.recover(args);
		if (Arrays.equals(args, new String[]{"--help"}))
		{
			System.out.println("Usage: request-session --harness <name> --project <dir> --prompt <file> " +
				"[--prompt <file>]... [--plugin <dir>]... --model <name> --effort <level> " +
				"[--install-cat] [--session-transcripts] [--wait <seconds>] | --await <id> [--wait <seconds>]");
			return;
		}
		String directory = System.getenv(SessionRequestAccess.REQUESTS_VARIABLE);
		if (directory == null || directory.isBlank())
			throw new IOException("Session requests are unavailable; start the runner with --session-requests <count>");
		int status = execute(args, Path.of(directory), Clock.systemUTC(), Pause.sleeping(Duration.ofMillis(100)),
			new PrintWriter(System.out, true, StandardCharsets.UTF_8));
		System.exit(status);
	}

	/**
	 * Writes a request atomically and waits, or waits again for an existing request. On timeout prints the id and the
	 * exact follow-up command. Response paths are printed even for unsuccessful requested sessions.
	 *
	 * @param args     submission options or await options
	 * @param requests the owning run's requests directory
	 * @param clock    measures the wait bound
	 * @param pause    paces response checks
	 * @param output   receives response paths or timeout instructions
	 * @return 0 for completed, 1 for another response status, or 3 after the wait bound (default 300 seconds)
	 * @throws IllegalArgumentException if options are incomplete, unknown, repeated, or invalid
	 * @throws IOException              if request or response I/O fails
	 */
	public static int execute(String[] args, Path requests, Clock clock, Pause pause, PrintWriter output)
		throws IOException
	{
		return execute(args, requests, RequestArchive.NULL_DEVICE, RequestLimits.DEFAULT, clock, pause, output);
	}

	/**
	 * Writes a request atomically and waits, or waits again for an existing request. On timeout prints the id and the
	 * exact follow-up command. Response paths are printed even for unsuccessful requested sessions.
	 *
	 * @param args       submission options or await options
	 * @param requests   the owning run's requests directory
	 * @param nullDevice the null device, which a sandbox mounts over each file that the session may not read; the
	 *                   request leaves those files out of the trees that it carries
	 * @param limits     the bounds that the request's archive must stay within
	 * @param clock      measures the wait bound
	 * @param pause      paces response checks
	 * @param output     receives response paths or timeout instructions
	 * @return 0 for completed, 1 for another response status, or 3 after the wait bound (default 300 seconds)
	 * @throws IllegalArgumentException if options are incomplete, unknown, repeated, or invalid
	 * @throws IOException              if request or response I/O fails, or the request exceeds {@code limits}
	 */
	public static int execute(String[] args, Path requests, Path nullDevice, RequestLimits limits, Clock clock,
		Pause pause, PrintWriter output) throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(requests, "requests").isDirectory();
		requireThat(nullDevice, "nullDevice").isNotNull();
		requireThat(limits, "limits").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(pause, "pause").isNotNull();
		requireThat(output, "output").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		ObjectNode request = json.createObjectNode().put("format_version", RequestArchive.VERSION);
		request.putArray("prompts");
		request.putArray("plugins");
		String id = "";
		Duration wait = Duration.ofSeconds(300);
		Set<String> scalars = new HashSet<>();
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			if (option.equals("--session-transcripts") || option.equals("--install-cat"))
			{
				request.put(option.substring(2).replace('-', '_'), true);
				continue;
			}
			if (index + 1 == args.length)
				throw new IllegalArgumentException("Missing value for " + option);
			String value = args[index + 1];
			index += 1;
			if (option.equals("--prompt") || option.equals("--plugin"))
			{
				String field = "prompts";
				if (option.equals("--plugin"))
					field = "plugins";
				request.withArray(field).add(Path.of(value).toAbsolutePath().toString());
				continue;
			}
			if (!scalars.add(option))
				throw new IllegalArgumentException("Repeated option: " + option);
			switch (option)
			{
				case "--await" -> id = value;
				case "--wait" ->
				{
					wait = Duration.ofSeconds(Long.parseLong(value));
					requireThat(wait, "wait").isGreaterThan(Duration.ZERO);
				}
				case "--project" -> request.put("project", Path.of(value).toAbsolutePath().toString());
				case "--harness", "--model", "--effort" -> request.put(option.substring(2), value);
				default -> throw new IllegalArgumentException("Unsupported option: " + option);
			}
		}
		if (id.isEmpty())
		{
			if (!request.hasNonNull("project") || !request.hasNonNull("harness") || request.path("prompts").isEmpty())
				throw new IllegalArgumentException("A request requires --harness, --project and at least one --prompt");
			HarnessRunnerOptions.requireProfile(request.path("harness").asString(), request.path("model").asString(""),
				request.path("effort").asString(""));
			id = UUID.randomUUID().toString();
			// The broker has the user's permissions while this sandboxed session keeps running. If it walked our
			// directories, we could swap a link or junction into its path and make it read a file we may not read.
			// Packing here makes the sandbox refuse that read; the broker opens only one file in requests, with no
			// session-controlled directory along its path.
			RequestArchive.pack(json, requests.toAbsolutePath().resolve(id + RequestArchive.EXTENSION), request,
				List.of(), nullDevice, limits);
		}
		else
		{
			if (!Set.of("--await", "--wait").containsAll(scalars) || !request.path("prompts").isEmpty() ||
				!request.path("plugins").isEmpty() || request.has("session_transcripts") || request.has("install_cat"))
				throw new IllegalArgumentException("--await accepts only --wait");
			validateId(id);
		}
		Path response = requests.toAbsolutePath().getParent().resolve("responses").resolve(id).resolve("response.json");
		Instant deadline = clock.instant().plus(wait);
		while (true)
		{
			if (Files.isRegularFile(response))
			{
				JsonNode result = json.readTree(response.toFile());
				output.println(response);
				if (result.path("status").asString().equals("completed"))
					return 0;
				return 1;
			}
			if (!clock.instant().isBefore(deadline))
			{
				output.println("id=" + id);
				output.println("\"$CAT_REQUEST_SESSION\" --await " + id);
				return 3;
			}
			pause.pause();
		}
	}

	/**
	 * Rejects identifiers that could escape a response directory.
	 *
	 * @param id the generated request identifier
	 * @throws IllegalArgumentException if the id is not a safe filename component
	 */
	static void validateId(String id)
	{
		requireThat(id, "id").matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}");
	}

	/**
	 * Publishes one document with an atomic same-directory move. Removes an unpublished temporary file on failure.
	 *
	 * @param json        the document mapper
	 * @param destination the final path
	 * @param value       the document
	 * @throws IOException if serialization or the atomic move fails
	 */
	static void write(JsonMapper json, Path destination, ObjectNode value) throws IOException
	{
		Path temporary = Files.createTempFile(destination.getParent(), ".request-", ".json");
		try
		{
			json.writeValue(temporary.toFile(), value);
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Prevents construction.
	 */
	private SessionRequestCli()
	{
	}
}
