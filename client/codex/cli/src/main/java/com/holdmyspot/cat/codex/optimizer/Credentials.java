package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.adapter.CredentialFile;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Classifies the host's Codex login without reading a credential value.
 * <p>
 * The classification uses only the class that the runner states in {@code CAT_HOST_LOGIN} and the names and JSON
 * types of the credential file's top-level keys. It never reads a credential's value, and the credential file is
 * never held in memory as a tree. The {@code forced_login_method} setting of {@code config.toml} is not consulted. The
 * runner's {@code CODEX_API_KEY} and {@code OPENAI_API_KEY} do not count: no session receives them, because each
 * session takes its credential from its launch's source home.
 */
public final class Credentials
{
	/**
	 * Classifies the login that the runner's sessions will use.
	 *
	 * @param codexHome the host's Codex home directory, which holds {@code auth.json}
	 * @param environment the environment that the runner passes to its sessions. Only {@code CAT_HOST_LOGIN} is read.
	 * @param json the JSON mapper
	 * @return the class that {@code CAT_HOST_LOGIN} states, if its value is {@code subscription}, {@code api_key} or
	 *   {@code unknown}; any other value is ignored. Otherwise an API key if the credential file's
	 *   {@code OPENAI_API_KEY} key holds a string and its {@code tokens} key does not hold an object. A subscription (a
	 *   ChatGPT sign-in) if {@code tokens} holds an object and {@code OPENAI_API_KEY} does not hold a string.
	 *   Otherwise unknown.
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the credential file exists but cannot be read
	 */
	public static HostLogin classify(Path codexHome, Map<String, String> environment, JsonMapper json)
		throws IOException
	{
		requireThat(codexHome, "codexHome").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(json, "json").isNotNull();
		String stated = environment.get(HarnessEnvironment.HOST_LOGIN_VARIABLE);
		for (HostLogin login : HostLogin.values())
		{
			if (AuthenticationOptions.classValue(login).equals(stated))
				return login;
		}
		Map<String, JsonToken> keys = CredentialFile.topLevelTypes(codexHome.resolve("auth.json"), json);
		boolean apiKey = keys.get("OPENAI_API_KEY") == JsonToken.VALUE_STRING;
		boolean tokens = keys.get("tokens") == JsonToken.START_OBJECT;
		if (apiKey && !tokens)
			return HostLogin.API_KEY;
		if (tokens && !apiKey)
			return HostLogin.SUBSCRIPTION;
		return HostLogin.UNKNOWN;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private Credentials()
	{
	}
}
