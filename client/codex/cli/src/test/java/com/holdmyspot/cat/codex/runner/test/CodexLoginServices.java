package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.login.CodexKeyring;
import com.holdmyspot.cat.common.runner.login.CodexLoginService;
import com.holdmyspot.cat.common.runner.login.CodexLoginSource;
import com.holdmyspot.cat.common.runner.login.CodexStoredLogin;
import com.holdmyspot.cat.common.runner.login.LoginRefusal;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Starts request-server login services for tests of the sessions that they serve, with a keyring double in place of
 * the operating system's secret store, whose real stores the login service's own tests cover.
 */
public final class CodexLoginServices
{
	/**
	 * The access token of the fixture ChatGPT sign-in.
	 */
	public static final String ACCESS_TOKEN = "fixture-access-token";
	/**
	 * The refresh token of the fixture ChatGPT sign-in.
	 */
	public static final String REFRESH_TOKEN = "fixture-refresh-token";
	/**
	 * The identity token of the fixture ChatGPT sign-in.
	 */
	public static final String ID_TOKEN = "fixture-id-token";
	/**
	 * The ChatGPT account of the fixture sign-in.
	 */
	public static final String ACCOUNT_ID = "fixture-account";

	/**
	 * Prevents construction.
	 */
	private CodexLoginServices()
	{
	}

	/**
	 * A keyring double that holds one login for every Codex folder.
	 *
	 * @param login the text of the login that it holds, or null if it holds none
	 */
	public record Keyring(String login) implements CodexKeyring
	{
		@Override
		public String name()
		{
			return "the fixture keyring item \"" + SERVICE + "\"";
		}

		@Override
		public boolean holds(Path codexHome)
		{
			return login != null;
		}

		@Override
		public CodexStoredLogin read(Path codexHome) throws LoginRefusal
		{
			if (login == null)
				throw new LoginRefusal("The fixture keyring holds no login");
			return CodexStoredLogin.parse(login, name());
		}
	}

	/**
	 * Returns the text of a ChatGPT sign-in as Codex stores it, with the fixture tokens.
	 *
	 * @return the text
	 */
	public static String signIn()
	{
		return """
			{"auth_mode": "chatgpt", "OPENAI_API_KEY": null, "last_refresh": "2026-11-26T00:00:00Z",
			 "tokens": {"id_token": "%s", "access_token": "%s", "refresh_token": "%s", "account_id": "%s"}}
			""".formatted(ID_TOKEN, ACCESS_TOKEN, REFRESH_TOKEN, ACCOUNT_ID);
	}

	/**
	 * Starts a login service that serves the sessions of a top-level session that runs unsandboxed.
	 *
	 * @param cleanup receives the service's folder
	 * @param keyring the keyring that the service reads
	 * @return the service
	 * @throws IOException if the service cannot start
	 */
	public static CodexLoginService start(TemporaryDirectories cleanup, CodexKeyring keyring) throws IOException
	{
		return start(cleanup, keyring, ParentPolicy.UNSANDBOXED);
	}

	/**
	 * Starts a login service.
	 *
	 * @param cleanup  receives the service's folder
	 * @param keyring  the keyring that the service reads
	 * @param topLevel the policy of the top-level session
	 * @return the service
	 * @throws IOException if the service cannot start
	 */
	public static CodexLoginService start(TemporaryDirectories cleanup, CodexKeyring keyring, ParentPolicy topLevel)
		throws IOException
	{
		Path server = cleanup.add(Files.createTempDirectory("login-server-"));
		return CodexLoginService.start(server, () -> topLevel, new CodexLoginSource(keyring), Platform.current(),
			Map.of("PATH", Objects.requireNonNullElse(System.getenv("PATH"), "")), Clock.systemUTC());
	}

	/**
	 * Returns a runner environment that names a service.
	 *
	 * @param service   the service
	 * @param variables more variables of the runner
	 * @return the environment
	 */
	public static Map<String, String> runnerEnvironment(CodexLoginService service, Map<String, String> variables)
	{
		Map<String, String> environment = new HashMap<>(variables);
		environment.put("PATH", Objects.requireNonNullElse(System.getenv("PATH"), ""));
		environment.putAll(service.runnerVariables());
		return environment;
	}
}
