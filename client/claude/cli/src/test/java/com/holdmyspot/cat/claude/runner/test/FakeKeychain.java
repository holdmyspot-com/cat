package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.ExecutableFiles.ProcessLauncher;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import com.holdmyspot.cat.common.test.ExitedProcess;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for the operating system's process start while a test asks the macOS {@code security} program whether
 * the Keychain holds an item: it records each command and returns, in place of the started program, a process that
 * has already exited with one chosen status and printed nothing. {@code security find-generic-password} exits with
 * status 0 when the Keychain holds the item and with 44 ({@code errSecItemNotFound}) when it does not. No
 * operating-system process starts.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeKeychain implements ProcessLauncher
{
	/**
	 * The status of {@code security find-generic-password} when the Keychain holds the item.
	 */
	public static final int FOUND = 0;
	/**
	 * The status of {@code security find-generic-password} when the Keychain holds no such item.
	 */
	public static final int NOT_FOUND = 44;
	private final int status;
	private final List<List<String>> commands = new ArrayList<>();

	/**
	 * Creates a stand-in that has started nothing.
	 *
	 * @param status the exit status of every command
	 */
	public FakeKeychain(int status)
	{
		this.status = status;
	}

	@Override
	public Process start(ProcessBuilder builder)
	{
		synchronized (this)
		{
			commands.add(List.copyOf(builder.command()));
		}
		return new ExitedProcess(builder, status, "", "");
	}

	/**
	 * Returns the commands that were started.
	 *
	 * @return the commands, in start order
	 */
	public List<List<String>> commands()
	{
		synchronized (this)
		{
			return List.copyOf(commands);
		}
	}

	/**
	 * Returns a scope whose executable files start through this stand-in.
	 *
	 * @param temporaryDirectory the scope's directory for temporary files
	 * @return the scope
	 */
	public TestJvmScope scope(Path temporaryDirectory)
	{
		ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(() ->
		{
		}, new ManualClock(Instant.EPOCH), this);
		return new TestJvmScope(temporaryDirectory, executables);
	}
}
