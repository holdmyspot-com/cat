package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.runner.RetainedEventArchive;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Checks the actual immutable archive-to-turn boundary with only the records needed by each claim. */
public final class ClaudeArchivedTurnReaderTest
{
	private static final String INITIALIZATION = """
		{"type":"system","subtype":"init","session_id":"s","model":"alias"}
		""";
	private static final String MESSAGE = """
		{"type":"assistant","session_id":"s","parent_tool_use_id":null,\
		 "message":{"role":"assistant","id":"m","model":"actual"}}
		""";
	private static final String RESULT = """
		{"type":"result","session_id":"s","uuid":"r","subtype":"success","is_error":false,"result":"answer"}
		""";

	/** Creates the test fixture. */
	public ClaudeArchivedTurnReaderTest()
	{
	}

	/**
	 * Keeps the native terminal reference and archive ordinal, including the first turn's initialization.
	 *
	 * @throws IOException if a fixture or valid archive cannot be read
	 */
	@Test
	public void bindsTerminalAndCarriesInitialization() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-archived-turn-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			RetainedEventArchive.Archive first = archive(root, store, "first", INITIALIZATION + MESSAGE + RESULT);
			ClaudeArchivedTurnReader.ObservedTurn observed = ClaudeArchivedTurnReader.read(first, store, Optional.empty());
			requireThat(observed.terminalArchiveOrdinal(), "terminalOrdinal").isEqualTo(3L);
			requireThat(observed.terminalEvidence(), "exactTerminalReference").isEqualTo(first.events().get(2));
			requireThat(observed.turn().reportedModelId(), "reportedModel").isEqualTo(Optional.of("actual"));
			RetainedEventArchive.Archive next = archive(root, store, "next", MESSAGE + RESULT);
			ClaudeArchivedTurnReader.ObservedTurn following = ClaudeArchivedTurnReader.read(next, store,
				Optional.of(observed.initialization()));
			requireThat(following.initialization(), "carriedInitialization").isEqualTo(observed.initialization());
			requireThat(following.terminalArchiveOrdinal(), "followingOrdinal").isEqualTo(2L);
			requireThat(following.terminalEvidence(), "followingReference").isEqualTo(next.events().get(1));
			RetainedEventArchive.Archive spaced = archive(root, store, "spaced",
				(INITIALIZATION + MESSAGE + RESULT).replace(":", " : "));
			ClaudeArchivedTurnReader.ObservedTurn formatted = ClaudeArchivedTurnReader.read(spaced, store, Optional.empty());
			requireThat(formatted.turn(), "equivalentFormatting").isEqualTo(observed.turn());
		}
	}

	/**
	 * Invalid native ownership, ambiguous fields, and initialization ordering cannot produce a turn.
	 *
	 * @throws IOException if fixtures cannot be archived
	 */
	@Test
	public void rejectsUnprovenArchivedTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-invalid-archive-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			List<String> invalid = List.of(
				INITIALIZATION + MESSAGE.replace("\"parent_tool_use_id\":null,", "") + RESULT,
				INITIALIZATION + MESSAGE.replace("\"model\":\"actual\"",
					"\"model\":\"actual\",\"model\":\"actual\"") + RESULT,
				MESSAGE + RESULT, INITIALIZATION + MESSAGE + RESULT + INITIALIZATION,
				INITIALIZATION + RESULT.replace("\"success\"", "\"error_during_execution\"") + INITIALIZATION);
			for (int index = 0; index < invalid.size(); ++index)
			{
				RetainedEventArchive.Archive bad = archive(root, store, "bad-" + index, invalid.get(index));
				Assert.expectThrows(IOException.class, () -> ClaudeArchivedTurnReader.read(bad, store, Optional.empty()));
			}
		}
	}

	/**
	 * A later modification of retained bytes is rejected before producing another observation.
	 *
	 * @throws IOException if fixture evidence cannot be created
	 */
	@Test
	public void rejectsChangedRetainedRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-corrupt-archive-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			RetainedEventArchive.Archive records = archive(root, store, "turn", INITIALIZATION + MESSAGE + RESULT);
			ClaudeArchivedTurnReader.ObservedTurn observed = ClaudeArchivedTurnReader.read(records, store, Optional.empty());
			Path bytes = store.directory().resolve(observed.terminalEvidence().collection()).
				resolve(observed.terminalEvidence().evidenceId() + ".blob");
			Files.writeString(bytes, "changed");
			Assert.expectThrows(IOException.class, () -> ClaudeArchivedTurnReader.read(records, store, Optional.empty()));
		}
	}

	/**
	 * Retains actual native records through the shared archive producer.
	 *
	 * @param root test-owned fixture directory
	 * @param store immutable evidence repository
	 * @param name unique fixture filename
	 * @param events complete native record stream
	 * @return archive for one native turn
	 * @throws IOException if fixture creation or retention fails
	 */
	private static RetainedEventArchive.Archive archive(Path root, CampaignStore store, String name, String events)
		throws IOException
	{
		Path file = Files.writeString(root.resolve(name + ".events"), events);
		return RetainedEventArchive.archive(List.of(file), store);
	}
}
