package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.engine.test.RegisteredProvider;
import com.holdmyspot.cat.common.installer.secrets.InstallerProcessSecrets;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how the CAT entry command and the installer of an installed version folder treat the release keys of a harness
 * installation's existing policy. The entry command, which the user runs, adds the signing key of its own release to a
 * policy that lists no key and keeps the policy's other fields; the installed installer, which an agent runs, never
 * changes the policy; and neither changes a policy that lists a key.
 */
public final class KeylessPolicyTest
{
	/**
	 * Creates the test.
	 */
	public KeylessPolicyTest()
	{
	}

	/**
	 * Where the harness has CAT and its policy lists no release key, the entry command adds the key of its own
	 * release to the policy, keeps the policy's release source, version limits and harness entry, and says so. A later
	 * install of a release signed by that key through the installed installer then succeeds.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void entryCommandAddsReleaseKey() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("keyless-policy-added-")));
			fixture.writePolicy(List.of());

			Run entry = fixture.runEntryCommand(List.of(fixture.releaseKey));

			requireThat(entry.status, "status").withContext(entry.output, "output").
				withContext(entry.diagnostics, "diagnostics").isEqualTo(0);
			JsonNode result = entry.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("updated");
			requireThat(result.path("message").stringValue(), "message").contains("no release key").
				contains(Fixture.RELEASE_KEY_ID);
			requireThat(result.path("key_ids").get(0).stringValue(), "keyId").isEqualTo(Fixture.RELEASE_KEY_ID);
			requireThat(result.path("key_ids").size(), "keyIds").isEqualTo(1);
			requireThat(TrustPolicy.read(fixture.policyFile), "policy").
				isEqualTo(fixture.policy(List.of(fixture.releaseKey)));

			Run update = fixture.runInstaller(PluginArchive.VERSION, "install", "--harness", "harness-a",
				PluginRelease.TAG);

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(update.result().path("result").stringValue(), "result").isEqualTo("up_to_date");
		}
	}

	/**
	 * Where the harness has CAT and its policy lists a key other than the one that signs the entry command's release,
	 * the entry command leaves the policy unchanged, and the installed installer refuses the release that the policy's
	 * key does not sign.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void entryCommandKeepsOtherKey() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("keyless-policy-other-")));
			fixture.writePolicy(List.of(trusted("other-key", keyPair())));
			byte[] policy = Files.readAllBytes(fixture.policyFile);

			Run entry = fixture.runEntryCommand(List.of(fixture.releaseKey));

			requireThat(entry.status, "status").withContext(entry.output, "output").
				withContext(entry.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(entry.result().path("result").stringValue(), "result").isEqualTo("updated");
			requireThat(Files.readAllBytes(fixture.policyFile), "policy").isEqualTo(policy);

			Run update = fixture.runInstaller(PluginArchive.VERSION, "install", "--harness", "harness-a",
				PluginRelease.TAG);

			requireThat(update.status, "status").withContext(update.output, "output").isEqualTo(3);
			requireThat(update.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(Files.readAllBytes(fixture.policyFile), "policy").isEqualTo(policy);
		}
	}

	/**
	 * The entry command of a development build, whose archive names no release key, leaves a policy that lists no key
	 * unchanged.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void keylessArchiveKeepsPolicy() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("keyless-policy-development-")));
			fixture.writePolicy(List.of());
			byte[] policy = Files.readAllBytes(fixture.policyFile);

			Run entry = fixture.runEntryCommand(List.of());

			requireThat(entry.status, "status").withContext(entry.output, "output").
				withContext(entry.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(entry.result().path("key_ids").size(), "keyIds").isEqualTo(0);
			requireThat(Files.readAllBytes(fixture.policyFile), "policy").isEqualTo(policy);
		}
	}

	/**
	 * The installed installer, which an agent runs, never adds a key to a policy that lists none: it refuses to
	 * install a release, says that the user's run of the CAT setup command of a release that ships its signing key
	 * repairs the policy, and leaves the policy unchanged.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void installerKeepsKeylessPolicy() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("keyless-policy-installer-")));
			fixture.writePolicy(List.of());
			byte[] policy = Files.readAllBytes(fixture.policyFile);

			Run update = fixture.runInstaller(Fixture.ACTIVE, "install", "--harness", "harness-a", PluginRelease.TAG);

			requireThat(update.status, "status").withContext(update.output, "output").isEqualTo(3);
			requireThat(update.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(update.result().path("message").stringValue(), "message").
				contains("The trust policy lists no release key").contains("setup command");
			requireThat(Files.readAllBytes(fixture.policyFile), "policy").isEqualTo(policy);
		}
	}

	/**
	 * Returns a replacer whose isolated files never need a retry wait.
	 *
	 * @return the replacer
	 */
	private static FileReplacer replacer()
	{
		return new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC());
	}

	/**
	 * The outcome of one invocation.
	 *
	 * @param status      the exit status
	 * @param output      the standard output
	 * @param diagnostics the standard error
	 */
	private record Run(int status, String output, String diagnostics)
	{
		/**
		 * Returns the printed result.
		 *
		 * @return the JSON object
		 */
		JsonNode result()
		{
			return JsonMapper.builder().build().readTree(output);
		}
	}

	/**
	 * An installation of {@code harness-a} whose active CAT version is {@link #ACTIVE}, with a policy whose release
	 * source and version limits differ from those of the entry command's archive, and the release source of release
	 * 1.2, which {@link #releaseKey} signs.
	 */
	private static final class Fixture
	{
		/**
		 * The active plugin version before the entry command runs.
		 */
		private static final String ACTIVE = "1.0+b0123456789ab";
		/**
		 * The identifier of the key that signs release 1.2.
		 */
		private static final String RELEASE_KEY_ID = "cat-release";
		/**
		 * The location of the policy's release source, which differs from that of the entry command's archive.
		 */
		private static final URI RELEASE_BASE_URL = URI.create("https://mirror.example/cat/releases/download");
		private final Path root;
		private final Path home;
		private final Path policyFile;
		private final HarnessCommand command;
		private final Map<String, String> environment;
		private final TrustedKey releaseKey;
		private final PluginRelease source;

		/**
		 * Creates the fixture without its policy.
		 *
		 * @param directory the test directory
		 * @throws IOException    if the fixture cannot be created
		 * @throws TrustException if a test key is invalid
		 */
		private Fixture(Path directory) throws IOException, TrustException
		{
			this.root = directory.toRealPath();
			this.home = Files.createDirectory(root.resolve("home"));
			this.policyFile = home.resolve("plugins/data/cat-cat/policy.json");
			Path user = Files.createDirectory(root.resolve("user"));
			Path program = RecordingIntegration.program(root.resolve("tools"));
			Map<String, String> environment = new HashMap<>(RecordingIntegration.windowsVariables());
			environment.put("HOME", user.toString());
			environment.put(Platform.current().homeVariable(), user.toString());
			environment.put("PATH", program.getParent().toString());
			this.environment = Map.copyOf(environment);
			this.command = new HarnessCommand(List.of(program.toString()), Map.of("HOME", user.toString()));

			Path active = Files.createDirectories(versionFolder(ACTIVE).resolve("bin"));
			Files.writeString(active.resolve("VERSION"), ACTIVE + "\n");
			Files.writeString(RecordingIntegration.registeredVersion(home), ACTIVE + "\n");

			KeyPair key = keyPair();
			this.releaseKey = trusted(RELEASE_KEY_ID, key);
			Path published = PluginArchive.create(root.resolve("published"), List.of(releaseKey));
			this.source = new PluginRelease(published, "harness-a", key, RELEASE_BASE_URL);
		}

		/**
		 * Returns the policy of the installation, which the entry command's archive did not write: its release
		 * source and oldest accepted release differ from the archive's.
		 *
		 * @param keys the release keys that the policy trusts
		 * @return the policy
		 * @throws TrustException if the policy is invalid
		 */
		private TrustPolicy policy(List<TrustedKey> keys) throws TrustException
		{
			return TrustPolicy.of(keys, RELEASE_BASE_URL, ReleaseVersion.parse("1.1"), 1, Map.of("harness-a", home),
				Map.of("harness-a", command));
		}

		/**
		 * Writes the installation's policy.
		 *
		 * @param keys the release keys that the policy trusts
		 * @throws IOException    if the policy cannot be written
		 * @throws TrustException if the policy is invalid
		 */
		private void writePolicy(List<TrustedKey> keys) throws IOException, TrustException
		{
			Files.createDirectories(policyFile.getParent());
			policy(keys).write(policyFile, replacer());
		}

		/**
		 * Returns a version folder of the installation.
		 *
		 * @param version the plugin version
		 * @return the folder
		 */
		private Path versionFolder(String version)
		{
			return RecordingIntegration.layout("harness-a", home).versionFolder(PluginVersion.parse(version));
		}

		/**
		 * Runs install from an extracted plugin archive of release 1.2 into the harness home, as the CAT entry
		 * command does.
		 *
		 * @param keys the release keys that the archive's default policy names
		 * @return the outcome
		 * @throws IOException if the archive cannot be created
		 */
		private Run runEntryCommand(List<TrustedKey> keys) throws IOException
		{
			Path archive = PluginArchive.create(Files.createTempDirectory(root, "download-"), keys);
			return run(archive, "--home", home.toString(), "install", "--harness", "harness-a");
		}

		/**
		 * Runs the installer of one of the installation's version folders, as an agent does.
		 *
		 * @param version   the plugin version of the folder
		 * @param arguments the command line
		 * @return the outcome
		 */
		private Run runInstaller(String version, String... arguments)
		{
			return run(versionFolder(version), arguments);
		}

		/**
		 * Returns a command line that asks for the result as JSON, as the last argument.
		 *
		 * @param arguments the command line
		 * @return the command line with {@code --json} added
		 */
		private static String[] json(String... arguments)
		{
			String[] json = Arrays.copyOf(arguments, arguments.length + 1);
			json[arguments.length] = "--json";
			return json;
		}

		/**
		 * Runs one installer invocation whose terminal answers nothing and whose harness program never starts.
		 *
		 * @param image     the plugin folder that the installer runs from
		 * @param arguments the command line
		 * @return the outcome
		 */
		private Run run(Path image, String... arguments)
		{
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			int status;
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
			{
				Invocation invocation = new Invocation(image, Platform.current(), environment, null,
					List.of(new RegisteredProvider()), List.of(new RecordingIntegration()), (recorded, recordedHome) ->
					RecordingIntegration.cli(), Clock.systemUTC(), interval -> () -> {}, replacer(),
					_ -> Optional.empty(), command ->
				{
					throw new AssertionError("Started " + command);
				}, source::open, InstallerProcessSecrets.getInstallerProcessAccess().create(Platform.current(),
					environment, builder ->
				{
					throw new AssertionError("Started " + builder.command());
				}), stdout, stderr);
				status = invocation.run(json(arguments));
			}
			return new Run(status, output.toString(UTF_8), diagnostics.toString(UTF_8));
		}
	}
}
