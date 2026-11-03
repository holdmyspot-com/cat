package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.sign;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.trusted;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.updater.engine.ReleaseSource;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

/**
 * Supplies signed releases and small gzip-compressed tar archives to the real engine. The source only writes supplied
 * bytes; signature, length, digest, extraction and activation decisions belong to production code.
 */
public final class Releases implements ReleaseSource
{
	private final KeyPair key = keyPair();
	private final String version;
	private byte[] archive;
	private byte[] manifest;
	private final byte[] signature;

	/**
	 * Creates a valid release, optionally corrupting exactly one property named by a test case.
	 *
	 * @param version the release version
	 * @param defect  empty for valid data, or the particular corrupted property
	 * @throws IOException if creating the in-memory archive fails
	 */
	public Releases(String version, String defect) throws IOException
	{
		this.version = version;
		this.archive = archive(version, defect);
		String digest = sha256(archive);
		long size = archive.length;
		if (defect.equals("digest"))
			digest = "0".repeat(64);
		if (defect.equals("size"))
			size += 1;
		String top = "cat-harness-a-v" + version + "-linux-x64";
		String asset = ReleaseFixtures.asset(top + ".tar.gz", "plugin", "\"harness\": \"harness-a\", ",
			"linux-x64", Long.toString(size), digest, top);
		this.manifest = ReleaseFixtures.manifest(version, "v" + version, asset).getBytes(UTF_8);
		KeyPair signer = key;
		if (defect.equals("unknownKey"))
			signer = keyPair();
		this.signature = sign(manifest, signer);
		if (defect.equals("manifest"))
			this.manifest = (new String(manifest, UTF_8) + " ").getBytes(UTF_8);
		if (defect.equals("truncated"))
			this.archive = Arrays.copyOf(archive, archive.length - 1);
	}

	/**
	 * Creates a policy that trusts this fixture's key and records only the supplied home.
	 *
	 * @param home the test-owned harness home
	 * @return the policy
	 * @throws TrustException if the policy is invalid
	 */
	public TrustPolicy policy(Path home) throws TrustException
	{
		return TrustPolicy.of(List.of(trusted("test-key", key)),
			URI.create("https://releases.example/cat/releases/download"), ReleaseVersion.parse("1.0"), 1,
			Map.of("harness-a", home));
	}

	@Override
	public String latestTag()
	{
		return "v" + version;
	}

	@Override
	public byte[] manifest(String tag)
	{
		return manifest.clone();
	}

	@Override
	public byte[] signature(String tag)
	{
		return signature.clone();
	}

	@Override
	public void download(String tag, String assetName, long expectedSize, Path destination) throws IOException
	{
		Files.write(destination, archive);
	}

	/**
	 * Builds only the entries needed to validate or refuse the named release property.
	 *
	 * @param version the declared release version
	 * @param defect the corrupted property, or empty
	 * @return the archive bytes
	 * @throws IOException if archive writing fails
	 */
	private static byte[] archive(String version, String defect) throws IOException
	{
		String top = "cat-harness-a-v" + version + "-linux-x64";
		if (defect.equals("top"))
			top = "another-top";
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes)))
		{
			String manifestVersion = version;
			if (defect.equals("pluginVersion"))
				manifestVersion = "1.9";
			entry(tar, top + "/.harness-a/plugin.json", "{\"version\":\"" + manifestVersion + "\"}", 0644);
			String clientVersion = version;
			if (defect.equals("clientVersion"))
				clientVersion = "1.9";
			entry(tar, top + "/client/VERSION", clientVersion, 0644);
			if (!defect.equals("missingBinary"))
				entry(tar, top + "/bin/plugin-info", "Never executed by the updater", 0755);
			if (!defect.equals("missingRegistry"))
			{
				String registry = "# No migration\n";
				if (defect.equals("migration"))
					registry = version + "\tmigrate.sh\n";
				entry(tar, top + "/install/migrations/registry.tsv", registry, 0644);
			}
			entry(tar, top + "/version", version, 0644);
			entry(tar, top + "/agents/reviewer.toml", "name = 'new'", 0644);
			switch (defect)
			{
				case "traversal" -> entry(tar, top + "/../escaped", "hostile", 0644);
				case "symlink", "hardLink" ->
				{
					byte type = TarConstants.LF_LINK;
					if (defect.equals("symlink"))
						type = TarConstants.LF_SYMLINK;
					TarArchiveEntry link = new TarArchiveEntry(top + "/link", type);
					link.setLinkName("../../escaped");
					tar.putArchiveEntry(link);
					tar.closeArchiveEntry();
				}
				default ->
				{
				}
			}
		}
		return bytes.toByteArray();
	}

	/**
	 * Writes a small regular file entry.
	 *
	 * @param tar the archive writer
	 * @param name the archive entry name
	 * @param text the content
	 * @param mode the file mode required for this fixture
	 * @throws IOException if writing fails
	 */
	private static void entry(TarArchiveOutputStream tar, String name, String text, int mode) throws IOException
	{
		byte[] content = text.getBytes(UTF_8);
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(content.length);
		entry.setMode(mode);
		tar.putArchiveEntry(entry);
		tar.write(content);
		tar.closeArchiveEntry();
	}

	/**
	 * Calculates the digest that the fixture's signer authenticates.
	 *
	 * @param bytes the archive bytes
	 * @return the lowercase digest
	 */
	private static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("The platform cannot provide SHA-256", e);
		}
	}
}
