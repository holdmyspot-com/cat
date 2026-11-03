package com.holdmyspot.cat.common.updater.trust.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link TrustPolicy}: which trust policies the updater accepts, and how setup writes them.
 */
public final class TrustPolicyTest
{
	/**
	 * Serializes fixture paths as JSON strings without assuming the build directory's characters.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates a test instance.
	 */
	public TrustPolicyTest()
	{
	}

	/**
	 * Returns a policy file's text.
	 *
	 * @param keys           the JSON of the key entries, separated by commas
	 * @param releaseBaseUrl the value of {@code release_base_url}
	 * @param harnesses      the JSON of the harness entries, separated by commas
	 * @return the policy's text
	 */
	private static String policyText(String keys, String releaseBaseUrl, String harnesses)
	{
		return """
			{
			  "format": 1,
			  "keys": [%s],
			  "release_base_url": "%s",
			  "allowed_versions": {"minimum": "1.0", "maximum_major": 1},
			  "harnesses": {%s}
			}
			""".formatted(keys, releaseBaseUrl, harnesses);
	}

	/**
	 * Returns the JSON of a harness entry whose home is an absolute path on the current platform.
	 *
	 * @return the entry's JSON
	 */
	private static String validHarness()
	{
		return "\"harness-a\": {\"home\": " + jsonString(absoluteHome().toString()) + "}";
	}

	/**
	 * Returns an absolute, normalized home directory on the current platform. Nothing is written there.
	 *
	 * @return the directory
	 */
	private static Path absoluteHome()
	{
		return Path.of(System.getProperty("cat.updater.test.directory")).resolve("harness-homes/harness-a");
	}

	/**
	 * Quotes text as a JSON string.
	 *
	 * @param text the text
	 * @return the JSON string
	 */
	private static String jsonString(String text)
	{
		return JSON.writeValueAsString(text);
	}

	/**
	 * Returns the JSON of a key entry.
	 *
	 * @param id the key's id
	 * @return the entry's JSON
	 */
	private static String keyEntry(String id)
	{
		String encoded = Base64.getEncoder().encodeToString(keyPair().getPublic().getEncoded());
		return "{\"id\": \"" + id + "\", \"ed25519_x509_base64\": \"" + encoded + "\"}";
	}

	/**
	 * Reads a policy from text and returns the refusal.
	 *
	 * @param text the policy's text
	 * @return the refusal
	 * @throws IOException if the policy file cannot be written or read
	 */
	private static TrustException refusal(String text) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(testDirectory("policy-refusal-")).resolve("policy.json");
			Files.writeString(file, text);
			try
			{
				TrustPolicy.read(file);
			}
			catch (TrustException e)
			{
				requireThat(e.reason(), "reason").isEqualTo(Reason.MALFORMED_POLICY);
				return e;
			}
		}
		throw new AssertionError("The policy was accepted:\n" + text);
	}

	/**
	 * A written policy reads back equal to the original, the write replaces an earlier policy, and it leaves no
	 * temporary file in the policy's directory.
	 *
	 * @throws TrustException if the policy is refused
	 * @throws IOException    if the policy file cannot be written or read
	 */
	@Test
	public void writeRoundTripLeavesNoTemporaryFile() throws TrustException, IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(testDirectory("policy-write-"));
			Path file = directory.resolve("policy.json");
			FileReplacer replacer = new FileReplacer(Platform.current(), () ->
			{
			}, Clock.systemUTC());
			TrustPolicy first = TrustPolicy.of(List.of(trusted("old-key", keyPair())),
				URI.create("https://releases.example/old"), ReleaseVersion.parse("1.0"), 1,
				Map.of("harness-a", absoluteHome()));
			first.write(file, replacer);
			TrustPolicy policy = TrustPolicy.of(List.of(trusted("key-1", keyPair()), trusted("key-2", keyPair())),
				URI.create("https://releases.example/cat/releases/download"), ReleaseVersion.parse("1.0.1"), 2,
				Map.of("harness-a", absoluteHome(), "harness-b", absoluteHome().resolveSibling("harness-b")));

			policy.write(file, replacer);

			requireThat(TrustPolicy.read(file), "read").isEqualTo(policy);
			try (Stream<Path> entries = Files.list(directory))
			{
				requireThat(entries.toList(), "entries").isEqualTo(List.of(file));
			}
		}
	}

	/**
	 * A policy with an empty key list parses, so that setup can report that the build has no release key.
	 *
	 * @throws TrustException if the policy is refused
	 * @throws IOException    if the policy file cannot be written or read
	 */
	@Test
	public void acceptsEmptyKeyList() throws TrustException, IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(testDirectory("policy-empty-")).resolve("policy.json");
			Files.writeString(file, policyText("", "https://releases.example/cat", validHarness()));

			TrustPolicy policy = TrustPolicy.read(file);

			requireThat(policy.keys(), "keys").isEmpty();
			requireThat(policy.harnesses(), "harnesses").isEqualTo(Map.of("harness-a", absoluteHome()));
		}
	}

	/**
	 * A release location that is not {@code https}, or has user information, a query, a fragment or a trailing slash,
	 * is refused.
	 *
	 * @throws IOException if the policy file cannot be written or read
	 */
	@Test
	public void refusesUnsafeReleaseBaseUrl() throws IOException
	{
		List<String> urls = List.of("http://releases.example/cat", "file:///releases/cat", "ftp://releases.example/cat",
			"https://user:secret@releases.example/cat", "https://releases.example/cat?mirror=evil",
			"https://releases.example/cat#x", "https://releases.example/cat/", "https:releases.example", "/cat",
			"https:///cat");
		for (String url : urls)
		{
			TrustException refusal = refusal(policyText(keyEntry("key"), url, validHarness()));
			requireThat(refusal.getMessage(), "message").contains("release_base_url");
		}
	}

	/**
	 * A harness home that is relative or not normalized is refused, and so is a malformed harness identifier.
	 *
	 * @throws IOException if the policy file cannot be written or read
	 */
	@Test
	public void refusesUnsafeHarnessEntry() throws IOException
	{
		String absolute = absoluteHome().toString();
		String nested = absoluteHome().resolve("../harness-a").toString();
		List<String> harnesses = List.of(
			"\"harness-a\": {\"home\": \"relative/home\"}",
			"\"harness-a\": {\"home\": " + jsonString(nested) + "}",
			"\"Harness-A\": {\"home\": " + jsonString(absolute) + "}",
			"\"../x\": {\"home\": " + jsonString(absolute) + "}");
		for (String harness : harnesses)
			refusal(policyText(keyEntry("key"), "https://releases.example/cat", harness));
	}

	/**
	 * A policy with a field that its format does not define, at any level, is refused.
	 *
	 * @throws IOException if the policy file cannot be written or read
	 */
	@Test
	public void refusesUnknownField() throws IOException
	{
		String valid = policyText(keyEntry("key"), "https://releases.example/cat", validHarness());
		List<String> policies = List.of(
			valid.replace("\"format\": 1,", "\"format\": 1, \"mirror\": \"x\","),
			valid.replace("\"maximum_major\": 1", "\"maximum_major\": 1, \"maximum\": \"9.9\""),
			valid.replace("{\"home\": ", "{\"config_dir\": \"/x\", \"home\": "),
			valid.replace("{\"id\": ", "{\"algorithm\": \"rsa\", \"id\": "));
		for (String policy : policies)
			requireThat(refusal(policy).getMessage(), "message").contains("unknown field");
	}

	/**
	 * Two keys with the same id, a key that is not an Ed25519 public key, or a key that is not canonical base64 are
	 * refused.
	 *
	 * @throws IOException if the policy file cannot be written or read
	 */
	@Test
	public void refusesMalformedKeys() throws IOException
	{
		String encoded = Base64.getEncoder().encodeToString(keyPair().getPublic().getEncoded());
		List<String> keys = List.of(
			keyEntry("key") + ", " + keyEntry("key"),
			"{\"id\": \"key\", \"ed25519_x509_base64\": \"AAAA\"}",
			"{\"id\": \"key\", \"ed25519_x509_base64\": \"" + encoded.replace("=", "") + "\"}",
			"{\"id\": \"key\", \"ed25519_x509_base64\": \"not base64\"}",
			"{\"id\": \"\", \"ed25519_x509_base64\": \"" + encoded + "\"}");
		for (String key : keys)
			refusal(policyText(key, "https://releases.example/cat", validHarness()));
	}

	/**
	 * A trusted key decodes only from the canonical base64 of an Ed25519 public key's X.509 encoding.
	 *
	 * @throws TrustException if a valid key is refused
	 */
	@Test
	public void decodesEd25519KeyOnly() throws TrustException
	{
		String encoded = Base64.getEncoder().encodeToString(keyPair().getPublic().getEncoded());
		TrustedKey key = TrustedKey.decode("key", encoded);
		requireThat(key.x509Base64(), "x509Base64").isEqualTo(encoded);

		try
		{
			TrustedKey.decode("key", Base64.getEncoder().encodeToString(new byte[44]));
			throw new AssertionError("Accepted a key that is not an Ed25519 public key");
		}
		catch (TrustException e)
		{
			requireThat(e.reason(), "reason").isEqualTo(Reason.MALFORMED_POLICY);
		}
	}
}
