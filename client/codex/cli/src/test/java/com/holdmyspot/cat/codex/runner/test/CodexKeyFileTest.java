package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.runner.CodexAuthentication;
import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexProcess;
import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Tests the credential that a source provider's {@code env_key} or inline {@code experimental_bearer_token} gives a
 * session: the runner writes it into the session's key file and gives the session's provider a token command that
 * prints the file, so that no variable of the app-server's environment and no setting of the session's
 * {@code config.toml} holds it.
 */
public final class CodexKeyFileTest
{
	/**
	 * The variable that the source provider's {@code env_key} names.
	 */
	private static final String VARIABLE = "GATEWAY_KEY";

	/**
	 * Returns the provider settings that supply the bearer token, each with the runner environment that holds it.
	 *
	 * @return each provider table's line and the runner's environment variables
	 */
	@DataProvider
	public static Object[][] bearerSources()
	{
		return new Object[][]
		{
			{"env_key = \"" + VARIABLE + "\"", Map.of(VARIABLE, CodexSourceHomes.SECRET)},
			{"experimental_bearer_token = \"" + CodexSourceHomes.SECRET + "\"", Map.of()}
		};
	}

	/**
	 * Verifies that the session's key file holds the token, readable by its owner alone, and that the session's
	 * {@code config.toml} holds neither the token nor the provider's {@code env_key} or
	 * {@code experimental_bearer_token}, and names a token command that prints the key file.
	 *
	 * @param line      the provider table's line that supplies the token
	 * @param variables the runner's variables
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "bearerSources")
	public void keyFileHoldsTokenBehindTokenCommand(String line, Map<String, String> variables) throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-key-file-")).toRealPath();
			Path codexFolder = Files.createDirectories(root.resolve("session/home/.codex"));
			CodexAuthentication authentication = read(root, "source", line, variables);

			authentication.writeKeyFile(codexFolder);

			Path keyFile = CodexAuthentication.keyFile(codexFolder);
			requireThat(Files.readString(keyFile), "keyFile").isEqualTo(CodexSourceHomes.SECRET);
			if (Files.getFileStore(keyFile).supportsFileAttributeView("posix"))
			{
				requireThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile)), "permissions").
					isEqualTo("rw-------");
			}
			String settings = authentication.settings(codexFolder);
			requireThat(settings, "settings").doesNotContain(CodexSourceHomes.SECRET).doesNotContain("env_key").
				doesNotContain("experimental_bearer_token").doesNotContain(VARIABLE);
			JsonNode provider = TomlMapper.builder().build().readTree(settings).path("model_providers").path("gateway");
			requireThat(provider.path("base_url").asString(), "baseUrl").isEqualTo("https://gateway.example/v1");
			requireThat(provider.path("auth").path("command").asString(), "command").isEqualTo("cat");
			requireThat(provider.path("auth").path("args").size(), "arguments").isEqualTo(1);
			requireThat(provider.path("auth").path("args").get(0).asString(), "keyFile").
				isEqualTo(keyFile.toString());
			requireThat(authentication.toString(), "description").doesNotContain(CodexSourceHomes.SECRET);
			requireThat(authentication.evidence().toString(), "evidence").doesNotContain(CodexSourceHomes.SECRET);
		}
	}

	/**
	 * Verifies that the variable that {@code env_key} names reaches neither the app-server's environment nor the
	 * session's configuration of its commands' environment.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void variableNeverReachesAppServer() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-key-file-")).toRealPath();
			Path home = root.resolve("session/home");
			Map<String, String> environment = new HashMap<>(Map.of("PATH", "/usr/bin", VARIABLE,
				CodexSourceHomes.SECRET));
			CodexAuthentication authentication = read(root, "source", "env_key = \"" + VARIABLE + "\"", environment);

			Map<String, String> appServer = CodexRunner.processEnvironment(environment, HarnessDependencyCaches.NONE,
				home, home.getParent(), home.resolve("tmp"), Platform.LINUX_X64, authentication);

			requireThat(appServer.keySet(), "variables").doesNotContain(VARIABLE);
			requireThat(appServer.values(), "values").doesNotContain(CodexSourceHomes.SECRET);
		}
	}

	/**
	 * Verifies that an {@code env_key} whose variable the runner lacks, or holds blank, is refused naming the provider
	 * and the variable.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesVariableThatTheRunnerLacks() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-key-file-")).toRealPath();
			for (Map<String, String> variables : List.of(Map.<String, String>of(), Map.of(VARIABLE, " ")))
			{
				SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () ->
					read(root, "source-" + variables.size(), "env_key = \"" + VARIABLE + "\"", variables));
				requireThat(refusal.getMessage(), "message").contains("gateway").contains(VARIABLE);
			}
		}
	}

	/**
	 * Verifies that a resume writes the resuming launch's token over the key file that an earlier launch left, and
	 * that a resume whose source supplies no such token removes the file.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void resumeWritesResumingToken() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-key-file-")).toRealPath();
			Path codexFolder = Files.createDirectories(root.resolve("session/home/.codex"));
			Path keyFile = CodexAuthentication.keyFile(codexFolder);
			read(root, "first", "env_key = \"" + VARIABLE + "\"", Map.of(VARIABLE, "first-token")).
				writeKeyFile(codexFolder);
			requireThat(Files.readString(keyFile), "first").isEqualTo("first-token");

			read(root, "second", "env_key = \"" + VARIABLE + "\"", Map.of(VARIABLE, "second-token")).
				writeKeyFile(codexFolder);
			requireThat(Files.readString(keyFile), "resumed").isEqualTo("second-token");

			Path source = CodexSourceHomes.create(root, "third");
			CodexSourceHomes.apiKeyLogin(source.resolve(".codex"));
			CodexAuthentication.read(new AuthenticationOptions(Optional.of(source), Optional.empty(), false), Map.of(),
				Platform.current()).writeKeyFile(codexFolder);
			requireThat(Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS), "removed").isFalse();
		}
	}

	/**
	 * Verifies that a source provider with its own token command is copied as before, with its command wrapped, and
	 * that no key file results.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void ownTokenCommandWritesNoKeyFile() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-key-file-")).toRealPath();
			Path codexFolder = Files.createDirectories(root.resolve("session/home/.codex"));
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.secret(source.resolve("key"));
			CodexSourceHomes.configuration(source.resolve(".codex"), """
				model_provider = "gateway"
				[model_providers.gateway]
				base_url = "https://gateway.example/v1"
				[model_providers.gateway.auth]
				command = "sh"
				args = ["-c", "cat ~/key"]
				""");
			CodexAuthentication authentication = CodexAuthentication.read(new AuthenticationOptions(Optional.of(source),
				Optional.of(HostLogin.API_KEY), false), Map.of(), Platform.current());

			authentication.writeKeyFile(codexFolder);

			requireThat(Files.exists(CodexAuthentication.keyFile(codexFolder), LinkOption.NOFOLLOW_LINKS), "keyFile").
				isFalse();
			JsonNode auth = TomlMapper.builder().build().readTree(authentication.settings(codexFolder)).
				path("model_providers").path("gateway").path("auth");
			requireThat(auth.path("command").asString(), "command").isEqualTo("env");
		}
	}

	/**
	 * Verifies that closing a session removes its key file, even though the app-server's login copy is another file.
	 * The stand-in for the app-server is {@code cat}, which exits when its input closes.
	 *
	 * @throws IOException if the fixture or the process fails
	 */
	@Test(timeOut = 10_000)
	// The test closes the session itself, because the close is the step whose effect it checks
	@SuppressWarnings("PMD.CloseResource")
	public void closeRemovesKeyFile() throws IOException
	{
		// Requirement: R-I15
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-key-file-")).toRealPath();
			Path codexFolder = Files.createDirectories(root.resolve("session/home/.codex"));
			read(root, "source", "env_key = \"" + VARIABLE + "\"", Map.of(VARIABLE, CodexSourceHomes.SECRET)).
				writeKeyFile(codexFolder);
			Path keyFile = CodexAuthentication.keyFile(codexFolder);
			Process appServer = new ProcessBuilder("cat").start();
			HarnessProcess session = CodexRunner.removingLoginCopy(new CodexProcess(appServer, scope, false, null),
				codexFolder);
			requireThat(Files.exists(keyFile), "whileRunning").isTrue();

			session.close();

			requireThat(Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS), "keyFileExists").isFalse();
		}
	}

	/**
	 * Verifies that the key file is among the session's denied reads, and so in the session's permission profile.
	 *
	 * @throws IOException if the fixture or the configuration cannot be built
	 */
	@Test
	public void deniesKeyFile() throws IOException
	{
		// Requirement: R-I15
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-codex-key-file-")).
				toRealPath();
			CodexAuthentication authentication = read(root, "source", "env_key = \"" + VARIABLE + "\"",
				Map.of(VARIABLE, CodexSourceHomes.SECRET));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path codexFolder = Files.createDirectories(nestedSession.directory().resolve("home/.codex"));
			authentication.writeKeyFile(codexFolder);
			Path configuration = codexFolder.resolve("config.toml");
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), configuration,
				nestedSession.directory().resolve("project"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(), List.of(),
				Platform.LINUX_X64, CodexSourceHomes.runnerEnvironment(root));
			CodexConnectionConfiguration.addSessionProfile(configuration, List.of(), deniedReads);

			Path keyFile = CodexAuthentication.keyFile(codexFolder);
			requireThat(deniedReads, "deniedReads").contains(keyFile);
			requireThat(Files.readString(configuration), "profile").contains("\"" + keyFile + "\" = \"deny\"");
		}
	}

	/**
	 * Reads the authentication of a source home whose selected provider has a base URL and one more setting.
	 *
	 * @param root      the folder that receives the source home
	 * @param name      the source home's name
	 * @param line      the provider table's extra line
	 * @param variables the runner's variables
	 * @return the authentication
	 * @throws IOException             if the fixture cannot be written
	 * @throws SessionRefusedException if the source is refused
	 */
	private static CodexAuthentication read(Path root, String name, String line, Map<String, String> variables)
		throws IOException
	{
		Path source = CodexSourceHomes.create(root, name);
		CodexSourceHomes.configuration(source.resolve(".codex"), """
			model_provider = "gateway"
			[model_providers.gateway]
			base_url = "https://gateway.example/v1"
			%s
			""".formatted(line));
		return CodexAuthentication.read(new AuthenticationOptions(Optional.of(source), Optional.of(HostLogin.API_KEY),
			false), variables, Platform.current());
	}
}
