package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Codex login as Codex stores it, in {@code auth.json} or as the value of a keyring item: an API key, which also
 * stands for a Business or Enterprise access token and a personal access token, or a ChatGPT sign-in, of which only
 * its access token is kept. The refresh token is never kept.
 * <p>
 * The description of an instance holds no secret.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param signIn    {@code true} for a ChatGPT sign-in, {@code false} for an API key
 * @param secret    the API key, or the sign-in's access token
 * @param accountId the sign-in's account ID, which is not a secret, or empty for an API key or a sign-in that names
 *                  none
 * @param expiry    when the access token expires, or empty for an API key or a token that states no expiry
 */
public record CodexStoredLogin(boolean signIn, String secret, Optional<String> accountId, Optional<Instant> expiry)
{
	/**
	 * The claim of a ChatGPT access token that holds the account's details.
	 */
	private static final String AUTH_CLAIM = "https://api.openai.com/auth";

	/**
	 * Creates a login.
	 *
	 * @param signIn    {@code true} for a ChatGPT sign-in, {@code false} for an API key
	 * @param secret    the API key, or the sign-in's access token
	 * @param accountId the sign-in's account ID, or empty
	 * @param expiry    when the access token expires, or empty
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code secret} is blank
	 */
	public CodexStoredLogin
	{
		requireThat(secret, "secret").isNotBlank();
		requireThat(accountId, "accountId").isNotNull();
		requireThat(expiry, "expiry").isNotNull();
	}

	/**
	 * Parses a login as Codex stores it.
	 *
	 * @param text  the stored JSON text
	 * @param where names the store in a refusal, such as the file's path or the keyring item
	 * @return the login
	 * @throws NullPointerException if any of the arguments are null
	 * @throws LoginRefusal         if the text cannot be parsed or holds neither an API key nor an access token; the
	 *                              reason names {@code where} and quotes nothing of the text
	 */
	public static CodexStoredLogin parse(String text, String where) throws LoginRefusal
	{
		requireThat(text, "text").isNotNull();
		requireThat(where, "where").isNotNull();
		JsonNode document;
		try
		{
			document = JsonMapper.builder().build().readTree(text);
		}
		catch (JacksonException _)
		{
			throw new LoginRefusal("The Codex login that " + where + " holds cannot be parsed, so CAT cannot tell " +
				"which login it holds. Sign in to Codex again.");
		}
		JsonNode tokens = document.path("tokens");
		String accessToken = tokens.path("access_token").asString("");
		if (tokens.isObject() && !accessToken.isBlank())
		{
			Optional<JsonNode> claims = claims(accessToken);
			Optional<String> accountId = Optional.of(tokens.path("account_id").asString("")).
				filter(id -> !id.isBlank());
			if (accountId.isEmpty() && claims.isPresent())
			{
				accountId = Optional.of(claims.get().path(AUTH_CLAIM).path("chatgpt_account_id").asString("")).
					filter(id -> !id.isBlank());
			}
			Optional<Instant> expiry = claims.map(node -> node.path("exp")).filter(JsonNode::isNumber).
				map(node -> Instant.ofEpochSecond(node.asLong()));
			return new CodexStoredLogin(true, accessToken, accountId, expiry);
		}
		JsonNode key = document.path("OPENAI_API_KEY");
		if (key.isString() && !key.asString().isBlank())
			return new CodexStoredLogin(false, key.asString(), Optional.empty(), Optional.empty());
		throw new LoginRefusal("The Codex login that " + where + " holds has neither an API key nor a ChatGPT " +
			"access token. Sign in to Codex again.");
	}

	/**
	 * Returns the claims of a JSON web token.
	 *
	 * @param token the token
	 * @return the claims, or empty if the token is not a JSON web token
	 */
	private static Optional<JsonNode> claims(String token)
	{
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3)
			return Optional.empty();
		try
		{
			byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
			JsonNode claims = JsonMapper.builder().build().readTree(new String(payload, UTF_8));
			if (claims.isObject())
				return Optional.of(claims);
			return Optional.empty();
		}
		catch (IllegalArgumentException | JacksonException _)
		{
			// A token that is not a JSON web token states no claims
			return Optional.empty();
		}
	}

	/**
	 * Returns whether the access token has expired.
	 *
	 * @param now the current time
	 * @return {@code true} if the token states an expiry that is not after {@code now}
	 * @throws NullPointerException if {@code now} is null
	 */
	public boolean expired(Instant now)
	{
		requireThat(now, "now").isNotNull();
		return expiry.isPresent() && !expiry.get().isAfter(now);
	}

	@Override
	public String toString()
	{
		return "CodexStoredLogin{signIn=" + signIn + ", accountId=" + accountId + ", expiry=" + expiry + "}";
	}
}
