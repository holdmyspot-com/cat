package io.github.cowwoc.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import org.testng.annotations.Test;

/**
 * Tests {@link Platform}.
 */
public final class PlatformTest
{
	/**
	 * Creates a test instance.
	 */
	public PlatformTest()
	{
	}

	/**
	 * Verifies aliases for every supported platform.
	 */
	@Test
	public void detectsSupportedAliases()
	{
		requireThat(Platform.detect("Linux", "amd64"), "linuxAmd64Platform").isEqualTo(Platform.LINUX_X64);
		requireThat(Platform.detect("linux", "x86_64"), "linuxX8664Platform").isEqualTo(Platform.LINUX_X64);
		requireThat(Platform.detect("LINUX", "aarch64"), "linuxAarch64Platform").isEqualTo(Platform.LINUX_ARM64);
		requireThat(Platform.detect("Linux", "arm64"), "linuxArm64Platform").isEqualTo(Platform.LINUX_ARM64);
		requireThat(Platform.detect("Mac OS X", "x86_64"), "macosX8664Platform").isEqualTo(Platform.MACOS_X64);
		requireThat(Platform.detect("Darwin", "amd64"), "darwinAmd64Platform").isEqualTo(Platform.MACOS_X64);
		requireThat(Platform.detect("Mac OS X", "aarch64"), "macosAarch64Platform").isEqualTo(Platform.MACOS_ARM64);
		requireThat(Platform.detect("Darwin", "arm64"), "darwinArm64Platform").isEqualTo(Platform.MACOS_ARM64);
		requireThat(Platform.detect("Windows 11", "amd64"), "windowsAmd64Platform").isEqualTo(Platform.WINDOWS_X64);
		requireThat(Platform.detect("Windows Server 2025", "aarch64"), "windowsAarch64Platform").
			isEqualTo(Platform.WINDOWS_ARM64);
	}

	/**
	 * Verifies that only the Windows platforms report that they are Windows.
	 */
	@Test
	public void identifiesWindows()
	{
		for (Platform platform : Platform.values())
		{
			boolean windows = platform == Platform.WINDOWS_X64 || platform == Platform.WINDOWS_ARM64;
			requireThat(platform.isWindows(), platform.id()).isEqualTo(windows);
		}
	}

	/**
	 * Verifies that Windows names the user's home directory in {@code USERPROFILE} and the other platforms in
	 * {@code HOME}.
	 */
	@Test
	public void namesHomeVariable()
	{
		for (Platform platform : Platform.values())
		{
			String expected;
			if (platform.isWindows())
				expected = "USERPROFILE";
			else
				expected = "HOME";
			requireThat(platform.homeVariable(), platform.id()).isEqualTo(expected);
		}
	}

	/**
	 * Verifies unsupported operating systems are rejected.
	 */
	@Test(expectedExceptions = UnsupportedOperationException.class,
		expectedExceptionsMessageRegExp = "Unsupported platform: os.name=FreeBSD, os.arch=amd64")
	public void rejectsUnsupportedOperatingSystems()
	{
		Platform.detect("FreeBSD", "amd64");
	}

	/**
	 * Verifies unsupported architectures are rejected.
	 */
	@Test(expectedExceptions = UnsupportedOperationException.class,
		expectedExceptionsMessageRegExp = "Unsupported platform: os.name=Linux, os.arch=riscv64")
	public void rejectsUnsupportedArchitectures()
	{
		Platform.detect("Linux", "riscv64");
	}

	/**
	 * Verifies distribution identifiers remain stable.
	 */
	@Test
	public void exposesStableDistributionIds()
	{
		requireThat(Platform.LINUX_X64.id(), "linuxX64Identifier").isEqualTo("linux-x64");
		requireThat(Platform.LINUX_ARM64.id(), "linuxArm64Identifier").isEqualTo("linux-arm64");
		requireThat(Platform.MACOS_X64.id(), "macosX64Identifier").isEqualTo("macos-x64");
		requireThat(Platform.MACOS_ARM64.id(), "macosArm64Identifier").isEqualTo("macos-arm64");
	}
}
