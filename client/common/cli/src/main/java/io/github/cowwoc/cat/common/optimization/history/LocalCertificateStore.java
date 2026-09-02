package io.github.cowwoc.cat.common.optimization.history;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns one installation's persistent Ed25519 identity and locally signed certificate-history operations.
 */
public final class LocalCertificateStore
{
	private static final String PRIVATE_KEY_FILE = "installation-key.pk8";
	private static final String PUBLIC_KEY_FILE = "installation-key.spki";
	private static final int MAXIMUM_KEY_DOCUMENT_BYTES = 16 * 1024;
	private static final Set<java.nio.file.attribute.PosixFilePermission> PRIVATE_PERMISSIONS =
		Set.of(OWNER_READ, OWNER_WRITE);
	private final PrivateKey privateKey;
	private final PublicKey publicKey;
	private final String keyIdentifier;

	/**
	 * Creates one loaded installation key store.
	 *
	 * @param privateKey the installation private key
	 * @param publicKey the installation public key
	 */
	private LocalCertificateStore(PrivateKey privateKey, PublicKey publicKey)
	{
		this.privateKey = Objects.requireNonNull(privateKey, "privateKey must not be null");
		this.publicKey = Objects.requireNonNull(publicKey, "publicKey must not be null");
		this.keyIdentifier = sha256(publicKey.getEncoded());
	}

	/**
	 * Opens or creates one installation identity below an explicit client-owned directory.
	 *
	 * @param root the installation-key directory
	 * @return the persistent local certificate store
	 * @throws IOException if the directory or encoded key pair is unsafe, incomplete, or inaccessible
	 */
	public static LocalCertificateStore open(Path root) throws IOException
	{
		Objects.requireNonNull(root, "root must not be null");
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Files.createDirectories(normalizedRoot);
		if (Files.isSymbolicLink(normalizedRoot))
			throw new IOException("Local certificate key directory must not be a symbolic link: " + normalizedRoot);
		Path privatePath = normalizedRoot.resolve(PRIVATE_KEY_FILE);
		Path publicPath = normalizedRoot.resolve(PUBLIC_KEY_FILE);
		RecordStore.withLock(privatePath, () -> ensureKeyPair(privatePath, publicPath));

		try
		{
			KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
			PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(readKey(privatePath)));
			PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(readKey(publicPath)));
			return new LocalCertificateStore(privateKey, publicKey);
		}
		catch (GeneralSecurityException e)
		{
			throw new IOException("Local certificate installation key is invalid: " + normalizedRoot, e);
		}
	}

	/**
	 * Signs one immutable certificate payload with this installation identity.
	 *
	 * @param payload the certificate payload
	 * @return the locally signed certificate
	 * @throws GeneralSecurityException if the platform cannot create the Ed25519 signature
	 */
	public LocalCertificate sign(LocalCertificate.Payload payload) throws GeneralSecurityException
	{
		return LocalCertificate.sign(payload, privateKey, keyIdentifier);
	}

	/**
	 * Writes one locally signed certificate document.
	 *
	 * @param json the JSON codec
	 * @param destination the history destination
	 * @param certificate the certificate to write
	 * @throws IOException if the certificate belongs to another key identifier or cannot be written
	 */
	public void write(JsonMapper json, Path destination, LocalCertificate certificate) throws IOException
	{
		Objects.requireNonNull(certificate, "certificate must not be null");
		if (!certificate.keyIdentifier().equals(keyIdentifier))
			throw new IOException("Certificate key identifier does not match this installation");
		LocalCertificateDocument.write(json, destination, certificate);
	}

	/**
	 * Reads and verifies one historical certificate against current retained evidence.
	 *
	 * @param json the JSON codec
	 * @param source the certificate document
	 * @param availableEvidenceFingerprints the evidence currently retained on this installation
	 * @return the imported certificate and current local verification state
	 * @throws IOException if the document is malformed or names another local key identifier
	 * @throws GeneralSecurityException if Ed25519 verification is unavailable
	 */
	public VerifiedCertificate readAndVerify(JsonMapper json, Path source, Set<String> availableEvidenceFingerprints)
		throws IOException, GeneralSecurityException
	{
		LocalCertificate certificate = LocalCertificateDocument.read(json, source);
		if (!certificate.keyIdentifier().equals(keyIdentifier))
			throw new IOException("Certificate names another installation key: " + source);
		return new VerifiedCertificate(certificate, certificate.verify(publicKey, availableEvidenceFingerprints));
	}

	/**
	 * Returns the lowercase SHA-256 identity of the installation public key.
	 *
	 * @return the key identifier
	 */
	public String keyIdentifier()
	{
		return keyIdentifier;
	}

	/**
	 * Creates the key pair when neither committed key document exists.
	 *
	 * @param privatePath the private-key path and commit marker
	 * @param publicPath the public-key path
	 * @throws IOException if key generation or publication fails
	 */
	private static void ensureKeyPair(Path privatePath, Path publicPath) throws IOException
	{
		boolean privateExists = Files.exists(privatePath, NOFOLLOW_LINKS);
		if (privateExists)
		{
			if (!Files.isRegularFile(privatePath, NOFOLLOW_LINKS) || !Files.isRegularFile(publicPath, NOFOLLOW_LINKS))
				throw new IOException("Local certificate installation key pair is incomplete or unsafe: " + privatePath);
			applyPrivatePermissions(privatePath);
			return;
		}
		if (Files.isSymbolicLink(publicPath))
			throw new IOException("Local certificate public key path must not be a symbolic link: " + publicPath);

		try
		{
			KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
			writeKey(publicPath, keyPair.getPublic().getEncoded(), true);
			writeKey(privatePath, keyPair.getPrivate().getEncoded(), false);
		}
		catch (GeneralSecurityException e)
		{
			throw new IOException("Ed25519 key generation is unavailable", e);
		}
	}

	/**
	 * Publishes one canonical Base64 key document atomically.
	 *
	 * @param destination the destination
	 * @param encodedKey the DER-encoded key
	 * @param replace whether a pre-commit public key may be replaced after interrupted initialization
	 * @throws IOException if publication fails
	 */
	private static void writeKey(Path destination, byte[] encodedKey, boolean replace) throws IOException
	{
		Path parent = destination.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Key path has no parent: " + destination);
		FileAttribute<?>[] attributes = new FileAttribute<?>[0];
		if (Files.getFileStore(parent).supportsFileAttributeView("posix"))
			attributes = new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS)};
		Path temporary = Files.createTempFile(parent, ".cat-installation-key-", ".tmp", attributes);
		try
		{
			String content = Base64.getEncoder().encodeToString(encodedKey) + System.lineSeparator();
			Files.writeString(temporary, content, UTF_8);
			applyPrivatePermissions(temporary);
			if (replace)
				Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING);
			else
				Files.move(temporary, destination, ATOMIC_MOVE);
			applyPrivatePermissions(destination);
		}
		catch (FileAlreadyExistsException e)
		{
			Files.deleteIfExists(temporary);
			throw new IOException("Local certificate private key already exists: " + destination, e);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * Reads one canonical bounded Base64 key document.
	 *
	 * @param source the key path
	 * @return the DER-encoded key
	 * @throws IOException if the document is unsafe or malformed
	 */
	private static byte[] readKey(Path source) throws IOException
	{
		if (!Files.isRegularFile(source, NOFOLLOW_LINKS))
			throw new IOException("Expected a regular local certificate key: " + source);
		String encoded = BoundedInput.readUtf8(source, MAXIMUM_KEY_DOCUMENT_BYTES, "Local certificate key").strip();
		try
		{
			byte[] result = Base64.getDecoder().decode(encoded);
			if (!Base64.getEncoder().encodeToString(result).equals(encoded))
				throw new IOException("Local certificate key is not canonical Base64: " + source);
			return result;
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Local certificate key is invalid Base64: " + source, e);
		}
	}

	/**
	 * Restricts a key document to its owner on POSIX filesystems.
	 *
	 * @param path the key document
	 * @throws IOException if permissions cannot be applied
	 */
	private static void applyPrivatePermissions(Path path) throws IOException
	{
		if (Files.getFileStore(path).supportsFileAttributeView("posix"))
			Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS);
	}

	/**
	 * Calculates one lowercase SHA-256 identifier.
	 *
	 * @param value the bytes
	 * @return the identifier
	 */
	private static String sha256(byte[] value)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Holds one imported certificate and its current installation-local verification state.
	 *
	 * @param certificate the imported certificate
	 * @param verification its current verification state
	 */
	public record VerifiedCertificate(LocalCertificate certificate, LocalCertificate.Verification verification)
	{
		/**
		 * Validates one verified certificate result.
		 *
		 * @param certificate the imported certificate
		 * @param verification its current verification state
		 */
		public VerifiedCertificate
		{
			Objects.requireNonNull(certificate, "certificate must not be null");
			Objects.requireNonNull(verification, "verification must not be null");
		}
	}
}
