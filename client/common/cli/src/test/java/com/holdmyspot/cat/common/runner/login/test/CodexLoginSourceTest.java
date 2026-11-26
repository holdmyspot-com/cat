package com.holdmyspot.cat.common.runner.login.test;

import static com.holdmyspot.cat.common.runner.login.test.LoginFixtures.ACCOUNT_ID;
import static com.holdmyspot.cat.common.runner.login.test.LoginFixtures.REFRESH_TOKEN;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.login.AgeFile;
import com.holdmyspot.cat.common.runner.login.CodexKeyring;
import com.holdmyspot.cat.common.runner.login.CodexLoginPlan;
import com.holdmyspot.cat.common.runner.login.CodexLoginSource;
import com.holdmyspot.cat.common.runner.login.CodexStoredLogin;
import com.holdmyspot.cat.common.runner.login.LinuxSecretService;
import com.holdmyspot.cat.common.runner.login.LoginRefusal;
import com.holdmyspot.cat.common.runner.login.MacosKeychain;
import com.holdmyspot.cat.common.runner.login.WindowsCredentials;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that {@link CodexLoginSource} and the keyrings read the login that Codex keeps for a source's Codex folder,
 * read-only: the item of the Linux Secret Service, the Windows credential and age file, and the macOS Keychain through
 * the user's own {@code codex}, and that {@code auto} prefers the item and falls back to {@code auth.json}.
 */
public final class CodexLoginSourceTest
{
	private static final Platform LINUX = Platform.detect("Linux", "amd64");
	private static final Platform MACOS = Platform.detect("Mac OS X", "aarch64");
	private static final Platform WINDOWS = Platform.detect("Windows 11", "amd64");
	private static final Instant LATER = Instant.parse("2030-01-01T00:00:00Z");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public CodexLoginSourceTest()
	{
	}

	/**
	 * Verifies that the account of a Codex folder's item is {@code cli|} and the first 16 hexadecimal digits of the
	 * SHA-256 of its canonical path, against a value computed by hand with {@code sha256sum}.
	 */
	@Test
	public void accountHashesCanonicalHome()
	{
		// Requirement: R-I15
		// printf '%s' /home/user/.codex | sha256sum
		requireThat(CodexLoginSource.account("/home/user/.codex"), "account").isEqualTo("cli|59448a44901f7128");
		requireThat(LinuxSecretService.attributes("/home/user/.codex"), "attributes").
			isEqualTo(Map.of("service", "Codex Auth", "username", "cli|59448a44901f7128"));
		requireThat(WindowsCredentials.target("/home/user/.codex"), "target").
			isEqualTo("secrets|59448a44901f7128.codex");
	}

	/**
	 * Verifies that a Codex folder's canonical path is its real path, with Windows's {@code \\?\} prefix there.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void canonicalHomeIsRealPath() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("login-canonical-"));
			String real = home.toRealPath().toString();
			requireThat(CodexLoginSource.canonical(home, LINUX), "linux").isEqualTo(real);
			requireThat(CodexLoginSource.canonical(home, WINDOWS), "windows").isEqualTo("\\\\?\\" + real);
		}
	}

	/**
	 * A Secret Service double that holds one item and records the attributes that it is asked for.
	 */
	private static final class Items implements LinuxSecretService.SecretItems
	{
		private final String value;
		private final boolean locked;
		private final List<Map<String, String>> searches = new ArrayList<>();

		/**
		 * Creates a double.
		 *
		 * @param value  the item's value, or null if it holds no item
		 * @param locked {@code true} if the item is locked
		 */
		Items(String value, boolean locked)
		{
			this.value = value;
			this.locked = locked;
		}

		@Override
		public LinuxSecretService.Found search(Map<String, String> attributes)
		{
			searches.add(attributes);
			if (value == null)
				return new LinuxSecretService.Found(List.of(), List.of());
			if (locked)
				return new LinuxSecretService.Found(List.of(), List.of("/org/freedesktop/secrets/collection/login/1"));
			return new LinuxSecretService.Found(List.of("/org/freedesktop/secrets/collection/login/1"), List.of());
		}

		@Override
		public byte[] secret(String item)
		{
			return value.getBytes(UTF_8);
		}
	}

	/**
	 * Verifies that the Linux keyring searches the item by Codex's service and account and parses its value, and that
	 * a locked item or a failing service is a refusal naming the keyring and the folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void linuxItemParses() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codex = cleanup.add(Files.createTempDirectory("login-linux-"));
			String token = LoginFixtures.accessToken("linux", LATER);
			Items items = new Items(LoginFixtures.signIn(token), false);
			LinuxSecretService keyring = new LinuxSecretService(LINUX, items);

			requireThat(keyring.holds(codex), "holds").isTrue();
			CodexStoredLogin login = keyring.read(codex);
			requireThat(login.signIn(), "signIn").isTrue();
			requireThat(login.secret(), "secret").isEqualTo(token);
			requireThat(login.accountId(), "accountId").isEqualTo(Optional.of(ACCOUNT_ID));
			requireThat(items.searches.getFirst(), "attributes").
				isEqualTo(LinuxSecretService.attributes(codex.toRealPath().toString()));

			LoginRefusal locked = expectThrows(LoginRefusal.class,
				() -> new LinuxSecretService(LINUX, new Items(LoginFixtures.signIn(token), true)).read(codex));
			requireThat(locked.getMessage(), "locked").contains(keyring.name()).contains(codex.toString()).
				contains("locked").doesNotContain(token);

			LinuxSecretService failing = new LinuxSecretService(LINUX, new LinuxSecretService.SecretItems()
			{
				@Override
				public LinuxSecretService.Found search(Map<String, String> attributes) throws IOException
				{
					throw new IOException("org.freedesktop.DBus.Error.ServiceUnknown");
				}

				@Override
				public byte[] secret(String item) throws IOException
				{
					throw new IOException("unused");
				}
			});
			LoginRefusal failure = expectThrows(LoginRefusal.class, () -> failing.read(codex));
			requireThat(failure.getMessage(), "failure").contains(failing.name()).contains(codex.toString());
		}
	}

	/**
	 * Verifies that age decryption matches the scrypt vector of the C2SP "CCTV" age test suite.
	 *
	 * @throws IOException              if the vector cannot be read
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void ageMatchesVector() throws IOException, NoSuchAlgorithmException
	{
		// Requirement: R-I15
		byte[] file = LoginFixtures.resource("cctv-scrypt.age");
		byte[] plaintext = AgeFile.decrypt(file, "password".getBytes(UTF_8));
		requireThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext)), "payload").
			isEqualTo("013f54400c82da08037759ada907a8b864e97de81c088a182062c4b5622fd2ab");

		LoginRefusal wrong = expectThrows(LoginRefusal.class,
			() -> AgeFile.decrypt(file, "wrong".getBytes(UTF_8)));
		requireThat(wrong.getMessage(), "wrong").doesNotContain("password");
	}

	/**
	 * Verifies that the Windows keyring reads the passphrase from the credential of Codex's target name and decrypts
	 * the age file with it, and that a failing store is a refusal naming the keyring and the folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void windowsCredentialDecryptsAgeFile() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codex = cleanup.add(Files.createTempDirectory("login-windows-"));
			Path age = Files.createDirectories(codex.resolve("secrets")).resolve("codex_auth.age");
			Files.write(age, LoginFixtures.resource("windows-login.age"));
			byte[] before = Files.readAllBytes(age);
			List<String> targets = new ArrayList<>();
			WindowsCredentials keyring = new WindowsCredentials(WINDOWS, target ->
			{
				targets.add(target);
				return Optional.of("fixture-windows-passphrase".getBytes(UTF_16LE));
			});

			requireThat(keyring.holds(codex), "holds").isTrue();
			CodexStoredLogin login = keyring.read(codex);
			requireThat(login.secret(), "secret").isEqualTo("fixture-windows-access");
			requireThat(login.accountId(), "accountId").isEqualTo(Optional.of("windows-account"));
			requireThat(targets.getFirst(), "target").
				isEqualTo(WindowsCredentials.target("\\\\?\\" + codex.toRealPath()));
			requireThat(Arrays.equals(Files.readAllBytes(age), before), "unchanged").isTrue();

			WindowsCredentials empty = new WindowsCredentials(WINDOWS, _ -> Optional.empty());
			requireThat(empty.holds(codex), "emptyHolds").isFalse();

			WindowsCredentials failing = new WindowsCredentials(WINDOWS, _ ->
			{
				throw new IOException("CredReadW failed with error 5");
			});
			LoginRefusal failure = expectThrows(LoginRefusal.class, () -> failing.read(codex));
			requireThat(failure.getMessage(), "failure").contains(failing.name()).contains(codex.toString());

			WindowsCredentials wrong = new WindowsCredentials(WINDOWS,
				_ -> Optional.of("other".getBytes(UTF_16LE)));
			LoginRefusal wrongPassphrase = expectThrows(LoginRefusal.class, () -> wrong.read(codex));
			requireThat(wrongPassphrase.getMessage(), "wrongPassphrase").contains(age.toString()).
				doesNotContain("fixture-windows");
		}
	}

	/**
	 * Verifies that {@code auto} reads the keyring's item when it exists and {@code auth.json} otherwise.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void autoPrefersItem() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("login-auto-"));
			Path codex = Files.createDirectory(home.resolve(".codex"));
			LoginFixtures.writeReadOnly(codex.resolve(CodexLoginSource.LOGIN_FILE),
				LoginFixtures.apiKey("sk-fixture-file"));
			CodexLoginPlan.Stored plan = new CodexLoginPlan.Stored(home, codex, CodexLoginPlan.Store.AUTO);

			LoginFixtures.Keyring keyring = new LoginFixtures.Keyring(null);
			CodexLoginSource source = new CodexLoginSource(keyring);
			requireThat(source.inKeyring(plan), "inKeyringWithoutItem").isFalse();
			requireThat(source.read(plan, false).secret(), "fileSecret").isEqualTo("sk-fixture-file");

			keyring.set(LoginFixtures.apiKey("sk-fixture-item"));
			boolean inKeyring = source.inKeyring(plan);
			requireThat(inKeyring, "inKeyringWithItem").isTrue();
			requireThat(source.read(plan, inKeyring).secret(), "itemSecret").isEqualTo("sk-fixture-item");
		}
	}

	/**
	 * A process double whose output is fixed and whose input is recorded.
	 */
	private static final class FixedProcess extends Process
	{
		private final ByteArrayOutputStream input = new ByteArrayOutputStream();
		private final InputStream output;
		private final int status;

		/**
		 * Creates a process.
		 *
		 * @param output what the process prints
		 * @param status the process's exit status
		 */
		FixedProcess(String output, int status)
		{
			this.output = new ByteArrayInputStream(output.getBytes(UTF_8));
			this.status = status;
		}

		@Override
		public OutputStream getOutputStream()
		{
			return input;
		}

		@Override
		public InputStream getInputStream()
		{
			return output;
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor()
		{
			return status;
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit)
		{
			return true;
		}

		@Override
		public int exitValue()
		{
			return status;
		}

		@Override
		public void destroy()
		{
		}
	}

	/**
	 * Verifies that the macOS keyring asks {@code security} whether the item exists and asks the user's
	 * {@code codex} app-server for the token with {@code includeToken} true and {@code refreshToken} false, and that a
	 * failing {@code security} is a refusal naming the keyring and the folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void macosAsksAppServer() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codex = cleanup.add(Files.createTempDirectory("login-macos-"));
			Path program = codex.resolve("codex");
			String token = LoginFixtures.accessToken("macos", LATER);
			List<List<String>> commands = new ArrayList<>();
			List<Map<String, String>> environments = new ArrayList<>();
			List<FixedProcess> processes = new ArrayList<>();
			MacosKeychain keychain = new MacosKeychain(Optional.of(program), Map.of("PATH", "/usr/bin"), MACOS,
				(command, environment) ->
				{
					commands.add(command);
					environments.add(environment);
					FixedProcess process;
					if (command.getFirst().equals(MacosKeychain.SECURITY))
						process = new FixedProcess("keychain: \"login.keychain-db\"\n", 0);
					else
					{
						process = new FixedProcess("""
							{"id":1,"result":{"userAgent":"codex"}}
							{"method":"account/updated","params":{}}
							{"id":2,"result":{"authMethod":"chatgpt","authToken":"%s","requiresOpenaiAuth":true}}
							""".formatted(token), 0);
					}
					processes.add(process);
					return process;
				});

			requireThat(keychain.holds(codex), "holds").isTrue();
			requireThat(commands.getFirst(), "security").isEqualTo(List.of(MacosKeychain.SECURITY,
				"find-generic-password", "-s", CodexKeyring.SERVICE, "-a",
				CodexLoginSource.account(codex.toRealPath().toString())));

			CodexStoredLogin login = keychain.read(codex);
			requireThat(login.secret(), "secret").isEqualTo(token);
			requireThat(login.signIn(), "signIn").isTrue();
			requireThat(commands.get(1), "appServer").isEqualTo(List.of(program.toString(), "app-server"));
			requireThat(environments.get(1).get("CODEX_HOME"), "codexHome").isEqualTo(codex.toString());
			JsonNode request = null;
			for (String line : processes.get(1).input.toString(UTF_8).split("\n"))
			{
				JsonNode message = JSON.readTree(line);
				if (message.path("method").asString("").equals("getAuthStatus"))
					request = message;
			}
			requireThat(request, "getAuthStatus").isNotNull();
			requireThat(request.path("params").path("includeToken").asBoolean(false), "includeToken").isTrue();
			requireThat(request.path("params").path("refreshToken").asBoolean(true), "refreshToken").isFalse();
			requireThat(login.toString(), "login").doesNotContain(token).doesNotContain(REFRESH_TOKEN);

			MacosKeychain failing = new MacosKeychain(Optional.of(program), Map.of(), MACOS,
				(_, _) -> new FixedProcess("", 51));
			LoginRefusal failure = expectThrows(LoginRefusal.class, () -> failing.holds(codex));
			requireThat(failure.getMessage(), "failure").contains(failing.name()).contains(codex.toString());

			MacosKeychain absent = new MacosKeychain(Optional.of(program), Map.of(), MACOS,
				(_, _) -> new FixedProcess("", 44));
			requireThat(absent.holds(codex), "absentHolds").isFalse();
			LoginRefusal noCodex = expectThrows(LoginRefusal.class,
				() -> new MacosKeychain(Optional.empty(), Map.of(), MACOS, (_, _) -> new FixedProcess("", 0)).
					read(codex));
			requireThat(noCodex.getMessage(), "noCodex").contains(codex.toString());
		}
	}

	/**
	 * Verifies that a stored sign-in whose token states an expiry in the past is expired.
	 *
	 * @throws IOException if the login cannot be parsed
	 */
	@Test
	public void signInExpiry() throws IOException
	{
		// Requirement: R-I15
		Instant now = Instant.parse("2026-11-26T12:00:00Z");
		CodexStoredLogin expired = CodexStoredLogin.parse(LoginFixtures.signIn(LoginFixtures.accessToken("old",
			now.minus(Duration.ofSeconds(1)))), "fixture");
		requireThat(expired.expired(now), "expired").isTrue();
		CodexStoredLogin current = CodexStoredLogin.parse(LoginFixtures.signIn(LoginFixtures.accessToken("new",
			now.plus(Duration.ofHours(1)))), "fixture");
		requireThat(current.expired(now), "current").isFalse();
	}
}
