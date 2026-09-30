package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.NativePromptPathObservation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests the Codex native prompt-path observation boundary.
 */
public final class CodexNativePromptPathCollectorTest
{
	/**
	 * Verifies absent native path signals produce limited coverage and no synthetic source.
	 *
	 * @throws IOException if native evidence validation fails
	 */
	@Test
	public void reportsMissingNativePathSignal() throws IOException
	{
		CodexNativePromptPathCollector collector = new CodexNativePromptPathCollector();
		NativePromptPathObservation observation = collector.collect("codex-1", "thread-1", List.of(), ref ->
		{
			throw new AssertionError("No native evidence should be read");
		});
		requireThat(observation.events(), "events").isEmpty();
		requireThat(observation.toRuntimePromptSources(), "sources").isEmpty();
		requireThat(observation.coverage().status(), "status").isEqualTo(ObservationCoverageStatus.LIMITED);
		requireThat(observation.coverage().limitation().orElseThrow(), "limitation").isNotBlank();
	}

	/**
	 * Verifies a tool command mentioning an instruction file does not become a native load observation.
	 *
	 * @throws IOException if retained evidence cannot be read
	 */
	@Test
	public void doesNotInferPathFromTaskFileRead() throws IOException
	{
		byte[] raw = """
			{"method":"item/completed","params":{"item":{"type":"commandExecution","command":"cat AGENTS.md"}}}
			""".strip().getBytes(StandardCharsets.UTF_8);
		EvidenceRef reference = new EvidenceRef("native-event",
			"4bafa8a28227283dc64a3ae1e4f9c760a6958f5cf964281cddfaf52628c42e8c");
		NativePromptPathObservation observation = new CodexNativePromptPathCollector().collect("codex-1", "thread-1",
			List.of(new NativeEventEvidence("thread-1", 1, reference)), requested -> raw);
		requireThat(observation.events(), "events").isEmpty();
		requireThat(observation.coverage().status(), "status").isEqualTo(ObservationCoverageStatus.LIMITED);
	}

	/**
	 * Verifies a claimed native event with different retained bytes is rejected before interpretation.
	 *
	 * @throws IOException if evidence validation rejects the mismatched bytes
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*hash mismatch.*")
	public void rejectsMismatchedNativeEvidence() throws IOException
	{
		EvidenceRef reference = new EvidenceRef("native-event", "0".repeat(64));
		new CodexNativePromptPathCollector().collect("codex-1", "thread-1",
			List.of(new NativeEventEvidence("thread-1", 1, reference)), requested -> new byte[]{1});
	}

	/**
	 * Verifies a settled Codex run seals a zero-entry native event index with an explicit limitation.
	 *
	 * @throws IOException if the test fixture or index cannot be written
	 */
	@Test
	public void sealsZeroEntryIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-native-path-"));
			HarnessRun run = new HarnessRun("codex", "codex-1", directory);
			CodexNativePromptPathCollector.seal(run);
			JsonNode index = scope.json().readTree(Files.readString(CodexNativePromptPathCollector.indexPath(run)));
			requireThat(index.path("producer").asString(), "producer").isEqualTo("codex-native-event-stream");
			requireThat(index.path("state").asString(), "state").isEqualTo("LIMITED");
			requireThat(index.path("events").size(), "eventCount").isZero();
			CodexNativePromptPathCollector.seal(run);
		}
	}

	/**
	 * Verifies a claimed missing index is refused as failed receipt evidence.
	 *
	 * @throws IOException if the test fixture cannot be created
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*index is missing.*")
	public void rejectsMissingClaimedIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-native-path-"));
			CodexNativePromptPathCollector.validateIndex(new HarnessRun("codex", "codex-1", directory));
		}
	}

	/**
	 * Verifies a claimed index with another run identity cannot be accepted.
	 *
	 * @throws IOException if the test fixture or index cannot be written
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*index is corrupt or mismatched.*")
	public void rejectsCorruptClaimedIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-native-path-"));
			HarnessRun run = new HarnessRun("codex", "codex-1", directory);
			CodexNativePromptPathCollector.seal(run);
			Path index = CodexNativePromptPathCollector.indexPath(run);
			Files.writeString(index, Files.readString(index).replace("codex-1", "codex-2"));
			CodexNativePromptPathCollector.validateIndex(run);
		}
	}
}
