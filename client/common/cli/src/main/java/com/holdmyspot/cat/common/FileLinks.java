package com.holdmyspot.cat.common;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads filesystem hard-link counts without following symbolic links or Windows reparse points.
 */
public final class FileLinks
{
	/**
	 * Reads a regular file's hard-link count, refusing unavailable observations.
	 *
	 * @param path file to inspect
	 * @return positive hard-link count
	 * @throws IOException if the file cannot be inspected or is not a regular Windows file
	 */
	public static long linkCount(Path path) throws IOException
	{
		Objects.requireNonNull(path, "path");
		if (Platform.current().isWindows())
			return windowsLinkCount(path);
		try
		{
			long count = ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue();
			if (count < 1)
				throw new IOException("Invalid hard-link count for " + path);
			return count;
		}
		catch (UnsupportedOperationException | ClassCastException exception)
		{
			throw new IOException("Cannot verify hard-link status for " + path, exception);
		}
	}

	/**
	 * Reads type and link count from the caller's already-open Windows handle.
	 *
	 * @param arena owner of temporary native storage
	 * @param symbols kernel32 entry points
	 * @param handle open file handle whose identity must remain unchanged
	 * @return attributes read from that exact handle
	 * @throws IOException if the native observation fails
	 */
	public static WindowsInformation windowsInformation(Arena arena, SymbolLookup symbols, MemorySegment handle)
		throws IOException
	{
		MethodHandle stat = function(symbols, "GetFileInformationByHandle", FunctionDescriptor.of(
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		MemorySegment attributes = arena.allocate(52, 4);
		try
		{
			int status = (int) stat.invokeExact(handle, attributes);
			if (status == 0)
				throw new IOException("Cannot read Windows file handle attributes");
		}
		catch (Throwable exception)
		{
			throw invocationFailure(exception);
		}
		return new WindowsInformation(attributes.get(ValueLayout.JAVA_INT, 0),
			Integer.toUnsignedLong(attributes.get(ValueLayout.JAVA_INT, 40)));
	}

	/**
	 * Prevents construction.
	 */
	private FileLinks()
	{
	}

	/**
	 * Opens a Windows file without following reparse points and reads its hard-link count from that handle.
	 *
	 * @param source file to inspect
	 * @return its positive hard-link count
	 * @throws IOException if opening, observation, validation or closing fails
	 */
	// Native access supplies link counts that the JDK's DOS attribute view does not expose.
	@SuppressWarnings("restricted")
	private static long windowsLinkCount(Path source) throws IOException
	{
		try (Arena arena = Arena.ofConfined())
		{
			SymbolLookup symbols = SymbolLookup.libraryLookup("kernel32", arena);
			MethodHandle open = function(symbols, "CreateFileW", FunctionDescriptor.of(ValueLayout.ADDRESS,
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			String path = source.toAbsolutePath().toString();
			if (path.startsWith("\\\\"))
				path = "\\\\?\\UNC\\" + path.substring(2);
			else
				path = "\\\\?\\" + path;
			MemorySegment handle;
			try
			{
				handle = (MemorySegment) open.invokeExact(arena.allocateFrom(path, StandardCharsets.UTF_16LE),
					0x80, 0, MemorySegment.NULL, 3, 0x00200000, MemorySegment.NULL);
			}
			catch (Throwable exception)
			{
				throw invocationFailure(exception);
			}
			if (handle.address() == -1)
				throw new IOException("Cannot open file without following reparse points: " + source);
			Closeable cleanup = () -> closeWindows(symbols, handle, source);
			try (cleanup)
			{
				WindowsInformation information = windowsInformation(arena, symbols, handle);
				if ((information.attributes() & 0x410) != 0 || information.linkCount() < 1)
					throw new IOException("Cannot verify regular file hard-link status for " + source);
				return information.linkCount();
			}
		}
	}

	/**
	 * Closes the inspected Windows file handle.
	 *
	 * @param symbols kernel32 entry points
	 * @param handle inspected file
	 * @param source path for diagnostics
	 * @throws IOException if closing fails
	 */
	private static void closeWindows(SymbolLookup symbols, MemorySegment handle, Path source) throws IOException
	{
		MethodHandle close = function(symbols, "CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS));
		try
		{
			int status = (int) close.invokeExact(handle);
			if (status == 0)
				throw new IOException("Cannot close inspected file: " + source);
		}
		catch (Throwable exception)
		{
			throw invocationFailure(exception);
		}
	}

	/**
	 * Binds a native file-information operation.
	 *
	 * @param symbols native library
	 * @param name exported function
	 * @param descriptor native function signature
	 * @return bound function
	 */
	// Native access is required to inspect the open handle rather than a separately resolved pathname.
	@SuppressWarnings("restricted")
	private static MethodHandle function(SymbolLookup symbols, String name, FunctionDescriptor descriptor)
	{
		return Linker.nativeLinker().downcallHandle(symbols.findOrThrow(name), descriptor);
	}

	/**
	 * Preserves native invocation failures and lets VM errors propagate.
	 *
	 * @param exception native failure
	 * @return contextual I/O failure
	 */
	private static IOException invocationFailure(Throwable exception)
	{
		if (exception instanceof Error error)
			throw error;
		if (exception instanceof IOException io)
			return io;
		return new IOException("Native file-information operation failed", exception);
	}

	/**
	 * Windows type flags and hard-link count from one handle observation.
	 *
	 * @param attributes Windows file attribute flags
	 * @param linkCount hard-link count
	 */
	public record WindowsInformation(int attributes, long linkCount)
	{
	}
}
