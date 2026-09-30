package io.github.cowwoc.cat.common.runner;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A point-in-time view of one retained bridge transcript for exact native-call recovery.
 *
 * @param runId the expected native run identity
 * @param state whether the bridge published its final completion event
 * @param turns retained terminal outcomes in call order
 */
public record HarnessTranscriptObservation(String runId, SessionState state, List<Turn> turns)
{
	/** Bridge session publication state. */
	public enum SessionState
	{
		IN_PROGRESS,
		COMPLETED
	}

	/** Terminal outcome of one bridge turn. */
	public enum TurnState
	{
		COMPLETED,
		INTERRUPTED
	}

	/**
	 * One retained turn outcome.
	 *
	 * @param ordinal one-based terminal turn order
	 * @param state native terminal outcome
	 * @param exitCode native exit status
	 * @param assistantResponse response present only for a completed turn
	 */
	public record Turn(int ordinal, TurnState state, int exitCode, Optional<String> assistantResponse)
	{
		/** Validates the retained turn shape. */
		public Turn
		{
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(assistantResponse, "assistantResponse");
			if (ordinal < 1 || exitCode < 0 ||
				(state == TurnState.COMPLETED) != assistantResponse.isPresent())
				throw new IllegalArgumentException("Contradictory bridge turn outcome");
		}
	}

	/** Validates the exact transcript identity and outcome sequence. */
	public HarnessTranscriptObservation
	{
		HarnessRun.requireValidId(runId);
		Objects.requireNonNull(state, "state");
		turns = List.copyOf(turns);
		for (int index = 0; index < turns.size(); ++index)
		{
			if (turns.get(index).ordinal() != index + 1)
				throw new IllegalArgumentException("Bridge turns are not contiguous");
		}
	}

	/**
	 * Reads the exact retained transcript without inferring that an absent path was never created.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param transcript retained bridge output path
	 * @param runId expected native run identity
	 * @return the parsed observation, or empty when the path is presently absent
	 * @throws IOException if a present transcript is malformed, has an incomplete final line, or identifies another run
	 */
	public static Optional<HarnessTranscriptObservation> read(JsonMapper json, Path transcript, String runId)
		throws IOException
	{
		Objects.requireNonNull(json, "json");
		Objects.requireNonNull(transcript, "transcript");
		HarnessRun.requireValidId(runId);
		if (Files.notExists(transcript, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		if (!Files.isRegularFile(transcript, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Bridge transcript is not a regular file: " + transcript);
		try (FileChannel channel = FileChannel.open(transcript, StandardOpenOption.READ,
			LinkOption.NOFOLLOW_LINKS))
		{
			long size = channel.size();
			if (size == 0)
				throw new IOException("Bridge transcript has no started event: " + transcript);
			ByteBuffer last = ByteBuffer.allocate(1);
			if (channel.read(last, size - 1) != 1 || last.get(0) != '\n')
				throw new IOException("Bridge transcript has an incomplete final line: " + transcript);
			try (BufferedReader lines = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8)))
			{
				HarnessTranscriptObservation result = parse(json, lines, runId, transcript);
				if (channel.size() != size)
					throw new IOException("Bridge transcript changed while being observed: " + transcript);
				return Optional.of(result);
			}
		}
	}

	/**
	 * Interprets only the bridge's outer event envelope and terminal turn fields.
	 *
	 * @param json the runner-compatible mapper
	 * @param lines complete JSONL lines from one stable file descriptor
	 * @param runId expected exact run identity
	 * @param transcript source used in diagnostics
	 * @return the observed bridge disposition
	 * @throws IOException if an envelope, identity, or terminal sequence is invalid
	 */
	private static HarnessTranscriptObservation parse(JsonMapper json, BufferedReader lines, String runId,
		Path transcript) throws IOException
	{
		List<Turn> turns = new ArrayList<>();
		SessionState state = SessionState.IN_PROGRESS;
		boolean started = false;
		while (true)
		{
			String line = lines.readLine();
			if (line == null)
				break;
			JsonNode event;
			try
			{
				event = json.readTree(line);
			}
			catch (JacksonException e)
			{
				throw new IOException("Bridge transcript has invalid JSON: " + transcript, e);
			}
			if (event == null || !event.isObject())
				throw new IOException("Bridge transcript event is not an object: " + transcript);
			String type = requiredText(event, "type", transcript);
			if (!runId.equals(requiredText(event, "runId", transcript)))
				throw new IOException("Bridge transcript identifies another run: " + transcript);
			if (!started)
			{
				if (!type.equals("started"))
					throw new IOException("Bridge transcript has no first started event: " + transcript);
				started = true;
				continue;
			}
			if (state == SessionState.COMPLETED)
				throw new IOException("Bridge transcript has events after completion: " + transcript);
			switch (type)
			{
				case "turn-completed" -> turns.add(new Turn(turns.size() + 1, TurnState.COMPLETED,
					exitCode(event, transcript), Optional.of(requiredText(event, "assistant_response", transcript))));
				case "turn-interrupted" -> turns.add(new Turn(turns.size() + 1, TurnState.INTERRUPTED,
					exitCode(event, transcript), Optional.empty()));
				case "completed" -> state = SessionState.COMPLETED;
				case "native-event" -> requiredText(event, "event", transcript);
				case "interrupted", "interrupt-raced-completion", "steered", "route-selected",
					"route-delivered" ->
				{
				}
				default -> throw new IOException("Bridge transcript has an unsupported event type: " + type);
			}
		}
		if (!started)
			throw new IOException("Bridge transcript has no started event: " + transcript);
		return new HarnessTranscriptObservation(runId, state, turns);
	}

	/** Reads one required string without collapsing absence into an empty value. */
	private static String requiredText(JsonNode event, String field, Path transcript) throws IOException
	{
		JsonNode value = event.path(field);
		if (!value.isString())
			throw new IOException("Bridge transcript has no string " + field + ": " + transcript);
		return value.stringValue();
	}

	/** Reads a nonnegative native exit code from one terminal turn event. */
	private static int exitCode(JsonNode event, Path transcript) throws IOException
	{
		JsonNode value = event.path("exitCode");
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
			throw new IOException("Bridge transcript has no valid native exit code: " + transcript);
		return value.intValue();
	}
}
