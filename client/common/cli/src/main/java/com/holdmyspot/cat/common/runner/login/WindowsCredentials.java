package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * The Windows keyring, in which the secrets backend of Codex keeps a login: the Windows credential of the service
 * {@code codex} and the account {@code secrets|<h>}, {@code <h>} being the hash of the Codex folder's canonical path,
 * holds the passphrase of the age file {@code secrets/codex_auth.age} in the Codex folder, whose plaintext is the
 * login.
 * <p>
 * It reads the credential with {@code CredReadW}, which needs no prompt in the user's own session, and changes neither
 * the credential nor the file.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class WindowsCredentials implements CodexKeyring
{
	/**
	 * The service of the credential that holds the passphrase.
	 */
	public static final String CREDENTIAL_SERVICE = "codex";
	/**
	 * The age file that holds the login, relative to the Codex folder.
	 */
	public static final String AGE_FILE = "secrets/codex_auth.age";
	private static final int MAXIMUM_FILE_BYTES = 1_048_576;

	/**
	 * Reads a Windows credential's secret.
	 */
	@FunctionalInterface
	public interface CredentialStore
	{
		/**
		 * Reads the secret of the generic credential that a target names.
		 *
		 * @param target the credential's target name
		 * @return the secret's bytes, or empty if no credential has the name
		 * @throws IOException if the store fails
		 */
		Optional<byte[]> read(String target) throws IOException;
	}

	private final Platform platform;
	private final CredentialStore store;

	/**
	 * Creates the keyring that reads the Credential Manager.
	 *
	 * @param platform the platform, which spells the Codex folder's canonical path
	 * @throws NullPointerException if {@code platform} is null
	 */
	public WindowsCredentials(Platform platform)
	{
		this(platform, WindowsCredentials::credRead);
	}

	/**
	 * Creates the keyring.
	 *
	 * @param platform the platform, which spells the Codex folder's canonical path
	 * @param store    reads a credential
	 * @throws NullPointerException if any of the arguments are null
	 */
	public WindowsCredentials(Platform platform, CredentialStore store)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(store, "store").isNotNull();
		this.platform = platform;
		this.store = store;
	}

	/**
	 * Returns the target name of the credential that holds a Codex folder's passphrase, as the keyring library of Codex
	 * names a credential: its account, a period and its service.
	 *
	 * @param canonicalHome the Codex folder's canonical path
	 * @return the target name
	 * @throws NullPointerException if {@code canonicalHome} is null
	 */
	public static String target(String canonicalHome)
	{
		requireThat(canonicalHome, "canonicalHome").isNotNull();
		return "secrets|" + CodexLoginSource.hash(canonicalHome) + "." + CREDENTIAL_SERVICE;
	}

	@Override
	public String name()
	{
		return "the Windows Credential Manager entry \"" + CREDENTIAL_SERVICE + "\"";
	}

	@Override
	public boolean holds(Path codexHome) throws LoginRefusal
	{
		requireThat(codexHome, "codexHome").isNotNull();
		Optional<byte[]> passphrase = passphrase(codexHome);
		passphrase.ifPresent(bytes -> Arrays.fill(bytes, (byte) 0));
		return passphrase.isPresent() && Files.isRegularFile(codexHome.resolve(AGE_FILE));
	}

	@Override
	public CodexStoredLogin read(Path codexHome) throws LoginRefusal
	{
		requireThat(codexHome, "codexHome").isNotNull();
		Optional<byte[]> passphrase = passphrase(codexHome);
		if (passphrase.isEmpty())
		{
			throw new LoginRefusal("The keyring holds no " + name() + " for the Codex folder " + codexHome + ". Sign " +
				"in to Codex on that source home again.");
		}
		Path file = codexHome.resolve(AGE_FILE);
		byte[] encrypted;
		try
		{
			try (InputStream input = Files.newInputStream(file))
			{
				encrypted = input.readNBytes(MAXIMUM_FILE_BYTES + 1);
			}
			if (encrypted.length > MAXIMUM_FILE_BYTES)
				throw new LoginRefusal("The Codex login file " + file + " is larger than CAT reads.");
		}
		catch (NoSuchFileException _)
		{
			Arrays.fill(passphrase.get(), (byte) 0);
			throw new LoginRefusal("The Codex login file " + file + " does not exist, though " + name() + " does. " +
				"Sign in to Codex on that source home again.");
		}
		catch (IOException e)
		{
			Arrays.fill(passphrase.get(), (byte) 0);
			throw new LoginRefusal("The Codex login file " + file + " cannot be read: " + e.getClass().getSimpleName(),
				e);
		}
		byte[] plaintext;
		try
		{
			plaintext = AgeFile.decrypt(encrypted, passphrase.get());
		}
		catch (LoginRefusal e)
		{
			throw new LoginRefusal(e.getMessage() + ". The file is " + file + ", whose passphrase " + name() +
				" holds. Sign in to Codex on that source home again.", e);
		}
		finally
		{
			Arrays.fill(passphrase.get(), (byte) 0);
		}
		try
		{
			return CodexStoredLogin.parse(new String(plaintext, UTF_8), file.toString());
		}
		finally
		{
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	/**
	 * Reads a Codex folder's passphrase.
	 *
	 * @param codexHome the Codex folder
	 * @return the passphrase's bytes, or empty if no credential holds one
	 * @throws LoginRefusal if the store fails
	 */
	private Optional<byte[]> passphrase(Path codexHome) throws LoginRefusal
	{
		String target = target(CodexLoginSource.canonical(codexHome, platform));
		Optional<byte[]> secret;
		try
		{
			secret = store.read(target);
		}
		catch (IOException e)
		{
			throw new LoginRefusal("The request server cannot read " + name() + " of the Codex folder " + codexHome +
				": " + e.getMessage() + ". Set cli_auth_credentials_store = \"file\" in that folder's config.toml and " +
				"sign in to Codex again.", e);
		}
		if (secret.isEmpty())
			return secret;
		// The keyring library of Codex stores a password as UTF-16 text; the age passphrase is its UTF-8 bytes
		byte[] stored = secret.get();
		String text = new String(stored, UTF_16LE);
		Arrays.fill(stored, (byte) 0);
		return Optional.of(text.getBytes(UTF_8));
	}

	/**
	 * Frees the memory that {@code CredReadW} allocated.
	 *
	 * @param free       {@code CredFree}
	 * @param credential the credential
	 */
	private static void free(MethodHandle free, MemorySegment credential)
	{
		try
		{
			free.invokeExact(credential);
		}
		catch (Throwable t)
		{
			// CredFree returns nothing and fails only if the handle does not match its signature
			throw new AssertionError(t);
		}
	}

	/**
	 * Reads a generic credential with {@code CredReadW}.
	 *
	 * @param target the credential's target name
	 * @return the secret, or empty if no credential has the name
	 * @throws IOException if the call fails
	 */
	// Only native code reads the Credential Manager
	@SuppressWarnings("restricted")
	private static Optional<byte[]> credRead(String target) throws IOException
	{
		Linker linker = Linker.nativeLinker();
		MemoryLayout state = Linker.Option.captureStateLayout();
		VarHandle lastError = state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
		try (Arena arena = Arena.ofConfined())
		{
			SymbolLookup advapi = SymbolLookup.libraryLookup("advapi32", arena);
			MethodHandle read = linker.downcallHandle(advapi.findOrThrow("CredReadW"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
					ValueLayout.JAVA_INT, ValueLayout.ADDRESS), Linker.Option.captureCallState("GetLastError"));
			MethodHandle free = linker.downcallHandle(advapi.findOrThrow("CredFree"),
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
			MemorySegment captured = arena.allocate(state);
			MemorySegment pointer = arena.allocate(ValueLayout.ADDRESS);
			int result;
			try
			{
				result = (int) read.invokeExact(captured, arena.allocateFrom(target, UTF_16LE), 1, 0, pointer);
			}
			catch (Throwable t)
			{
				throw new IOException("CredReadW failed", t);
			}
			if (result == 0)
			{
				int error = (int) lastError.get(captured, 0L);
				// ERROR_NOT_FOUND
				if (error == 1168)
					return Optional.empty();
				throw new IOException("CredReadW failed with error " + error);
			}
			MemorySegment credential = pointer.get(ValueLayout.ADDRESS, 0).reinterpret(80);
			try
			{
				int size = credential.get(ValueLayout.JAVA_INT, 32);
				MemorySegment blob = credential.get(ValueLayout.ADDRESS, 40).reinterpret(size);
				byte[] secret = blob.toArray(ValueLayout.JAVA_BYTE);
				blob.fill((byte) 0);
				return Optional.of(secret);
			}
			finally
			{
				free(free, credential);
			}
		}
	}
}
