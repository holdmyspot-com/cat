package io.github.cowwoc.cat.claude.optimizer;

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
 * Classifies the host's Claude login without reading a credential value.
 * <p>
 * The classification uses only the names of environment variables and the names and JSON types of the credential
 * file's top-level keys. It never reads a variable's or key's value, and the credential file is never held in
 * memory as a tree.
 */
public final class Credentials
{
	/**
	 * Environment variables that select an API key. They take precedence over the credential file.
	 */
	private static final Set<String> API_KEY_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN");
	private static final String SUBSCRIPTION_VARIABLE = "CLAUDE_CODE_OAUTH_TOKEN";
	private static final String SUBSCRIPTION_KEY = "claudeAiOauth";

	/**
	 * Classifies the login that the runner's sessions will use.
	 *
	 * @param configurationDirectory the host's Claude configuration directory, which holds
	 *   {@code .credentials.json}
	 * @param environment the environment that the runner passes to its sessions. Only its variable names are read.
	 * @param json the JSON mapper
	 * @return an API key if the environment has {@code ANTHROPIC_API_KEY} or {@code ANTHROPIC_AUTH_TOKEN}.
	 *   Otherwise a subscription if the environment has {@code CLAUDE_CODE_OAUTH_TOKEN} or the credential file's
	 *   {@code claudeAiOauth} key holds an object. Otherwise unknown.
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the credential file exists but cannot be read
	 */
	public static HostLogin classify(Path configurationDirectory, Map<String, String> environment, JsonMapper json)
		throws IOException
	{
		requireThat(configurationDirectory, "configurationDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(json, "json").isNotNull();
		for (String variable : API_KEY_VARIABLES)
		{
			if (environment.containsKey(variable))
				return HostLogin.API_KEY;
		}
		if (environment.containsKey(SUBSCRIPTION_VARIABLE))
			return HostLogin.SUBSCRIPTION;
		Path credentials = configurationDirectory.resolve(".credentials.json");
		Map<String, JsonToken> keys = CredentialFile.topLevelTypes(credentials, json);
		if (keys.get(SUBSCRIPTION_KEY) == JsonToken.START_OBJECT)
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
