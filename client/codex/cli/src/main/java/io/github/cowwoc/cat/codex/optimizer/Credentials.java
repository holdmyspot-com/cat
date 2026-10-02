package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.adapter.CredentialFile;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Classifies the host's Codex login without reading a credential value.
 * <p>
 * The classification uses only the names of environment variables and the names and JSON types of the credential
 * file's top-level keys. It never reads a variable's or key's value, and the credential file is never held in
 * memory as a tree. The {@code forced_login_method} setting of {@code config.toml} is not consulted.
 */
public final class Credentials
{
	/**
	 * Environment variables that select an API key. They take precedence over the credential file.
	 */
	private static final Set<String> API_KEY_VARIABLES = Set.of("CODEX_API_KEY", "OPENAI_API_KEY");

	/**
	 * Classifies the login that the runner's sessions will use.
	 *
	 * @param codexHome the host's Codex home directory, which holds {@code auth.json}
	 * @param environment the environment that the runner passes to its sessions. Only its variable names are read.
	 * @param json the JSON mapper
	 * @return an API key if the environment has {@code CODEX_API_KEY} or {@code OPENAI_API_KEY}, or if the credential
	 *   file's {@code OPENAI_API_KEY} key holds a string and its {@code tokens} key does not hold an object. A
	 *   subscription (a ChatGPT sign-in) if {@code tokens} holds an object and {@code OPENAI_API_KEY} does not hold
	 *   a string. Otherwise unknown.
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the credential file exists but cannot be read
	 */
	public static HostLogin classify(Path codexHome, Map<String, String> environment, JsonMapper json)
		throws IOException
	{
		requireThat(codexHome, "codexHome").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(json, "json").isNotNull();
		for (String variable : API_KEY_VARIABLES)
		{
			if (environment.containsKey(variable))
				return HostLogin.API_KEY;
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
