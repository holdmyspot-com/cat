package com.holdmyspot.cat.common.runner.policy;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The running processes that the operating system reports.
 * <p>
 * On Linux, {@link ProcessHandle.Info#arguments()} reports no arguments for a process whose command line exceeds one
 * memory page (4 KiB), such as a harness started with long inline settings. The arguments of a Linux process
 * therefore come from its {@code cmdline} file in the process folder; a process whose file cannot be read reports
 * none, and every reader that needs them refuses. macOS reports every argument through {@link ProcessHandle}.
 * Windows reports none, so every reader that needs them refuses there; no session launcher is served on native
 * Windows.
 */
final class SystemProcesses implements HarnessProcesses
{
	static
	{
		HarnessProcessesSecrets.setHarnessProcessesAccess(SystemProcesses::new);
	}

	/**
	 * The folder that holds the {@code <pid>/cmdline} files, or null to take the arguments from
	 * {@link ProcessHandle}.
	 */
	private final Path commandLineFolder;

	/**
	 * Creates the processes of this computer's operating system.
	 *
	 * @return the processes
	 */
	static SystemProcesses current()
	{
		Path commandLineFolder = null;
		if (Platform.current().isLinux())
			commandLineFolder = Path.of("/proc");
		return new SystemProcesses(commandLineFolder);
	}

	/**
	 * Creates processes.
	 *
	 * @param commandLineFolder the folder that holds the {@code <pid>/cmdline} files of Linux processes, or null to
	 *                          take the arguments from {@link ProcessHandle}
	 */
	private SystemProcesses(Path commandLineFolder)
	{
		this.commandLineFolder = commandLineFolder;
	}

	@Override
	public Optional<ProcessDescription> find(long processId)
	{
		return ProcessHandle.of(processId).
			filter(ProcessHandle::isAlive).
			map(this::describe);
	}

	/**
	 * Returns the processes that started a process, the nearest first.
	 *
	 * @param process the process
	 * @return the ancestors of {@code process}, starting with its parent
	 */
	List<ProcessDescription> ancestryOf(ProcessHandle process)
	{
		List<ProcessDescription> ancestors = new ArrayList<>();
		Optional<ProcessHandle> parent = process.parent();
		while (parent.isPresent())
		{
			ProcessHandle handle = parent.get();
			ancestors.add(describe(handle));
			parent = handle.parent();
		}
		return ancestors;
	}

	/**
	 * Returns what the operating system reports about a process.
	 *
	 * @param handle the process
	 * @return the process's description
	 */
	private ProcessDescription describe(ProcessHandle handle)
	{
		ProcessHandle.Info info = handle.info();
		return new ProcessDescription(handle.pid(), info.startInstant(), info.command(), arguments(handle, info));
	}

	/**
	 * Returns the arguments of a process after its program.
	 *
	 * @param handle the process
	 * @param info   what {@link ProcessHandle} reports about it
	 * @return the arguments, or empty if the system does not report them
	 */
	private Optional<List<String>> arguments(ProcessHandle handle, ProcessHandle.Info info)
	{
		if (commandLineFolder == null)
			return info.arguments().map(List::of);
		byte[] commandLine;
		try
		{
			commandLine = Files.readAllBytes(commandLineFolder.resolve(String.valueOf(handle.pid())).
				resolve("cmdline"));
		}
		catch (IOException _)
		{
			// The process ended, or the system hides its command line
			return Optional.empty();
		}
		// A process that has ended but not been reaped yet has an empty command line
		if (commandLine.length == 0)
			return Optional.empty();
		// Each argument, the program's included, ends with a NUL
		int end = commandLine.length;
		if (commandLine[end - 1] == 0)
			--end;
		String[] words = new String(commandLine, 0, end, StandardCharsets.UTF_8).split("\0", -1);
		return Optional.of(List.of(words).subList(1, words.length));
	}
}
