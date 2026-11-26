package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts a file in the age format whose file key is wrapped by a passphrase, the {@code scrypt} recipient, as the
 * secrets backend of Codex on Windows keeps a login in {@code secrets/codex_auth.age}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class AgeFile
{
	private static final String VERSION_LINE = "age-encryption.org/v1";
	private static final String SCRYPT_LABEL = "age-encryption.org/v1/scrypt";
	/**
	 * The largest scrypt work factor, as a power of two, that the reader accepts.
	 */
	private static final int MAXIMUM_LOG_N = 20;
	private static final int CHUNK = 64 * 1024;
	private static final int TAG = 16;
	private static final int NONCE = 16;
	private static final int LINE_COLUMNS = 64;

	/**
	 * Prevents construction.
	 */
	private AgeFile()
	{
	}

	/**
	 * Decrypts a file.
	 *
	 * @param file       the file's bytes
	 * @param passphrase the passphrase's bytes
	 * @return the plaintext
	 * @throws NullPointerException if any of the arguments are null
	 * @throws LoginRefusal         if the file is not an age file with a single {@code scrypt} recipient, or the
	 *                              passphrase does not decrypt it, or it was changed; the reason holds no secret
	 */
	// PMD.HardCodedCryptoKey: every key is derived from the passphrase; a zero key is the padding that HMAC and HKDF
	// define for an empty one
	@SuppressWarnings("PMD.HardCodedCryptoKey")
	public static byte[] decrypt(byte[] file, byte[] passphrase) throws LoginRefusal
	{
		requireThat(file, "file").isNotNull();
		requireThat(passphrase, "passphrase").isNotNull();
		try
		{
			int position = 0;
			int lineEnd = lineEnd(file, position);
			if (!text(file, position, lineEnd).equals(VERSION_LINE))
				throw refusal("it does not start with the age header");
			position = lineEnd + 1;
			lineEnd = lineEnd(file, position);
			String[] stanza = text(file, position, lineEnd).split(" ");
			if (stanza.length != 4 || !stanza[0].equals("->") || !stanza[1].equals("scrypt"))
				throw refusal("its recipient is not a passphrase");
			byte[] salt = decode(stanza[2]);
			int logN = Integer.parseInt(stanza[3]);
			if (salt.length != 16 || logN < 1 || logN > MAXIMUM_LOG_N || !stanza[3].equals(String.valueOf(logN)))
				throw refusal("its passphrase recipient is malformed");
			position = lineEnd + 1;
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			while (true)
			{
				lineEnd = lineEnd(file, position);
				String line = text(file, position, lineEnd);
				body.writeBytes(decode(line));
				position = lineEnd + 1;
				if (line.length() < LINE_COLUMNS)
					break;
			}
			lineEnd = lineEnd(file, position);
			String macLine = text(file, position, lineEnd);
			if (!macLine.startsWith("--- "))
				throw refusal("it holds more than one recipient");
			int macStart = position + 3;
			byte[] mac = decode(macLine.substring(4));
			position = lineEnd + 1;

			byte[] saltInput = concat(SCRYPT_LABEL.getBytes(US_ASCII), salt);
			byte[] wrapKey = Scrypt.derive(passphrase, saltInput, 1 << logN, 8, 1, 32);
			byte[] fileKey;
			try
			{
				fileKey = chacha(Cipher.DECRYPT_MODE, wrapKey, new byte[12], body.toByteArray());
			}
			catch (GeneralSecurityException _)
			{
				throw refusal("the passphrase does not decrypt it");
			}
			finally
			{
				Arrays.fill(wrapKey, (byte) 0);
			}
			try
			{
				byte[] headerKey = hkdf(new byte[0], fileKey, "header");
				Mac hmac = Mac.getInstance("HmacSHA256");
				hmac.init(new SecretKeySpec(headerKey, "HmacSHA256"));
				hmac.update(file, 0, macStart);
				if (!MessageDigest.isEqual(hmac.doFinal(), mac))
					throw refusal("its header was changed");
				if (file.length - position < NONCE)
					throw refusal("it is truncated");
				byte[] nonce = Arrays.copyOfRange(file, position, position + NONCE);
				position += NONCE;
				byte[] payloadKey = hkdf(nonce, fileKey, "payload");
				return payload(file, position, payloadKey);
			}
			finally
			{
				Arrays.fill(fileKey, (byte) 0);
			}
		}
		catch (LoginRefusal e)
		{
			throw e;
		}
		catch (GeneralSecurityException | RuntimeException e)
		{
			throw new LoginRefusal("The age file cannot be decrypted: it is malformed", e);
		}
	}

	/**
	 * Decrypts the payload's chunks.
	 *
	 * @param file     the file
	 * @param position the first chunk's position
	 * @param key      the payload key
	 * @return the plaintext
	 * @throws GeneralSecurityException if a chunk was changed
	 * @throws LoginRefusal             if the payload is truncated
	 */
	private static byte[] payload(byte[] file, int position, byte[] key) throws GeneralSecurityException, LoginRefusal
	{
		ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
		long counter = 0;
		while (true)
		{
			int remaining = file.length - position;
			int size = Math.min(remaining, CHUNK + TAG);
			if (size < TAG)
				throw refusal("it is truncated");
			boolean last = remaining <= CHUNK + TAG;
			byte[] nonce = new byte[12];
			for (int i = 0; i < 8; ++i)
				nonce[10 - i] = (byte) (counter >>> (8 * i));
			if (last)
				nonce[11] = 1;
			byte[] chunk = Arrays.copyOfRange(file, position, position + size);
			try
			{
				plaintext.writeBytes(chacha(Cipher.DECRYPT_MODE, key, nonce, chunk));
			}
			catch (GeneralSecurityException _)
			{
				throw refusal("its payload was changed");
			}
			position += size;
			++counter;
			if (last)
				return plaintext.toByteArray();
		}
	}

	/**
	 * Runs ChaCha20-Poly1305.
	 *
	 * @param mode  the cipher's mode
	 * @param key   the key
	 * @param nonce the nonce
	 * @param input the input
	 * @return the output
	 * @throws GeneralSecurityException if the input's tag does not match
	 */
	private static byte[] chacha(int mode, byte[] key, byte[] nonce, byte[] input) throws GeneralSecurityException
	{
		Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
		cipher.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
		return cipher.doFinal(input);
	}

	/**
	 * Derives a 32-byte key with HKDF-SHA-256.
	 *
	 * @param salt the salt
	 * @param key  the input key
	 * @param info the context
	 * @return the key
	 * @throws GeneralSecurityException if HMAC-SHA-256 is unavailable
	 */
	// PMD.HardCodedCryptoKey: every key is derived from the passphrase; a zero key is the padding that HMAC and HKDF
	// define for an empty one
	@SuppressWarnings("PMD.HardCodedCryptoKey")
	private static byte[] hkdf(byte[] salt, byte[] key, String info) throws GeneralSecurityException
	{
		Mac hmac = Mac.getInstance("HmacSHA256");
		byte[] extractSalt = salt;
		if (extractSalt.length == 0)
			extractSalt = new byte[32];
		hmac.init(new SecretKeySpec(extractSalt, "HmacSHA256"));
		byte[] pseudorandom = hmac.doFinal(key);
		hmac.init(new SecretKeySpec(pseudorandom, "HmacSHA256"));
		hmac.update(info.getBytes(US_ASCII));
		hmac.update((byte) 1);
		return hmac.doFinal();
	}

	/**
	 * Returns the index of the line end at or after a position.
	 *
	 * @param file     the file
	 * @param position the position
	 * @return the index of the line feed
	 * @throws LoginRefusal if the header ends first
	 */
	private static int lineEnd(byte[] file, int position) throws LoginRefusal
	{
		for (int i = position; i < file.length; ++i)
		{
			if (file[i] == '\n')
				return i;
		}
		throw refusal("its header is truncated");
	}

	/**
	 * Returns a header line's text.
	 *
	 * @param file  the file
	 * @param start the line's start
	 * @param end   the line's end
	 * @return the text
	 */
	private static String text(byte[] file, int start, int end)
	{
		return new String(file, start, end - start, US_ASCII);
	}

	/**
	 * Decodes unpadded base64.
	 *
	 * @param text the text
	 * @return the bytes
	 * @throws LoginRefusal if the text is not unpadded base64
	 */
	private static byte[] decode(String text) throws LoginRefusal
	{
		if (text.contains("="))
			throw refusal("its header holds padded base64");
		try
		{
			return Base64.getDecoder().decode(text);
		}
		catch (IllegalArgumentException _)
		{
			throw refusal("its header holds malformed base64");
		}
	}

	/**
	 * Concatenates two arrays.
	 *
	 * @param first  the first
	 * @param second the second
	 * @return the concatenation
	 */
	private static byte[] concat(byte[] first, byte[] second)
	{
		byte[] result = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	/**
	 * Returns a refusal.
	 *
	 * @param why why the file cannot be decrypted
	 * @return the refusal
	 */
	private static LoginRefusal refusal(String why)
	{
		return new LoginRefusal("The age file cannot be decrypted: " + why);
	}

	/**
	 * The scrypt key derivation of RFC 7914.
	 */
	static final class Scrypt
	{
		/**
		 * Prevents construction.
		 */
		private Scrypt()
		{
		}

		/**
		 * Derives a key.
		 *
		 * @param password    the password
		 * @param salt        the salt
		 * @param workFactor  the work factor
		 * @param blockSize   the block size
		 * @param parallelism the parallelization
		 * @param length      the key's length
		 * @return the key
		 * @throws GeneralSecurityException if HMAC-SHA-256 is unavailable
		 */
		static byte[] derive(byte[] password, byte[] salt, int workFactor, int blockSize, int parallelism, int length)
			throws GeneralSecurityException
		{
			byte[] blocks = pbkdf2(password, salt, parallelism * 128 * blockSize);
			int[] x = new int[32 * blockSize];
			int[] v = new int[32 * blockSize * workFactor];
			for (int i = 0; i < parallelism; ++i)
				roMix(blocks, i * 128 * blockSize, blockSize, workFactor, x, v);
			byte[] key = pbkdf2(password, blocks, length);
			Arrays.fill(blocks, (byte) 0);
			Arrays.fill(v, 0);
			return key;
		}

		/**
		 * Runs PBKDF2-HMAC-SHA-256 with one iteration.
		 *
		 * @param password the password
		 * @param salt     the salt
		 * @param length   the output's length
		 * @return the output
		 * @throws GeneralSecurityException if HMAC-SHA-256 is unavailable
		 */
		// PMD.HardCodedCryptoKey: every key is derived from the passphrase; a zero key is the padding that HMAC and HKDF
		// define for an empty one
		@SuppressWarnings("PMD.HardCodedCryptoKey")
		private static byte[] pbkdf2(byte[] password, byte[] salt, int length) throws GeneralSecurityException
		{
			Mac hmac = Mac.getInstance("HmacSHA256");
			byte[] key = password;
			if (key.length == 0)
			{
				// An HMAC key may not be empty in Java, and HMAC pads an empty key with zeros anyway
				key = new byte[1];
			}
			hmac.init(new SecretKeySpec(key, "HmacSHA256"));
			byte[] output = new byte[length];
			int block = 1;
			for (int offset = 0; offset < length; offset += 32)
			{
				hmac.update(salt);
				hmac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8),
					(byte) block});
				byte[] t = hmac.doFinal();
				System.arraycopy(t, 0, output, offset, Math.min(32, length - offset));
				++block;
			}
			return output;
		}

		/**
		 * Runs scrypt's ROMix on a block.
		 *
		 * @param bytes      the blocks
		 * @param offset     the block's offset
		 * @param blockSize  the block size
		 * @param workFactor the work factor
		 * @param scratch    a buffer of {@code 32 * blockSize} words
		 * @param table      a buffer of {@code 32 * blockSize * workFactor} words
		 */
		private static void roMix(byte[] bytes, int offset, int blockSize, int workFactor, int[] scratch, int[] table)
		{
			int words = 32 * blockSize;
			for (int i = 0; i < words; ++i)
			{
				int at = offset + 4 * i;
				scratch[i] = (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8 | (bytes[at + 2] & 0xFF) << 16 |
					(bytes[at + 3] & 0xFF) << 24;
			}
			int[] y = new int[words];
			for (int i = 0; i < workFactor; ++i)
			{
				System.arraycopy(scratch, 0, table, i * words, words);
				blockMix(scratch, y, blockSize);
			}
			for (int i = 0; i < workFactor; ++i)
			{
				int j = scratch[(2 * blockSize - 1) * 16] & (workFactor - 1);
				for (int k = 0; k < words; ++k)
					scratch[k] ^= table[j * words + k];
				blockMix(scratch, y, blockSize);
			}
			for (int i = 0; i < words; ++i)
			{
				int at = offset + 4 * i;
				bytes[at] = (byte) scratch[i];
				bytes[at + 1] = (byte) (scratch[i] >>> 8);
				bytes[at + 2] = (byte) (scratch[i] >>> 16);
				bytes[at + 3] = (byte) (scratch[i] >>> 24);
			}
		}

		/**
		 * Runs scrypt's BlockMix with Salsa20/8 in place.
		 *
		 * @param block     the block, {@code 32 * blockSize} words
		 * @param buffer    a buffer of {@code 32 * blockSize} words
		 * @param blockSize the block size
		 */
		private static void blockMix(int[] block, int[] buffer, int blockSize)
		{
			int[] t = Arrays.copyOfRange(block, (2 * blockSize - 1) * 16, 2 * blockSize * 16);
			for (int i = 0; i < 2 * blockSize; ++i)
			{
				for (int k = 0; k < 16; ++k)
					t[k] ^= block[i * 16 + k];
				salsa(t);
				int target;
				if (i % 2 == 0)
					target = (i / 2) * 16;
				else
					target = (blockSize + i / 2) * 16;
				System.arraycopy(t, 0, buffer, target, 16);
			}
			System.arraycopy(buffer, 0, block, 0, 32 * blockSize);
		}

		/**
		 * Runs Salsa20/8 on a 16-word block in place.
		 *
		 * @param block the block
		 */
		private static void salsa(int[] block)
		{
			int[] x = block.clone();
			for (int i = 0; i < 8; i += 2)
			{
				x[4] ^= Integer.rotateLeft(x[0] + x[12], 7);
				x[8] ^= Integer.rotateLeft(x[4] + x[0], 9);
				x[12] ^= Integer.rotateLeft(x[8] + x[4], 13);
				x[0] ^= Integer.rotateLeft(x[12] + x[8], 18);
				x[9] ^= Integer.rotateLeft(x[5] + x[1], 7);
				x[13] ^= Integer.rotateLeft(x[9] + x[5], 9);
				x[1] ^= Integer.rotateLeft(x[13] + x[9], 13);
				x[5] ^= Integer.rotateLeft(x[1] + x[13], 18);
				x[14] ^= Integer.rotateLeft(x[10] + x[6], 7);
				x[2] ^= Integer.rotateLeft(x[14] + x[10], 9);
				x[6] ^= Integer.rotateLeft(x[2] + x[14], 13);
				x[10] ^= Integer.rotateLeft(x[6] + x[2], 18);
				x[3] ^= Integer.rotateLeft(x[15] + x[11], 7);
				x[7] ^= Integer.rotateLeft(x[3] + x[15], 9);
				x[11] ^= Integer.rotateLeft(x[7] + x[3], 13);
				x[15] ^= Integer.rotateLeft(x[11] + x[7], 18);
				x[1] ^= Integer.rotateLeft(x[0] + x[3], 7);
				x[2] ^= Integer.rotateLeft(x[1] + x[0], 9);
				x[3] ^= Integer.rotateLeft(x[2] + x[1], 13);
				x[0] ^= Integer.rotateLeft(x[3] + x[2], 18);
				x[6] ^= Integer.rotateLeft(x[5] + x[4], 7);
				x[7] ^= Integer.rotateLeft(x[6] + x[5], 9);
				x[4] ^= Integer.rotateLeft(x[7] + x[6], 13);
				x[5] ^= Integer.rotateLeft(x[4] + x[7], 18);
				x[11] ^= Integer.rotateLeft(x[10] + x[9], 7);
				x[8] ^= Integer.rotateLeft(x[11] + x[10], 9);
				x[9] ^= Integer.rotateLeft(x[8] + x[11], 13);
				x[10] ^= Integer.rotateLeft(x[9] + x[8], 18);
				x[12] ^= Integer.rotateLeft(x[15] + x[14], 7);
				x[13] ^= Integer.rotateLeft(x[12] + x[15], 9);
				x[14] ^= Integer.rotateLeft(x[13] + x[12], 13);
				x[15] ^= Integer.rotateLeft(x[14] + x[13], 18);
			}
			for (int i = 0; i < 16; ++i)
				block[i] += x[i];
		}
	}
}
