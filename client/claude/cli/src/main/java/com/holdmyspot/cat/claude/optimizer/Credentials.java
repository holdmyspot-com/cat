package com.holdmyspot.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.adapter.CredentialFile;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Classifies the Claude login that a runner's sessions use, without reading a credential value.
 * <p>
 * The classification uses only the names of environment variables and the names and JSON types of the credential
 * files' top-level keys. It never reads a variable's or key's value, and a credential file is never held in memory
 * as a tree.
 */
public final class Credentials
{
	/**
	 * Environment variables that select an API key. They take precedence over the credential files.
	 */
	private static final Set<String> API_KEY_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN");
	private static final String SUBSCRIPTION_VARIABLE = "CLAUDE_CODE_OAUTH_TOKEN";
	/**
	 * The variable that names the Claude configuration directory whose {@code .credentials.json} Claude Code uses,
	 * in place of the one in its own configuration directory.
	 */
	private static final String LOGIN_STORE_VARIABLE = "CLAUDE_SECURESTORAGE_CONFIG_DIR";
	private static final String CREDENTIALS_FILE = ".credentials.json";
	private static final String SUBSCRIPTION_KEY = "claudeAiOauth";

	/**
	 * Classifies the login that the runner's sessions will use.
	 *
	 * @param configurationDirectory the host's Claude configuration directory, which holds
	 *   {@code .credentials.json}
	 * @param environment the environment that the runner passes to its sessions. Only its variable names are read,
	 *   except for {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}, whose value is a directory rather than a credential.
	 *   When the value is not empty, it names the login store, whose {@code .credentials.json} is read before the
	 *   configuration directory's, because that is where a session of a CAT run keeps the user's login. A login
	 *   store without a subscription login leaves the classification to the configuration directory. A relative
	 *   value names a directory relative to the process's working directory.
	 * @param json the JSON mapper
	 * @return an API key if the environment has {@code ANTHROPIC_API_KEY} or {@code ANTHROPIC_AUTH_TOKEN}.
	 *   Otherwise a subscription if the environment has {@code CLAUDE_CODE_OAUTH_TOKEN} or the {@code claudeAiOauth}
	 *   key of the login store's, or else the configuration directory's, {@code .credentials.json} holds an object.
	 *   Otherwise unknown.
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if a credential file exists but cannot be read
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
		String loginStore = environment.get(LOGIN_STORE_VARIABLE);
		if (loginStore != null && !loginStore.isEmpty() && holdsSubscription(Path.of(loginStore), json))
			return HostLogin.SUBSCRIPTION;
		if (holdsSubscription(configurationDirectory, json))
			return HostLogin.SUBSCRIPTION;
		return HostLogin.UNKNOWN;
	}

	/**
	 * Indicates whether a Claude configuration directory's credential file holds a subscription login.
	 *
	 * @param directory the configuration directory
	 * @param json the JSON mapper
	 * @return {@code true} if the {@code claudeAiOauth} key of the directory's {@code .credentials.json} holds an
	 *   object
	 * @throws IOException if the credential file exists but cannot be read
	 */
	private static boolean holdsSubscription(Path directory, JsonMapper json) throws IOException
	{
		Map<String, JsonToken> keys = CredentialFile.topLevelTypes(directory.resolve(CREDENTIALS_FILE), json);
		return keys.get(SUBSCRIPTION_KEY) == JsonToken.START_OBJECT;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private Credentials()
	{
	}
}
