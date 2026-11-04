package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.sign;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.engine.StagedRelease;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

/**
 * Supplies signed releases and small gzip-compressed tar archives to the real engine. The source only writes supplied
 * bytes; signature, length, digest, extraction and installation decisions belong to production code.
 */
public final class Releases implements ReleaseSource
{
	private final KeyPair key;
	private final String version;
	private byte[] archive;
	private byte[] manifest;
	private final byte[] signature;

	/**
	 * Creates a valid release, optionally corrupting exactly one property named by a test case. Two valid variants
	 * change the migration registry: {@code layout} registers only the per-harness layout migration, and
	 * {@code layoutAndMigration} also registers a data migration for the release.
	 *
	 * @param version the release version
	 * @param defect  empty for valid data, or the particular corrupted property
	 * @throws IOException if creating the in-memory archive fails
	 */
	public Releases(String version, String defect) throws IOException
	{
		this(version, ReleaseFixtures.BUILD, defect, null, keyPair());
	}

	/**
	 * Creates a valid release that another release's key signs and whose plugin holds the supplied migration
	 * registry, so that one source can serve several releases that the same policy approves.
	 *
	 * @param version  the release version
	 * @param registry the content of the plugin's {@code install/migrations/registry.tsv}
	 * @param signer   the release whose key signs this one
	 * @throws IOException if creating the in-memory archive fails
	 */
	Releases(String version, String registry, Releases signer) throws IOException
	{
		this(version, ReleaseFixtures.BUILD, "", registry, signer.key);
	}

	/**
	 * Creates a valid release of a build other than the fixture's usual one, as a republished release is, that another
	 * release's key signs and whose plugin holds the supplied migration registry.
	 *
	 * @param version  the release version
	 * @param build    the first twelve lower-case hexadecimal digits of the commit that the release is built from
	 * @param registry the content of the plugin's {@code install/migrations/registry.tsv}
	 * @param signer   the release whose key signs this one
	 * @throws IOException if creating the in-memory archive fails
	 */
	Releases(String version, String build, String registry, Releases signer) throws IOException
	{
		this(version, build, "", registry, signer.key);
	}

	/**
	 * Creates a release that another release's key signs, corrupting exactly one property named by a test case, as
	 * {@link #Releases(String, String)} describes.
	 *
	 * @param version the release version
	 * @param defect  the corrupted property
	 * @param signer  the release whose key signs this one
	 * @return the release
	 * @throws IOException if creating the in-memory archive fails
	 */
	static Releases defective(String version, String defect, Releases signer) throws IOException
	{
		return new Releases(version, ReleaseFixtures.BUILD, defect, null, signer.key);
	}

	/**
	 * Creates a release.
	 *
	 * @param version  the release version
	 * @param build    the first twelve lower-case hexadecimal digits of the commit that the release is built from
	 * @param defect   empty for valid data, or the particular corrupted property
	 * @param registry the plugin's migration registry, or null for the one that {@code defect} selects
	 * @param key      the key that signs the release
	 * @throws IOException if creating the in-memory archive fails
	 */
	private Releases(String version, String build, String defect, String registry, KeyPair key) throws IOException
	{
		this.version = version;
		this.key = key;
		this.archive = archive(version, build, defect, registry);
		String digest = sha256(archive);
		long size = archive.length;
		if (defect.equals("digest"))
			digest = "0".repeat(64);
		if (defect.equals("size"))
			size += 1;
		String top = "cat-harness-a-linux-x64-v" + version;
		String assets = ReleaseFixtures.asset(top + ".tar.gz", "plugin", "\"harness\": \"harness-a\", ",
			"linux-x64", Long.toString(size), digest, top);
		this.manifest = ReleaseFixtures.manifest(version, "v" + version, build, assets).getBytes(UTF_8);
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
		return policy(home, "1.0");
	}

	/**
	 * Creates a policy that trusts this fixture's key, approves releases from a minimum version and records only the
	 * supplied home.
	 *
	 * @param home    the test-owned harness home
	 * @param minimum the lowest release version that the policy approves
	 * @return the policy
	 * @throws TrustException if the policy is invalid
	 */
	TrustPolicy policy(Path home, String minimum) throws TrustException
	{
		return TrustPolicy.of(List.of(trusted("test-key", key)),
			URI.create("https://releases.example/cat/releases/download"), ReleaseVersion.parse(minimum), 1,
			Map.of("harness-a", home));
	}

	/**
	 * Returns the full plugin version that the plugin of a release of this fixture states.
	 *
	 * @param release the release version
	 * @return the release, then the commit that the fixture's releases are built from
	 */
	public static String pluginVersion(String release)
	{
		return pluginVersion(release, ReleaseFixtures.BUILD);
	}

	/**
	 * Returns the full plugin version of a build of a release.
	 *
	 * @param release the release version
	 * @param build   the first twelve lower-case hexadecimal digits of the commit that the release is built from
	 * @return the release, then the build
	 */
	public static String pluginVersion(String release, String build)
	{
		return release + PluginVersion.BUILD_SEPARATOR + build;
	}

	/**
	 * Returns every regular file under a directory with its content in hexadecimal, so that a test can show that an
	 * operation changed nothing. The operation log and lock file, which every locked operation writes, are left out.
	 *
	 * @param root the directory
	 * @return each file's relative path mapped to its content
	 * @throws IOException if the tree cannot be read
	 */
	public static Map<String, String> snapshot(Path root) throws IOException
	{
		Map<String, String> entries = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.toList())
			{
				String name = path.getFileName().toString();
				if (name.equals("update.log") || name.equals("lock") ||
					!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				{
					continue;
				}
				entries.put(root.relativize(path).toString(), HexFormat.of().formatHex(Files.readAllBytes(path)));
			}
		}
		return entries;
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
	 * @param version  the declared release version
	 * @param build    the first twelve lower-case hexadecimal digits of the commit that the release is built from
	 * @param defect   the corrupted property, or empty
	 * @param registry the migration registry, or null for the one that {@code defect} selects
	 * @return the archive bytes
	 * @throws IOException if archive writing fails
	 */
	private static byte[] archive(String version, String build, String defect, String registry) throws IOException
	{
		String top = "cat-harness-a-linux-x64-v" + version;
		if (defect.equals("top"))
			top = "another-top";
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes)))
		{
			String pluginVersion = pluginVersion(version, build);
			String manifestVersion = pluginVersion;
			if (defect.equals("pluginVersion"))
				manifestVersion = "1.9";
			entry(tar, top + "/.harness-a/plugin.json", "{\"version\":\"" + manifestVersion + "\"}", 0644);
			String binVersion = pluginVersion;
			if (defect.equals("binVersion"))
				binVersion = "1.9";
			entry(tar, top + "/bin/VERSION", binVersion, 0644);
			if (!defect.equals("missingBinary"))
				entry(tar, top + "/bin/plugin-info", "Never executed by the installer", 0755);
			if (!defect.equals("missingRegistry"))
			{
				String content = registry;
				if (content == null)
					content = registry(version, defect);
				entry(tar, top + "/install/migrations/registry.tsv", content, 0644);
			}
			entry(tar, top + "/version", pluginVersion, 0644);
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
	 * Returns the migration registry that a release defect selects.
	 *
	 * @param version the release version
	 * @param defect  the corrupted property, or empty
	 * @return the registry's content
	 */
	private static String registry(String version, String defect)
	{
		String layout = StagedRelease.LAYOUT_MIGRATION + "\tper-harness-layout.sh\n";
		return switch (defect)
		{
			case "migration" -> version + "\tmigrate.sh\n";
			case "layout" -> layout;
			case "layoutAndMigration" -> layout + version + "\tmigrate.sh\n";
			default -> "# No migration\n";
		};
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
		entry(tar, name, text.getBytes(UTF_8), mode);
	}

	/**
	 * Writes a small regular file entry.
	 *
	 * @param tar     the archive writer
	 * @param name    the archive entry name
	 * @param content the content
	 * @param mode    the file mode required for this fixture
	 * @throws IOException if writing fails
	 */
	private static void entry(TarArchiveOutputStream tar, String name, byte[] content, int mode) throws IOException
	{
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
