package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests raw evidence retention by {@link ClaudeNativeInstructionsLoadedCapture}.
 */
public final class ClaudeNativeInstructionsLoadedCaptureTest
{
	/**
	 * Verifies duplicate raw arrivals remain distinct and a pending writer limits coverage.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsDistinctRawEventsAndPendingCount() throws IOException
	{
		try (HarnessRunnerScope scope = new HarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-native-capture-"));
			HarnessRun run = new HarnessRun("claude", "abc123-def456", root.resolve("run"));
			Path command = ClaudeNativeInstructionsLoadedCapture.install(run);
			Path inbox = command.getParent();
			byte[] event = "{\"file_path\":\"/project/CLAUDE.md\"}\n".getBytes(StandardCharsets.UTF_8);
			Files.write(inbox.resolve(".pending.abcdefgh.json"), event);
			Files.write(inbox.resolve(".pending.ijklmnop.json"), event);
			Files.writeString(inbox.resolve(".pending.qrstuvwx"), "incomplete");

			ClaudeNativeInstructionsLoadedCapture.retain(run);

			requireThat(new String(ClaudeNativeInstructionsLoadedCapture.readRaw(run, 1), StandardCharsets.UTF_8),
				"firstEvent").isEqualTo(new String(event, StandardCharsets.UTF_8));
			requireThat(new String(ClaudeNativeInstructionsLoadedCapture.readRaw(run, 2), StandardCharsets.UTF_8),
				"secondEvent").isEqualTo(new String(event, StandardCharsets.UTF_8));
			JsonNode index = scope.json().readTree(Files.readString(run.directory().resolve(
				"artifacts/native-instructions-loaded/index.json")));
			requireThat(index.path("state").asString(), "state").isEqualTo("LIMITED");
			requireThat(index.path("pendingFileCount").asInt(), "pendingFileCount").isEqualTo(1);
			requireThat(Files.getPosixFilePermissions(inbox), "inboxPermissions").isEqualTo(Set.of(
				java.nio.file.attribute.PosixFilePermission.OWNER_READ,
				java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
				java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
		}
	}

	/**
	 * Verifies a symlink cannot be retained as a native event.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*contains a symlink.*")
	public void rejectsSymlinkEvent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-native-symlink-"));
			HarnessRun run = new HarnessRun("claude", "abc123-def456", root.resolve("run"));
			Path inbox = ClaudeNativeInstructionsLoadedCapture.install(run).getParent();
			Files.createSymbolicLink(inbox.resolve(".pending.abcdefgh.json"), root.resolve("outside"));
			ClaudeNativeInstructionsLoadedCapture.retain(run);
		}
	}

	/**
	 * Verifies a claimed event with altered bytes is refused.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*hash mismatch.*")
	public void rejectsCorruptClaimedEvent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-native-corrupt-"));
			HarnessRun run = new HarnessRun("claude", "abc123-def456", root.resolve("run"));
			Path inbox = ClaudeNativeInstructionsLoadedCapture.install(run).getParent();
			Files.writeString(inbox.resolve(".pending.abcdefgh.json"), "original");
			ClaudeNativeInstructionsLoadedCapture.retain(run);
			Files.writeString(run.directory().resolve("artifacts/native-instructions-loaded/000001.json"), "changed");
			ClaudeNativeInstructionsLoadedCapture.readRaw(run, 1);
		}
	}
}
