package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

/**
 * A folder that stands for Linux's process folder {@code /proc}, with the files that the kernel would show for the
 * processes that the test names: a process's {@code <pid>/cmdline} and {@code <pid>/stat}, the boot's identifier in
 * {@code sys/kernel/random/boot_id}, and the boot time in {@code stat}, which the kernel derives from the current wall
 * clock and which therefore moves whenever the wall clock is set.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class ProcessFolder
{
	/**
	 * The identifier of the boot that the folder shows unless the test reboots the computer.
	 */
	public static final String BOOT_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
	/**
	 * The number of fields of {@code <pid>/stat} between the process's state and its start, which is field 22.
	 */
	private static final int FIELDS_BEFORE_START = 18;
	private final Path folder;

	/**
	 * Creates the folder's files for the boot {@link #BOOT_ID}, which started at an instant in seconds since the
	 * epoch.
	 *
	 * @param folder   an empty folder
	 * @param bootTime the instant at which the computer started, in seconds since the epoch, as the kernel derives it
	 *                 now
	 * @throws NullPointerException if {@code folder} is null
	 * @throws IOException          if the files cannot be written
	 */
	public ProcessFolder(Path folder, long bootTime) throws IOException
	{
		requireThat(folder, "folder").isNotNull();
		this.folder = folder;
		reboot(BOOT_ID);
		setBootTime(bootTime);
	}

	/**
	 * Returns the folder.
	 *
	 * @return the folder
	 */
	public Path path()
	{
		return folder;
	}

	/**
	 * Shows another boot of the computer.
	 *
	 * @param bootId the boot's identifier, a UUID
	 * @throws NullPointerException if {@code bootId} is null
	 * @throws IOException          if the file cannot be written
	 */
	public void reboot(String bootId) throws IOException
	{
		requireThat(bootId, "bootId").isNotNull();
		Path file = Files.createDirectories(folder.resolve("sys/kernel/random")).resolve("boot_id");
		Files.writeString(file, bootId + "\n", StandardCharsets.US_ASCII);
	}

	/**
	 * Shows the boot time that the kernel derives from the wall clock after it was set.
	 *
	 * @param bootTime the instant at which the computer started, in seconds since the epoch
	 * @throws IOException if the file cannot be written
	 */
	public void setBootTime(long bootTime) throws IOException
	{
		Files.writeString(folder.resolve("stat"), "cpu  1 2 3 4\nbtime " + bootTime + "\nprocesses 42\n",
			StandardCharsets.US_ASCII);
	}

	/**
	 * Shows the start of a process.
	 *
	 * @param pid     the process's identifier
	 * @param program the program's name, which the kernel shows in parentheses
	 * @param ticks   the clock ticks between the boot and the process's start
	 * @throws NullPointerException if {@code program} is null
	 * @throws IOException          if the file cannot be written
	 */
	public void start(long pid, String program, long ticks) throws IOException
	{
		requireThat(program, "program").isNotNull();
		StringJoiner fields = new StringJoiner(" ");
		fields.add(pid + " (" + program + ")").add("S");
		for (int i = 0; i < FIELDS_BEFORE_START; ++i)
			fields.add("0");
		fields.add(String.valueOf(ticks)).add("4096").add("123");
		Files.writeString(processFile(pid, "stat"), fields + "\n", StandardCharsets.US_ASCII);
	}

	/**
	 * Shows the command line of a process.
	 *
	 * @param pid         the process's identifier
	 * @param commandLine the program and the arguments after it
	 * @throws NullPointerException if {@code commandLine} is null
	 * @throws IOException          if the file cannot be written
	 */
	public void writeCommandLine(long pid, List<String> commandLine) throws IOException
	{
		requireThat(commandLine, "commandLine").isNotNull();
		StringBuilder content = new StringBuilder();
		for (String word : commandLine)
			content.append(word).append('\0');
		Files.writeString(processFile(pid, "cmdline"), content, StandardCharsets.UTF_8);
	}

	/**
	 * Returns a file of a process's folder, creating the folder.
	 *
	 * @param pid  the process's identifier
	 * @param name the file's name
	 * @return the file
	 * @throws IOException if the folder cannot be created
	 */
	private Path processFile(long pid, String name) throws IOException
	{
		return Files.createDirectories(folder.resolve(String.valueOf(pid))).resolve(name);
	}
}
