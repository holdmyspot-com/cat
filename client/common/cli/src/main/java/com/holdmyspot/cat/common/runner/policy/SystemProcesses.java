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
 * Windows reports none, so every reader that needs them refuses there; no request server serves a session on
 * native Windows.
 * <p>
 * The {@link ProcessDescription#start() start} of a Linux process comes from the process folder too: the boot's
 * identifier from {@code sys/kernel/random/boot_id}, and the clock ticks since the boot from field 22 of
 * {@code <pid>/stat}. A process whose files cannot be read reports no start. Elsewhere the start comes from
 * {@link ProcessHandle.Info#startInstant()}.
 */
final class SystemProcesses implements HarnessProcesses
{
	static
	{
		HarnessProcessesSecrets.setHarnessProcessesAccess(SystemProcesses::new);
	}

	/**
	 * The field of {@code <pid>/stat} that holds the clock ticks between the boot and the process's start, counted
	 * from 1.
	 */
	private static final int START_TICKS_FIELD = 22;
	/**
	 * The field of {@code <pid>/stat} that follows the parenthesized program name, counted from 1.
	 */
	private static final int FIRST_FIELD_AFTER_NAME = 3;
	/**
	 * Linux's process folder, which holds the {@code <pid>/cmdline} and {@code <pid>/stat} files and the boot's
	 * identifier, or null to take the arguments and the start from {@link ProcessHandle}.
	 */
	private final Path processFolder;

	/**
	 * Creates the processes of this computer's operating system.
	 *
	 * @return the processes
	 */
	static SystemProcesses current()
	{
		Path processFolder = null;
		if (Platform.current().isLinux())
			processFolder = Path.of("/proc");
		return new SystemProcesses(processFolder);
	}

	/**
	 * Creates processes.
	 *
	 * @param processFolder Linux's process folder, or null to take the arguments and the start of each process from
	 *                      {@link ProcessHandle}
	 */
	private SystemProcesses(Path processFolder)
	{
		this.processFolder = processFolder;
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
		return new ProcessDescription(handle.pid(), start(handle, info), info.command(), arguments(handle, info));
	}

	/**
	 * Returns the start of a process.
	 *
	 * @param handle the process
	 * @param info   what {@link ProcessHandle} reports about it
	 * @return the start, or empty if the system does not report it
	 */
	private Optional<String> start(ProcessHandle handle, ProcessHandle.Info info)
	{
		if (processFolder == null)
			return info.startInstant().map(instant -> String.valueOf(instant.toEpochMilli()));
		String boot;
		String status;
		try
		{
			boot = Files.readString(processFolder.resolve("sys/kernel/random/boot_id"), StandardCharsets.US_ASCII);
			status = Files.readString(processFolder.resolve(String.valueOf(handle.pid())).resolve("stat"),
				StandardCharsets.UTF_8);
		}
		catch (IOException _)
		{
			// The process ended, or the system hides its files
			return Optional.empty();
		}
		return linuxStart(boot, status);
	}

	/**
	 * Returns the start of a Linux process from what the kernel reports.
	 *
	 * @param boot   the content of {@code sys/kernel/random/boot_id}: the boot's identifier, a UUID
	 * @param status the content of the process's {@code stat} file
	 * @return the boot's identifier without hyphens, the letter {@code t}, and the clock ticks between the boot and
	 *   the process's start; or empty if either content has another form
	 */
	private static Optional<String> linuxStart(String boot, String status)
	{
		String bootId = boot.strip().replace("-", "");
		if (!bootId.matches("[0-9a-f]{32}"))
			return Optional.empty();
		// The program's name, in parentheses, may itself hold spaces and parentheses
		int nameEnd = status.lastIndexOf(')');
		if (nameEnd < 0)
			return Optional.empty();
		String[] fields = status.substring(nameEnd + 1).strip().split(" ");
		int index = START_TICKS_FIELD - FIRST_FIELD_AFTER_NAME;
		if (fields.length <= index || !fields[index].matches("[0-9]+"))
			return Optional.empty();
		return Optional.of(bootId + "t" + fields[index]);
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
		if (processFolder == null)
			return info.arguments().map(List::of);
		byte[] commandLine;
		try
		{
			commandLine = Files.readAllBytes(processFolder.resolve(String.valueOf(handle.pid())).
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
