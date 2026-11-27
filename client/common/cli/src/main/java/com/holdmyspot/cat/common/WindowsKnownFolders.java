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
import java.nio.file.Path;

/**
 * Asks Windows for the location of one of its known folders, as its shell does.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class WindowsKnownFolders
{
	/**
	 * The bytes of {@code FOLDERID_ProgramData}, {62AB5D82-FDC1-4DC3-A9DD-070D1D495D97}, whose last eight bytes keep
	 * their order.
	 */
	private static final byte[] PROGRAM_DATA_LAST_BYTES = {(byte) 0xA9, (byte) 0xDD, 0x07, 0x0D, 0x1D, 0x49, 0x5D,
		(byte) 0x97};
	/**
	 * The size of a {@code GUID}.
	 */
	private static final int GUID_BYTES = 16;

	/**
	 * Prevents construction.
	 */
	private WindowsKnownFolders()
	{
	}

	/**
	 * Returns the folder that holds the program data that every user of the machine shares, which is the one that
	 * {@code SHGetKnownFolderPath(FOLDERID_ProgramData)} names. It is usually {@code C:\ProgramData}, but the
	 * {@code ProgramData} environment variable does not decide it.
	 *
	 * @return the absolute folder
	 * @throws IOException if Windows cannot be asked, or it fails or answers with a relative path
	 */
	// Only native code reads a known folder's location
	@SuppressWarnings("restricted")
	public static Path programData() throws IOException
	{
		Linker linker = Linker.nativeLinker();
		try (Arena arena = Arena.ofConfined())
		{
			SymbolLookup shell = SymbolLookup.libraryLookup("shell32", arena);
			SymbolLookup ole = SymbolLookup.libraryLookup("ole32", arena);
			MethodHandle get = linker.downcallHandle(shell.findOrThrow("SHGetKnownFolderPath"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			MethodHandle free = linker.downcallHandle(ole.findOrThrow("CoTaskMemFree"),
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
			MemorySegment id = arena.allocate(GUID_BYTES, 4);
			id.set(ValueLayout.JAVA_INT, 0, 0x62AB5D82);
			id.set(ValueLayout.JAVA_SHORT, 4, (short) 0xFDC1);
			id.set(ValueLayout.JAVA_SHORT, 6, (short) 0x4DC3);
			MemorySegment.copy(PROGRAM_DATA_LAST_BYTES, 0, id, ValueLayout.JAVA_BYTE, 8, PROGRAM_DATA_LAST_BYTES.length);
			MemorySegment answer = arena.allocate(ValueLayout.ADDRESS);
			int result = (int) get.invokeExact(id, 0, MemorySegment.NULL, answer);
			MemorySegment text = answer.get(ValueLayout.ADDRESS, 0);
			try
			{
				if (result != 0)
				{
					throw new IOException("SHGetKnownFolderPath failed with HRESULT 0x" +
						Integer.toHexString(result));
				}
				String path = text.reinterpret(Long.MAX_VALUE).getString(0, UTF_16LE);
				Path folder = Path.of(path);
				if (!folder.isAbsolute())
					throw new IOException("SHGetKnownFolderPath answered with the relative path " + path);
				return folder;
			}
			finally
			{
				// Windows allocates the text even when the call fails
				if (!text.equals(MemorySegment.NULL))
					free.invokeExact(text);
			}
		}
		catch (RuntimeException | LinkageError e)
		{
			throw new IOException("SHGetKnownFolderPath cannot be called: " + e, e);
		}
		catch (IOException | Error e)
		{
			throw e;
		}
		catch (Throwable t)
		{
			throw new IOException("SHGetKnownFolderPath failed", t);
		}
	}
}
