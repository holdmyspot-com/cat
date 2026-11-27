package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
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
 * Reads the registry of Windows.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class WindowsRegistry
{
	/**
	 * {@code HKEY_LOCAL_MACHINE}, which is the sign-extended {@code 0x80000002}.
	 */
	private static final long HKEY_LOCAL_MACHINE = 0xFFFFFFFF80000002L;
	/**
	 * {@code RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ | RRF_SUBKEY_WOW64_64KEY}: a text value, whose environment variables
	 * Windows expands, read from the 64-bit view of the registry.
	 */
	private static final int TEXT_FROM_64_BIT_VIEW = 0x2 | 0x4 | 0x10000;
	private static final int ERROR_FILE_NOT_FOUND = 2;
	private static final int ERROR_PATH_NOT_FOUND = 3;
	private static final int ERROR_MORE_DATA = 234;
	/**
	 * The number of times that a read repeats because the value grew between asking its size and reading it.
	 */
	private static final int READ_ATTEMPTS = 3;

	/**
	 * Prevents construction.
	 */
	private WindowsRegistry()
	{
	}

	/**
	 * Reads a text value of {@code HKEY_LOCAL_MACHINE}, from the 64-bit view of the registry.
	 *
	 * @param subkey the key below {@code HKEY_LOCAL_MACHINE}, such as {@code SOFTWARE\Policies\OpenAI\Codex}
	 * @param name   the value's name
	 * @return the value, with the environment variables of an expandable value expanded, or empty if the key or the
	 *         value does not exist
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if Windows cannot be asked, or it fails, such as when the value is not text or the
	 *                              user may not read the key
	 */
	// Only native code reads the registry
	@SuppressWarnings("restricted")
	public static Optional<String> machineText(String subkey, String name) throws IOException
	{
		requireThat(subkey, "subkey").isNotNull();
		requireThat(name, "name").isNotNull();
		Linker linker = Linker.nativeLinker();
		try (Arena arena = Arena.ofConfined())
		{
			SymbolLookup advapi = SymbolLookup.libraryLookup("advapi32", arena);
			MethodHandle read = linker.downcallHandle(advapi.findOrThrow("RegGetValueW"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS));
			MemorySegment root = MemorySegment.ofAddress(HKEY_LOCAL_MACHINE);
			MemorySegment key = arena.allocateFrom(subkey, UTF_16LE);
			MemorySegment value = arena.allocateFrom(name, UTF_16LE);
			MemorySegment size = arena.allocate(ValueLayout.JAVA_INT);
			for (int attempt = 0; attempt < READ_ATTEMPTS; ++attempt)
			{
				int result = (int) read.invokeExact(root, key, value, TEXT_FROM_64_BIT_VIEW, MemorySegment.NULL,
					MemorySegment.NULL, size);
				if (result == ERROR_FILE_NOT_FOUND || result == ERROR_PATH_NOT_FOUND)
					return Optional.empty();
				if (result != 0)
					throw failure(subkey, name, result);
				MemorySegment buffer = arena.allocate(Integer.toUnsignedLong(size.get(ValueLayout.JAVA_INT, 0)));
				result = (int) read.invokeExact(root, key, value, TEXT_FROM_64_BIT_VIEW, MemorySegment.NULL, buffer,
					size);
				if (result == ERROR_MORE_DATA)
					continue;
				if (result == ERROR_FILE_NOT_FOUND || result == ERROR_PATH_NOT_FOUND)
					return Optional.empty();
				if (result != 0)
					throw failure(subkey, name, result);
				return Optional.of(buffer.getString(0, UTF_16LE));
			}
			throw new IOException("The registry value HKLM\\" + subkey + " " + name + " kept changing size");
		}
		catch (RuntimeException | LinkageError e)
		{
			throw new IOException("RegGetValueW cannot be called: " + e, e);
		}
		catch (IOException | Error e)
		{
			throw e;
		}
		catch (Throwable t)
		{
			throw new IOException("RegGetValueW failed", t);
		}
	}

	/**
	 * Describes a failed read.
	 *
	 * @param subkey the key
	 * @param name   the value's name
	 * @param error  the Windows error code
	 * @return the exception
	 */
	private static IOException failure(String subkey, String name, int error)
	{
		return new IOException("RegGetValueW failed with error " + error + " for the registry value HKLM\\" +
			subkey + " " + name);
	}
}
