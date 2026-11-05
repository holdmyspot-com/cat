package com.holdmyspot.cat.claude.policy.test;

import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReading;
import com.holdmyspot.cat.claude.policy.ClaudeSession;
import com.holdmyspot.cat.common.runner.policy.HarnessProcess;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Claude Code session whose files lie in a temporary folder that stands for the user's file system, and whose
 * process is a fake that reports a command line that the test chooses.
 */
final class ClaudeSessionFixture implements AutoCloseable
{
	/**
	 * The ID of the fake Claude Code process.
	 */
	static final long PROCESS_ID = 4242;
	/**
	 * The instant that the fake process started.
	 */
	static final Instant START = Instant.parse("2026-11-05T12:00:00Z");
	/**
	 * The temporary folders.
	 */
	private final TemporaryDirectories cleanup = new TemporaryDirectories();
	/**
	 * The folder that stands for the file system's root.
	 */
	final Path root;
	/**
	 * The project folder.
	 */
	final Path project;
	/**
	 * The user's home folder.
	 */
	final Path home;
	/**
	 * Claude Code's configuration folder.
	 */
	final Path configuration;
	/**
	 * The folder of the managed settings.
	 */
	final Path managed;
	/**
	 * The folder of the managed settings that Claude Code reads only in some configurations.
	 */
	final Path otherManaged;
	/**
	 * A file that may hold managed settings in a form that CAT cannot read.
	 */
	final Path unreadableManaged;
	/**
	 * The arguments of the fake process.
	 */
	final List<String> arguments = new ArrayList<>();
	/**
	 * The instant that the fake process reports as its start.
	 */
	Instant reportedStart = START;
	/**
	 * Whether the fake process is running.
	 */
	boolean running = true;

	/**
	 * Creates a fixture.
	 *
	 * @throws IOException if the folders cannot be created
	 */
	ClaudeSessionFixture() throws IOException
	{
		root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
		project = Files.createDirectories(root.resolve("project"));
		home = Files.createDirectories(root.resolve("home"));
		configuration = Files.createDirectories(home.resolve(".claude"));
		managed = root.resolve("etc/claude-code");
		otherManaged = root.resolve("mnt/c/Program Files/ClaudeCode");
		unreadableManaged = root.resolve("Library/Managed Preferences/com.anthropic.claudecode.plist");
	}

	/**
	 * Writes a file, creating its folder.
	 *
	 * @param file    the file
	 * @param content the content
	 * @return {@code file}
	 * @throws IOException if the file cannot be written
	 */
	static Path write(Path file, String content) throws IOException
	{
		Files.createDirectories(file.getParent());
		return Files.writeString(file, content);
	}

	/**
	 * Writes the user's settings.
	 *
	 * @param json the settings
	 * @throws IOException if the file cannot be written
	 */
	void userSettings(String json) throws IOException
	{
		write(configuration.resolve("settings.json"), json);
	}

	/**
	 * Writes the project's shared settings.
	 *
	 * @param json the settings
	 * @throws IOException if the file cannot be written
	 */
	void projectSettings(String json) throws IOException
	{
		write(project.resolve(".claude/settings.json"), json);
	}

	/**
	 * Writes the project's local settings.
	 *
	 * @param json the settings
	 * @throws IOException if the file cannot be written
	 */
	void localSettings(String json) throws IOException
	{
		write(project.resolve(".claude/settings.local.json"), json);
	}

	/**
	 * Returns the session.
	 *
	 * @return the session
	 */
	ClaudeSession session()
	{
		return new ClaudeSession(PROCESS_ID, project, home, configuration, Optional.empty(), managed,
			List.of(otherManaged), List.of(unreadableManaged));
	}

	/**
	 * Returns a reader whose processes are the fake process.
	 *
	 * @return the reader
	 */
	ClaudePolicyReader reader()
	{
		return new ClaudePolicyReader(processId ->
		{
			if (processId != PROCESS_ID || !running)
				return Optional.empty();
			return Optional.of(new HarnessProcess(processId, Optional.of(reportedStart),
				Optional.of("/usr/local/bin/claude"), Optional.of(arguments)));
		});
	}

	/**
	 * Reads the session's policy.
	 *
	 * @return the reading
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the policy is refused
	 */
	ClaudePolicyReading read() throws IOException, PolicyRefusal
	{
		return reader().read(session());
	}

	/**
	 * Reads the session's policy.
	 *
	 * @return the policy
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the policy is refused
	 */
	ParentPolicy policy() throws IOException, PolicyRefusal
	{
		return read().policy();
	}

	/**
	 * Reads the session's policy, which the test expects to be refused.
	 *
	 * @return the message that the user sees
	 * @throws IOException if a file cannot be read
	 */
	String refusal() throws IOException
	{
		try
		{
			ParentPolicy policy = policy();
			throw new AssertionError("The policy was not refused: " + policy);
		}
		catch (PolicyRefusal e)
		{
			return e.messageFor(ClaudePolicyReader.HARNESS_NAME);
		}
	}

	@Override
	public void close() throws IOException
	{
		cleanup.close();
	}
}
