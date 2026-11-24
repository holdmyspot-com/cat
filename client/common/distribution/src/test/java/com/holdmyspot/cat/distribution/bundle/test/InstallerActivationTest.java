package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.cli.internal.DetachedProcessStarter;
import com.holdmyspot.cat.common.installer.cli.internal.HarnessCliFactory;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessHarnessCli;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessTerminal;
import com.holdmyspot.cat.common.installer.cli.internal.Terminal;
import com.holdmyspot.cat.common.installer.fakes.test.ExitedProgram;
import com.holdmyspot.cat.common.installer.fakes.test.HarnessProgram;
import com.holdmyspot.cat.common.installer.secrets.ProcessHarnessCliSecrets;
import com.holdmyspot.cat.distribution.bundle.ApplicationModules;
import com.holdmyspot.cat.distribution.bundle.ArtifactLayer;
import com.holdmyspot.cat.distribution.bundle.PluginArtifactBuilder;
import com.holdmyspot.cat.distribution.bundle.PluginInstaller;
import com.holdmyspot.cat.distribution.bundle.PluginLaunchers;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the installer that each assembled plugin carries through a whole installation's life, with the packaged
 * harness providers and a fake harness program: an install, an install of another build, a status, an uninstall that
 * keeps CAT's plugin data and an uninstall that deletes it.
 */
public final class InstallerActivationTest
{
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	/**
	 * The marketplace metadata of each harness, which names the plugin folder beneath the marketplace.
	 */
	private static final Map<String, String> MARKETPLACES = Map.of(
		"claude", """
			{
				"name": "cat",
				"owner": {
					"name": "cowwoc"
				},
				"plugins": [
					{
						"name": "cat",
						"source": "./plugins/cat"
					}
				]
			}
			""",
		"codex", """
			{
				"name": "cat",
				"plugins": [
					{
						"name": "cat",
						"source": {
							"source": "local",
							"path": "./plugins/cat"
						},
						"policy": {
							"installation": "INSTALLED_BY_DEFAULT",
							"authentication": "ON_INSTALL"
						}
					}
				]
			}
			""");
	/**
	 * The build that the harness runs first.
	 */
	private static final PluginVersion FIRST = PluginVersion.parse("1.0+b00000000000a");
	/**
	 * The build that replaces it.
	 */
	private static final PluginVersion SECOND = PluginVersion.parse("1.0+b00000000000b");

	/**
	 * Creates the test.
	 */
	public InstallerActivationTest()
	{
	}

	/**
	 * Supplies each packaged harness.
	 *
	 * @return the harness identifiers
	 */
	@DataProvider
	public Object[][] harnesses()
	{
		return new Object[][]{{"claude"}, {"codex"}};
	}

	/**
	 * Supplies lost policy states and uninstall data choices for each harness.
	 *
	 * @return cases with missing data, existing data to keep, and existing data to delete
	 */
	@DataProvider
	public Object[][] missingPolicies()
	{
		return new Object[][]{
			{"claude", false, false}, {"claude", true, false}, {"claude", true, true},
			{"codex", false, false}, {"codex", true, false}, {"codex", true, true}};
	}

	/**
	 * Uninstall without a policy removes the plugin and applicable credentials and reports how to finish registration
	 * and settings cleanup. It preserves existing data by default and removes data created solely for its lock.
	 *
	 * @param harness the packaged harness to exercise
	 * @param hasData true if plugin data exists without a policy
	 * @param deleteData true if the user asks to delete plugin data
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "missingPolicies")
	public void uninstallWithoutPolicy(String harness, boolean hasData, boolean deleteData) throws IOException
	{
		// Requirement: R-I18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("uninstall-no-policy-").toRealPath());
			Path userHome = Files.createDirectory(root.resolve("user"));
			HarnessIntegration integration = provider(HarnessIntegration.class, harness);
			HarnessInstallation installation = integration.installation(root.resolve("harness"));
			Path image = Files.createDirectories(installation.versionFolder(FIRST));
			Path data = installation.dataDirectory();
			if (hasData)
				Files.writeString(Files.createDirectories(data).resolve("user-data"), "keep");
			boolean claude = harness.equals("claude");
			Path settings;
			String originalSettings;
			if (claude)
			{
				settings = installation.home().resolve("settings.json");
				originalSettings = "{}\n";
			}
			else
			{
				settings = installation.home().resolve("config.toml");
				originalSettings = "# user configuration\n";
			}
			Files.writeString(settings, originalSettings);
			Path credential = Files.writeString(Files.createDirectories(userHome.resolve(".cat/claude")).
				resolve(".credentials.json"), "{\"format_version\": 1, \"token\": \"sk-ant-oat01-fixture\"}");
			List<String> arguments = new ArrayList<>(List.of("uninstall", "--harness", harness));
			if (deleteData)
				arguments.add("--delete-data");
			arguments.add("--json");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			Platform platform = Platform.LINUX_X64;
			Waiting waiting = _ -> () ->
			{
			};
			int status;
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
			{
				status = new Invocation(image, platform, Map.of("HOME", userHome.toString()), userHome,
					List.of(provider(PluginActivation.class, harness)), List.of(integration), ProcessHarnessCli::new,
					Clock.systemUTC(), waiting, FileReplacer.retrying(waiting),
					new ProcessTerminal(platform), new DetachedProcessStarter(), stdout, stderr).
					run(arguments.toArray(String[]::new));
			}

			String printed = output.toString(UTF_8);
			requireThat(status, "status").withContext(printed, "output").isEqualTo(0);
			JsonNode result = MAPPER.readTree(printed);
			requireThat(result.path("result").asString(), "result").isEqualTo("uninstalled");
			requireThat(result.path("unregistered").isBoolean(), "unregisteredReported").isTrue();
			requireThat(result.path("unregistered").asBoolean(), "unregistered").isFalse();
			String message = result.path("message").asString();
			requireThat(message, "message").contains("policy.json").contains(settings.toString()).
				contains(harness + " plugin marketplace remove cat");
			boolean keepData = hasData && !deleteData;
			if (claude)
			{
				String uninstall = "claude plugin uninstall cat@cat";
				if (keepData)
					uninstall = "claude plugin uninstall --keep-data cat@cat";
				requireThat(message, "message").contains(uninstall).contains("Deleted " + credential);
			}
			else
				requireThat(message, "message").contains("codex plugin remove cat@cat");
			requireThat(Files.readString(settings), "settings").isEqualTo(originalSettings);
			requireThat(Files.exists(credential), "credentialLeft").isEqualTo(!claude);
			requireThat(Files.exists(image), "versionFolderLeft").isFalse();
			requireThat(Files.exists(data), "dataFolderLeft").isEqualTo(keepData);
			if (keepData)
				requireThat(Files.readString(data.resolve("user-data")), "data").isEqualTo("keep");
		}
	}

	/**
	 * The installer of an assembled plugin installs CAT, a second build's installer replaces it, the installer in the
	 * harness's version folder reports the installed build, uninstall unregisters CAT and keeps its plugin data, and
	 * an uninstall with {@code --delete-data} also deletes that data. The installer's own version folder is deleted
	 * once the uninstalling installer exits.
	 * <p>
	 * This test can take over one second: it assembles two plugin versions and exercises the complete
	 * install, update and uninstall lifecycle. Both versions and both data-retention choices are needed to check
	 * replacement and cleanup through the assembled installer's entry point.
	 *
	 * @param harness the packaged harness to exercise
	 * @throws IOException if a fixture file cannot be written or read
	 */
	@Test(dataProvider = "harnesses")
	public void installUpdateStatusUninstall(String harness) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			// Install looks for the harness program outside the temporary directory, so the fixture lies beside the
			// test classes in the build directory
			Path build = Path.of(URI.create(InstallerActivationTest.class.getProtectionDomain().getCodeSource().
				getLocation().toString())).getParent();
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(build.resolve("test-files")),
				"installer-activation-").toRealPath());
			Platform platform = Platform.current();
			PluginActivation activation = provider(PluginActivation.class, harness);
			HarnessIntegration integration = provider(HarnessIntegration.class, harness);
			FakeHarness fake = new FakeHarness(harness, platform, root);
			Path first = assemble(scope, harness, platform, FIRST, root.resolve("first"));
			Path second = assemble(scope, harness, platform, SECOND, root.resolve("second"));
			Path home = fake.home;
			Path data = activation.dataDirectory(home);

			JsonNode installed = fake.run(first, "--home", home.toString(), "install", "--harness", harness);
			requireThat(installed.path("result").asString(), "installed").withContext(installed, "output").
				isEqualTo("installed");
			requireThat(activation.installed(home).version(), "firstVersion").isEqualTo(Optional.of(FIRST.toString()));

			JsonNode updated = fake.run(second, "--home", home.toString(), "install", "--harness", harness);
			requireThat(updated.path("result").asString(), "updated").withContext(updated, "output").
				isEqualTo("updated");
			requireThat(activation.installed(home).version(), "secondVersion").
				isEqualTo(Optional.of(SECOND.toString()));

			Path versionFolder = integration.installation(home).versionFolder(SECOND);
			JsonNode status = fake.run(versionFolder, "status");
			requireThat(status.path("result").asString(), "status").withContext(status, "output").isEqualTo("status");
			requireThat(status.path("version").asString(), "statusVersion").isEqualTo(SECOND.toString());
			requireThat(status.path("consistent").asBoolean(), "consistent").withContext(status, "output").isTrue();

			JsonNode kept = fake.run(versionFolder, "uninstall");
			requireThat(kept.path("result").asString(), "kept").withContext(kept, "output").isEqualTo("uninstalled");
			fake.requireExitDeletion(versionFolder);
			requireThat(activation.installed(home).version(), "uninstalledVersion").isEmpty();
			requireThat(Files.isDirectory(data), "dataKept").isTrue();

			JsonNode reinstalled = fake.run(second, "--home", home.toString(), "install", "--harness", harness);
			requireThat(reinstalled.path("result").asString(), "reinstalled").withContext(reinstalled, "output").
				isEqualTo("installed");
			JsonNode deleted = fake.run(versionFolder, "uninstall", "--delete-data");
			requireThat(deleted.path("result").asString(), "deleted").withContext(deleted, "output").
				isEqualTo("uninstalled");
			fake.requireExitDeletion(versionFolder);
			requireThat(Files.exists(data), "dataDeleted").isFalse();
		}
	}

	/**
	 * A person who installs CAT reads which files CAT changed and what their entries let the harness do, grouped by
	 * effect, with the installer's operations and the runner launchers by name, the paths in the user's home directory
	 * relative to {@code ~} and the command that lists the entries exactly. That command lists each entry as the
	 * harness configuration holds it.
	 * <p>
	 * This test can take over one second because it assembles and installs a plugin before reading its
	 * verbose status. The installed harness integration must supply the reported entries; supplying report entries
	 * directly would omit that connection between installation and reporting.
	 *
	 * @param harness the packaged harness to exercise
	 * @throws IOException if a fixture file cannot be written or read
	 */
	@Test(dataProvider = "harnesses")
	public void installReportsEntriesByEffect(String harness) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			// Install looks for the harness program outside the temporary directory, so the fixture lies beside the
			// test classes in the build directory
			Path build = Path.of(URI.create(InstallerActivationTest.class.getProtectionDomain().getCodeSource().
				getLocation().toString())).getParent();
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(build.resolve("test-files")),
				"installer-report-").toRealPath());
			Platform platform = Platform.current();
			HarnessIntegration integration = provider(HarnessIntegration.class, harness);
			FakeHarness fake = new FakeHarness(harness, platform, root);
			Path first = assemble(scope, harness, platform, FIRST, root.resolve("first"));
			Path home = fake.home;

			String installed = fake.report(first, "--home", home.toString(), "install", "--harness", harness);
			Path versionFolder = integration.installation(home).versionFolder(FIRST);
			String exact = fake.report(versionFolder, "status", "--verbose");

			String lineSeparator = System.lineSeparator();
			Path launcher = versionFolder.resolve("bin").resolve("installer");
			if (platform.isWindows())
				launcher = launcher.resolveSibling("installer.ps1");
			// The request server runs outside the sandbox where the harnesses sandbox commands
			String server = "";
			if (!platform.isWindows())
			{
				server = ", request-server";
				if (harness.equals("codex"))
					server = ", prompt-optimizer, request-server";
			}
			requireThat(installed, "installed").
				contains("CAT 1.0 is installed for " + integration.productName() + ". Restart " +
					integration.productName() + " for the changes to take effect.").
				contains("Uninstalling CAT undoes these changes.").
				contains("Runs without asking").
				contains("    installer install, installer status" + server + lineSeparator).
				contains("Runs outside the sandbox").
				contains("    installer install, installer status, installer uninstall" + server + lineSeparator).
				doesNotContain("claude-runner").doesNotContain("codex-runner").
				contains("The sandbox may write to").contains("    " + String.join(fake.user.getFileSystem().
					getSeparator(), "~", ".cat", "locks") + lineSeparator).
				contains("  where ~         = " + fake.user + lineSeparator).
				contains("        installer = " + launcher + lineSeparator).
				endsWith("Run installer status --verbose to see the exact entries." + lineSeparator).
				doesNotContain("\"result\"");
			// Codex's rules file holds each launcher path as a quoted string literal, which escapes backslashes
			String entryLauncher = launcher.toString();
			if (harness.equals("codex"))
			{
				String literal = MAPPER.writeValueAsString(entryLauncher);
				entryLauncher = literal.substring(1, literal.length() - 1);
			}
			requireThat(exact, "exact").contains("Uninstalling CAT undoes these changes.").
				contains("Runs without asking").contains(entryLauncher).doesNotContain("installer =").
				doesNotContain("--verbose").doesNotContain("\"result\"");
		}
	}

	/**
	 * Supplies each packaged harness with each exit status by which a shell reports that it could not run a program.
	 *
	 * @return the harness identifiers and exit statuses
	 */
	@DataProvider
	public Object[][] unstartedPrograms()
	{
		return new Object[][]{{"claude", 127}, {"claude", 126}, {"codex", 127}, {"codex", 126}};
	}

	/**
	 * A harness program on PATH that cannot start in the installer's environment, such as a program whose interpreter
	 * exits with status 127 because the environment lacks what it loads, is reported as a program that could not start
	 * there, with its status and output and the environment to run the installer with. Install refuses, because the
	 * same environment fails the same way, and the report does not call it another program, ask the user to reinstall
	 * the harness, or ask the user to run the entry command again.
	 * <p>
	 * This test can take over one second: it assembles a plugin and calls its installer with a simulated
	 * program result. The assembled installer must translate that result into the refusal and recovery report.
	 *
	 * @param harness the packaged harness to exercise
	 * @param status  the program's exit status
	 * @throws IOException if a fixture file cannot be written or read
	 */
	@Test(dataProvider = "unstartedPrograms")
	public void unstartedProgramIsReported(String harness, int status) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path root = cleanup.add(fixtureDirectory("installer-unstarted-"));
			Platform platform = Platform.current();
			String product = provider(HarnessIntegration.class, harness).productName();
			FakeHarness fake = new FakeHarness(harness, platform, root);
			Path first = assemble(scope, harness, platform, FIRST, root.resolve("first"));
			String output = "";
			String printed = "";
			if (status == 127)
			{
				output = "node: nvm is not installed at " + fake.user.resolve(".nvm");
				printed = output + "\n";
			}
			Path program = fake.program();
			CommandResult answer = new CommandResult(status, printed);

			JsonNode refused = fake.runStarting(first, _ -> new ExitedProgram(answer), "--home",
				fake.home.toString(), "install", "--harness", harness);

			String shown = "";
			if (!output.isEmpty())
				shown = " Its output: " + output + ".";
			requireThat(refused.path("result").asString(), "result").withContext(refused, "output").
				isEqualTo("refused");
			requireThat(refused.path("message").asString(), "message").isEqualTo(product + " at " + program +
				" could not start in this environment (status " + status + ")." + shown + " Run the installer with " +
				"the environment your " + product + " sessions use.");
		}
	}

	/**
	 * A first install whose PATH holds no program of the harness is refused, because running it again in the same
	 * environment finds no program either. The message names the harness and the program that was searched for, says
	 * that it was not found on PATH in the installer's environment, tells the user to install the harness or run the
	 * installer with the PATH that its sessions use, and then to run the same command again, without asking for a run
	 * of the entry command.
	 *
	 * @param harness the packaged harness to exercise
	 * @throws IOException if a fixture file cannot be written or read
	 */
	@Test(dataProvider = "harnesses")
	public void missingProgramIsRefused(String harness) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path root = cleanup.add(fixtureDirectory("installer-missing-program-"));
			Platform platform = Platform.current();
			HarnessIntegration integration = provider(HarnessIntegration.class, harness);
			String product = integration.productName();
			FakeHarness fake = new FakeHarness(harness, platform, root);
			Path first = assemble(scope, harness, platform, FIRST, root.resolve("first"));
			Path empty = Files.createDirectories(root.resolve("empty"));
			fake.environment.put("PATH", empty.toString());

			JsonNode refused = fake.run(first, "--home", fake.home.toString(), "install", "--harness", harness);

			requireThat(refused.path("result").asString(), "result").withContext(refused, "output").
				isEqualTo("refused");
			requireThat(refused.path("message").asString(), "message").isEqualTo("The program \"" +
				integration.programName() + "\" was not found on PATH in the installer's environment (" + empty +
				"). Install " + product + ", or run the installer with the PATH your " + product + " sessions use, " +
				"then run the same command again.");
		}
	}

	/**
	 * A harness program on PATH that the operating system cannot launch in the installer's environment is reported as
	 * a program that could not start there, with the launch failure and the environment to run the installer with.
	 *
	 * @param harness the packaged harness to exercise
	 * @throws IOException if a fixture file cannot be written or read
	 */
	@Test(dataProvider = "harnesses")
	public void unlaunchedProgramIsReported(String harness) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path root = cleanup.add(fixtureDirectory("installer-unlaunched-"));
			Platform platform = Platform.current();
			String product = provider(HarnessIntegration.class, harness).productName();
			FakeHarness fake = new FakeHarness(harness, platform, root);
			Path first = assemble(scope, harness, platform, FIRST, root.resolve("first"));
			Path program = fake.program();
			String failure = "Cannot run program \"" + program + "\": error=13, Permission denied";

			JsonNode refused = fake.runStarting(first, _ ->
			{
				throw new IOException(failure);
			}, "--home", fake.home.toString(), "install", "--harness", harness);

			requireThat(refused.path("result").asString(), "result").withContext(refused, "output").
				isEqualTo("refused");
			requireThat(refused.path("message").asString(), "message").isEqualTo(product + " at " + program +
				" could not start in this environment: " + failure + ". Run the installer with the environment your " +
				product + " sessions use.");
		}
	}

	/**
	 * A program on PATH that runs but does not answer {@code --version} as the harness's program does is reported as
	 * not the harness's program.
	 *
	 * @param harness the packaged harness to exercise
	 * @throws IOException if a fixture file cannot be written or read
	 */
	@Test(dataProvider = "harnesses")
	public void otherProgramIsNotTheHarness(String harness) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path root = cleanup.add(fixtureDirectory("installer-other-program-"));
			Platform platform = Platform.current();
			FakeHarness fake = new FakeHarness(harness, platform, root);
			Path first = assemble(scope, harness, platform, FIRST, root.resolve("first"));
			Path program = fake.program();

			JsonNode failed = fake.runStarting(first, _ -> new ExitedProgram(new CommandResult(2,
				"other 2.0: unknown option --version\n")), "--home", fake.home.toString(), "install", "--harness",
				harness);

			requireThat(failed.path("result").asString(), "result").withContext(failed, "output").
				isEqualTo("failed");
			requireThat(failed.path("message").asString(), "message").
				startsWith("The installer could not complete install: The program " + program + " that install " +
					"found on PATH is not the " + harness + " program: ").
				contains("other 2.0: unknown option --version").doesNotContain("could not start");
		}
	}

	/**
	 * Creates a test directory beside the test classes in the build directory, because install looks for the harness
	 * program outside the temporary directory.
	 *
	 * @param prefix the start of the directory's name
	 * @return the real path of the new directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path fixtureDirectory(String prefix) throws IOException
	{
		Path build = Path.of(URI.create(InstallerActivationTest.class.getProtectionDomain().getCodeSource().
			getLocation().toString())).getParent();
		return Files.createTempDirectory(Files.createDirectories(build.resolve("test-files")), prefix).toRealPath();
	}

	/**
	 * Returns the packaged provider of a harness.
	 *
	 * @param <T>     the provider's service
	 * @param service the provider's service
	 * @param harness the harness
	 * @return the provider
	 */
	private static <T> T provider(Class<T> service, String harness)
	{
		for (T provider : ServiceLoader.load(service))
		{
			String identifier;
			if (provider instanceof PluginActivation activation)
				identifier = activation.harness();
			else
				identifier = ((HarnessIntegration) provider).harness();
			if (identifier.equals(harness))
				return provider;
		}
		throw new AssertionError("No packaged " + service.getSimpleName() + " for " + harness);
	}

	/**
	 * Assembles a harness's extracted plugin archive with the build's assembly steps: a plugin manifest, the plugin's
	 * modules beside a stand-in Java runtime, the harness's launchers, the installer's launchers and default policy,
	 * and the harness's marketplace metadata with an empty migration registry.
	 *
	 * @param scope     the build's process scope
	 * @param harness   the harness
	 * @param platform  the platform that the plugin runs on
	 * @param version   the plugin's build
	 * @param directory the new directory that receives the inputs and the plugin
	 * @return the plugin's folder
	 * @throws IOException if the plugin cannot be assembled
	 */
	private static Path assemble(TestJvmScope scope, String harness, Platform platform, PluginVersion version,
		Path directory) throws IOException
	{
		String manifest = "." + harness + "-plugin/plugin.json";
		Path layer = Files.createDirectories(directory).resolve("plugin.jar");
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(layer)))
		{
			jar.putNextEntry(new JarEntry(manifest));
			jar.write("""
				{
					"name": "cat",
					"version": "1.0"
				}
				""".getBytes(UTF_8));
			jar.closeEntry();
		}
		Path install = Files.createDirectories(directory.resolve("install/migrations")).getParent();
		Files.writeString(install.resolve("migrations/registry.tsv"), "# target-version<TAB>script\n");
		Files.writeString(install.resolve(harness + "-marketplace.json"), MARKETPLACES.get(harness));
		Path runtime = Files.createDirectories(directory.resolve("runtime/bin")).getParent();
		Files.writeString(runtime.resolve("bin/java"), "java");
		Path module = Files.writeString(directory.resolve("app.jar"), "module");
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		Path bin = access.stageBin(new ApplicationModules(List.of(module), new TreeSet<>(Set.of("java.base"))), runtime,
			directory.resolve("bin"), scope.json());
		PluginInstaller installer = new PluginInstaller(scope, version.release(), "");
		if (harness.equals("claude"))
		{
			PluginLaunchers.write(access.claudeLaunchers(), platform, true, bin);
			installer.write(bin, platform, true);
		}
		else
		{
			access.writeCodexLaunchers(bin, platform);
			installer.write(bin, platform, !platform.isWindows());
		}
		Path legal = Files.createDirectories(directory.resolve("legal/licenses"));
		Files.writeString(legal.resolveSibling("LICENSE.txt"), "license");
		Files.writeString(legal.resolveSibling("NOTICE.txt"), "notice");
		return new PluginArtifactBuilder(version).build(List.of(new ArtifactLayer(harness + "-plugin", layer)), bin,
			legal.getParent(), List.of(install),
			directory.resolve("cat-" + harness + "-" + platform.id() + "-v" + version.release()));
	}

	/**
	 * A harness home whose harness program is a fake: the program that install finds on PATH, and the commands that it
	 * answers as the harness does. The installer runs the program through its own runner, which this fake gives a
	 * stand-in for the program's process, so no program starts. The Claude Code answers follow Claude Code 2.1.318,
	 * including that its uninstall deletes the plugin's data directory unless it is told to keep it. The Codex answers
	 * are scripted from Codex's documented behavior; no recording of Codex backs them.
	 */
	private static final class FakeHarness
	{
		private final String harness;
		private final Platform platform;
		private final Path home;
		private final Path user;
		private final HarnessProgram.Answers answers;
		/**
		 * The folder that the search path names, which holds the harness program.
		 */
		private final Path tools;
		private final Map<String, String> environment = new HashMap<>();
		/**
		 * The commands that the installer started to delete folders once it exits.
		 */
		private final List<List<String>> started = new ArrayList<>();

		/**
		 * Creates the harness's home, the user's home and the fake program.
		 *
		 * @param harness  the harness
		 * @param platform the platform that the installer runs on
		 * @param root     the test's directory
		 * @throws IOException if a directory or the program cannot be created
		 */
		private FakeHarness(String harness, Platform platform, Path root) throws IOException
		{
			this.harness = harness;
			this.platform = platform;
			this.home = Files.createDirectories(root.resolve("harness-home"));
			this.user = Files.createDirectories(root.resolve("user"));
			this.tools = Files.createDirectories(root.resolve("tools"));
			List<HarnessProgram.Factory> factories = ServiceLoader.load(HarnessProgram.Factory.class).stream().
				map(ServiceLoader.Provider::get).filter(factory -> factory.harness().equals(harness)).toList();
			if (factories.size() != 1)
				throw new IllegalStateException("Expected one harness program factory for " + harness + ", found " +
					factories.size());
			this.answers = factories.getFirst().answers(home);
			environment.put("PATH", tools.toString());
			if (platform.isWindows())
			{
				Files.writeString(tools.resolve(harness + ".cmd"), "@exit /b 0\r\n");
				environment.put("USERPROFILE", user.toString());
				environment.put("PATHEXT", ".EXE;.CMD");
				environment.put("SystemRoot", System.getenv("SystemRoot"));
			}
			else
			{
				Path program = Files.writeString(tools.resolve(harness), "#!/bin/sh\n");
				if (!program.toFile().setExecutable(true))
					throw new IOException("Cannot make " + program + " executable");
				environment.put("HOME", user.toString());
			}
		}

		/**
		 * Runs an installer command of a plugin folder, as its launcher starts the installer there.
		 *
		 * @param plugin    the plugin folder that the installer belongs to
		 * @param arguments the command line
		 * @return the printed result
		 * @throws IOException if the output is not one JSON result
		 */
		private JsonNode run(Path plugin, String... arguments) throws IOException
		{
			return json(output(plugin, _ -> Optional.empty(), this::cli, withJson(arguments)));
		}

		/**
		 * Runs an installer command of a plugin folder whose runner of the harness program that install finds starts
		 * the program through a stand-in for the operating system's process start, instead of answering as this fake.
		 *
		 * @param plugin    the plugin folder that the installer belongs to
		 * @param starter   stands in for the operating system's start of the program
		 * @param arguments the command line
		 * @return the printed result
		 * @throws IOException if the output is not one JSON result
		 */
		private JsonNode runStarting(Path plugin, ProcessHarnessCli.Starter starter, String... arguments)
			throws IOException
		{
			return json(output(plugin, _ -> Optional.empty(), (command, directory) ->
				ProcessHarnessCliSecrets.getProcessHarnessCliAccess().create(command, directory, starter),
				withJson(arguments)));
		}

		/**
		 * Returns a command line that asks for the result as JSON, as the last argument.
		 *
		 * @param arguments the command line
		 * @return the command line with {@code --json} added
		 */
		private static String[] withJson(String... arguments)
		{
			String[] json = Arrays.copyOf(arguments, arguments.length + 1);
			json[arguments.length] = "--json";
			return json;
		}

		/**
		 * Returns the result that an installer command printed.
		 *
		 * @param printed what the command printed
		 * @return the result
		 * @throws IOException if the output is not one JSON result
		 */
		private static JsonNode json(Printed printed) throws IOException
		{
			try
			{
				return MAPPER.readTree(printed.output());
			}
			catch (RuntimeException e)
			{
				throw new IOException("The installer printed no JSON result: " + printed.output() +
					printed.diagnostics(), e);
			}
		}

		/**
		 * Runs an installer command of a plugin folder without {@code --json}, so that it prints the report to a person.
		 *
		 * @param plugin    the plugin folder that the installer belongs to
		 * @param arguments the command line
		 * @return what the installer printed to its standard output
		 */
		private String report(Path plugin, String... arguments)
		{
			Printed printed = output(plugin, new Terminal()
			{
				@Override
				public Optional<String> ask(String question)
				{
					return Optional.empty();
				}
			}, this::cli, arguments);
			requireThat(printed.diagnostics(), "diagnostics").withContext(printed.output(), "output").isEmpty();
			return printed.output();
		}

		/**
		 * Runs an installer command of a plugin folder, as its launcher starts the installer there.
		 *
		 * @param plugin     the plugin folder that the installer belongs to
		 * @param terminal   the user's terminal
		 * @param cliFactory runs the harness program
		 * @param arguments  the command line
		 * @return what the installer printed
		 */
		private Printed output(Path plugin, Terminal terminal, HarnessCliFactory cliFactory, String... arguments)
		{
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
			{
				FileReplacer replacer = new FileReplacer(platform, () ->
				{
				}, Clock.systemUTC());
				new Invocation(plugin, platform, environment, null,
					List.copyOf(ServiceLoader.load(PluginActivation.class).stream().map(ServiceLoader.Provider::get).
						toList()),
					List.copyOf(ServiceLoader.load(HarnessIntegration.class).stream().map(ServiceLoader.Provider::get).
						toList()),
					cliFactory, Clock.systemUTC(), _ -> () ->
				{
				}, replacer, terminal, started::add, stdout, stderr).run(arguments);
			}
			return new Printed(output.toString(UTF_8), diagnostics.toString(UTF_8));
		}

		/**
		 * Returns the harness program that install finds on the search path.
		 *
		 * @return the program
		 */
		private Path program()
		{
			if (platform.isWindows())
				return tools.resolve(harness + ".cmd");
			return tools.resolve(harness);
		}

		/**
		 * Requires that the uninstalling installer's version folder is deleted once the installer exits. On Windows,
		 * where the folder holds the running installer's files, the installer leaves it to a Windows PowerShell
		 * process that waits for the installer to exit; this fake stands in for that process and deletes the folder.
		 *
		 * @param versionFolder the installer's version folder
		 * @throws IOException if the folder cannot be deleted
		 */
		private void requireExitDeletion(Path versionFolder) throws IOException
		{
			if (platform.isWindows())
			{
				requireThat(started, "started").size().isEqualTo(1);
				requireThat(started.getFirst().getFirst(), "waiter").
					endsWith("\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
				started.clear();
				TemporaryDirectories.deleteRecursively(versionFolder);
			}
			requireThat(Files.exists(versionFolder), "versionFolderLeft").isFalse();
		}

		/**
		 * Returns the installer's runner of the recorded harness program, which starts the stand-in for the program's
		 * process instead of the program.
		 *
		 * @param command   the recorded program and its environment
		 * @param directory the program's working directory
		 * @return the runner
		 */
		private HarnessCli cli(HarnessCommand command, Path directory)
		{
			int prefix = command.command().size();
			return ProcessHarnessCliSecrets.getProcessHarnessCliAccess().create(command, directory, builder ->
			{
				List<String> line = builder.command();
				return new ExitedProgram(answers.answer(line.subList(prefix, line.size())));
			});
		}
	}

	/**
	 * What one installer command printed.
	 *
	 * @param output      the standard output
	 * @param diagnostics the standard error
	 */
	private record Printed(String output, String diagnostics)
	{
	}
}
