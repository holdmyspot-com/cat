package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installation.HarnessInstallation;
import java.nio.file.Path;
import java.util.List;

/**
 * What an install that the running installer performs needs besides the release: the harness to install through, the
 * plugin folder that the installer runs from, which the install never changes, and the other harnesses' CAT
 * installations, which the harness's sandbox entries name, and whether the user allows a change that needs their
 * permission.
 *
 * @param session  the harness that installs the plugin
 * @param image    the plugin folder that the running installer runs from
 * @param others   the CAT installations of the other harnesses
 * @param catLocks the directory of the capacity locks, which the sandbox entries name
 * @param consent  decides whether the user allows a change that needs their permission
 */
public record InstallRequest(HarnessSession session, Path image, List<HarnessInstallation> others, Path catLocks,
	Consent consent)
{
	/**
	 * Creates the request.
	 *
	 * @param session  the harness that installs the plugin
	 * @param image    the plugin folder that the running installer runs from
	 * @param others   the CAT installations of the other harnesses
	 * @param catLocks the directory of the capacity locks, which the sandbox entries name
	 * @param consent  decides whether the user allows a change that needs their permission
	 * @throws NullPointerException if any of the arguments are null
	 */
	public InstallRequest
	{
		requireThat(session, "session").isNotNull();
		requireThat(image, "image").isNotNull();
		others = List.copyOf(others);
		requireThat(catLocks, "catLocks").isNotNull();
		requireThat(consent, "consent").isNotNull();
	}
}
