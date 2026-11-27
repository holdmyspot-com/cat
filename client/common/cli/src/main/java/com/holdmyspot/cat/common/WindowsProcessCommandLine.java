package com.holdmyspot.cat.common;

import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Reads the command line of a Windows process from Windows itself, because {@link ProcessHandle.Info#arguments()} and
 * {@link ProcessHandle.Info#commandLine()} report none there.
 * <p>
 * The read opens the process with the least access that Windows asks for ({@code PROCESS_QUERY_LIMITED_INFORMATION}),
 * so it also reads the command line of a process of the same user that a limited token started, and asks
 * {@code NtQueryInformationProcess} for the {@code ProcessCommandLineInformation} class.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class WindowsProcessCommandLine
{
	/**
	 * The access right that lets a caller query a process's limited information.
	 */
	private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
	/**
	 * The information class that returns a process's command line.
	 */
	private static final int PROCESS_COMMAND_LINE_INFORMATION = 60;
	/**
	 * The largest process identifier, which Windows holds in 32 bits without a sign.
	 */
	private static final long MAXIMUM_PROCESS_ID = 0xFFFF_FFFFL;
	/**
	 * The offset of the text's address in the {@code UNICODE_STRING} that the query returns.
	 */
	private static final int BUFFER_OFFSET = 8;

	/**
	 * Prevents construction.
	 */
	private WindowsProcessCommandLine()
	{
	}

	/**
	 * Reads the command line of a process.
	 *
	 * @param processId the process's identifier
	 * @return the command line, or empty if Windows does not let the caller open or read the process, or the process
	 *   has ended
	 * @throws IOException if Windows cannot be asked
	 */
	// Only native code reads another process's command line
	@SuppressWarnings("restricted")
	public static Optional<String> read(long processId) throws IOException
	{
		if (processId < 0 || processId > MAXIMUM_PROCESS_ID)
			return Optional.empty();
		Linker linker = Linker.nativeLinker();
		try (Arena arena = Arena.ofConfined())
		{
			SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", arena);
			SymbolLookup ntdll = SymbolLookup.libraryLookup("ntdll", arena);
			MethodHandle open = linker.downcallHandle(kernel.findOrThrow("OpenProcess"),
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
					ValueLayout.JAVA_INT));
			MethodHandle close = linker.downcallHandle(kernel.findOrThrow("CloseHandle"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			MethodHandle query = linker.downcallHandle(ntdll.findOrThrow("NtQueryInformationProcess"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			MemorySegment process = (MemorySegment) open.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0,
				(int) processId);
			if (process.equals(MemorySegment.NULL))
			{
				// Windows does not let the caller open the process, or the process has ended
				return Optional.empty();
			}
			try
			{
				MemorySegment needed = arena.allocate(ValueLayout.JAVA_INT);
				// The first call fails with a length mismatch and reports the size that the second call needs
				int ignored = (int) query.invokeExact(process, PROCESS_COMMAND_LINE_INFORMATION, MemorySegment.NULL, 0,
					needed);
				int size = needed.get(ValueLayout.JAVA_INT, 0);
				if (size <= BUFFER_OFFSET)
					return Optional.empty();
				MemorySegment buffer = arena.allocate(size, 8);
				int status = (int) query.invokeExact(process, PROCESS_COMMAND_LINE_INFORMATION, buffer, size, needed);
				if (status != 0)
					return Optional.empty();
				int length = buffer.get(ValueLayout.JAVA_SHORT, 0) & 0xFFFF;
				MemorySegment text = buffer.get(ValueLayout.ADDRESS, BUFFER_OFFSET).reinterpret(length);
				return Optional.of(new String(text.toArray(ValueLayout.JAVA_BYTE), UTF_16LE));
			}
			finally
			{
				int ignoredResult = (int) close.invokeExact(process);
			}
		}
		catch (RuntimeException | LinkageError e)
		{
			throw new IOException("The command line of process " + processId + " cannot be read: " + e, e);
		}
		catch (IOException | Error e)
		{
			throw e;
		}
		catch (Throwable t)
		{
			throw new IOException("The command line of process " + processId + " cannot be read", t);
		}
	}
}
