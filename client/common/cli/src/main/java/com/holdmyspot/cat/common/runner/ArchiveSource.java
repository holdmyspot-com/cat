package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.io.Closeable;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies one request archive through a native handle, checking its type and link count on that same handle.
 * Java's public file attributes do not expose attributes of an open channel, and Windows exposes no link count.
 */
final class ArchiveSource
{
	/**
	 * Copies an ordinary, singly linked archive into a private file, with a bound on bytes read.
	 *
	 * @param source      the request file in the runner-owned requests directory
	 * @param destination the new private copy
	 * @throws IOException if opening, validation or bounded copying fails
	 */
	static void copy(Path source, Path destination) throws IOException
	{
		try (Arena arena = Arena.ofConfined();
			OutputStream output = Files.newOutputStream(destination))
		{
			if (Platform.current().isWindows())
				copyWindows(arena, source, output);
			else
				copyUnix(arena, source, output);
		}
	}

	/**
	 * Opens a Unix file without following links or blocking on a FIFO, then uses descriptor attributes and reads.
	 * Linux statx has one layout on both supported architectures; Darwin uses its 64-bit-inode stat ABI.
	 *
	 * @param arena  native memory owner
	 * @param source the archive
	 * @param output the private copy
	 * @throws IOException if the native operations fail or the file is unsafe
	 */
	private static void copyUnix(Arena arena, Path source, OutputStream output) throws IOException
	{
		Platform platform = Platform.current();
		boolean linux = platform.isLinux();
		SymbolLookup symbols = Linker.nativeLinker().defaultLookup();
		MethodHandle open = function(symbols, "open", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		// O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC on each Unix platform. Linux's O_NOFOLLOW differs by architecture: on
		// arm64 the x64 value means O_LARGEFILE, so that open would follow a link.
		int flags = switch (platform)
		{
			case LINUX_X64 -> 0xA0800;
			case LINUX_ARM64 -> 0x88800;
			default -> 0x1000104;
		};
		int descriptor;
		try
		{
			descriptor = (int) open.invokeExact(arena.allocateFrom(source.toString()), flags);
		}
		catch (Throwable exception)
		{
			throw invocationFailure(exception);
		}
		if (descriptor == -1)
			throw new IOException("Cannot open request archive without following links: " + source);
		Closeable cleanup = () -> closeUnix(symbols, descriptor, source);
		try (cleanup)
		{
			checkUnix(arena, symbols, descriptor, linux);
			MethodHandle read = function(symbols, "read", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
				ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
			MemorySegment buffer = arena.allocate(8192);
			long total = 0;
			while (true)
			{
				long count;
				try
				{
					count = (long) read.invokeExact(descriptor, buffer, buffer.byteSize());
				}
				catch (Throwable exception)
				{
					throw invocationFailure(exception);
				}
				if (count == -1)
					throw new IOException("Cannot read request archive: " + source);
				if (count == 0)
					break;
				total += count;
				if (total > RequestArchive.MAXIMUM_ARCHIVE_BYTES)
					throw new IOException("Request archive exceeds " + RequestArchive.MAXIMUM_ARCHIVE_BYTES + " bytes");
				output.write(buffer.asSlice(0, count).toArray(ValueLayout.JAVA_BYTE));
			}
		}
	}

	/**
	 * Closes a Unix descriptor; the caller's resource scope preserves any earlier failure.
	 *
	 * @param symbols    libc entry points
	 * @param descriptor the open file
	 * @param source     the archive path for diagnostics
	 * @throws IOException if closing fails
	 */
	private static void closeUnix(SymbolLookup symbols, int descriptor, Path source) throws IOException
	{
		MethodHandle close = function(symbols, "close", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT));
		try
		{
			int result = (int) close.invokeExact(descriptor);
			if (result == -1)
				throw new IOException("Cannot close request archive: " + source);
		}
		catch (Throwable exception)
		{
			throw invocationFailure(exception);
		}
	}

	/**
	 * Checks regular-file type and one hard link on an already opened Unix descriptor.
	 *
	 * @param arena      native memory owner
	 * @param symbols    libc entry points
	 * @param descriptor the opened file
	 * @param linux      indicates whether the platform uses Linux statx
	 * @throws IOException if attributes are unavailable or unsafe
	 */
	private static void checkUnix(Arena arena, SymbolLookup symbols, int descriptor, boolean linux) throws IOException
	{
		MemorySegment attributes = arena.allocate(256, 8);
		int status;
		int mode;
		long links;
		try
		{
			if (linux)
			{
				MethodHandle stat = function(symbols, "statx", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS));
				status = (int) stat.invokeExact(descriptor, arena.allocateFrom(""), 0x1000, 5, attributes);
				int mask = attributes.get(ValueLayout.JAVA_INT, 0);
				if ((mask & 5) != 5)
					throw new IOException("Request archive file system does not expose type and hard-link count");
				links = Integer.toUnsignedLong(attributes.get(ValueLayout.JAVA_INT, 16));
				mode = Short.toUnsignedInt(attributes.get(ValueLayout.JAVA_SHORT, 28));
			}
			else
			{
				String name = "fstat$INODE64";
				if (Platform.current() == Platform.MACOS_ARM64)
					name = "fstat";
				MethodHandle stat = function(symbols, name, FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
				status = (int) stat.invokeExact(descriptor, attributes);
				mode = Short.toUnsignedInt(attributes.get(ValueLayout.JAVA_SHORT, 4));
				links = Short.toUnsignedInt(attributes.get(ValueLayout.JAVA_SHORT, 6));
			}
		}
		catch (Throwable exception)
		{
			throw invocationFailure(exception);
		}
		if (status != 0)
			throw new IOException("Cannot read request archive attributes");
		if ((mode & 0170000) != 0100000 || links != 1)
			throw new IOException("Request archive must be a regular file with exactly one hard link");
	}

	/**
	 * Uses a Windows handle that neither follows reparse points nor permits concurrent writes or renames.
	 * BY_HANDLE_FILE_INFORMATION supplies the hard-link count omitted by Java's DOS attributes.
	 *
	 * @param arena  native memory owner
	 * @param source the archive
	 * @param output the private copy
	 * @throws IOException if the handle is unsafe or native I/O fails
	 */
	// Native access is required to inspect and read the same file handle.
	@SuppressWarnings("restricted")
	private static void copyWindows(Arena arena, Path source, OutputStream output) throws IOException
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
				0x80000000, 0, MemorySegment.NULL, 3, 0x00200000, MemorySegment.NULL);
		}
		catch (Throwable exception)
		{
			throw invocationFailure(exception);
		}
		if (handle.address() == -1)
			throw new IOException("Cannot open request archive without following reparse points: " + source);
		Closeable cleanup = () -> closeWindows(symbols, handle, source);
		try (cleanup)
		{
			MethodHandle stat = function(symbols, "GetFileInformationByHandle", FunctionDescriptor.of(
				ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			MemorySegment attributes = arena.allocate(52, 4);
			int status;
			try
			{
				status = (int) stat.invokeExact(handle, attributes);
			}
			catch (Throwable exception)
			{
				throw invocationFailure(exception);
			}
			if (status == 0)
				throw new IOException("Cannot read request archive handle attributes");
			int type = attributes.get(ValueLayout.JAVA_INT, 0);
			int links = attributes.get(ValueLayout.JAVA_INT, 40);
			if ((type & 0x410) != 0 || links != 1)
				throw new IOException("Request archive must be a regular file with exactly one hard link");
			MethodHandle read = function(symbols, "ReadFile", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
				ValueLayout.ADDRESS));
			MemorySegment buffer = arena.allocate(8192);
			MemorySegment readCount = arena.allocate(ValueLayout.JAVA_INT);
			long total = 0;
			while (true)
			{
				try
				{
					status = (int) read.invokeExact(handle, buffer, 8192, readCount, MemorySegment.NULL);
				}
				catch (Throwable exception)
				{
					throw invocationFailure(exception);
				}
				if (status == 0)
					throw new IOException("Cannot read request archive: " + source);
				int count = readCount.get(ValueLayout.JAVA_INT, 0);
				if (count == 0)
					break;
				total += count;
				if (total > RequestArchive.MAXIMUM_ARCHIVE_BYTES)
					throw new IOException("Request archive exceeds " + RequestArchive.MAXIMUM_ARCHIVE_BYTES + " bytes");
				output.write(buffer.asSlice(0, count).toArray(ValueLayout.JAVA_BYTE));
			}
		}
	}

	/**
	 * Closes a Windows handle; the caller's resource scope preserves any earlier failure.
	 *
	 * @param symbols kernel32 entry points
	 * @param handle  the opened file
	 * @param source  the archive path for diagnostics
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
				throw new IOException("Cannot close request archive: " + source);
		}
		catch (Throwable exception)
		{
			throw invocationFailure(exception);
		}
	}

	/**
	 * Binds one operating-system operation through the supported foreign function API.
	 *
	 * @param symbols    the native library
	 * @param name       its exported function
	 * @param descriptor the function's ABI
	 * @return the native call
	 */
	// Native access is required to inspect and read the same file handle.
	@SuppressWarnings("restricted")
	private static MethodHandle function(SymbolLookup symbols, String name, FunctionDescriptor descriptor)
	{
		return Linker.nativeLinker().downcallHandle(symbols.findOrThrow(name), descriptor);
	}

	/**
	 * Preserves native invocation failures without treating VM errors as recoverable I/O.
	 *
	 * @param exception the native invocation failure
	 * @return the I/O failure, preserving its cause
	 */
	private static IOException invocationFailure(Throwable exception)
	{
		if (exception instanceof Error error)
			throw error;
		if (exception instanceof IOException io)
			return io;
		return new IOException("Request archive native file operation failed", exception);
	}

	/**
	 * Prevents construction.
	 */
	private ArchiveSource()
	{
	}
}
