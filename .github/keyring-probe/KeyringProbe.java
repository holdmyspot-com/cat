import com.holdmyspot.cat.codex.policy.CodexSandbox;
import com.holdmyspot.cat.codex.policy.PermissionEntry;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.login.CodexKeyring;
import com.holdmyspot.cat.common.runner.login.CodexLoginClient;
import com.holdmyspot.cat.common.runner.login.CodexLoginPlan;
import com.holdmyspot.cat.common.runner.login.CodexLoginService;
import com.holdmyspot.cat.common.runner.login.CodexLoginSource;
import com.holdmyspot.cat.common.runner.login.CodexStoredLogin;
import com.holdmyspot.cat.common.runner.login.KeyringReach;
import com.holdmyspot.cat.common.runner.login.LoginRefusal;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the keyring item that Codex wrote, through CAT's own keyring reader and login service, and prints what the
 * reach check says for each top-level policy. Prints hashes, never a token.
 * <p>
 * Arguments: the source home, its Codex folder, the file that holds the access token that Codex stored.
 */
public final class KeyringProbe
{
	private static int failures;

	public static void main(String[] args) throws Exception
	{
		Path sourceHome = Path.of(args[0]).toAbsolutePath();
		Path codexHome = Path.of(args[1]).toAbsolutePath();
		String expected = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8).strip();
		Platform platform = Platform.current();
		System.out.println("platform: " + platform);
		System.out.println("expected token sha256: " + sha(expected));
		System.out.println("canonical Codex folder: " + CodexLoginSource.canonical(codexHome, platform));

		CodexLoginSource source = CodexLoginSource.system(platform, System.getenv());
		CodexKeyring keyring = source.keyring();
		System.out.println("keyring: " + keyring.name());

		// Direct read through the platform's keyring reader
		try
		{
			boolean holds = keyring.holds(codexHome);
			check("keyring holds the item", holds);
			CodexStoredLogin login = keyring.read(codexHome);
			System.out.println("direct read: " + login + " token sha256: " + sha(login.secret()));
			check("direct read is a ChatGPT sign-in", login.signIn());
			check("direct read token equals the stored token", login.secret().equals(expected));
			check("direct read account is probe-account", login.accountId().equals(Optional.of("probe-account")));
			check("direct read token has not expired", !login.expired(Instant.now()));
		}
		catch (LoginRefusal e)
		{
			check("direct read: " + e.getMessage(), false);
		}

		// The reach check and the login service, for each top-level policy
		Map<String, ParentPolicy> policies = new LinkedHashMap<>();
		Map<String, Boolean> expectedReach = new LinkedHashMap<>();
		List<PermissionEntry> everything = List.of(new PermissionEntry(rootOf(codexHome), PermissionEntry.Access.READ),
			new PermissionEntry(sourceHome, PermissionEntry.Access.WRITE));
		policies.put("unsandboxed", ParentPolicy.UNSANDBOXED);
		policies.put("codex-network-full", new CodexSandbox(true, everything, HostAccess.ALL, false).toParentPolicy());
		policies.put("codex-network-off", new CodexSandbox(true, everything, HostAccess.NONE, false).toParentPolicy());
		// ClaudePolicyComputation.allowLocalServices returns false on every platform other than Linux
		policies.put("claude-code-sandboxed", new ParentPolicy(true, List.of(sourceHome), List.of(), List.of(),
			List.of(), HostAccess.NONE, false, false, List.of()));
		for (String name : policies.keySet())
			expectedReach.put(name, name.equals("unsandboxed") || name.equals("codex-network-full") && platform.isMacos());

		AtomicReference<ParentPolicy> topLevel = new AtomicReference<>(ParentPolicy.UNSANDBOXED);
		Path serverFolder = Files.createTempDirectory("probe-server-");
		try (CodexLoginService service = CodexLoginService.start(serverFolder, topLevel::get, source, platform,
			System.getenv(), Instant::now))
		{
			String key = service.runnerVariables().get(CodexLoginService.KEY_VARIABLE);
			for (Map.Entry<String, ParentPolicy> entry : policies.entrySet())
			{
				String name = entry.getKey();
				Optional<String> unreachable = KeyringReach.unreachable(entry.getValue(), platform, System.getenv());
				System.out.println("check " + name + ": " + (unreachable.isEmpty() ? "reachable" :
					"unreachable (" + unreachable.get() + ")"));
				check("check " + name + " as designed", unreachable.isEmpty() == expectedReach.get(name));
				topLevel.set(entry.getValue());
				CodexLoginPlan.Stored plan = new CodexLoginPlan.Stored(sourceHome, codexHome,
					CodexLoginPlan.Store.KEYRING);
				try
				{
					CodexLoginClient.Opened opened = CodexLoginClient.open(service.socket(), key, plan);
					String token = CodexLoginClient.token(service.socket(), opened.credential());
					System.out.println("service " + name + ": opened, inKeyring=" + opened.inKeyring() + ", account=" +
						opened.accountId() + ", token sha256: " + sha(token));
					check("service " + name + " opens only when the check says reachable", unreachable.isEmpty());
					check("service " + name + " token equals the stored token", token.equals(expected));
					CodexLoginClient.close(service.socket(), opened.credential());
				}
				catch (LoginRefusal e)
				{
					System.out.println("service " + name + ": refused: " + e.getMessage());
					check("service " + name + " refuses only when the check says unreachable", unreachable.isPresent());
					check("service " + name + " refusal names the keyring", e.getMessage().contains(keyring.name()));
					check("service " + name + " refusal holds no token", !e.getMessage().contains(expected));
				}
			}
		}
		System.out.println(failures == 0 ? "PROBE PASSED" : "PROBE FAILED: " + failures + " check(s)");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static Path rootOf(Path path)
	{
		return path.getRoot();
	}

	private static void check(String what, boolean passed)
	{
		System.out.println((passed ? "  ok   " : "  FAIL ") + what);
		if (!passed)
			++failures;
	}

	private static String sha(String text) throws Exception
	{
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest).substring(0, 16);
	}
}
