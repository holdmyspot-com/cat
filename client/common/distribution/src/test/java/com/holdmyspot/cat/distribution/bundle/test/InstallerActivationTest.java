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
import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.engine.RuntimeSwap;
import com.holdmyspot.cat.common.installer.engine.RuntimeSwap.Operation;
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
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an authenticated update of the whole bundle through the engine and the packaged harness providers together.
 */
public final class InstallerActivationTest
{
	/**
	 * The description of the installed Java runtime and of the release's runtime.
	 */
	private static final String RUNTIME_DESCRIPTION = "{\"format\": 1, \"java_version\": \"25.0.1\", " +
		"\"java_feature\": 25, \"modules\": [\"java.base\"]}";
	/**
	 * The runtime requirement of the installer and the plugin.
	 */
	private static final String REQUIREMENT = "{\"format\": 1, \"java_feature\": 25, \"modules\": [\"java.base\"]}";
	/**
	 * The full plugin version that the signed release names.
	 */
	private static final String PLUGIN_VERSION = "1.1+b0123456789ab";

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
	 * An authenticated release changes the actual provider destinations, stages its runtime and installs its stable
	 * launcher, while configuration, user files and staged executable content remain untouched.
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
			writeInstallation(data);
			UpdateEngine engine = new UpdateEngine(data, policy, release, provider,
				"linux-x64", Clock.systemUTC(), interval -> () -> {});
			FixtureIntegration integration = new FixtureIntegration(harness, new ArrayList<>());
			HarnessSession session = new HarnessSession(integration,
				arguments -> new CommandResult(0, ""), new FileReplacer(Platform.current(), () ->
			{
			}, Clock.systemUTC()));

			UpdateResult updated = engine.update("v1.1", session);

			requireThat(updated.result(), "updateResult").isEqualTo(Result.UPDATED);
			requireThat(updated.consistent(), "updatedConsistent").isTrue();
			requireThat(integration.registered(), "registered").isEqualTo(List.of(PluginVersion.parse(PLUGIN_VERSION)));
			requireThat(provider.installed(home), "updatedInstallation").
				isEqualTo(new InstalledState(Optional.of(PLUGIN_VERSION), true));
			requireThat(engine.pendingSwap(), "updateSwap").
				isEqualTo(Optional.of(new RuntimeSwap(Operation.UPDATE, "1.0", "1.1")));
			finishSwap(data);
			requireThat(Files.readString(data.resolve("runtime/release")), "updatedRuntime").isEqualTo("runtime 1.1");
			requireThat(Files.readString(data.resolve("bin/cat-updater")), "updatedLauncher").
				isEqualTo("stable launcher 1.1");
			requireThat(engine.status().previousVersion(), "previousVersion").isEqualTo(Optional.of("1.0"));
			requireThat(Files.readString(provider.migrationVersionFile(home)), "updatedMigrationVersion").
				isEqualTo("1.1\n");
			if (harness.equals("codex"))
			{
				requireThat(Files.exists(home.resolve("agents/cat-a.toml"), NOFOLLOW_LINKS), "retiredAgentExists").isFalse();
				requireThat(Files.readString(home.resolve("agents/cat-b.toml")), "newAgent").isEqualTo("new agent");
				requireThat(home.resolve("plugins/cache/cat/cat/1.0"), "retainedCache").isDirectory();
			}
			else
				requireThat(home.resolve("plugins/cat-marketplace/plugins/cat/1.0"), "retainedFolder").isDirectory();
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
		write(provider.migrationVersionFile(home), "1.0\n");
		if (provider.harness().equals("claude"))
		{
			write(home.resolve("plugins/cat-marketplace/plugins/cat/1.0").resolve(provider.pluginManifest()), manifest);
			recordClaudeInstallation(home, "1.0");
		}
		else
		{
			write(home.resolve("plugins/cat-marketplace/plugins/cat").resolve(provider.pluginManifest()), manifest);
			write(home.resolve("plugins/cache/cat/cat/1.0").resolve(provider.pluginManifest()), manifest);
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
	 * Writes the installation of release 1.0 that setup leaves: its Java runtime, which satisfies the fixture release,
	 * its installer version directory, the stable launcher and the current selection.
	 *
	 * @param data the installation's installer data directory
	 * @throws IOException if writing fails
	 */
	private static void writeInstallation(Path data) throws IOException
	{
		write(data.resolve("runtime/cat-runtime.json"), RUNTIME_DESCRIPTION);
		write(data.resolve("runtime/release"), "runtime 1.0");
		write(data.resolve("1.0/VERSION"), "1.0\n");
		write(data.resolve("1.0/runtime-requirement.json"), REQUIREMENT);
		write(data.resolve("1.0/conf/cat-updater/policy-path"), data.resolveSibling("policy.json") + "\n");
		write(data.resolve("1.0/stable-launcher/cat-updater"), "stable launcher 1.0");
		write(data.resolve("bin/cat-updater"), "stable launcher 1.0");
		write(data.resolve("current"), "1.0\n");
	}

	/**
	 * Finishes the requested runtime replacement as the stable launcher does after the installer's JVM has exited and
	 * the new runtime runs.
	 *
	 * @param data the installation's installer data directory
	 * @throws IOException if no replacement is pending or a rename fails
	 */
	private static void finishSwap(Path data) throws IOException
	{
		List<String> request = Files.readAllLines(data.resolve("state/runtime-swap"), UTF_8);
		Files.move(data.resolve("runtime"), data.resolve("runtime.previous"));
		Files.move(data.resolve("runtime.next"), data.resolve("runtime"));
		Files.writeString(data.resolve("current"), request.get(2) + "\n", UTF_8);
		Files.delete(data.resolve("state/runtime-swap"));
		Files.deleteIfExists(data.resolve("state/runtime-swap-result.json"));
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
		String installerTop = "cat-updater-v1.1-linux-x64";
		bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes)))
		{
			entry(tar, installerTop + "/runtime/cat-runtime.json", RUNTIME_DESCRIPTION, 0644);
			entry(tar, installerTop + "/runtime/release", "runtime 1.1", 0644);
			entry(tar, installerTop + "/1.1/VERSION", "1.1\n", 0644);
			entry(tar, installerTop + "/1.1/runtime-requirement.json", REQUIREMENT, 0644);
			entry(tar, installerTop + "/1.1/stable-launcher/cat-updater", "stable launcher 1.1", 0755);
			entry(tar, installerTop + "/1.1/stable-launcher/cat-updater.ps1", "stable launcher 1.1", 0644);
		}
		byte[] installer = bytes.toByteArray();
		Map<String, Object> installerAsset = Map.of("name", installerTop + ".tar.gz", "kind", "updater",
			"platform", "linux-x64", "size", installer.length, "sha256",
			HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(installer)), "top_directory",
			installerTop);
		byte[] manifest = JsonMapper.builder().build().writeValueAsBytes(Map.of("format", 1, "product", "cat",
			"version", "1.1", "tag", "v1.1", "build", "0123456789ab", "published", "2026-11-03T13:44:35Z", "assets",
			List.of(asset, installerAsset)));
		KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(key.getPrivate());
		signer.update(manifest);
		byte[] signature = Base64.getEncoder().encode(signer.sign());
		return new SignedRelease(key, Map.of(top + ".tar.gz", archive, installerTop + ".tar.gz", installer), manifest,
			signature);
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
	 * @param archives the plugin and installer archives, by asset name
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
	 * Stands in for the harness's registration, whose commands the providers' own tests cover. It records each version
	 * that it registers, and records it where the harness reports its installed version: Claude Code in its record of
	 * installed plugins, while Codex names its cache folder after the version, which activation already wrote.
	 *
	 * @param harness    the harness identifier
	 * @param registered the version of each registration, in call order
	 */
	private record FixtureIntegration(String harness, List<PluginVersion> registered) implements HarnessIntegration
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
		}

		@Override
		public void unregister(Path home, HarnessCli cli, boolean keepData)
		{
		}

		@Override
		public HarnessInstallation installation(Path home)
		{
			throw new UnsupportedOperationException("Only install locates the harness's installation");
		}

		@Override
		public HarnessInstallation installation(Map<String, String> environment, Path userHome)
		{
			throw new UnsupportedOperationException("Only install locates the harness's installation");
		}

		@Override
		public Path marketplaceFolder(Path home, PluginVersion version)
		{
			throw new UnsupportedOperationException("Only install writes the marketplace folder");
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
	}
}
