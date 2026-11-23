package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Allocation;
import com.holdmyspot.cat.common.optimizer.campaign.AllocationRule;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.cli.CommandRefusedException;
import com.holdmyspot.cat.common.optimizer.cli.EvaluatorMetadata;
import com.holdmyspot.cat.common.optimizer.cli.EvaluatorObservations;
import com.holdmyspot.cat.common.optimizer.cli.PromptOptimizerCli;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.PromptConfiguration;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.UnreachableReference;
import com.holdmyspot.cat.common.optimizer.port.test.FakeProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;
import com.holdmyspot.cat.common.optimizer.store.TrialRecovery;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Status;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.secrets.OptimizerCommandScopeSecrets;
import com.holdmyspot.cat.common.test.ExitedProcess;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Tests shared optimizer command scope ownership and failure output.
 */
public final class PromptOptimizerCliTest
{
	/**
	 * Creates the test.
	 */
	public PromptOptimizerCliTest()
	{
	}

	/**
	 * Verifies that the metadata command inspects the staged target without initializing an optimizer campaign.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsStagedEvaluatorMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-evaluator-"));
			Path staged = Files.createDirectory(project.resolve("staged"));
			String target = "rules.md";
			Files.writeString(project.resolve(target), "Working tree contents");
			Files.writeString(staged.resolve(target), "Staged contents");
			Harness harness = Harness.values()[0];
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(harness, project);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				scope.userLoaderSettings("private fixture setting");
				scope.sessions().loading((root, plugins) ->
				{
					requireThat(root, "root").isEqualTo(staged);
					requireThat(plugins, "plugins").isEmpty();
					return new LoadedSources(List.of(), List.of(new DerivedSource(target, "Imported project rule")),
						List.of(), List.of(), List.of());
				});

				int exit = PromptOptimizerCli.run(new String[]{"evaluator-info", project.toString(), staged.toString(),
					target}, _ -> scope, ZoneOffset.UTC, out);

				requireThat(exit, "exit").isEqualTo(0);
				String report = bytes.toString(StandardCharsets.UTF_8);
				requireThat(report, "report").doesNotContain("private fixture setting");
				EvaluatorMetadata result = EvaluatorMetadata.read(JsonMapper.builder().build(),
					new ByteArrayInputStream(bytes.toByteArray()));
				requireThat(result.harness(), "harness").isEqualTo(harness);
				requireThat(result.roleProfile(), "roleProfile").isEqualTo(FakeProfileInventory.ROLE);
				requireThat(result.profiles().inventory(), "inventory").isEqualTo(FakeProfileInventory.INVENTORY);
				requireThat(result.loaderFiles(), "stagedLoaderFiles").isEmpty();
				requireThat(result.loaderPaths().orElseThrow(), "configuredLoaderPaths").size().isEqualTo(1);
				requireThat(result.loadedSources(), "loadedSources").
					isEqualTo(List.of(new DerivedSource(target, "Imported project rule")));
				requireThat(result.refusal().isEmpty(), "refusal").isTrue();
			}
			requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies that the report identifies an evaluator whose trial sources omit the target.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsUnloadedEvaluatorTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-evaluator-"));
			String target = "other.md";
			Files.writeString(project.resolve(target), "Existing file the evaluator does not load");
			Harness harness = Harness.values()[0];
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(harness, project);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(new String[]{"evaluator-info", project.toString(), project.toString(),
					target}, _ -> scope, ZoneOffset.UTC, out);

				requireThat(exit, "exit").isEqualTo(0);
				EvaluatorMetadata result = EvaluatorMetadata.read(JsonMapper.builder().build(),
					new ByteArrayInputStream(bytes.toByteArray()));
				requireThat(result.refusal().orElseThrow(), "refusal").contains(harness.productName()).contains(target).
					contains("trial loaders do not load that file");
			}
		}
	}

	/**
	 * Verifies that oversized evaluator output is rejected before JSON parsing.
	 *
	 * @throws IOException if the bounded reader refuses the report
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*exceeds the maximum.*")
	public void rejectsOversizedEvaluatorOutput() throws IOException
	{
		EvaluatorMetadata.read(JsonMapper.builder().build(), new ByteArrayInputStream(new byte[1_048_577]));
	}

	/**
	 * Verifies that the owner sends the selected frozen plugin base to the peer's loader inspection.
	 *
	 * @throws IOException if the fixture or peer report cannot be read
	 * @throws InterruptedException if waiting for the peer is interrupted
	 */
	@Test
	public void inspectsSelectedPeerPluginBase() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-peer-plugin-")).toRealPath();
			Path target = Path.of("guide.md");
			Path base = Files.createDirectory(project.resolve("plugin base"));
			Files.writeString(project.resolve(target), "Fixture instructions");
			Files.writeString(base.resolve("README.md"), "Fixture plugin");
			String digest = InventoryScanner.scan(base, Set.of()).identity().hex();
			List<TrialPlugin.Mapping> mappings = List.of(new TrialPlugin.Mapping(target, Path.of("rules/common/guide.md")));
			TrialPlugin plugin = new TrialPlugin("docs", digest, new TrialPlugin.Build(List.of(), project.relativize(base)),
				mappings, null);
			PluginBase selected = new PluginBase(plugin, base);
			HookRegistration hook = new HookRegistration(plugin.name(), "hooks/hooks.json", "PostToolUse", "", "check");
			UnreachableReference reference = new UnreachableReference(target.toString(), "missing.md",
				UnreachableReference.Problem.MISSING);
			DerivedSource trial = new DerivedSource(target.toString(), "Loaded by the Codex fixture plugin");
			DerivedSource direct = new DerivedSource("AGENTS.md", "Loaded by the Codex project loader");
			EvaluatorObservations expected = new EvaluatorObservations(List.of(direct), List.of("user-sources.md"),
				List.of("the MCP server `fixture`"), List.of(reference), List.of(hook),
				List.of(plugin.name() + " PostToolUse check"));
			try (TestOptimizerCommandScope<?> owner = TestOptimizerCommandScope.of(Harness.CLAUDE, project))
			{
				owner.usePeerArtifact(project.resolve("codex-artifact"));
				owner.launchProcesses(builder ->
				{
					List<String> command = builder.command();
					requireThat(command.size(), "commandSize").isEqualTo(6);
					requireThat(command.get(1), "command").isEqualTo("evaluator-info");
					requireThat(command.getFirst(), "launcher").contains("codex-artifact");
					ByteArrayOutputStream bytes = new ByteArrayOutputStream();
					FakeSessionPort nativePeer = new FakeSessionPort()
					{
						@Override
						public List<HookRegistration> hookRegistrations(Path staged, List<PluginBase> bases)
						{
							requireThat(staged, "staged").isEqualTo(project);
							requireThat(bases.size(), "baseCount").isEqualTo(1);
							requireThat(bases.getFirst().plugin().mappings(), "hookMappings").isEqualTo(mappings);
							return List.of(hook);
						}
					};
					try (TestOptimizerCommandScope<FakeSessionPort> peer =
							new TestOptimizerCommandScope<>(Harness.CODEX, project, Set.of(), _ -> nativePeer);
						PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
					{
						peer.sessions().loading((root, bases) ->
						{
							requireThat(root, "stagedRoot").isEqualTo(project);
							requireThat(bases.size(), "baseCount").isEqualTo(1);
							PluginBase inspected = bases.getFirst();
							requireThat(inspected.directory(), "inspectedDirectory").isEqualTo(base);
							requireThat(inspected.plugin().baseDigest(), "inspectedDigest").isEqualTo(digest);
							if (inspected.plugin().mappings().isEmpty())
								return new LoadedSources(List.of(), List.of(direct), List.of(), List.of(), List.of());
							requireThat(inspected.plugin().name(), "pluginName").isEqualTo(plugin.name());
							requireThat(inspected.plugin().isProjectBuilt(), "projectBuilt").isTrue();
							requireThat(inspected.plugin().mappings(), "loaderMappings").isEqualTo(mappings);
							return new LoadedSources(List.of(), List.of(trial), expected.productionOnly(), expected.homeOnly(),
								List.of(reference));
						});
						nativePeer.unapprovedHooks(directory ->
						{
							requireThat(directory, "base").isEqualTo(base);
							return List.of("PostToolUse check");
						});
						String[] args = command.subList(1, command.size()).toArray(String[]::new);
						int exit = PromptOptimizerCli.run(args, _ -> peer, ZoneOffset.UTC, out);
						return new ExitedProcess(builder, exit, bytes.toString(StandardCharsets.UTF_8), "");
					}
				});
				EvaluatorMetadata report = owner.peerEvaluatorMetadata(Harness.CODEX, project, project, target,
					List.of(selected));
				requireThat(report.harness(), "peerHarness").isEqualTo(Harness.CODEX);
				requireThat(report.refusal().isEmpty(), "targetLoads").isTrue();
				requireThat(report.loadedSources().getFirst().rule(), "rule").contains("Codex fixture plugin");
				requireThat(report.observations(), "observations").isEqualTo(Optional.of(expected));
			}
		}
	}

	/**
	 * Verifies that a report naming a different harness is rejected after launching the selected peer artifact.
	 *
	 * @throws IOException          when the report fails evaluator validation
	 * @throws InterruptedException if interrupted while waiting for the report
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*reported evaluator.*")
	public void refusesMismatchedPeerEvaluator() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-peer-"));
			Path target = Path.of("rules.md");
			Files.writeString(project.resolve(target), "Fixture instructions");
			Harness owner = Harness.values()[0];
			Harness requested = Harness.values()[1];
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(new String[]{"evaluator-info", project.toString(), project.toString(),
					target.toString()}, _ -> TestOptimizerCommandScope.of(owner, project), ZoneOffset.UTC, out);
				requireThat(exit, "exit").isEqualTo(0);
			}

			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(owner, project))
			{
				Path artifact = project.resolve("installed-peer");
				scope.usePeerArtifact(artifact);
				scope.launchProcesses(builder ->
				{
					String launcher = "prompt-optimizer";
					if (Platform.current().isWindows())
						launcher += ".cmd";
					requireThat(builder.command(), "command").isEqualTo(List.of(
						artifact.resolve("bin").resolve(launcher).toString(), "evaluator-info",
						project.toString(), project.toString(), target.toString()));
					requireThat(builder.directory().toPath(), "directory").isEqualTo(project);
					requireThat(builder.environment().containsKey(SessionRequestAccess.REQUESTS_VARIABLE), "requests").
						isFalse();
					return new ExitedProcess(builder, 0, bytes.toString(StandardCharsets.UTF_8), "private fixture setting");
				});

				scope.peerEvaluatorMetadata(requested, project, project, target);
			}
		}
	}

	/**
	 * Verifies a command closes its supplied scope and retains the invalid-input exit code.
	 */
	@Test
	public void closesScopeAfterInvalidCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, project);
				PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(new String[]{"frobnicate"}, _ -> scope, ZoneOffset.UTC, out);

				requireThat(exit, "exit").isEqualTo(2);
				requireThat(scope.isClosed(), "closed").isTrue();
			}
		}
	}

	/**
	 * Verifies that a command whose project is the user's home folder is refused, with a message that says the home
	 * folder cannot be a CAT project, before it creates a CAT session directory there.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesHomeFolderProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			Files.createDirectory(home.resolve(".git"));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, home);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				scope.useHome(home);

				int exit = PromptOptimizerCli.run(new String[]{"status", "AGENTS.md"}, _ -> scope, ZoneOffset.UTC,
					out);

				requireThat(exit, "exit").isEqualTo(1);
				JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
				requireThat(result.get("status").asString(), "status").isEqualTo("refused");
				requireThat(result.get("message").asString(), "message").contains("cannot be a CAT project").
					contains("Open a folder below the home folder");
			}
			requireThat(Files.exists(home.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies scope-creation failures produce the same failure JSON and exit code as command failures.
	 */
	@Test
	public void rendersScopeCreationFailure()
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int exit = PromptOptimizerCli.run(new String[]{"status"}, _ ->
			{
				throw new IOException("configuration unavailable");
			}, ZoneOffset.UTC, out);

			requireThat(exit, "exit").isEqualTo(1);
			JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
			requireThat(result.get("status").asString(), "status").isEqualTo("error");
			requireThat(result.get("message").asString(), "message").isEqualTo("configuration unavailable");
		}
	}

	/**
	 * Verifies that a contract tool runs without opening a command scope, so that it needs nothing of the harness
	 * inside a contract author session's sandbox.
	 */
	@Test
	public void contractToolOpensNoScope()
	{
		// Requirement: R-C27
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int exit = PromptOptimizerCli.run(new String[]{"contract", "check", "missing-cat-contract.json"}, _ ->
			{
				throw new AssertionError("A contract tool opened a command scope");
			}, ZoneOffset.UTC, out);

			requireThat(exit, "exit").isEqualTo(1);
			requireThat(bytes.toString(StandardCharsets.UTF_8), "output").contains("missing-cat-contract.json");
		}
	}

	/**
	 * Verifies that {@code start} refuses a prompt test configuration that has no key for the harness, through the
	 * command entry point, without changing the project: no CAT session directory and no {@code .gitignore}, which
	 * creating the session directory would add, and the refusal does not mention a {@code .gitignore}.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusedStartLeavesProjectUnchanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			Files.createDirectory(project.resolve(".git"));
			Files.writeString(project.resolve("AGENTS.md"), "Be brief.\n");
			Path configuration = project.resolve(PromptConfiguration.location(Path.of("AGENTS.md")));
			Files.createDirectories(configuration.getParent());
			Files.writeString(configuration, "{\"codex\": {\"plugins\": []}}");
			Map<String, String> before = contents(project);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, project);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(new String[]{"start", "AGENTS.md"}, _ -> scope, ZoneOffset.UTC, out);

				requireThat(exit, "exit").isEqualTo(1);
			}
			JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
			requireThat(result.get("status").asString(), "status").isEqualTo("refused");
			requireThat(result.has("reason"), "hasReason").isFalse();
			requireThat(result.get("message").asString(), "message").contains("no \"claude\" key").
				doesNotContain(".gitignore");
			requireThat(contents(project), "contents").isEqualTo(before);
		}
	}

	/**
	 * Verifies that the caller can distinguish a target without a campaign or certification from other refusals.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void statusReportsNoCampaignOrRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			Files.createDirectory(project.resolve(".git"));
			Files.writeString(project.resolve("AGENTS.md"), "Be brief.\n");
			Map<String, String> before = contents(project);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CODEX, project);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(new String[]{"status", "AGENTS.md"}, _ -> scope, ZoneOffset.UTC, out);

				requireThat(exit, "exit").isEqualTo(1);
			}
			JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
			requireThat(result.get("status").asString(), "status").isEqualTo("refused");
			requireThat(result.path("reason").asString(""), "reason").isEqualTo("no_campaign_or_certification_record");
			requireThat(result.get("message").asString(), "message").
				isEqualTo("No campaign or certification record exists for AGENTS.md.");
			requireThat(contents(project), "contents").isEqualTo(before);
		}
	}

	/**
	 * Verifies that a command that starts sessions passes its source home and gateway login class to the scope that
	 * starts the sessions, so that every session that the command starts takes its credential from that source home,
	 * while a command that starts no session refuses them with the invalid-input status, naming the option, before it
	 * opens a scope.
	 *
	 * @throws IOException if the test project cannot be created
	 */
	@Test
	public void passesSourceHomeToSessionStartingCommand() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			Files.createDirectory(project.resolve(".git"));
			Files.writeString(project.resolve("AGENTS.md"), "Be brief.\n");
			Path home = Files.createDirectories(project.resolve("source-home"));
			AtomicReference<AuthenticationOptions> received = new AtomicReference<>();
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CODEX, project);
				PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				PromptOptimizerCli.run(new String[]{"start", "AGENTS.md", "--home", home.toString(),
					"--gateway-login-class", "api_key"}, authentication ->
				{
					received.set(authentication);
					return scope;
				}, ZoneOffset.UTC, out);
			}
			requireThat(received.get(), "received").isEqualTo(new AuthenticationOptions(Optional.of(home),
				Optional.of(HostLogin.API_KEY), false));

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(new String[]{"status", "AGENTS.md", "--home", home.toString()}, _ ->
				{
					throw new AssertionError("A command that starts no session opened a scope");
				}, ZoneOffset.UTC, out);

				requireThat(exit, "exit").isEqualTo(2);
			}
			requireThat(bytes.toString(StandardCharsets.UTF_8), "output").contains("--home");
		}
	}

	private enum Preflight
	{
		READY, BACKGROUND_READY, WRONG_HARNESS, WRONG_REQUEST, BAD_PREFIX, BUSY,
		LEGACY, MISSING, NO_LOOKUP, TIMEOUT, CONFIRM
	}

	/**
	 * Returns the outcomes of a preflight at spent allowance.
	 *
	 * @return the outcomes
	 */
	@DataProvider
	public Object[][] retainedPreflights()
	{
		return Stream.of(Preflight.values()).map(value -> new Object[]{value}).toArray(Object[][]::new);
	}

	/**
	 * A checked retained session reaches the next command guard. Other runs keep the original allowance refusal.
	 *
	 * @param preflight the local trial state and broker answer
	 * @throws IOException if the test filesystem cannot be used
	 * @throws CommandRefusedException if the test store cannot be opened
	 */
	@Test(dataProvider = "retainedPreflights")
	public void runWithSpentAllowance(Preflight preflight) throws IOException, CommandRefusedException
	{
		// Requirement: R-I12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			Files.createDirectory(project.resolve(".git"));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, project);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				UUID original = UUID.fromString("d031fc97-6965-4106-ab81-1ea2a0588ba1");
				String campaign = "campaign-1";
				if (preflight != Preflight.MISSING)
					storeRunningTrial(scope, project, campaign, original, preflight != Preflight.LEGACY);
				scope.nestedQuotaRefusal = "The nested session has spent its allowance.";
				scope.sandboxRefusal = "The next command guard was reached.";
				AtomicInteger probes = new AtomicInteger();
				if (preflight != Preflight.NO_LOOKUP)
				{
					scope.retainedTrialLookup = requestId ->
					{
						requireThat(requestId, "originalRequest").isEqualTo(original);
						probes.incrementAndGet();
						return preflightResponse(preflight, original);
					};
				}
				String[] args = switch (preflight)
				{
					case BACKGROUND_READY -> new String[]{"run", campaign, "--background"};
					case CONFIRM -> new String[]{"confirm", campaign};
					default -> new String[]{"run", campaign};
				};
				Map<String, String> before = contents(project);
				int exit = PromptOptimizerCli.run(args, _ -> scope, ZoneOffset.UTC, out);

				int expectedProbes = switch (preflight)
				{
					case READY, BACKGROUND_READY, WRONG_HARNESS, WRONG_REQUEST, BAD_PREFIX, BUSY, TIMEOUT -> 1;
					case LEGACY, MISSING, NO_LOOKUP, CONFIRM -> 0;
				};
				int expectedExit = 2;
				String expectedMessage = scope.nestedQuotaRefusal;
				if (preflight == Preflight.READY || preflight == Preflight.BACKGROUND_READY)
				{
					expectedExit = 1;
					expectedMessage = scope.sandboxRefusal;
				}
				requireThat(exit, "exit").isEqualTo(expectedExit);
				JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
				requireThat(result.get("status").asString(), "status").isEqualTo("refused");
				requireThat(result.get("message").asString(), "message").isEqualTo(expectedMessage);
				requireThat(probes.get(), "probes").isEqualTo(expectedProbes);
				requireThat(contents(project), "contents").isEqualTo(before);
			}
		}
	}

	/**
	 * Stores a running development trial and, when requested, its original launch binding.
	 *
	 * @param scope the command scope
	 * @param project the project root
	 * @param id the campaign identifier
	 * @param original the original launch identifier
	 * @param recoverable whether the trial has recovery state
	 * @throws IOException if the campaign cannot be written
	 * @throws CommandRefusedException if the store cannot be opened
	 */
	private static void storeRunningTrial(TestOptimizerCommandScope<?> scope, Path project, String id, UUID original,
		boolean recoverable) throws IOException, CommandRefusedException
	{
		CampaignStore store = OptimizerCommandScopeSecrets.getOptimizerCommandScopeAccess().store(scope);
		Header header = new Header(id, project, project.resolve("CLAUDE.md"), Harness.CLAUDE, Lifecycle.ACTIVE);
		Campaign campaign = new Builder().header(header).confirmed().build();
		Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(
			Purpose.DEVELOPMENT, campaign.development().series(), campaign.development().working().snapshot(),
			"safety", null, new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
				scope.clock().instant()))));
		LogicalTrial trial = assigned.trials().getLast();
		store.writeAssignment(id, trial);
		store.replaceSnapshot(Transitions.apply(assigned, new CampaignEvent.Start(trial.key())));
		if (recoverable)
			store.writeTrialRecovery(id, new TrialRecovery(trial.key(), TrialRecovery.attemptOrdinal(trial), original,
				0, Map.of(), false));
	}

	/**
	 * Returns the broker answer for a preflight that reaches the original launch.
	 *
	 * @param preflight the case under test
	 * @param original the original launch identifier
	 * @return the status
	 * @throws IOException if a response does not arrive
	 */
	private static RetainedTrialResponse preflightResponse(Preflight preflight, UUID original) throws IOException
	{
		if (preflight == Preflight.TIMEOUT)
			throw new IOException("Retained trial status timed out");
		if (preflight == Preflight.BUSY)
			return new RetainedTrialResponse(original, Status.BUSY, Optional.empty(), OptionalInt.empty());
		String harness = "claude";
		if (preflight == Preflight.WRONG_HARNESS)
			harness = "codex";
		UUID slot = UUID.fromString("4aaba386-e766-49fd-85d0-9ed548740596");
		HarnessSession session = new HarnessSession(harness, slot.toString());
		int completed = 1;
		if (preflight == Preflight.BAD_PREFIX)
			completed = 2;
		UUID request = original;
		if (preflight == Preflight.WRONG_REQUEST)
			request = slot;
		return new RetainedTrialResponse(request, Status.READY, Optional.of(new Identity(slot, Optional.of(session))),
			OptionalInt.of(completed));
	}

	/**
	 * Returns the content of every file below a directory.
	 *
	 * @param root the directory
	 * @return each path relative to the directory, with the file's content or a marker for a directory
	 * @throws IOException if a file cannot be read
	 */
	private static Map<String, String> contents(Path root) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> files = Files.walk(root))
		{
			for (Path path : files.toList())
			{
				String value = "<directory>";
				if (Files.isRegularFile(path))
					value = Files.readString(path, StandardCharsets.ISO_8859_1);
				result.put(root.relativize(path).toString(), value);
			}
		}
		return result;
	}
}
