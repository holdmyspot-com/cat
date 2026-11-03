package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.engine.ReleaseSource;
import com.holdmyspot.cat.common.updater.engine.UpdateEngine;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs authenticated update and rollback through the engine and the packaged harness providers together.
 */
public final class UpdaterActivationTest
{
	/**
	 * Creates the test.
	 */
	public UpdaterActivationTest()
	{
	}

	/**
	 * Supplies each production activation service.
	 *
	 * @return the harness identifiers
	 */
	@DataProvider
	public Object[][] harnesses()
	{
		return new Object[][]{{"claude"}, {"codex"}};
	}

	/**
	 * An authenticated release changes the actual provider destinations and rollback restores the original version
	 * and owned files, while configuration, user files and staged executable content remain untouched.
	 *
	 * @param harness the production provider to exercise
	 * @throws IOException if fixture files cannot be written or read
	 * @throws GeneralSecurityException if release signing is unavailable
	 * @throws TrustException if the fixture policy cannot be created
	 */
	@Test(dataProvider = "harnesses")
	public void updateRollbackPreservesConfiguration(String harness)
		throws IOException, GeneralSecurityException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("updater-activation-").toRealPath());
			Path home = Files.createDirectory(root.resolve("harness-home"));
			PluginActivation provider = ServiceLoader.load(PluginActivation.class).stream().
				map(ServiceLoader.Provider::get).filter(service -> service.harness().equals(harness)).
				findFirst().orElseThrow();
			install(home, provider);
			Map<Path, String> contents = new HashMap<>();
			Map<Path, FileTime> times = new HashMap<>();
			for (String name : List.of("config.toml", "auth.json", "settings.json", ".mcp.json",
				"plugins/installed_plugins.json", "plugins/cat-marketplace/.agents/plugins/marketplace.json",
				"agents/user.toml"))
			{
				Path file = home.resolve(name);
				write(file, "user-owned " + name);
				contents.put(file, Files.readString(file));
				times.put(file, Files.getLastModifiedTime(file));
			}
			SignedRelease release = release(provider, root.resolve("executed"));
			TrustedKey key = TrustedKey.decode("fixture-key", Base64.getEncoder().
				encodeToString(release.key().getPublic().getEncoded()));
			TrustPolicy policy = TrustPolicy.of(List.of(key), URI.create("https://releases.example/cat/releases/download"),
				ReleaseVersion.parse("1.0"), 1, Map.of(harness, home));
			UpdateEngine engine = new UpdateEngine(root.resolve("updater"), policy, release, provider,
				"linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult updated = engine.update("v1.1");

			requireThat(updated.result(), "updateResult").isEqualTo(Result.UPDATED);
			requireThat(updated.consistent(), "updatedConsistent").isTrue();
			requireThat(provider.installed(home), "updatedInstallation").
				isEqualTo(new InstalledState(Optional.of("1.1"), true));
			requireThat(engine.status().previousVersion(), "previousVersion").isEqualTo(Optional.of("1.0"));
			requireThat(Files.readString(provider.migrationVersionFile(home)), "updatedMigrationVersion").
				isEqualTo("1.1\n");
			if (harness.equals("codex"))
			{
				requireThat(Files.exists(home.resolve("agents/cat-a.toml"), NOFOLLOW_LINKS), "retiredAgentExists").isFalse();
				requireThat(Files.readString(home.resolve("agents/cat-b.toml")), "newAgent").isEqualTo("new agent");
				requireThat(home.resolve("plugins/cache/cat/cat/1.0"), "retainedCache").isDirectory();
			}
			unchanged(contents, times);

			UpdateResult rolledBack = engine.rollback();

			requireThat(rolledBack.result(), "rollbackResult").isEqualTo(Result.ROLLED_BACK);
			requireThat(rolledBack.consistent(), "rollbackConsistent").isTrue();
			requireThat(provider.installed(home), "restoredInstallation").
				isEqualTo(new InstalledState(Optional.of("1.0"), true));
			requireThat(Files.readString(provider.migrationVersionFile(home)), "restoredMigrationVersion").
				isEqualTo("1.0\n");
			if (harness.equals("codex"))
			{
				requireThat(Files.readString(home.resolve("agents/cat-a.toml")), "restoredAgent").isEqualTo("old agent");
				requireThat(Files.exists(home.resolve("agents/cat-b.toml"), NOFOLLOW_LINKS), "newAgentExists").isFalse();
				requireThat(Files.readString(home.resolve("plugins/data/cat-cat/codex-agent-files")), "restoredOwnership").
					isEqualTo("cat-a.toml\n");
				requireThat(Files.exists(home.resolve("plugins/cache/cat/cat/1.1"), NOFOLLOW_LINKS), "newCacheExists").
					isFalse();
			}
			unchanged(contents, times);
			requireThat(Files.exists(root.resolve("executed"), NOFOLLOW_LINKS), "downloadedCodeExecuted").isFalse();
		}
	}

	/**
	 * Creates only the initial installation files that the real providers read.
	 *
	 * @param home the isolated harness home
	 * @param provider the service whose installation is created
	 * @throws IOException if writing fails
	 */
	private static void install(Path home, PluginActivation provider) throws IOException
	{
		String manifest = "{\"name\":\"cat\",\"version\":\"1.0\"}";
		write(home.resolve("plugins/cat-marketplace/plugins/cat").resolve(provider.pluginManifest()), manifest);
		write(provider.migrationVersionFile(home), "1.0\n");
		if (provider.harness().equals("codex"))
		{
			write(home.resolve("plugins/cache/cat/cat/1.0").resolve(provider.pluginManifest()), manifest);
			write(home.resolve("agents/cat-a.toml"), "old agent");
			write(home.resolve("plugins/data/cat-cat/codex-agent-files"), "cat-a.toml\n");
		}
	}

	/**
	 * Checks both byte content and modification time after an operation that must preserve user files.
	 *
	 * @param contents the original content
	 * @param times the original modification times
	 * @throws IOException if a protected file cannot be read
	 */
	private static void unchanged(Map<Path, String> contents, Map<Path, FileTime> times) throws IOException
	{
		for (Map.Entry<Path, String> entry : contents.entrySet())
		{
			requireThat(Files.readString(entry.getKey()), "protectedContent").isEqualTo(entry.getValue());
			requireThat(Files.getLastModifiedTime(entry.getKey()), "protectedModificationTime").
				isEqualTo(times.get(entry.getKey()));
		}
	}

	/**
	 * Writes one fixture file below an isolated harness home.
	 *
	 * @param path the file
	 * @param content its content
	 * @throws IOException if writing fails
	 */
	private static void write(Path path, String content) throws IOException
	{
		Files.createDirectories(path.getParent());
		Files.writeString(path, content);
	}

	/**
	 * Signs an archive with real Ed25519 so production authentication and extraction participate in acceptance.
	 *
	 * @param provider the production provider
	 * @param executionMarker the isolated marker a downloaded launcher would create if incorrectly executed
	 * @return the authenticated release source
	 * @throws IOException if archive creation fails
	 * @throws GeneralSecurityException if signing fails
	 */
	private static SignedRelease release(PluginActivation provider, Path executionMarker)
		throws IOException, GeneralSecurityException
	{
		String top = "cat-" + provider.harness() + "-v1.1-linux-x64";
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes)))
		{
			entry(tar, top + "/" + provider.pluginManifest(), "{\"name\":\"cat\",\"version\":\"1.1\"}", 0644);
			entry(tar, top + "/client/VERSION", "1.1\n", 0644);
			entry(tar, top + "/bin/plugin-info", "#!/bin/sh\nprintf executed > " +
				ShellQuoting.posix(executionMarker.toString()) + "\n", 0755);
			entry(tar, top + "/install/migrations/registry.tsv", "# no migrations\n", 0644);
			entry(tar, top + "/agents/codex/b.toml", "new agent", 0644);
		}
		byte[] archive = bytes.toByteArray();
		Map<String, Object> asset = Map.of("name", top + ".tar.gz", "kind", "plugin", "harness", provider.harness(),
			"platform", "linux-x64", "size", archive.length, "sha256",
			HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)), "top_directory", top);
		byte[] manifest = JsonMapper.builder().build().writeValueAsBytes(Map.of("format", 1, "product", "cat",
			"version", "1.1", "tag", "v1.1", "published", "2026-11-03T13:44:35Z", "assets", List.of(asset)));
		KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(key.getPrivate());
		signer.update(manifest);
		byte[] signature = Base64.getEncoder().encode(signer.sign());
		return new SignedRelease(key, archive, manifest, signature);
	}

	/**
	 * Writes one regular archive entry with the platform-relevant mode.
	 *
	 * @param tar the archive writer
	 * @param name its complete relative name
	 * @param content its content
	 * @param mode its permissions
	 * @throws IOException if archive writing fails
	 */
	private static void entry(TarArchiveOutputStream tar, String name, String content, int mode) throws IOException
	{
		byte[] bytes = content.getBytes(UTF_8);
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(bytes.length);
		entry.setMode(mode);
		tar.putArchiveEntry(entry);
		tar.write(bytes);
		tar.closeArchiveEntry();
	}

	/**
	 * Supplies bytes only; the production engine owns every trust and activation decision.
	 *
	 * @param key the fixture signing key
	 * @param archive the plugin archive
	 * @param manifest the signed manifest bytes
	 * @param signature the detached signature bytes
	 */
	private record SignedRelease(KeyPair key, byte[] archive, byte[] manifest, byte[] signature) implements ReleaseSource
	{
		@Override
		public String latestTag()
		{
			return "v1.1";
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
	}
}
