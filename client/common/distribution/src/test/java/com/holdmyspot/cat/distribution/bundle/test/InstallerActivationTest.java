package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.engine.HarnessSession;
import com.holdmyspot.cat.common.installer.engine.InstallRequest;
import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an authenticated update through the engine and the packaged harness providers together.
 */
public final class InstallerActivationTest
{
	/**
	 * The runtime requirement of the installer and the plugin.
	 */
	private static final String REQUIREMENT = "{\"format\": 1, \"java_feature\": 25, \"modules\": [\"java.base\"]}";
	/**
	 * The full plugin version that the signed release names.
	 */
	private static final String PLUGIN_VERSION = "1.1+b0123456789ab";
	/**
	 * The full plugin version that the harness has before the update.
	 */
	private static final String ACTIVE_VERSION = "1.0+b00000000000a";

	/**
	 * Creates the test.
	 */
	public InstallerActivationTest()
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
	 * An authenticated release is installed into its own version folder by the harness, once, while the active version
	 * folder, configuration, user files and the release's executable content remain untouched.
	 *
	 * @param harness the production provider to exercise
	 * @throws IOException if fixture files cannot be written or read
	 * @throws GeneralSecurityException if release signing is unavailable
	 * @throws TrustException if the fixture policy cannot be created
	 */
	@Test(dataProvider = "harnesses")
	public void updatePreservesConfiguration(String harness)
		throws IOException, GeneralSecurityException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("installer-activation-").toRealPath());
			Path home = Files.createDirectory(root.resolve("harness-home"));
			PluginActivation provider = ServiceLoader.load(PluginActivation.class).stream().
				map(ServiceLoader.Provider::get).filter(service -> service.harness().equals(harness)).
				findFirst().orElseThrow();
			install(home, provider);
			Map<Path, String> contents = new HashMap<>();
			Map<Path, FileTime> times = new HashMap<>();
			List<String> userFiles = new ArrayList<>(List.of("config.toml", "auth.json", "settings.json", ".mcp.json",
				"plugins/cat-marketplace/.agents/plugins/marketplace.json", "agents/user.toml"));
			// Claude Code's record of its installed plugins is Claude Code's, which its registration rewrites
			if (harness.equals("codex"))
				userFiles.add("plugins/installed_plugins.json");
			for (String name : userFiles)
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
			Path data = root.resolve("installer");
			UpdateEngine engine = new UpdateEngine(data, policy, release, provider,
				"linux-x64", Clock.systemUTC(), interval -> () -> {});
			FixtureIntegration integration = new FixtureIntegration(harness, layout(harness), new ArrayList<>());
			HarnessSession session = new HarnessSession(integration,
				arguments -> new CommandResult(0, ""), new FileReplacer(Platform.current(), () ->
			{
			}, Clock.systemUTC()));
			Path activeFolder = integration.installation(home).versionFolder(PluginVersion.parse(ACTIVE_VERSION));
			Map<String, String> activeBefore = snapshot(activeFolder);

			UpdateResult updated = engine.install("v1.1", new InstallRequest(session, root.resolve("download/plugin"),
				List.of(), root.resolve("user/.cat/locks"), _ -> false));

			requireThat(updated.result(), "updateResult").withContext(updated.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(updated.consistent(), "updatedConsistent").isTrue();
			requireThat(updated.previousVersion(), "previousVersion").isEqualTo(Optional.of(ACTIVE_VERSION));
			requireThat(integration.registered(), "registered").isEqualTo(List.of(PluginVersion.parse(PLUGIN_VERSION)));
			requireThat(provider.installed(home), "updatedInstallation").
				isEqualTo(new InstalledState(Optional.of(PLUGIN_VERSION), true));
			Path newFolder = integration.installation(home).versionFolder(PluginVersion.parse(PLUGIN_VERSION));
			requireThat(Files.readString(newFolder.resolve("bin/runtime-binding")).strip(), "runtimeBinding").
				isEqualTo(newFolder.resolve("bin/runtime").toString());
			requireThat(snapshot(activeFolder), "activeFolder").isEqualTo(activeBefore);
			requireThat(Files.readString(provider.migrationVersionFile(home)), "updatedMigrationVersion").
				isEqualTo("1.1\n");
			if (harness.equals("codex"))
			{
				requireThat(Files.exists(home.resolve("agents/cat-a.toml"), NOFOLLOW_LINKS), "retiredAgentExists").isFalse();
				requireThat(Files.readString(home.resolve("agents/cat-b.toml")), "newAgent").isEqualTo("new agent");
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
		String manifest = "{\"name\":\"cat\",\"version\":\"" + ACTIVE_VERSION + "\"}";
		write(provider.migrationVersionFile(home), "1.0\n");
		if (provider.harness().equals("claude"))
		{
			write(home.resolve("plugins/cat-marketplace/plugins/cat/" + ACTIVE_VERSION).
				resolve(provider.pluginManifest()), manifest);
			recordClaudeInstallation(home, ACTIVE_VERSION);
		}
		else
		{
			write(home.resolve("plugins/cat-marketplace/plugins/cat").resolve(provider.pluginManifest()), manifest);
			write(home.resolve("plugins/cache/cat/cat/" + ACTIVE_VERSION).resolve(provider.pluginManifest()), manifest);
			write(home.resolve("agents/cat-a.toml"), "old agent");
			write(home.resolve("plugins/data/cat-cat/codex-agent-files"), "cat-a.toml\n");
		}
	}

	/**
	 * Records CAT's installation in Claude Code's record of its installed plugins, as Claude Code does when it
	 * installs or updates CAT.
	 *
	 * @param home    the isolated harness home
	 * @param version the installed plugin version
	 * @throws IOException if writing fails
	 */
	private static void recordClaudeInstallation(Path home, String version) throws IOException
	{
		write(home.resolve("plugins/installed_plugins.json"), "{\"version\": 2, \"plugins\": {\"cat@cat\": " +
			"[{\"scope\": \"user\", \"version\": \"" + version + "\"}]}}\n");
	}

	/**
	 * Returns the harness's own layout of CAT's installation, which the fixture integration reports as the packaged
	 * integration does.
	 *
	 * @param harness the harness identifier
	 * @return the packaged integration
	 */
	private static HarnessIntegration layout(String harness)
	{
		return ServiceLoader.load(HarnessIntegration.class).stream().map(ServiceLoader.Provider::get).
			filter(service -> service.harness().equals(harness)).findFirst().orElseThrow();
	}

	/**
	 * Returns the content of each regular file below a directory, by its relative path.
	 *
	 * @param directory the directory
	 * @return the contents
	 * @throws IOException if a file cannot be read
	 */
	private static Map<String, String> snapshot(Path directory) throws IOException
	{
		Map<String, String> contents = new HashMap<>();
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS)).toList())
				contents.put(directory.relativize(path).toString(), Files.readString(path));
		}
		return contents;
	}

	/**
	 * Copies a directory tree, as a harness copies CAT's marketplace folder into a version folder.
	 *
	 * @param source      the tree
	 * @param destination the new directory
	 * @throws IOException if the tree cannot be copied
	 */
	private static void copyTree(Path source, Path destination) throws IOException
	{
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : paths.toList())
			{
				Path copy = destination.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path, NOFOLLOW_LINKS))
					Files.createDirectories(copy);
				else
					Files.copy(path, copy);
			}
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
		String top = "cat-" + provider.harness() + "-linux-x64-v1.1";
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes)))
		{
			entry(tar, top + "/" + provider.pluginManifest(), "{\"name\":\"cat\",\"version\":\"" + PLUGIN_VERSION +
				"\"}", 0644);
			entry(tar, top + "/bin/VERSION", PLUGIN_VERSION + "\n", 0644);
			entry(tar, top + "/bin/runtime-requirement.json", REQUIREMENT, 0644);
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
			"version", "1.1", "tag", "v1.1", "build", "0123456789ab", "published", "2026-11-03T13:44:35Z", "assets",
			List.of(asset)));
		KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(key.getPrivate());
		signer.update(manifest);
		byte[] signature = Base64.getEncoder().encode(signer.sign());
		return new SignedRelease(key, Map.of(top + ".tar.gz", archive), manifest, signature);
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
	 * @param archives the plugin archives, by asset name
	 * @param manifest the signed manifest bytes
	 * @param signature the detached signature bytes
	 */
	private record SignedRelease(KeyPair key, Map<String, byte[]> archives, byte[] manifest, byte[] signature)
		implements ReleaseSource
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
			Files.write(destination, archives.get(assetName));
		}
	}

	/**
	 * Stands in for the harness's registration, whose commands the providers' own tests cover, and reports CAT's
	 * folders where the packaged integration does. It records each version that it registers and installs it as the
	 * harness does: Claude Code installs from the version folder itself and records the version in its record of
	 * installed plugins, while Codex copies CAT's marketplace folder into the version folder.
	 *
	 * @param harness    the harness identifier
	 * @param packaged   the packaged integration of the harness
	 * @param registered the version of each registration, in call order
	 */
	private record FixtureIntegration(String harness, HarnessIntegration packaged, List<PluginVersion> registered)
		implements HarnessIntegration
	{
		@Override
		public String programName()
		{
			throw new UnsupportedOperationException("Only install discovers the harness program");
		}

		@Override
		public Map<String, String> programEnvironment(Path home, Map<String, String> environment)
		{
			throw new UnsupportedOperationException("Only install discovers the harness program");
		}

		@Override
		public void requireIdentity(CommandResult version)
		{
			throw new UnsupportedOperationException("Only install discovers the harness program");
		}

		@Override
		public void validateConfiguration(Path home)
		{
		}

		@Override
		public void register(Path home, PluginVersion version, HarnessCli cli, FileReplacer replacer)
			throws IOException
		{
			registered.add(version);
			if (harness.equals("claude"))
				recordClaudeInstallation(home, version.toString());
			else
				copyTree(packaged.marketplaceFolder(home, version), installation(home).versionFolder(version));
		}

		@Override
		public void unregister(Path home, HarnessCli cli, boolean keepData)
		{
		}

		@Override
		public HarnessInstallation installation(Path home)
		{
			return packaged.installation(home);
		}

		@Override
		public HarnessInstallation installation(Map<String, String> environment, Path userHome)
		{
			throw new UnsupportedOperationException("Only install locates the harness's installation");
		}

		@Override
		public Path marketplaceFolder(Path home, PluginVersion version)
		{
			return packaged.marketplaceFolder(home, version);
		}

		@Override
		public Map<String, List<String>> installSandboxEntries(Path home, HarnessInstallation other, Path catLocks,
			FileReplacer replacer)
		{
			return Map.of();
		}

		@Override
		public Map<String, List<String>> sandboxEntries(Path home, HarnessInstallation other, Path catLocks)
		{
			return Map.of();
		}

		@Override
		public Map<String, List<String>> installPermissions(Path home, Path installer, FileReplacer replacer)
		{
			return Map.of();
		}

		@Override
		public Map<String, List<String>> permissions(Path installer)
		{
			return Map.of();
		}

		@Override
		public void removePermissions(Path home, Map<String, List<String>> added, FileReplacer replacer)
		{
		}

		@Override
		public Optional<String> consentRequest(Path home, Path catLocks)
		{
			return Optional.empty();
		}

		@Override
		public Map<String, List<String>> installConsentedEntries(Path home, FileReplacer replacer)
		{
			return Map.of();
		}

		@Override
		public List<String> describeAdded(Path home, Map<String, List<String>> added)
		{
			return List.of();
		}

		@Override
		public List<String> describeRetained(Path home, Map<String, List<String>> added)
		{
			return List.of();
		}
	}
}
