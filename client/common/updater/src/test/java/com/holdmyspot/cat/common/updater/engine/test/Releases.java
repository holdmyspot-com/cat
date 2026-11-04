package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.sign;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.updater.engine.ReleaseSource;
import com.holdmyspot.cat.common.updater.engine.StagedRelease;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures;
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
 * bytes; signature, length, digest, extraction and activation decisions belong to production code.
 */
public final class Releases implements ReleaseSource
{
	/**
	 * The Java feature release of the runtime that {@link #installation(Path, String)} writes.
	 */
	private static final int INSTALLED_FEATURE = 25;
	/**
	 * The installation-relative policy anchor that setup writes into each updater version directory.
	 */
	private static final String ANCHOR = "conf/cat-updater/policy-path";
	/**
	 * The updater-version-relative directory that holds the release's stable launchers.
	 */
	private static final String STABLE_LAUNCHERS = "stable-launcher/";
	private final KeyPair key = keyPair();
	private final String version;
	private byte[] archive;
	private final byte[] updater;
	private byte[] manifest;
	private final byte[] signature;

	/**
	 * Creates a valid release, optionally corrupting exactly one property named by a test case. Besides the plugin
	 * defects, {@code noUpdater} omits the updater archive, {@code updaterDigest} misstates its digest,
	 * {@code updaterRequirement} makes the updater need a newer runtime than its archive contains,
	 * {@code noStableLauncher} omits the stable launchers from the updater archive, and {@code newerRuntime} ships a
	 * runtime and plugin of a feature release newer than the installed runtime's. Two valid variants change the
	 * migration registry: {@code layout} registers only the per-harness layout migration, and
	 * {@code layoutAndMigration} also registers a data migration for the release.
	 *
	 * @param version the release version
	 * @param defect  empty for valid data, or the particular corrupted property
	 * @throws IOException if creating the in-memory archive fails
	 */
	public Releases(String version, String defect) throws IOException
	{
		this(version, defect, false);
	}

	/**
	 * Creates a valid release whose plugin describes how to train its startup cache. Its {@code client/bin/java}
	 * wrapper runs {@link FakeJava} on the runtime that the plugin's binding names, so the cache that a training writes
	 * names the runtime that trained it.
	 *
	 * @param version the release version
	 * @return the release
	 * @throws IOException if creating the in-memory archive fails
	 */
	public static Releases trainable(String version) throws IOException
	{
		return new Releases(version, "", true);
	}

	/**
	 * Creates a release.
	 *
	 * @param version   the release version
	 * @param defect    empty for valid data, or the particular corrupted property
	 * @param trainable true if the plugin describes how to train its startup cache
	 * @throws IOException if creating the in-memory archive fails
	 */
	private Releases(String version, String defect, boolean trainable) throws IOException
	{
		this.version = version;
		this.archive = archive(version, defect, trainable);
		this.updater = updaterArchive(version, defect);
		String digest = sha256(archive);
		long size = archive.length;
		if (defect.equals("digest"))
			digest = "0".repeat(64);
		if (defect.equals("size"))
			size += 1;
		String top = "cat-harness-a-v" + version + "-linux-x64";
		String assets = ReleaseFixtures.asset(top + ".tar.gz", "plugin", "\"harness\": \"harness-a\", ",
			"linux-x64", Long.toString(size), digest, top);
		if (!defect.equals("noUpdater"))
		{
			String updaterTop = updaterTop(version);
			String updaterDigest = sha256(updater);
			if (defect.equals("updaterDigest"))
				updaterDigest = "0".repeat(64);
			assets += ", " + ReleaseFixtures.asset(updaterTop + ".tar.gz", "updater", "", "linux-x64",
				Integer.toString(updater.length), updaterDigest, updaterTop);
		}
		this.manifest = ReleaseFixtures.manifest(version, "v" + version, assets).getBytes(UTF_8);
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

	/**
	 * Writes the description of an installation's Java runtime, whose feature release and modules satisfy every
	 * release of this fixture except one with the {@code requirement} or {@code newerRuntime} defect.
	 *
	 * @param data the installation's updater data directory
	 * @return the runtime directory
	 * @throws IOException if the description cannot be written
	 */
	public static Path runtime(Path data) throws IOException
	{
		Path runtime = Files.createDirectories(data.resolve("runtime"));
		Files.writeString(runtime.resolve("cat-runtime.json"), runtimeDescription(INSTALLED_FEATURE));
		return runtime;
	}

	/**
	 * Writes the updater installation of one version as setup leaves it on a POSIX platform: the current selection,
	 * the stable launcher, the updater version directory with its policy anchor, runtime requirement and stable
	 * launchers, and the Java runtime, whose {@code release} file names the version that installed it and whose
	 * {@code java} names that runtime to {@link FakeJava}.
	 *
	 * @param data    the installation's updater data directory
	 * @param version the installed updater version
	 * @return the runtime directory
	 * @throws IOException if a file cannot be written
	 */
	public static Path installation(Path data, String version) throws IOException
	{
		Path runtime = runtime(data);
		Files.writeString(runtime.resolve("release"), "runtime " + version);
		Path java = Files.writeString(Files.createDirectories(runtime.resolve("bin")).resolve("java"),
			StartupCacheFixtures.posixRuntimeJava("runtime " + version));
		requireThat(java.toFile().setExecutable(true), "executable").isTrue();
		Files.writeString(runtime.resolve("bin/java.cmd"),
			StartupCacheFixtures.windowsRuntimeJava("runtime " + version));
		Path image = Files.createDirectories(data.resolve(version));
		Files.writeString(image.resolve("VERSION"), version + "\n");
		Files.writeString(image.resolve("runtime-requirement.json"), requirement(INSTALLED_FEATURE));
		Files.createDirectories(image.resolve(ANCHOR).getParent());
		Files.writeString(image.resolve(ANCHOR), data.toAbsolutePath().getParent().resolve("policy.json") + "\n");
		Files.createDirectories(image.resolve(STABLE_LAUNCHERS));
		Files.writeString(image.resolve(STABLE_LAUNCHERS + "cat-updater"), stableLauncher(version));
		Files.writeString(image.resolve(STABLE_LAUNCHERS + "cat-updater.ps1"), windowsStableLauncher(version));
		Files.createDirectories(data.resolve("bin"));
		Files.writeString(data.resolve("bin/cat-updater"), stableLauncher(version));
		Files.writeString(data.resolve("current"), version + "\n");
		return runtime;
	}

	/**
	 * Returns the POSIX stable launcher that a release of this fixture ships.
	 *
	 * @param version the release version
	 * @return the launcher's text, which names the version
	 */
	public static String stableLauncher(String version)
	{
		return "#!/bin/sh\n# The stable launcher of release " + version + "\n";
	}

	/**
	 * Returns the Windows stable launcher that a release of this fixture ships.
	 *
	 * @param version the release version
	 * @return the launcher's text, which names the version
	 */
	private static String windowsStableLauncher(String version)
	{
		return "# The Windows stable launcher of release " + version + "\n";
	}

	/**
	 * Writes an installation whose last update replaced one bundle with another and whose stable launcher finished the
	 * runtime replacement: the replaced runtime and updater version directory stay cached.
	 *
	 * @param data     the installation's updater data directory
	 * @param replaced the version that the update replaced
	 * @param selected the version that the update installed
	 * @throws IOException if a file cannot be written
	 */
	public static void updated(Path data, String replaced, String selected) throws IOException
	{
		installation(data, replaced);
		Files.move(data.resolve("runtime"), data.resolve("runtime.previous"));
		installation(data, selected);
	}

	/**
	 * Returns the installation-relative policy anchor of an updater version directory.
	 *
	 * @param version the updater version
	 * @return the relative path
	 */
	public static Path anchor(String version)
	{
		return Path.of(version).resolve(ANCHOR);
	}

	/**
	 * Returns every regular file under a directory with its content, so that a test can show that an operation changed
	 * nothing. The operation log and lock file, which every locked operation writes, are left out.
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
				entries.put(root.relativize(path).toString(), Files.readString(path));
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
		if (assetName.startsWith("cat-updater-"))
			Files.write(destination, updater);
		else
			Files.write(destination, archive);
	}

	/**
	 * Builds only the entries needed to validate or refuse the named release property, and to train the plugin's
	 * startup cache when it is trainable.
	 *
	 * @param version   the declared release version
	 * @param defect    the corrupted property, or empty
	 * @param trainable true if the plugin describes how to train its startup cache
	 * @return the archive bytes
	 * @throws IOException if archive writing fails
	 */
	private static byte[] archive(String version, String defect, boolean trainable) throws IOException
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
				String layout = StagedRelease.LAYOUT_MIGRATION + "\tper-harness-layout.sh\n";
				if (defect.equals("migration"))
					registry = version + "\tmigrate.sh\n";
				else if (defect.equals("layout"))
					registry = layout;
				else if (defect.equals("layoutAndMigration"))
					registry = layout + version + "\tmigrate.sh\n";
				entry(tar, top + "/install/migrations/registry.tsv", registry, 0644);
			}
			entry(tar, top + "/version", version, 0644);
			int feature = INSTALLED_FEATURE;
			if (defect.equals("requirement"))
				feature = 99;
			else if (defect.equals("newerRuntime"))
				feature = INSTALLED_FEATURE + 1;
			entry(tar, top + "/client/runtime-requirement.json", requirement(feature), 0644);
			entry(tar, top + "/agents/reviewer.toml", "name = 'new'", 0644);
			if (trainable)
			{
				String main = FakeJava.class.getName();
				entry(tar, top + "/client/bin/java", StartupCacheFixtures.posixWrapper(main), 0755);
				entry(tar, top + "/client/bin/java.cmd", StartupCacheFixtures.windowsWrapper(main, "java.cmd"), 0644);
				entry(tar, top + "/" + StartupCacheFixtures.JAR, StartupCacheFixtures.jar(FakeJava.class), 0644);
				entry(tar, top + "/client/startup-cache-training.json",
					StartupCacheFixtures.description(List.of("-Dfixture.behavior=train")), 0644);
			}
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
	 * Builds the updater archive, whose top directory mirrors an installation: the Java runtime, whose
	 * {@code release} file names this version, and the updater version directory with the release's stable launchers.
	 *
	 * @param version the release version
	 * @param defect  the corrupted property, or empty
	 * @return the archive bytes
	 * @throws IOException if archive writing fails
	 */
	private static byte[] updaterArchive(String version, String defect) throws IOException
	{
		String top = updaterTop(version);
		int runtimeFeature = INSTALLED_FEATURE;
		if (defect.equals("newerRuntime"))
			runtimeFeature = INSTALLED_FEATURE + 1;
		int updaterFeature = runtimeFeature;
		if (defect.equals("updaterRequirement"))
			updaterFeature = 99;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes)))
		{
			entry(tar, top + "/runtime/cat-runtime.json", runtimeDescription(runtimeFeature), 0644);
			entry(tar, top + "/runtime/release", "runtime " + version, 0644);
			entry(tar, top + "/runtime/bin/java", StartupCacheFixtures.posixRuntimeJava("runtime " + version), 0755);
			entry(tar, top + "/runtime/bin/java.cmd", StartupCacheFixtures.windowsRuntimeJava("runtime " + version),
				0644);
			entry(tar, top + "/" + version + "/VERSION", version + "\n", 0644);
			entry(tar, top + "/" + version + "/runtime-requirement.json", requirement(updaterFeature), 0644);
			entry(tar, top + "/" + version + "/app/updater.jar", "Never executed by the updater", 0644);
			entry(tar, top + "/" + version + "/bin/cat-updater", "Never executed by the updater", 0755);
			if (!defect.equals("noStableLauncher"))
			{
				entry(tar, top + "/" + version + "/" + STABLE_LAUNCHERS + "cat-updater", stableLauncher(version), 0755);
				entry(tar, top + "/" + version + "/" + STABLE_LAUNCHERS + "cat-updater.ps1",
					windowsStableLauncher(version), 0644);
			}
		}
		return bytes.toByteArray();
	}

	/**
	 * Returns the top directory of a version's updater archive.
	 *
	 * @param version the release version
	 * @return the directory name
	 */
	private static String updaterTop(String version)
	{
		return "cat-updater-v" + version + "-linux-x64";
	}

	/**
	 * Returns a runtime description of the fixture's modules.
	 *
	 * @param feature the runtime's Java feature release
	 * @return the description's JSON text
	 */
	private static String runtimeDescription(int feature)
	{
		return "{\"format\": 1, \"java_version\": \"" + feature + ".0.1\", \"java_feature\": " + feature +
			", \"modules\": [\"java.base\", \"java.net.http\"]}";
	}

	/**
	 * Returns a runtime requirement for the fixture's modules.
	 *
	 * @param feature the required Java feature release
	 * @return the requirement's JSON text
	 */
	private static String requirement(int feature)
	{
		return "{\"format\": 1, \"java_feature\": " + feature + ", \"modules\": [\"java.base\"]}";
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
