package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests the closed first-release edition, harness, and platform manifest.
 */
public final class ReleaseManifestTest
{
	/**
	 * Creates the test fixture.
	 */
	public ReleaseManifestTest()
	{
	}

	/**
	 * Verifies each edition owns all eight harness-platform targets and canonical repository directories.
	 */
	@Test
	public void containsEightTargetsPerEdition()
	{
		List<ReleaseManifest.ReleaseTarget> personal = ReleaseManifest.targets(ReleaseManifest.Edition.PERSONAL);
		List<ReleaseManifest.ReleaseTarget> commercial = ReleaseManifest.targets(ReleaseManifest.Edition.COMMERCIAL);

		requireThat(personal, "personal").size().isEqualTo(8);
		requireThat(commercial, "commercial").size().isEqualTo(8);
		requireThat(ReleaseManifest.targets(), "allTargets").size().isEqualTo(16);
		requireThat(personal.getFirst().repositoryDirectory(), "repositoryDirectory").isEqualTo(
			"claude/linux-arm64");
	}

	/**
	 * Verifies WSL selects the matching Linux target while native Windows receives the required recovery action.
	 */
	@Test
	public void distinguishesWslFromNativeWindows()
	{
		ReleaseManifest.Detection wsl = ReleaseManifest.detect(ReleaseManifest.Edition.COMMERCIAL,
			ReleaseManifest.Harness.CODEX, "Windows 11", "amd64", true);

		requireThat(wsl.status(), "wslStatus").isEqualTo(ReleaseManifest.DetectionStatus.SUPPORTED);
		requireThat(wsl.target().orElseThrow().repositoryDirectory(), "wslTarget").isEqualTo("codex/linux-x86_64");
		try
		{
			ReleaseManifest.detect(ReleaseManifest.Edition.COMMERCIAL, ReleaseManifest.Harness.CODEX,
				"Windows 11", "amd64", false);
			throw new AssertionError("Native Windows was accepted");
		}
		catch (UnsupportedOperationException expected)
		{
			requireThat(expected.getMessage(), "diagnostic").contains("install and run CAT inside WSL");
		}
	}

	/**
	 * Verifies an unknown harness value is rejected at the untyped manifest boundary.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported CAT harness: cursor.*")
	public void rejectsUnknownHarness()
	{
		ReleaseManifest.Harness.fromExternalValue("cursor");
	}
}
