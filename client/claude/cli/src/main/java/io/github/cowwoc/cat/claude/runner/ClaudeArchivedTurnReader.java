package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.RetainedEventArchive;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads one Claude turn from exact records already retained by the shared native-event archive. */
final class ClaudeArchivedTurnReader
{
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

	/**
	 * Native initialization, turn settlement, and its exact position and reference in the supplied archive.
	 *
	 * @param initialization native metadata available for the next turn
	 * @param turn validated parent-conversation settlement
	 * @param terminalArchiveOrdinal one-based terminal record position including initialization records
	 * @param terminalEvidence exact immutable native result record
	 */
	record ObservedTurn(ClaudeSessionInitialization initialization, ClaudeTurnObservation turn,
		long terminalArchiveOrdinal, EvidenceRef terminalEvidence)
	{
	}

	/**
	 * Decodes one archived native turn, carrying initialization from an earlier turn when it is not repeated.
	 * Repeated initialization may update announced settings, but cannot change the parent conversation.
	 *
	 * @param archive records and source hash produced for one native turn file
	 * @param store campaign repository holding the exact immutable records
	 * @param previous initialization observed in preceding turns, absent for the first turn
	 * @return settlement bound to the archived terminal record
	 * @throws IOException if retained bytes, initialization, record order, or settlement are invalid
	 */
	static ObservedTurn read(RetainedEventArchive.Archive archive, CampaignStore store,
		Optional<ClaudeSessionInitialization> previous) throws IOException
	{
		Objects.requireNonNull(archive, "archive");
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(previous, "previous");
		if (archive.sourceSha256().size() != 1 || archive.events().isEmpty())
			throw new IOException("Claude turn reader requires one nonempty native turn archive");
		ClaudeSessionInitialization initialization = previous.orElse(null);
		List<JsonNode> records = new ArrayList<>(archive.events().size());
		List<Integer> ordinals = new ArrayList<>(archive.events().size());
		boolean turnActivitySeen = false;
		for (int index = 0; index < archive.events().size(); ++index)
		{
			byte[] bytes = store.readImmutableBytes(archive.events().get(index));
			JsonNode event = parse(bytes, index + 1);
			if (event.path("type").asString().equals("system") &&
				event.path("subtype").asString().equals("init"))
			{
				if (turnActivitySeen)
					throw new IOException("Claude initialization follows turn activity in the same turn");
				ClaudeSessionInitialization announced = ClaudeSessionInitialization.read(bytes);
				if (initialization != null && !initialization.sessionId().equals(announced.sessionId()))
					throw new IOException("Claude initialization changes the parent native session");
				initialization = announced;
				continue;
			}
			if (event.path("type").asString().equals("assistant"))
			{
				if (initialization == null)
					throw new IOException("Claude assistant message precedes native initialization");
				turnActivitySeen = true;
			}
			if (event.path("type").asString().equals("result"))
				turnActivitySeen = true;
			records.add(event);
			ordinals.add(index + 1);
		}
		if (initialization == null)
			throw new IOException("Claude native turn has no observed initialization");
		ClaudeTurnObservation turn = ClaudeTurnObservation.read(initialization, records);
		int terminalOrdinal = ordinals.get(Math.toIntExact(turn.terminalEventOrdinal() - 1));
		return new ObservedTurn(initialization, turn, terminalOrdinal, archive.events().get(terminalOrdinal - 1));
	}

	/**
	 * Parses exactly one retained object with the same duplicate-field policy as native initialization.
	 *
	 * @param bytes immutable native record
	 * @param ordinal archive position used in diagnostics
	 * @return parsed native event
	 * @throws IOException if JSON is malformed, ambiguous, or not an object
	 */
	private static JsonNode parse(byte[] bytes, int ordinal) throws IOException
	{
		try
		{
			JsonNode event = JSON.readTree(bytes);
			if (event == null || !event.isObject())
				throw new IOException("Claude native event is not an object at archive record " + ordinal);
			return event;
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid or ambiguous Claude native JSON at archive record " + ordinal, failure);
		}
	}

	/** Prevents construction. */
	private ClaudeArchivedTurnReader()
	{
	}
}
