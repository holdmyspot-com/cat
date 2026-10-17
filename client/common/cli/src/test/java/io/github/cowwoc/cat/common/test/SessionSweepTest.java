package io.github.cowwoc.cat.common.test;

import static io.github.cowwoc.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.cat.common.test.SessionHarnesses.HARNESS_B;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.SessionHarness;
import io.github.cowwoc.cat.common.SessionDirectory;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the sweep of {@link SessionDirectory}, which removes the directories of sessions that their harness no
 * longer keeps.
 */
public final class SessionSweepTest
{
	private static final String CURRENT = "6b1f7c1e-5d0a-4c55-9a8e-2f6c0f1d2e3a";
	private static final String OTHER = "01a147d8-bf56-7283-92b4-12dcaeb53738";
	private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
	private static final Instant OLD = NOW.minus(Duration.ofHours(25));

	/**
	 * Creates a test instance.
	 */
	public SessionSweepTest()
	{
	}

	/**
	 * Verifies the sweep removes another session's directory whose marker is older than 24 hours and whose transcript
	 * the first harness's configuration directory no longer holds, while it keeps the current session's directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void removesGoneSessionOfFirstHarness() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sweep-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path configuration = Files.createDirectories(root.resolve("configuration/projects/other-project"));
			Files.writeString(configuration.resolve("unrelated.jsonl"), "{}\n", UTF_8);
			SessionDirectory gone = open(project, OTHER, HARNESS_A, root.resolve("configuration"), OLD);
			SessionDirectory current = open(project, CURRENT, HARNESS_A, root.resolve("configuration"), OLD);
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			current.sweep(NOW, new PrintStream(error, true, UTF_8));

			requireThat(Files.exists(gone.directory()), "goneExists").isFalse();
			requireThat(current.temp(), "currentTemp").isDirectory();
			requireThat(error.toString(UTF_8), "error").isEmpty();
		}
	}

	/**
	 * Verifies the sweep, started by a session of one harness, removes a directory of the other harness's session whose
	 * marker is older than 24 hours and whose transcript is gone.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void removesGoneSessionOfSecondHarness() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sweep-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path configuration = Files.createDirectories(root.resolve("configuration-b/sessions/2026/10/14"));
			Files.writeString(configuration.resolve("rollout-2026-10-14T10-00-00-" + CURRENT + ".jsonl"), "{}\n",
				UTF_8);
			SessionDirectory gone = open(project, OTHER, HARNESS_B, root.resolve("configuration-b"), OLD);
			SessionDirectory current = open(project, CURRENT, HARNESS_A, root.resolve("configuration-a"), NOW);

			current.sweep(NOW, new PrintStream(new ByteArrayOutputStream(), true, UTF_8));

			requireThat(Files.exists(gone.directory()), "goneExists").isFalse();
		}
	}

	/**
	 * Verifies the sweep keeps an old directory whose transcript the configuration directory still holds, for the
	 * flat layout, the nested layout and the archived layout.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsSessionsWithTranscript() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sweep-"));
			Path project = Files.createDirectories(root.resolve("project"));
			String flat = "11111111-1111-4111-8111-111111111111";
			String nested = "22222222-2222-4222-8222-222222222222";
			String archived = "33333333-3333-4333-8333-333333333333";
			Path configurationA = root.resolve("configuration-a");
			Path configurationB = root.resolve("configuration-b");
			writeTranscript(configurationA.resolve("projects/-home-user-project/" + flat + ".jsonl"));
			writeTranscript(configurationB.resolve("sessions/2026/10/14/rollout-2026-10-14T10-00-00-" + nested +
				".jsonl"));
			writeTranscript(configurationB.resolve("archived_sessions/rollout-2026-10-14T10-00-00-" + archived +
				".jsonl"));
			SessionDirectory keptFlat = open(project, flat, HARNESS_A, configurationA, OLD);
			SessionDirectory keptNested = open(project, nested, HARNESS_B, configurationB, OLD);
			SessionDirectory keptArchived = open(project, archived, HARNESS_B, configurationB, OLD);
			SessionDirectory current = open(project, CURRENT, HARNESS_A, configurationA, NOW);
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			current.sweep(NOW, new PrintStream(error, true, UTF_8));

			requireThat(keptFlat.marker(), "flatMarker").isRegularFile();
			requireThat(keptNested.marker(), "nestedMarker").isRegularFile();
			requireThat(keptArchived.marker(), "archivedMarker").isRegularFile();
			requireThat(error.toString(UTF_8), "error").isEmpty();
		}
	}

	/**
	 * Verifies the sweep finds a transcript through a configuration directory that is a symbolic link.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsTranscriptBehindLink() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sweep-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path target = root.resolve("target-configuration");
			writeTranscript(target.resolve("projects/-home-user-project/" + OTHER + ".jsonl"));
			Path link = Files.createSymbolicLink(root.resolve("configuration"), target);
			SessionDirectory kept = open(project, OTHER, HARNESS_A, link, OLD);
			SessionDirectory current = open(project, CURRENT, HARNESS_A, link, NOW);

			current.sweep(NOW, new PrintStream(new ByteArrayOutputStream(), true, UTF_8));

			requireThat(kept.marker(), "keptMarker").isRegularFile();
		}
	}

	/**
	 * Verifies the sweep keeps a directory whose marker is less than 24 hours old even though its transcript is gone,
	 * and a directory without a marker, and that it removes nothing that is not named by a session ID.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsNewAndUnmarkedDirectories() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sweep-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path configuration = root.resolve("configuration");
			SessionDirectory recent = open(project, OTHER, HARNESS_A, configuration, NOW.minus(Duration.ofHours(23)));
			Path unmarked = Files.createDirectories(recent.directory().resolveSibling(
				"44444444-4444-4444-8444-444444444444").resolve("temp"));
			Path legacy = Files.createDirectories(recent.directory().resolveSibling("temp").resolve("old"));
			SessionDirectory current = open(project, CURRENT, HARNESS_A, configuration, NOW);

			current.sweep(NOW, new PrintStream(new ByteArrayOutputStream(), true, UTF_8));

			requireThat(recent.marker(), "recentMarker").isRegularFile();
			requireThat(unmarked, "unmarked").isDirectory();
			requireThat(legacy, "legacy").isDirectory();
		}
	}

	/**
	 * Verifies a directory that cannot be removed is reported on standard error, the sweep returns normally, and it
	 * still removes the other directories that it may remove.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsFailedRemoval() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sweep-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path configuration = root.resolve("configuration");
			String stuck = "55555555-5555-4555-8555-555555555555";
			SessionDirectory stuckDirectory = open(project, stuck, HARNESS_A, configuration, OLD);
			SessionDirectory gone = open(project, OTHER, HARNESS_A, configuration, OLD);
			SessionDirectory current = open(project, CURRENT, HARNESS_A, configuration, NOW);
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			current.sweep(NOW, new PrintStream(error, true, UTF_8), (_, _) ->
			{
			}, directory ->
			{
				if (directory.getFileName().toString().equals(stuck))
					throw new IOException("Directory is busy");
				TemporaryDirectories.deleteRecursively(directory);
			});

			requireThat(stuckDirectory.marker(), "stuckMarker").isRegularFile();
			requireThat(Files.exists(gone.directory()), "goneExists").isFalse();
			requireThat(error.toString(UTF_8), "error").contains(stuckDirectory.directory().toString()).
				contains("Directory is busy");
		}
	}

	/**
	 * Verifies the sweep removes the optimization worktree of each campaign in a gone session's directory, giving the
	 * project and the campaign directory, before it removes the session's directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void removesCampaignWorktreesFirst() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sweep-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path configuration = root.resolve("configuration");
			SessionDirectory gone = open(project, OTHER, HARNESS_A, configuration, OLD);
			Path campaign = Files.createDirectories(gone.campaigns().resolve("20261015-0900-abc123"));
			SessionDirectory current = open(project, CURRENT, HARNESS_A, configuration, NOW);
			List<String> calls = new ArrayList<>();
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			current.sweep(NOW, new PrintStream(error, true, UTF_8),
				(base, directory) -> calls.add("worktree " + base + " " + directory), directory ->
				{
					calls.add("directory " + directory);
					TemporaryDirectories.deleteRecursively(directory);
				});

			Path normalizedProject = project.toAbsolutePath().normalize();
			requireThat(calls, "calls").isEqualTo(List.of("worktree " + normalizedProject + " " + campaign,
				"directory " + gone.directory()));
			requireThat(Files.exists(gone.directory()), "goneExists").isFalse();
			requireThat(error.toString(UTF_8), "error").isEmpty();
		}
	}

	/**
	 * Verifies a campaign worktree that cannot be removed keeps its session's directory, is reported on standard error
	 * with the session's directory, the campaign and the cause, and does not stop the sweep from removing another gone
	 * session's directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void failedWorktreeKeepsSession() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sweep-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path configuration = root.resolve("configuration");
			SessionDirectory stuck = open(project, OTHER, HARNESS_A, configuration, OLD);
			Files.createDirectories(stuck.campaigns().resolve("20261015-0900-abc123"));
			SessionDirectory gone = open(project, "66666666-6666-4666-8666-666666666666", HARNESS_A,
				configuration, OLD);
			SessionDirectory current = open(project, CURRENT, HARNESS_A, configuration, NOW);
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			current.sweep(NOW, new PrintStream(error, true, UTF_8), (_, _) ->
			{
				throw new IOException("git worktree remove failed");
			}, TemporaryDirectories::deleteRecursively);

			requireThat(stuck.marker(), "stuckMarker").isRegularFile();
			requireThat(Files.exists(gone.directory()), "goneExists").isFalse();
			requireThat(error.toString(UTF_8), "error").contains(stuck.directory().toString()).
				contains("20261015-0900-abc123").contains("git worktree remove failed");
		}
	}

	/**
	 * Opens a CAT session directory whose marker records a given creation time.
	 *
	 * @param project the project
	 * @param sessionId the session's ID
	 * @param harness the harness
	 * @param configuration the harness's configuration directory
	 * @param created the marker's creation time
	 * @return the CAT session directory
	 * @throws IOException if the directory cannot be created
	 */
	private static SessionDirectory open(Path project, String sessionId, SessionHarness harness,
		Path configuration, Instant created) throws IOException
	{
		return SessionDirectory.open(project, sessionId, harness, configuration, created,
			JsonMapper.builder().build());
	}

	/**
	 * Writes a transcript file.
	 *
	 * @param transcript the file
	 * @throws IOException if the file cannot be written
	 */
	private static void writeTranscript(Path transcript) throws IOException
	{
		Files.createDirectories(transcript.getParent());
		Files.writeString(transcript, "{}\n", UTF_8);
	}
}
