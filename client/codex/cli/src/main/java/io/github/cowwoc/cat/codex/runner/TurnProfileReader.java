package io.github.cowwoc.cat.codex.runner;

import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the model profile effective at each retained Codex app-server turn start.
 */
final class TurnProfileReader
{
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

	/**
	 * Native terminal statuses exposed by Codex app-server.
	 */
	enum TurnStatus
	{
		COMPLETED,
		INTERRUPTED,
		FAILED
	}

	/**
	 * Locates one terminal turn event in the ordered native record stream.
	 *
	 * @param turnOrdinal one-based logical turn number
	 * @param nativeTurnId the native Codex turn identity
	 * @param eventOrdinal one-based native record number across all turn files
	 * @param status the native terminal status
	 * @param responseText the published final answer for a completed turn, otherwise empty
	 */
	record TerminalTurn(int turnOrdinal, String nativeTurnId, long eventOrdinal, TurnStatus status,
		String responseText)
	{
		/**
		 * Validates one native terminal event location.
		 *
		 * @param turnOrdinal the positive logical turn number
		 * @param nativeTurnId the nonblank native turn identity
		 * @param eventOrdinal the positive native record number
		 * @param status the native terminal status
		 * @param responseText the published final answer for a completed turn, otherwise empty
		 */
		TerminalTurn
		{
			if (turnOrdinal < 1 || eventOrdinal < 1 || nativeTurnId == null || nativeTurnId.isBlank())
				throw new IllegalArgumentException("Invalid Codex terminal turn location");
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(responseText, "responseText");
		}
	}

	/**
	 * One native thread and the stable model profile observed at its turn boundaries.
	 *
	 * @param conversationId the native Codex thread identity
	 * @param nativeVersion the Codex version reported by the thread
	 * @param providerIdentity the model provider effective at the observed turns
	 * @param profile the effective profile shared by the observed turns
	 * @param terminalTurns the ordered native terminal turn locations
	 * @param sourceSha256 the exact content hashes of the ordered turn files
	 * @param allCommandExecutionsFailed whether every observed completed command failed
	 */
	record Observation(String conversationId, Optional<String> nativeVersion,
		Optional<String> providerIdentity, ModelProfile profile, List<TerminalTurn> terminalTurns,
		List<String> sourceSha256, boolean allCommandExecutionsFailed)
	{
		/**
		 * Validates the observed native identity.
		 *
		 * @param conversationId the nonblank native thread identity
		 * @param nativeVersion the optional native CLI version
		 * @param providerIdentity the optional effective model provider
		 * @param profile the stable effective model profile
		 * @param terminalTurns the ordered native terminal turn locations
		 * @param sourceSha256 the exact content hashes of the ordered turn files
		 * @param allCommandExecutionsFailed whether every observed completed command failed
		 */
		Observation
		{
			if (conversationId == null || conversationId.isBlank())
				throw new IllegalArgumentException("conversationId must not be blank");
			Objects.requireNonNull(nativeVersion, "nativeVersion");
			Objects.requireNonNull(providerIdentity, "providerIdentity");
			Objects.requireNonNull(profile, "profile");
			terminalTurns = List.copyOf(terminalTurns);
			sourceSha256 = List.copyOf(sourceSha256);
		}
	}

	/**
	 * Reads one or more ordered retained turn event files.
	 *
	 * @param eventFiles native event files in turn order
	 * @return the model profile effective at every native turn start
	 * @throws NullPointerException if the file list or an entry is null
	 * @throws IllegalArgumentException if no turn file is supplied
	 * @throws IOException if the events cannot establish one stable profile
	 */
	static Observation read(List<Path> eventFiles) throws IOException
	{
		eventFiles = List.copyOf(eventFiles);
		if (eventFiles.isEmpty())
			throw new IllegalArgumentException("At least one native turn event file is required");
		State state = new State();
		for (Path file : eventFiles)
			readTurn(file, state);
		return state.observation();
	}

	/**
	 * Reads one complete turn's events without following a substituted event-file link.
	 *
	 * @param file the retained event file
	 * @param state identity observed in preceding turn files
	 * @throws IOException if the file is missing, changing, malformed, or lacks a terminal turn
	 */
	private static void readTurn(Path file, State state) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Codex native turn events are not a regular file: " + file);
		state.beginTurnFile();
		MessageDigest digest = sha256();
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
		{
			long size = channel.size();
			if (size == 0)
				throw new IOException("Codex native turn events are empty: " + file);
			ByteBuffer last = ByteBuffer.allocate(1);
			if (channel.read(last, size - 1) != 1 || last.get(0) != '\n')
				throw new IOException("Codex native turn events have an incomplete final line: " + file);
			try (BufferedReader lines = new BufferedReader(new InputStreamReader(new DigestInputStream(
				Channels.newInputStream(channel), digest), StandardCharsets.UTF_8.newDecoder())))
			{
				int ordinal = 0;
				while (true)
				{
					String line = lines.readLine();
					if (line == null)
						break;
					++ordinal;
					state.accept(parse(line, file, ordinal), file + ":" + ordinal);
				}
				if (channel.size() != size)
					throw new IOException("Codex native turn events changed while being read: " + file);
			}
		}
		state.finishTurnFile(file, HexFormat.of().formatHex(digest.digest()));
	}

	/**
	 * Obtains a digest for the exact bytes interpreted by the profile reader.
	 *
	 * @return the SHA-256 digest
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static MessageDigest sha256() throws IOException
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new IOException("SHA-256 is unavailable", failure);
		}
	}

	/**
	 * Parses one retained native JSONL event or response.
	 *
	 * @param line one complete UTF-8 JSONL record
	 * @param file the event file used in diagnostics
	 * @param ordinal the one-based record number
	 * @return the parsed native object
	 * @throws IOException if the record is invalid JSON or not an object
	 */
	private static JsonNode parse(String line, Path file, int ordinal) throws IOException
	{
		try
		{
			JsonNode result = JSON.readTree(line);
			if (result == null || !result.isObject())
				throw new IOException("Codex native event is not an object: " + file + ":" + ordinal);
			return result;
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid Codex native event JSON: " + file + ":" + ordinal, failure);
		}
	}

	/**
	 * Reads one required nonblank native string field.
	 *
	 * @param source the containing JSON object
	 * @param field the required field name
	 * @param location the event location used in diagnostics
	 * @return the observed string
	 * @throws IOException if the field is absent or blank
	 */
	private static String requiredText(JsonNode source, String field, String location) throws IOException
	{
		JsonNode value = source.path(field);
		if (!value.isString() || value.stringValue().isBlank())
			throw new IOException("Codex native event has no nonblank " + field + ": " + location);
		return value.stringValue();
	}

	/**
	 * Reads an optional native string without treating an invalid value as absence.
	 *
	 * @param source the containing JSON object
	 * @param field the optional field name
	 * @param location the event location used in diagnostics
	 * @return the observed string, or empty when native omitted it
	 * @throws IOException if a present field is not a nonblank string
	 */
	private static Optional<String> optionalText(JsonNode source, String field, String location) throws IOException
	{
		JsonNode value = source.path(field);
		if (value.isMissingNode() || value.isNull())
			return Optional.empty();
		if (!value.isString() || value.stringValue().isBlank())
			throw new IOException("Codex native event has an invalid " + field + ": " + location);
		return Optional.of(value.stringValue());
	}

	/**
	 * Accumulates thread defaults and settings changes across ordered native turn files.
	 */
	private static final class State
	{
		private String conversationId;
		private Optional<String> nativeVersion = Optional.empty();
		private Optional<String> currentProvider = Optional.empty();
		private Optional<String> observedProvider;
		private ModelProfile currentProfile;
		private ModelProfile observedProfile;
		private String turnId;
		private boolean started;
		private boolean completed;
		private long recordOrdinal;
		private int failedCommandExecutions;
		private int successfulCommandExecutions;
		private boolean unclassifiedCommandExecution;
		private final List<TerminalTurn> terminalTurns = new ArrayList<>();
		private final List<String> sourceSha256 = new ArrayList<>();

		/**
		 * Resets only the turn lifecycle before reading its next event file.
		 */
		private void beginTurnFile()
		{
			turnId = null;
			started = false;
			completed = false;
		}

		/**
		 * Applies one native event to the effective profile at the next turn boundary.
		 *
		 * @param event the parsed native record
		 * @param location the event's file and record number
		 * @throws IOException if event identity or order contradicts the native thread
		 */
		private void accept(JsonNode event, String location) throws IOException
		{
			recordOrdinal += 1;
			if (completed)
				throw new IOException("Codex native event follows terminal turn: " + location);
			JsonNode method = event.path("method");
			if (method.isMissingNode())
				return;
			if (!method.isString())
				throw new IOException("Codex native event has a non-string method: " + location);
			switch (method.stringValue())
			{
				case "thread/started" -> startThread(event.path("params").path("thread"), location);
				case "thread/settings/updated" -> updateSettings(event.path("params"), location);
				case "turn/started" -> startTurn(event.path("params"), location);
				case "turn/completed" -> completeTurn(event.path("params"), location);
				case "item/completed" -> observeCommand(event.path("params").path("item"), location);
				default ->
				{
				}
			}
		}

		/**
		 * Records whether a completed native command actually ran successfully.
		 *
		 * @param item completed native item
		 * @param location event location used in diagnostics
		 * @throws IOException if a command omits its terminal status
		 */
		private void observeCommand(JsonNode item, String location) throws IOException
		{
			if (!started || !item.path("type").isString() ||
				!item.path("type").stringValue().equals("commandExecution"))
				return;
			String status = requiredText(item, "status", location);
			switch (status)
			{
				case "completed" -> ++successfulCommandExecutions;
				case "failed" -> ++failedCommandExecutions;
				default -> unclassifiedCommandExecution = true;
			}
		}

		/**
		 * Captures the native thread's startup defaults before any turn override.
		 *
		 * @param thread the native thread value
		 * @param location the event location
		 * @throws IOException if startup is missing or repeated
		 */
		private void startThread(JsonNode thread, String location) throws IOException
		{
			if (conversationId != null || started)
				throw new IOException("Codex native thread startup is repeated: " + location);
			conversationId = requiredText(thread, "id", location);
			nativeVersion = optionalText(thread, "cliVersion", location);
			currentProvider = optionalText(thread, "modelProvider", location);
			currentProfile = new ModelProfile(requiredText(thread, "model", location),
				optionalText(thread, "reasoningEffort", location), Map.of());
		}

		/**
		 * Applies the native settings snapshot only before the turn it will govern.
		 *
		 * @param params the native settings update parameters
		 * @param location the event location
		 * @throws IOException if the update targets another thread or arrives during a turn
		 */
		private void updateSettings(JsonNode params, String location) throws IOException
		{
			if (conversationId == null || started ||
				!conversationId.equals(requiredText(params, "threadId", location)))
				throw new IOException("Codex settings update is outside this thread's pre-turn boundary: " + location);
			JsonNode settings = params.path("threadSettings");
			currentProfile = new ModelProfile(requiredText(settings, "model", location),
				optionalText(settings, "effort", location), Map.of());
			currentProvider = optionalText(settings, "modelProvider", location);
		}

		/**
		 * Records the profile in effect when a native turn begins.
		 *
		 * @param params the native turn-start parameters
		 * @param location the event location
		 * @throws IOException if the turn is repeated, mismatched, or changes the call profile
		 */
		private void startTurn(JsonNode params, String location) throws IOException
		{
			if (conversationId == null || started ||
				!conversationId.equals(requiredText(params, "threadId", location)))
				throw new IOException("Codex turn start is outside the observed thread: " + location);
			turnId = requiredText(params.path("turn"), "id", location);
			started = true;
			if (observedProfile != null && (!observedProfile.equals(currentProfile) ||
				!observedProvider.equals(currentProvider)))
				throw new IOException("Codex model profile changed between native turns: " + location);
			observedProfile = currentProfile;
			observedProvider = currentProvider;
		}

		/**
		 * Verifies a terminal native event names the turn that observed this profile.
		 *
		 * @param params the native turn-completion parameters
		 * @param location the event location
		 * @throws IOException if the terminal event is mismatched or has an unknown status
		 */
		private void completeTurn(JsonNode params, String location) throws IOException
		{
			if (!started || !conversationId.equals(requiredText(params, "threadId", location)) ||
				!turnId.equals(requiredText(params.path("turn"), "id", location)))
				throw new IOException("Codex terminal turn differs from its start: " + location);
			String status = requiredText(params.path("turn"), "status", location);
			TurnStatus terminalStatus = switch (status)
			{
				case "completed" -> TurnStatus.COMPLETED;
				case "interrupted" -> TurnStatus.INTERRUPTED;
				case "failed" -> TurnStatus.FAILED;
				default -> throw new IOException("Codex terminal turn has unknown status: " + location);
			};
			String response = "";
			if (terminalStatus == TurnStatus.COMPLETED)
				response = CodexTurnResponse.read(params.path("turn"));
			terminalTurns.add(new TerminalTurn(terminalTurns.size() + 1, turnId, recordOrdinal, terminalStatus, response));
			completed = true;
		}

		/**
		 * Ensures the current event file has one complete native turn.
		 *
		 * @param file the retained event file
		 * @param sourceHash the hash of the exact interpreted file bytes
		 * @throws IOException if its turn start or terminal event is missing
		 */
		private void finishTurnFile(Path file, String sourceHash) throws IOException
		{
			if (!started || !completed)
				throw new IOException("Codex native event file has no complete turn: " + file);
			sourceSha256.add(sourceHash);
		}

		/**
		 * Returns the stable native profile after every turn file has been validated.
		 *
		 * @return the observed thread and model profile
		 */
		private Observation observation()
		{
			return new Observation(conversationId, nativeVersion, observedProvider, observedProfile, terminalTurns,
				sourceSha256, failedCommandExecutions > 0 && successfulCommandExecutions == 0 &&
					!unclassifiedCommandExecution);
		}
	}

	/**
	 * Prevents construction.
	 */
	private TurnProfileReader()
	{
	}
}
