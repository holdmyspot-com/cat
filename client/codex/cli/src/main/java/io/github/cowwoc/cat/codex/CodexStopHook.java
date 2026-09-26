package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.usage.UsageReportOutbox;
import io.github.cowwoc.cat.common.usage.WorkOsUsageDelivery;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

/**
 * Retains and delivers measured Codex usage when the host ends a turn.
 */
public final class CodexStopHook
{
	/**
	 * Processes the native Stop event and returns Codex's JSON hook response.
	 *
	 * @param args no command-line arguments are accepted
	 */
	public static void main(String[] args)
	{
		run(args, System.in, System.out, System.err, System.getenv(), HttpClient.newHttpClient(),
			WorkOsDeviceAuthClient.API_BASE);
	}

	/**
	 * Reads one Stop event, persists a measured report, and attempts delivery.
	 * A failed delivery leaves the report in the plugin's outbox for a later invocation.
	 *
	 * @param args no command-line arguments are accepted
	 * @param input native Stop event JSON
	 * @param output native JSON hook response
	 * @param error diagnostic output
	 * @param environment native Codex hook environment
	 * @param http client used for the host and WorkOS requests
	 * @param workOsApiBase WorkOS API origin
	 */
	static void run(String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, HttpClient http, URI workOsApiBase)
	{
		JsonMapper json = JsonMapper.builder().build();
		boolean enqueued = false;
		boolean deliveryAttempted = false;
		WorkOsUsageDelivery delivery = null;
		try (CodexHookScope scope = new CodexHookScope(environment))
		{
			json = scope.json();
			if (args == null || args.length != 0)
				throw new IllegalArgumentException("Codex Stop hook accepts no arguments");
			JsonNode event = CodexHookInput.read(json, input, "Stop");
			if (!"Stop".equals(requiredText(event, "hook_event_name")))
				throw new IOException("Codex hook event is not Stop");
			UUID sessionId = requiredId(event, "session_id");
			UUID turnId = requiredId(event, "turn_id");
			Path transcript = transcript(event, sessionId);
			Path dataDirectory = dataDirectory(environment);

			WorkOsSessionStore sessions = new WorkOsSessionStore(dataDirectory.resolve("auth"));
			WorkOsSessionStore.Session login = sessions.load().orElseThrow(() ->
				new IOException("No WorkOS login is saved; sign in before reporting usage"));
			UsageReportOutbox outbox = new UsageReportOutbox(dataDirectory.resolve("outbox"));
			delivery = new WorkOsUsageDelivery(http, workOsApiBase, sessions, outbox);
			Optional<CodexRolloutUsageReader.TurnUsage> measurement;
			try (InputStream rollout = Files.newInputStream(transcript, READ, NOFOLLOW_LINKS))
			{
				measurement = CodexRolloutUsageReader.readTurn(rollout, turnId);
			}
			if (measurement.isPresent() && measurement.orElseThrow().outputTokens() > 0)
			{
				CodexRolloutUsageReader.TurnUsage usage = measurement.orElseThrow();
				long accountId = login.selectedAccountId().orElseThrow(() ->
					new IOException("No HoldMySpot account is selected; run account-select before reporting usage"));
				String measurementKey = "cat/codex/" + usage.harnessVersion().runtimeValue() + "/" +
					turnId + "/" + usage.outputTokens();
				CodexUsageReportEncoder encoder = new CodexUsageReportEncoder();
				outbox.enqueue("workos:" + login.tokens().userId(), accountId, measurementKey,
					usage.outputTokens(), eventId -> encoder.encode(new CodexUsageReportEncoder.Report(turnId,
						eventId, usage.outputTokens(), usage.harnessVersion())));
				enqueued = true;
			}
			deliveryAttempted = true;
			delivery.flush();
			writeResponse(json, output, error, null);
		}
		catch (JacksonException _)
		{
			error.println("CAT Stop: malformed event JSON");
			writeResponse(json, output, error, "CAT could not record usage because the Stop event is malformed");
		}
		catch (IOException | IllegalArgumentException exception)
		{
			if (!deliveryAttempted && delivery != null)
			{
				try
				{
					delivery.flush();
				}
				catch (IOException retryFailure)
				{
					error.println("CAT Stop: pending-report retry failed: " + retryFailure.getMessage());
				}
				catch (InterruptedException retryFailure)
				{
					Thread.currentThread().interrupt();
					error.println("CAT Stop: pending-report retry was interrupted: " + retryFailure.getMessage());
				}
			}
			error.println("CAT Stop: " + exception.getMessage());
			String message;
			if (enqueued)
				message = "Usage report queued for retry; delivery was not confirmed";
			else
				message = "CAT could not queue usage: " + exception.getMessage();
			writeResponse(json, output, error, message);
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			error.println("CAT Stop: usage delivery was interrupted");
			String message;
			if (enqueued)
				message = "Usage report queued for retry after an interruption";
			else
				message = "CAT could not queue usage because the Stop hook was interrupted";
			writeResponse(json, output, error, message);
		}
	}

	/**
	 * Reads one required nonblank text field from a native Stop event.
	 *
	 * @param event native event object
	 * @param field required field name
	 * @return field value
	 * @throws IOException if the field is missing or invalid
	 */
	private static String requiredText(JsonNode event, String field) throws IOException
	{
		JsonNode value = event.path(field);
		if (!value.isString() || value.stringValue().isBlank())
			throw new IOException("Codex Stop event has no valid " + field);
		return value.stringValue();
	}

	/**
	 * Converts one Codex identity to a UUID for matching rollout records.
	 *
	 * @param event native event object
	 * @param field session or turn identity field
	 * @return parsed identity
	 * @throws IOException if the field is not a UUID
	 */
	private static UUID requiredId(JsonNode event, String field) throws IOException
	{
		try
		{
			return UUID.fromString(requiredText(event, field));
		}
		catch (IllegalArgumentException exception)
		{
			throw new IOException("Codex Stop event has an invalid " + field, exception);
		}
	}

	/**
	 * Uses the native transcript only when its filename identifies this Codex session.
	 *
	 * @param event native event object
	 * @param sessionId native session identity
	 * @return absolute regular rollout path
	 * @throws IOException if Codex supplied no matching regular transcript
	 */
	private static Path transcript(JsonNode event, UUID sessionId) throws IOException
	{
		Path path;
		try
		{
			path = Path.of(requiredText(event, "transcript_path")).toAbsolutePath().normalize();
		}
		catch (IllegalArgumentException exception)
		{
			throw new IOException("Codex Stop event has an invalid transcript_path", exception);
		}
		if (!path.getFileName().toString().endsWith("-" + sessionId + ".jsonl") ||
			!Files.isRegularFile(path, NOFOLLOW_LINKS))
			throw new IOException("Codex Stop transcript does not identify this regular session rollout");
		return path;
	}

	/**
	 * Resolves Codex's writable plugin data and checks the installed command's data path when available.
	 *
	 * @param environment native Stop hook environment
	 * @return writable plugin data directory
	 * @throws IOException if Codex supplied no data directory or its home points elsewhere
	 */
	private static Path dataDirectory(Map<String, String> environment) throws IOException
	{
		String nativeData = environment.get("PLUGIN_DATA");
		if (nativeData == null || nativeData.isBlank())
			throw new IOException("Codex PLUGIN_DATA is not set");
		Path data = Path.of(nativeData).toAbsolutePath().normalize();
		String nativeHome = environment.get("CODEX_HOME");
		if (nativeHome != null && !nativeHome.isBlank())
		{
			Path expected = Path.of(nativeHome).toAbsolutePath().normalize().resolve("plugins/data/cat-cat");
			if (!data.equals(expected))
				throw new IOException("Codex PLUGIN_DATA differs from the saved-login directory under CODEX_HOME");
		}
		return data;
	}

	/**
	 * Returns one valid Codex Stop response without placing diagnostics in stdout.
	 *
	 * @param json invocation-owned JSON mapper
	 * @param output native hook output
	 * @param error diagnostic output
	 * @param message warning shown to the user, or null after confirmed delivery
	 */
	private static void writeResponse(JsonMapper json, OutputStream output, PrintStream error, String message)
	{
		try
		{
			Map<String, String> response;
			if (message == null)
				response = Map.of();
			else
				response = Map.of("systemMessage", message);
			output.write(json.writeValueAsBytes(response));
			output.write('\n');
		}
		catch (IOException exception)
		{
			error.println("CAT Stop: unable to return the hook response: " + exception.getMessage());
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexStopHook()
	{
	}
}
