package com.holdmyspot.cat.common.runner.login.test;

import static com.holdmyspot.cat.common.runner.login.test.LoginFixtures.ACCOUNT_ID;
import static com.holdmyspot.cat.common.runner.login.test.LoginFixtures.REFRESH_TOKEN;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.login.CodexLoginClient;
import com.holdmyspot.cat.common.runner.login.CodexLoginPlan;
import com.holdmyspot.cat.common.runner.login.CodexLoginService;
import com.holdmyspot.cat.common.runner.login.CodexLoginSource;
import com.holdmyspot.cat.common.runner.login.LoginRefusal;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

/**
 * Tests that the login service of a request server serves each nested session a token of the source's login, and
 * nothing else: it reads a sign-in read-only and never serves its refresh token, runs a token command once per
 * interval with the source home as its home, refuses a request without a session's credential, and opens a keyring
 * login only when the top-level session can itself reach the keyring.
 */
public final class CodexLoginServiceTest
{
	private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
	private static final ParentPolicy NO_LOCAL_SERVICES = new ParentPolicy(true, List.of(), List.of(), List.of(),
		List.of(), HostAccess.ALL, false, false, List.of());

	/**
	 * Creates the test.
	 */
	public CodexLoginServiceTest()
	{
	}

	/**
	 * A source home and its Codex folder.
	 *
	 * @param home  the source home
	 * @param codex the source's Codex folder
	 */
	private record Source(Path home, Path codex)
	{
		/**
		 * Returns the plan that reads the source's stored login.
		 *
		 * @param store where the source keeps its login
		 * @return the plan
		 */
		CodexLoginPlan.Stored stored(CodexLoginPlan.Store store)
		{
			return new CodexLoginPlan.Stored(home, codex, store);
		}
	}

	/**
	 * Creates a source home.
	 *
	 * @param cleanup deletes the fixture
	 * @return the source
	 * @throws IOException if it cannot be created
	 */
	private static Source source(TemporaryDirectories cleanup) throws IOException
	{
		Path home = cleanup.add(Files.createTempDirectory("login-source-")).toRealPath();
		return new Source(home, Files.createDirectory(home.resolve(".codex")));
	}

	/**
	 * Starts a service in a folder of a fixture.
	 *
	 * @param cleanup  deletes the fixture
	 * @param topLevel reads the top-level session's policy
	 * @param source   reads the source's stored login
	 * @param clock    the service's clock
	 * @return the service
	 * @throws IOException if it cannot start
	 */
	private static CodexLoginService start(TemporaryDirectories cleanup, CodexLoginService.TopLevelPolicy topLevel,
		CodexLoginSource source, InstantSource clock) throws IOException
	{
		Path server = cleanup.add(Files.createTempDirectory("login-server-"));
		return CodexLoginService.start(server, topLevel, source, Platform.current(), Map.of(), clock);
	}

	/**
	 * Opens a session's login.
	 *
	 * @param service the service
	 * @param plan    the login
	 * @return the answer
	 * @throws IOException if the login is refused
	 */
	private static CodexLoginClient.Opened open(CodexLoginService service, CodexLoginPlan plan) throws IOException
	{
		return CodexLoginClient.open(service.socket(), service.runnerVariables().get(CodexLoginService.KEY_VARIABLE),
			plan);
	}

	/**
	 * Verifies that a ChatGPT sign-in in a read-only {@code auth.json} serves its access token and never its refresh
	 * token, leaves the file byte-identical, and serves a changed token at the next request.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void servesSignInAccessToken() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			Path login = source.codex().resolve(CodexLoginSource.LOGIN_FILE);
			String first = LoginFixtures.accessToken("first", NOW.plus(Duration.ofDays(1)));
			LoginFixtures.writeReadOnly(login, LoginFixtures.signIn(first));
			byte[] before = Files.readAllBytes(login);
			try (CodexLoginService service = start(cleanup, () -> ParentPolicy.UNSANDBOXED,
				new CodexLoginSource(new LoginFixtures.Keyring(null)), () -> NOW))
			{
				CodexLoginClient.Opened opened = open(service, source.stored(CodexLoginPlan.Store.FILE));
				requireThat(opened.signIn(), "signIn").isTrue();
				requireThat(opened.accountId(), "accountId").isEqualTo(Optional.of(ACCOUNT_ID));
				requireThat(opened.inKeyring(), "inKeyring").isFalse();

				String token = CodexLoginClient.token(service.socket(), opened.credential());
				requireThat(token, "token").isEqualTo(first);
				requireThat(token, "token").doesNotContain(REFRESH_TOKEN);
				requireThat(Arrays.equals(Files.readAllBytes(login), before), "unchanged").isTrue();

				String second = LoginFixtures.accessToken("second", NOW.plus(Duration.ofDays(1)));
				LoginFixtures.writeReadOnly(login, LoginFixtures.signIn(second));
				requireThat(CodexLoginClient.token(service.socket(), opened.credential()), "renewed").
					isEqualTo(second);
			}
		}
	}

	/**
	 * Verifies that a request with no session's credential, a wrong one or an ended session's gets no token, and that
	 * a login opens only with the key of the server's runners.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWithoutCredential() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			String key = "sk-fixture-service-key";
			LoginFixtures.writeReadOnly(source.codex().resolve(CodexLoginSource.LOGIN_FILE), LoginFixtures.apiKey(key));
			try (CodexLoginService service = start(cleanup, () -> ParentPolicy.UNSANDBOXED,
				new CodexLoginSource(new LoginFixtures.Keyring(null)), () -> NOW))
			{
				CodexLoginClient.Opened opened = open(service, source.stored(CodexLoginPlan.Store.FILE));
				requireThat(opened.signIn(), "signIn").isFalse();
				requireThat(CodexLoginClient.token(service.socket(), opened.credential()), "token").isEqualTo(key);

				for (String credential : List.of("", "wrong-credential", opened.credential() + "x"))
				{
					LoginRefusal refusal = expectThrows(LoginRefusal.class,
						() -> CodexLoginClient.token(service.socket(), credential));
					requireThat(refusal.getMessage(), "refusal").doesNotContain(key);
				}
				LoginRefusal wrongKey = expectThrows(LoginRefusal.class, () -> CodexLoginClient.open(service.socket(),
					"wrong-key", source.stored(CodexLoginPlan.Store.FILE)));
				requireThat(wrongKey.getMessage(), "wrongKey").doesNotContain(key);

				CodexLoginClient.close(service.socket(), opened.credential());
				LoginRefusal ended = expectThrows(LoginRefusal.class,
					() -> CodexLoginClient.token(service.socket(), opened.credential()));
				requireThat(ended.getMessage(), "ended").doesNotContain(key);
			}
		}
	}

	/**
	 * Verifies that the socket is in a folder that its owner alone may use, which the service removes when it closes.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void socketFolderIsOwnerOnly() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder;
			try (CodexLoginService service = start(cleanup, () -> ParentPolicy.UNSANDBOXED,
				new CodexLoginSource(new LoginFixtures.Keyring(null)), () -> NOW))
			{
				folder = service.folder();
				requireThat(service.socket().getParent(), "socketFolder").isEqualTo(folder);
				requireThat(LoginFixtures.ownerOnly(folder), "ownerOnly").isTrue();
				requireThat(service.runnerVariables().keySet(), "runnerVariables").
					containsExactly(List.of(CodexLoginService.SOCKET_VARIABLE, CodexLoginService.KEY_VARIABLE));
			}
			requireThat(Files.exists(folder), "folderExists").isFalse();
		}
	}

	/**
	 * Verifies that a service whose folder's parent has a path too long for a socket's address, as a project's session
	 * directory can, still starts and serves, with its socket in an owner-only folder whose path a socket's address
	 * holds.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsBelowLongParent() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			String key = "sk-fixture-long-parent";
			LoginFixtures.writeReadOnly(source.codex().resolve(CodexLoginSource.LOGIN_FILE), LoginFixtures.apiKey(key));
			Path parent = cleanup.add(Files.createTempDirectory("login-server-"));
			while (parent.toAbsolutePath().toString().getBytes(UTF_8).length <= 120)
				parent = Files.createDirectory(parent.resolve("a-folder-of-a-project"));
			try (CodexLoginService service = CodexLoginService.start(parent, () -> ParentPolicy.UNSANDBOXED,
				new CodexLoginSource(new LoginFixtures.Keyring(null)), Platform.current(), Map.of(), () -> NOW))
			{
				requireThat(service.socket().toString().getBytes(UTF_8).length, "socketPathBytes").
					isLessThanOrEqualTo(100);
				requireThat(LoginFixtures.ownerOnly(service.folder()), "ownerOnly").isTrue();
				CodexLoginClient.Opened opened = open(service, source.stored(CodexLoginPlan.Store.FILE));
				requireThat(CodexLoginClient.token(service.socket(), opened.credential()), "token").isEqualTo(key);
			}
		}
	}

	/**
	 * Verifies that an expired sign-in with no newer one is refused, naming the top login and holding no token.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesExpiredSignIn() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			Path login = source.codex().resolve(CodexLoginSource.LOGIN_FILE);
			String expired = LoginFixtures.accessToken("expired", NOW.minus(Duration.ofMinutes(1)));
			LoginFixtures.writeReadOnly(login, LoginFixtures.signIn(expired));
			try (CodexLoginService service = start(cleanup, () -> ParentPolicy.UNSANDBOXED,
				new CodexLoginSource(new LoginFixtures.Keyring(null)), () -> NOW))
			{
				CodexLoginClient.Opened opened = open(service, source.stored(CodexLoginPlan.Store.FILE));
				LoginRefusal refusal = expectThrows(LoginRefusal.class,
					() -> CodexLoginClient.token(service.socket(), opened.credential()));
				requireThat(refusal.getMessage(), "refusal").contains(login.toString()).contains("expired").
					contains("top Codex login").doesNotContain(expired).doesNotContain(REFRESH_TOKEN);
			}
		}
	}

	/**
	 * Returns a token command that counts its runs in a file, writes its home folder into another and prints a token
	 * that names the run.
	 *
	 * @param source        the source
	 * @param counter       the file that counts the runs
	 * @param homeRecord    the file that records the command's home folder
	 * @param interval      how long the command's output serves, in milliseconds
	 * @param timeoutMillis how long the command may run
	 * @param body          the shell commands that end the script
	 * @return the command
	 */
	private static CodexLoginPlan.Command counting(Source source, Path counter, Path homeRecord, long interval,
		long timeoutMillis, String body)
	{
		String script = "printf x >> \"$1\"; printf '%s' \"$HOME\" > \"$2\"; " + body;
		return new CodexLoginPlan.Command("sh", List.of("-c", script, "sh", counter.toString(), homeRecord.toString()),
			Optional.empty(), timeoutMillis, interval, source.home());
	}

	/**
	 * Returns how many times a counting command ran.
	 *
	 * @param counter the command's counter file
	 * @return the count
	 * @throws IOException if the file cannot be read
	 */
	private static int runs(Path counter) throws IOException
	{
		if (Files.notExists(counter))
			return 0;
		return Files.readString(counter).length();
	}

	/**
	 * Verifies that a provider's token command runs once for several sessions within its interval, with the source
	 * home as its home folder, and again once the interval has passed on the service's clock.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void commandRunsOncePerInterval() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			Path counter = source.home().resolve("runs");
			Path homeRecord = source.home().resolve("home");
			CodexLoginPlan.Command command = counting(source, counter, homeRecord, 300_000, 30_000,
				"printf 'token-%s\\n' \"$(wc -c < \"$1\" | tr -d ' ')\"");
			AtomicReference<Instant> now = new AtomicReference<>(NOW);
			try (CodexLoginService service = start(cleanup, () -> ParentPolicy.UNSANDBOXED,
				new CodexLoginSource(new LoginFixtures.Keyring(null)), now::get))
			{
				CodexLoginClient.Opened first = open(service, command);
				CodexLoginClient.Opened second = open(service, command);
				requireThat(runs(counter), "runsAtOpen").isEqualTo(0);
				for (CodexLoginClient.Opened opened : List.of(first, second, first, second))
				{
					requireThat(CodexLoginClient.token(service.socket(), opened.credential()), "token").
						isEqualTo("token-1");
				}
				requireThat(runs(counter), "runs").isEqualTo(1);
				requireThat(Files.readString(homeRecord), "home").isEqualTo(LoginFixtures.shellPath(source.home()));

				now.set(NOW.plusMillis(299_999));
				requireThat(CodexLoginClient.token(service.socket(), second.credential()), "withinInterval").
					isEqualTo("token-1");
				now.set(NOW.plusMillis(300_000));
				requireThat(CodexLoginClient.token(service.socket(), second.credential()), "afterInterval").
					isEqualTo("token-2");
				requireThat(runs(counter), "runsAfterInterval").isEqualTo(2);
			}
		}
	}

	/**
	 * Verifies that a token command whose interval is {@code 0} runs for each request.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void commandWithoutIntervalRunsPerRequest() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			Path counter = source.home().resolve("runs");
			CodexLoginPlan.Command command = counting(source, counter, source.home().resolve("home"), 0, 30_000,
				"printf 'token-%s' \"$(wc -c < \"$1\" | tr -d ' ')\"");
			try (CodexLoginService service = start(cleanup, () -> ParentPolicy.UNSANDBOXED,
				new CodexLoginSource(new LoginFixtures.Keyring(null)), () -> NOW))
			{
				CodexLoginClient.Opened opened = open(service, command);
				for (int run = 1; run <= 3; ++run)
				{
					requireThat(CodexLoginClient.token(service.socket(), opened.credential()), "token").
						isEqualTo("token-" + run);
				}
				requireThat(runs(counter), "runs").isEqualTo(3);
			}
		}
	}

	/**
	 * Verifies that a token command that fails or times out is a refusal that holds nothing of what it printed.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void commandFailureIsRefused() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			String printed = "fixture-printed-token";
			CodexLoginPlan.Command failing = counting(source, source.home().resolve("failing-runs"),
				source.home().resolve("home"), 300_000, 30_000, "printf '" + printed + "'; exit 3");
			CodexLoginPlan.Command slow = counting(source, source.home().resolve("slow-runs"),
				source.home().resolve("home"), 300_000, 500, "printf '" + printed + "'; sleep 30");
			try (CodexLoginService service = start(cleanup, () -> ParentPolicy.UNSANDBOXED,
				new CodexLoginSource(new LoginFixtures.Keyring(null)), () -> NOW))
			{
				for (CodexLoginPlan.Command command : List.of(failing, slow))
				{
					CodexLoginClient.Opened opened = open(service, command);
					LoginRefusal refusal = expectThrows(LoginRefusal.class,
						() -> CodexLoginClient.token(service.socket(), opened.credential()));
					requireThat(refusal.getMessage(), "refusal").doesNotContain(printed).
						contains(source.home().toString());
				}
			}
		}
	}

	/**
	 * Verifies that a keyring login opens only when the top-level session can reach the keyring, that the refusal
	 * names the keyring and holds no secret, and that the policy is read once per opening, so that a later change ends
	 * no open session.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void keyringLoginNeedsTopLevelReach() throws IOException
	{
		// Requirement: R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			String token = LoginFixtures.accessToken("keyring", NOW.plus(Duration.ofDays(1)));
			LoginFixtures.Keyring keyring = new LoginFixtures.Keyring(LoginFixtures.signIn(token));
			AtomicReference<ParentPolicy> policy = new AtomicReference<>(NO_LOCAL_SERVICES);
			AtomicInteger readings = new AtomicInteger();
			try (CodexLoginService service = start(cleanup, () ->
			{
				readings.incrementAndGet();
				return policy.get();
			}, new CodexLoginSource(keyring), () -> NOW))
			{
				for (CodexLoginPlan.Store store : List.of(CodexLoginPlan.Store.KEYRING, CodexLoginPlan.Store.AUTO))
				{
					LoginRefusal refusal = expectThrows(LoginRefusal.class, () -> open(service, source.stored(store)));
					requireThat(refusal.getMessage(), "refusal").contains(keyring.name()).
						contains("the top-level session cannot reach that keyring").doesNotContain(token).
						doesNotContain(REFRESH_TOKEN);
				}
				requireThat(readings.get(), "readingsWhenRefused").isEqualTo(2);
				requireThat(keyring.reads(), "keyringReadsWhenRefused").isEqualTo(0);

				policy.set(ParentPolicy.UNSANDBOXED);
				CodexLoginClient.Opened opened = open(service, source.stored(CodexLoginPlan.Store.KEYRING));
				requireThat(readings.get(), "readings").isEqualTo(3);
				requireThat(opened.inKeyring(), "inKeyring").isTrue();
				requireThat(opened.accountId(), "accountId").isEqualTo(Optional.of(ACCOUNT_ID));

				policy.set(NO_LOCAL_SERVICES);
				requireThat(CodexLoginClient.token(service.socket(), opened.credential()), "token").isEqualTo(token);
				requireThat(readings.get(), "readingsAfterToken").isEqualTo(3);
			}
		}
	}

	/**
	 * Verifies that an {@code auth.json} login opens without the keyring check, whatever the top-level policy, and
	 * that {@code auto} reads {@code auth.json} when the keyring holds no item.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void fileLoginSkipsReachCheck() throws IOException
	{
		// Requirement: R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Source source = source(cleanup);
			String key = "sk-fixture-file-key";
			LoginFixtures.writeReadOnly(source.codex().resolve(CodexLoginSource.LOGIN_FILE), LoginFixtures.apiKey(key));
			AtomicInteger readings = new AtomicInteger();
			try (CodexLoginService service = start(cleanup, () ->
			{
				readings.incrementAndGet();
				return NO_LOCAL_SERVICES;
			}, new CodexLoginSource(new LoginFixtures.Keyring(null)), () -> NOW))
			{
				for (CodexLoginPlan.Store store : List.of(CodexLoginPlan.Store.FILE, CodexLoginPlan.Store.AUTO))
				{
					CodexLoginClient.Opened opened = open(service, source.stored(store));
					requireThat(opened.inKeyring(), "inKeyring").isFalse();
					requireThat(CodexLoginClient.token(service.socket(), opened.credential()), "token").isEqualTo(key);
				}
				requireThat(readings.get(), "readings").isEqualTo(0);
			}
		}
	}
}
