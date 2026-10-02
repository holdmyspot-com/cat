package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Tests {@link TrialEvidence}.
 */
public final class TrialEvidenceTest
{
	/**
	 * Creates the test.
	 */
	public TrialEvidenceTest()
	{
	}

	/**
	 * Returns the evidence of a trial whose project copy is a directory.
	 *
	 * @param effects the project copy
	 * @param replies the trial's replies
	 * @return the evidence
	 */
	private static TrialEvidence evidence(Path effects, List<String> replies)
	{
		return new TrialEvidence(new SessionResult.Completed(effects.resolveSibling("transcript"), replies, effects,
			"model-a", null, null, List.of(), "none", List.of()));
	}

	/**
	 * Verifies that the evidence holds the trial's final replies and the files of its project copy, and that a path
	 * that names no file has no content.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void readsRepliesAndProjectFiles() throws IOException
	{
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path effects = cleanup.add(Files.createTempDirectory("trial-evidence"));
			Files.createDirectories(effects.resolve("logs"));
			Files.writeString(effects.resolve("logs/out.txt"), "done\n", UTF_8);
			TrialEvidence evidence = evidence(effects, List.of("first", "second"));

			requireThat(evidence.replies(), "replies").isEqualTo(List.of("first", "second"));
			requireThat(evidence.exists("logs"), "logsExists").isTrue();
			requireThat(evidence.exists("cache"), "cacheExists").isFalse();
			requireThat(evidence.content("logs/out.txt"), "content").isEqualTo(Optional.of("done\n"));
			requireThat(evidence.content("logs"), "directoryContent").isEmpty();
			requireThat(evidence.content("missing.txt"), "missingContent").isEmpty();
		}
	}

	/**
	 * Verifies that a link that the trial left in the project exists as a link, while its target outside the project
	 * is not read as a project file.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void doesNotReadThroughLinksOutOfTheProject() throws IOException
	{
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("trial-evidence"));
			Path effects = Files.createDirectories(root.resolve("project"));
			Path outside = Files.writeString(root.resolve("secret.txt"), "outside", UTF_8);
			Files.createSymbolicLink(effects.resolve("link.txt"), outside);
			Files.createSymbolicLink(effects.resolve("dangling.txt"), root.resolve("missing.txt"));
			TrialEvidence evidence = evidence(effects, List.of("reply"));

			requireThat(evidence.exists("link.txt"), "linkExists").isTrue();
			requireThat(evidence.exists("dangling.txt"), "danglingExists").isTrue();
			requireThat(evidence.content("link.txt"), "linkContent").isEmpty();
			requireThat(evidence.content("dangling.txt"), "danglingContent").isEmpty();
		}
	}

	/**
	 * Verifies that bytes that are not valid UTF-8 are replaced rather than making the file unreadable.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void replacesMalformedText() throws IOException
	{
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path effects = cleanup.add(Files.createTempDirectory("trial-evidence"));
			Files.write(effects.resolve("data.bin"), new byte[]{'o', 'k', (byte) 0xFF});
			TrialEvidence evidence = evidence(effects, List.of("reply"));

			requireThat(evidence.content("data.bin"), "content").isEqualTo(Optional.of("ok�"));
		}
	}

	/**
	 * Verifies that a trial without tool calls supplies an empty recorded list, so absence checks can decide.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void recordsAnEmptyToolCallList() throws IOException
	{
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path effects = cleanup.add(Files.createTempDirectory("trial-evidence"));
			requireThat(evidence(effects, List.of("reply")).toolCalls(), "toolCalls").isEqualTo(Optional.of(List.of()));
		}
	}

	/**
	 * Verifies that tool evidence preserves repeated calls, invocation order, and unreported values.
	 */
	@Test
	public void preservesRecordedToolCalls()
	{
		// Requirement: R-C9
		List<ToolCall> calls = List.of(new ToolCall("Bash", "true", 0), new ToolCall("Read", null, null),
			new ToolCall("Bash", "false", 1), new ToolCall("Bash", "true", null));
		SessionResult.Completed trial = new SessionResult.Completed(Path.of("transcript"), List.of("done"),
			Path.of("effects"), "model-a", null, null, List.of(), "none", calls);

		requireThat(new TrialEvidence(trial).toolCalls(), "toolCalls").isEqualTo(Optional.of(calls));
	}
}
