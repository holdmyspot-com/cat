package io.github.cowwoc.cat.codex.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests effective model-profile observation from retained Codex native events.
 */
public final class TurnProfileReaderTest
{
	/**
	 * A native turn that could not execute any command cannot establish a successful evaluator read.
	 *
	 * @throws IOException if the retained event fixture cannot be written or read
	 */
	@Test
	public void observesWhenEveryCommandFailed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-tool-disposition-"));
			String original = Files.readString(copyFixture("effort-updated.events",
				directory.resolve("base.events")));
			String failed = """
				{"method":"item/completed","params":{"item":{"type":"commandExecution","status":"failed"}}}
				""";
			String completed = """
				{"method":"item/completed","params":{"item":{"type":"commandExecution","status":"completed"}}}
				""";
			Path onlyFailures = Files.writeString(directory.resolve("failed.events"), original.replace(
				"{\"method\":\"turn/completed\"", failed + "{\"method\":\"turn/completed\""));
			Path recovered = Files.writeString(directory.resolve("recovered.events"), original.replace(
				"{\"method\":\"turn/completed\"", failed + completed +
					"{\"method\":\"turn/completed\""));

			requireThat(TurnProfileReader.read(List.of(onlyFailures)).allCommandExecutionsFailed(),
				"onlyFailedCommands").isTrue();
			requireThat(TurnProfileReader.read(List.of(recovered)).allCommandExecutionsFailed(),
				"successfulRetry").isFalse();
		}
	}

	/**
	 * Creates the test fixture.
	 */
	public TurnProfileReaderTest()
	{
	}

	/**
	 * Verifies a settings update before turn start replaces the thread's startup default.
	 *
	 * @throws IOException if the test-owned native event file cannot be written or read
	 */
	@Test
	public void observesEffortAppliedToTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-turn-profile-"));
			Path events = copyFixture("effort-updated.events", directory.resolve("turn-1.events"));

			TurnProfileReader.Observation result = TurnProfileReader.read(List.of(events));
			requireThat(result.conversationId(), "conversationId").isEqualTo("thread-1");
			requireThat(result.nativeVersion(), "nativeVersion").isEqualTo(Optional.of("version-a"));
			requireThat(result.providerIdentity(), "providerIdentity").isEqualTo(Optional.of("provider-a"));
			requireThat(result.profile(), "profile").
				isEqualTo(new ModelProfile("model-a", Optional.of("low"), Map.of()));
			requireThat(result.terminalTurns(), "terminalTurns").isEqualTo(List.of(
				new TurnProfileReader.TerminalTurn(1, "turn-1", 4,
					TurnProfileReader.TurnStatus.COMPLETED, "")));
			requireThat(result.sourceSha256(), "sourceSha256").isEqualTo(List.of(
				"3129576ae44f9d4a4fe4071bf7c91febb6e4ff26071f0a432c923f38d30dcc58"));
		}
	}

	/**
	 * Verifies a turn without an override uses the profile reported when its thread started.
	 *
	 * @throws IOException if the test-owned native event file cannot be written or read
	 */
	@Test
	public void observesThreadDefaultWithoutUpdate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-turn-profile-"));
			Path events = copyFixture("effort-default.events", directory.resolve("turn-1.events"));

			TurnProfileReader.Observation result = TurnProfileReader.read(List.of(events));
			requireThat(result.profile(), "profile").
				isEqualTo(new ModelProfile("model-a", Optional.of("high"), Map.of()));
		}
	}

	/**
	 * Verifies one logical call cannot report a single profile when a later native turn changes it.
	 *
	 * @throws IOException when the second native turn changes the effective profile
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*model profile changed between native turns.*")
	public void rejectsProfileChangeBetweenTurns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-turn-profile-"));
			Path first = copyFixture("effort-default.events", directory.resolve("turn-1.events"));
			Path second = copyFixture("effort-updated-second.events", directory.resolve("turn-2.events"));

			TurnProfileReader.read(List.of(first, second));
		}
	}

	/**
	 * Verifies a second turn under the same effective settings retains one call profile.
	 *
	 * @throws IOException if the native event fixtures cannot be read or copied
	 */
	@Test
	public void observesStableProfileAcrossTurns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-turn-profile-"));
			Path first = copyFixture("effort-updated.events", directory.resolve("turn-1.events"));
			Path second = copyFixture("effort-updated-second.events", directory.resolve("turn-2.events"));

			TurnProfileReader.Observation result = TurnProfileReader.read(List.of(first, second));
			requireThat(result.profile(), "profile").
				isEqualTo(new ModelProfile("model-a", Optional.of("low"), Map.of()));
			requireThat(result.terminalTurns(), "terminalTurns").isEqualTo(List.of(
				new TurnProfileReader.TerminalTurn(1, "turn-1", 4, TurnProfileReader.TurnStatus.COMPLETED, ""),
				new TurnProfileReader.TerminalTurn(2, "turn-2", 7, TurnProfileReader.TurnStatus.COMPLETED, "")));
			requireThat(result.sourceSha256(), "sourceSha256").isEqualTo(List.of(
				"3129576ae44f9d4a4fe4071bf7c91febb6e4ff26071f0a432c923f38d30dcc58",
				"2066e6c6b1047c3503d29c65819d5af4bd1767749671356a25150381dc0f410d"));
		}
	}

	/**
	 * Verifies a settings change during a turn cannot be claimed as that turn's starting profile.
	 *
	 * @throws IOException when the native settings update follows turn start
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*settings update is outside this thread's pre-turn boundary.*")
	public void rejectsSettingsUpdateDuringTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-turn-profile-"));
			Path events = copyFixture("effort-updated-during-turn.events", directory.resolve("turn-1.events"));

			TurnProfileReader.read(List.of(events));
		}
	}

	/**
	 * Verifies a native response after the terminal turn cannot be silently included in the observed profile.
	 *
	 * @throws IOException when the retained event file contains a trailing response
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*native event follows terminal turn.*")
	public void rejectsResponseAfterTerminalTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-turn-profile-"));
			Path events = copyFixture("trailing-response.events", directory.resolve("turn-1.events"));

			TurnProfileReader.read(List.of(events));
		}
	}

	/**
	 * Copies one static native-event sequence into the test-owned retained run directory.
	 *
	 * @param name the bundled native-event fixture name
	 * @param destination the test-owned turn artifact path
	 * @return the written turn artifact
	 * @throws IOException if the fixture cannot be copied
	 */
	private static Path copyFixture(String name, Path destination) throws IOException
	{
		try (InputStream fixture = Objects.requireNonNull(TurnProfileReaderTest.class.getResourceAsStream(
			"/codex-turn-profiles/" + name)))
		{
			Files.copy(fixture, destination);
		}
		return destination;
	}
}
