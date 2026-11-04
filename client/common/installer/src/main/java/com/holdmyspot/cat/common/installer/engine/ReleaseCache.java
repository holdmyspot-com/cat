package com.holdmyspot.cat.common.installer.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.installation.TreeDigest;
import com.holdmyspot.cat.common.installer.trust.ManifestVerifier;
import com.holdmyspot.cat.common.installer.trust.ReleaseApproval;
import com.holdmyspot.cat.common.installer.trust.ReleaseAsset;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.VerifiedManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Keeps, in the installation's protected data directory, what an operation needs to activate a release again without
 * downloading it: the release's signed manifest and its signature, its plugin archive, and the identity of the Java
 * runtime and installer version directory that the installer installed from it. Each release has a directory
 * {@code releases/<version>}. Nothing in it is trusted until it is verified again: the manifest against the policy's
 * keys, an archive against the manifest's digest, and the installed runtime and installer against the recorded
 * identity. An entry that fails verification is discarded, and the release is downloaded instead.
 */
final class ReleaseCache
{
	/**
	 * The data-relative directory of the cached releases.
	 */
	static final Path DIRECTORY = Path.of("releases");
	private static final String MANIFEST = "cat-release-manifest.json";
	private static final String SIGNATURE = "cat-release-manifest.json.sig";
	private static final String IDENTITY = "bundle-identity";
	/**
	 * The largest identity record: two labelled digests.
	 */
	private static final int MAXIMUM_IDENTITY_BYTES = 1024;
	/**
	 * The installer-version-relative policy anchor, which setup and every update rewrite after installing the directory.
	 */
	private static final Path ANCHOR = Bundle.POLICY_ANCHOR;
	private final Path data;
	private final Path root;

	/**
	 * Creates the cache of an installation.
	 *
	 * @param data the installation's absolute, normalized data directory
	 */
	ReleaseCache(Path data)
	{
		this.data = data;
		this.root = data.resolve(DIRECTORY);
	}

	/**
	 * Returns a cached release whose manifest the policy's keys verify and approve for the version.
	 *
	 * @param version the release version
	 * @param policy  the user's trust policy
	 * @return the release, or empty when none is cached or the cached one failed verification and was discarded
	 * @throws IOException if a failed entry cannot be discarded
	 */
	Optional<SignedRelease> release(String version, TrustPolicy policy) throws IOException
	{
		Path directory = directory(version);
		Path manifest = directory.resolve(MANIFEST);
		Path signature = directory.resolve(SIGNATURE);
		if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS) || !Files.isRegularFile(signature, NOFOLLOW_LINKS))
			return Optional.empty();
		try
		{
			byte[] manifestBytes = read(manifest, ManifestVerifier.MAXIMUM_MANIFEST_SIZE);
			byte[] signatureBytes = read(signature, ManifestVerifier.MAXIMUM_SIGNATURE_SIZE);
			SignedRelease release = SignedRelease.verify(manifestBytes, signatureBytes, policy);
			ReleaseApproval.approveUpdate(release.verified(), policy, ReleaseVersion.parse(version));
			return Optional.of(release);
		}
		catch (IOException | TrustException _)
		{
			discard(version);
			return Optional.empty();
		}
	}

	/**
	 * Copies a cached archive of a release to a destination when it matches the release's signed manifest.
	 *
	 * @param version     the release version
	 * @param asset       the archive's signed entry
	 * @param destination the file to write
	 * @return true if the archive was copied; false if none is cached or the cached one failed verification, which
	 * 	discards the release's entry
	 * @throws IOException if the archive cannot be copied, or a failed entry cannot be discarded
	 */
	boolean archive(String version, ReleaseAsset asset, Path destination) throws IOException
	{
		Path cached = directory(version).resolve(asset.name());
		if (!Files.isRegularFile(cached, NOFOLLOW_LINKS))
			return false;
		Files.copy(cached, destination, REPLACE_EXISTING);
		try
		{
			ReleaseDownload.validate(destination, asset);
			return true;
		}
		catch (IOException _)
		{
			Files.deleteIfExists(destination);
			discard(version);
			return false;
		}
	}

	/**
	 * Keeps a release that an operation activated: its signed manifest and the archives that it obtained.
	 *
	 * @param version  the release version
	 * @param release  the release's signed manifest
	 * @param archives the validated archives
	 * @throws IOException if the release cannot be kept
	 */
	void keep(String version, SignedRelease release, List<Path> archives) throws IOException
	{
		Path directory = directory(version);
		StateFiles.createDirectories(directory);
		StateFiles.write(directory.resolve(MANIFEST), release.manifest());
		StateFiles.write(directory.resolve(SIGNATURE), release.signature());
		for (Path archive : archives)
		{
			Path target = directory.resolve(archive.getFileName());
			Path temporary = directory.resolve(archive.getFileName() + ".partial");
			Files.copy(archive, temporary, REPLACE_EXISTING);
			Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
		}
	}

	/**
	 * Records the identity of the Java runtime and the installer version directory that the installer installed for a
	 * release.
	 *
	 * @param version the release version
	 * @param runtime the installed runtime
	 * @param image   the installed installer version directory
	 * @throws IOException if either cannot be read, or the identity cannot be written
	 */
	void recordBundle(String version, Path runtime, Path image) throws IOException
	{
		Path directory = directory(version);
		StateFiles.createDirectories(directory);
		StateFiles.write(directory.resolve(IDENTITY), identity(runtime, image).getBytes(UTF_8));
	}

	/**
	 * Indicates whether a runtime and an installer version directory hold exactly the files that the installer recorded
	 * when it installed them for a release. A mismatch discards the release's entry.
	 *
	 * @param version the release version
	 * @param runtime the runtime to check
	 * @param image   the installer version directory to check
	 * @return true if both match the recorded identity
	 * @throws IOException if a failed entry cannot be discarded
	 */
	boolean matchesBundle(String version, Path runtime, Path image) throws IOException
	{
		Path record = directory(version).resolve(IDENTITY);
		if (!Files.isRegularFile(record, NOFOLLOW_LINKS))
			return false;
		try
		{
			String recorded = BoundedInput.readUtf8(record, MAXIMUM_IDENTITY_BYTES, "The bundle identity");
			if (recorded.equals(identity(runtime, image)))
				return true;
		}
		catch (IOException _)
		{
			// An unreadable record or bundle is discarded below
		}
		discard(version);
		return false;
	}

	/**
	 * Deletes the entries of every release other than the kept ones.
	 *
	 * @param kept the versions to keep
	 * @throws IOException if an entry cannot be deleted
	 */
	void retain(Collection<String> kept) throws IOException
	{
		if (!Files.isDirectory(root, NOFOLLOW_LINKS))
			return;
		List<Path> others = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(root))
		{
			for (Path entry : entries)
			{
				if (!kept.contains(entry.getFileName().toString()))
					others.add(entry);
			}
		}
		for (Path other : others)
			LinkSafeDeletion.delete(other, data);
	}

	/**
	 * Deletes a release's entry.
	 *
	 * @param version the release version
	 * @throws IOException if the entry cannot be deleted
	 */
	void discard(String version) throws IOException
	{
		LinkSafeDeletion.delete(directory(version), data);
	}

	/**
	 * Returns a release's entry.
	 *
	 * @param version the release version
	 * @return the directory
	 * @throws IOException if a link leads to the directory
	 */
	private Path directory(String version) throws IOException
	{
		Path directory = root.resolve(version);
		StateFiles.requireNoLinks(directory);
		return directory;
	}

	/**
	 * Returns the identity of a runtime and an installer version directory, leaving out the policy anchor.
	 *
	 * @param runtime the runtime
	 * @param image   the installer version directory
	 * @return the identity's text
	 * @throws IOException if a tree cannot be read
	 */
	private static String identity(Path runtime, Path image) throws IOException
	{
		return "runtime " + TreeDigest.of(runtime) + "\ninstaller " + TreeDigest.of(image, image.resolve(ANCHOR)) + "\n";
	}

	/**
	 * Reads a bounded cached file.
	 *
	 * @param file    the file
	 * @param maximum the largest number of bytes
	 * @return the bytes
	 * @throws IOException if the file is larger or cannot be read
	 */
	private static byte[] read(Path file, int maximum) throws IOException
	{
		try (InputStream input = Files.newInputStream(file, NOFOLLOW_LINKS))
		{
			return BoundedInput.readBytes(input, file, maximum, "The cached release file");
		}
	}

	/**
	 * A release manifest, its signature, and the manifest that the policy's keys verified from them.
	 *
	 * @param manifest  the manifest's bytes
	 * @param signature the signature file's bytes
	 * @param verified  the verified manifest
	 */
	record SignedRelease(byte[] manifest, byte[] signature, VerifiedManifest verified)
	{
		/**
		 * Verifies a manifest and its signature against the policy's keys.
		 *
		 * @param manifest  the manifest's bytes
		 * @param signature the signature file's bytes
		 * @param policy    the user's trust policy
		 * @return the release
		 * @throws IOException    if the bytes cannot be read
		 * @throws TrustException if no key of the policy verifies the manifest, or it breaks its format
		 */
		static SignedRelease verify(byte[] manifest, byte[] signature, TrustPolicy policy)
			throws IOException, TrustException
		{
			VerifiedManifest verified = ManifestVerifier.verify(new ByteArrayInputStream(manifest),
				new ByteArrayInputStream(signature), policy);
			return new SignedRelease(manifest, signature, verified);
		}
	}
}
