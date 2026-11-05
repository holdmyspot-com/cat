package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;

import java.util.Optional;

/**
 * Decides whether a coding harness confines, in a sandbox of its own, a session that CAT starts in it. CAT starts a
 * session only where the session's own harness can sandbox it, whichever harness runs CAT. Each harness module
 * supplies the decision for its own harness.
 */
@FunctionalInterface
public interface HarnessSandbox
{
	/**
	 * Returns why CAT refuses to start a session of the harness on a platform.
	 *
	 * @param platform the platform that the session would run on
	 * @return the reason, which names what is missing and where to run CAT instead, or empty if the harness
	 *   sandboxes its sessions on {@code platform}
	 * @throws NullPointerException if {@code platform} is null
	 */
	Optional<String> refusal(Platform platform);

	/**
	 * Returns the reason that CAT refuses to start a session of a harness on native Windows, which directs the user to
	 * WSL2.
	 *
	 * @param product the harness's product name, as its users know it, such as {@code Codex}
	 * @param missing what native Windows lacks, as a clause that completes the reason
	 * @return the reason
	 * @throws NullPointerException if any argument is null
	 */
	static String nativeWindowsRefusal(String product, String missing)
	{
		requireThat(product, "product").isNotNull();
		requireThat(missing, "missing").isNotNull();
		return "CAT starts a " + product + " session only inside a sandbox that confines it, and " + missing +
			". Install " + product + " and CAT inside WSL2 and run this command there. To install WSL2, run " +
			"`wsl --install` in PowerShell as an administrator; see https://learn.microsoft.com/windows/wsl/install.";
	}
}
