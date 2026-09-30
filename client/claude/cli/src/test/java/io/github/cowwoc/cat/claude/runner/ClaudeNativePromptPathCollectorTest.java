package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.runner.NativePromptPathObservation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeNativePromptPathCollector} against retained native hook bytes.
 */
public final class ClaudeNativePromptPathCollectorTest
{
	/**
	 * Verifies eager and lazy events remain distinct and retain optional trigger metadata.
	 *
	 * @throws IOException if raw event decoding fails
	 */
	@Test
	public void decodesSelectedAndLazyPaths() throws IOException
	{
		String first = """
			{"hook_event_name":"InstructionsLoaded","session_id":"session-1","file_path":"/copied/CLAUDE.md",\
			"load_reason":"session_start"}
			""";
		String second = """
			{"hook_event_name":"InstructionsLoaded","session_id":"session-1",\
			"file_path":"/copied/.claude/rules/lazy.md","load_reason":"path_glob_match",\
			"trigger_file_path":"/copied/src/Main.java"}
			""";
		byte[] firstBytes = first.getBytes(UTF_8);
		byte[] secondBytes = second.getBytes(UTF_8);
		EvidenceRef firstRef = new EvidenceRef("native-events", ClaudeNativeInstructionsLoadedCapture.sha256(firstBytes));
		EvidenceRef secondRef = new EvidenceRef("native-events", ClaudeNativeInstructionsLoadedCapture.sha256(secondBytes));
		NativePromptPathObservation result = new ClaudeNativePromptPathCollector(Path.of("/copied")).collect(
			"run-1", "session-1", List.of(new NativeEventEvidence("session-1", 1, firstRef),
				new NativeEventEvidence("session-1", 2, secondRef)),
				ref ->
				{
					if (ref.equals(firstRef))
						return firstBytes;
					return secondBytes;
				});

		requireThat(result.events().size(), "eventCount").isEqualTo(2);
		requireThat(result.events().get(0).normalizedRelativePath(), "eagerPath").isEqualTo(Path.of("CLAUDE.md"));
		requireThat(result.events().get(1).normalizedRelativePath(), "lazyPath").isEqualTo(Path.of(
			".claude/rules/lazy.md"));
		requireThat(result.events().get(1).triggerPath().orElseThrow(), "triggerPath").isEqualTo(Path.of(
			"src/Main.java"));
		requireThat(result.coverage().status(), "coverage").isEqualTo(ObservationCoverageStatus.LIMITED);
	}

	/**
	 * Verifies an outside-project event remains only raw evidence.
	 *
	 * @throws IOException if raw event decoding fails
	 */
	@Test
	public void limitsOutsideProjectPath() throws IOException
	{
		byte[] bytes = """
			{"hook_event_name":"InstructionsLoaded","session_id":"session-1",\
			"file_path":"/other/CLAUDE.md"}
			""".getBytes(UTF_8);
		EvidenceRef ref = new EvidenceRef("native-events", ClaudeNativeInstructionsLoadedCapture.sha256(bytes));
		NativePromptPathObservation result = new ClaudeNativePromptPathCollector(Path.of("/copied")).collect(
			"run-1", "session-1", List.of(new NativeEventEvidence("session-1", 1, ref)), ignored -> bytes);
		requireThat(result.events().isEmpty(), "events").isTrue();
		requireThat(result.coverage().status(), "coverage").isEqualTo(ObservationCoverageStatus.LIMITED);
	}

	/**
	 * Verifies repeated native loads of one path remain separate observations.
	 *
	 * @throws IOException if raw event decoding fails
	 */
	@Test
	public void retainsDuplicatePathEvents() throws IOException
	{
		byte[] bytes = """
			{"hook_event_name":"InstructionsLoaded","session_id":"session-1",\
			"file_path":"/copied/CLAUDE.md"}
			""".getBytes(UTF_8);
		EvidenceRef ref = new EvidenceRef("native-events", ClaudeNativeInstructionsLoadedCapture.sha256(bytes));
		NativePromptPathObservation result = new ClaudeNativePromptPathCollector(Path.of("/copied")).collect(
			"run-1", "session-1", List.of(new NativeEventEvidence("session-1", 1, ref),
				new NativeEventEvidence("session-1", 2, ref)), ignored -> bytes);
		requireThat(result.events().size(), "eventCount").isEqualTo(2);
		requireThat(result.events().get(0).nativeEventOrdinal(), "firstOrdinal").isEqualTo(1L);
		requireThat(result.events().get(1).nativeEventOrdinal(), "secondOrdinal").isEqualTo(2L);
	}

	/**
	 * Verifies a claimed missing raw event cannot be treated as limited path coverage.
	 *
	 * @throws IOException if the claimed event cannot be read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*missing.*")
	public void rejectsMissingClaimedEvidence() throws IOException
	{
		EvidenceRef ref = new EvidenceRef("native-events", "0".repeat(64));
		new ClaudeNativePromptPathCollector(Path.of("/copied")).collect("run-1", "session-1",
			List.of(new NativeEventEvidence("session-1", 1, ref)), ignored ->
			{
				throw new IOException("missing retained evidence");
			});
	}
}
