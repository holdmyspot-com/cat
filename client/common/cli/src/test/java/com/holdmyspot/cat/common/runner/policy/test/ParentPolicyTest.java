package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that {@link ParentPolicy} trusts only the files that a parent session's commands cannot change, and that
 * combining two policies of one parent gives only the accesses that both give. Each test's policies name folders in a
 * temporary directory that stands for the user's file system.
 */
public final class ParentPolicyTest
{
	/**
	 * Creates the test.
	 */
	public ParentPolicyTest()
	{
	}

	/**
	 * Verifies that a file that the parent's commands may write is refused as a source of the parent's policy.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the source is refused, as expected
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*settings\\.json.*")
	public void refusesSourceInsideWrites() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path settings = Files.writeString(project.resolve("settings.json"), "{}");
			ParentPolicy parent = new ParentPolicy(true, List.of(project), List.of(), List.of(), List.of(),
				HostAccess.NONE, false, List.of());

			parent.requireTrusted(settings);
		}
	}

	/**
	 * Verifies that a file inside the parent's writes is trusted when the harness protects it, or when the parent may
	 * not write the path that holds it, and that a file outside the parent's writes is trusted.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if a source is refused
	 */
	@Test
	public void trustsProtectedOrUnwritableSources() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path harnessConfig = Files.createDirectory(project.resolve(".harness"));
			Path denied = Files.createDirectory(project.resolve("denied"));
			Path link = Files.createSymbolicLink(root.resolve("link"), project);
			ParentPolicy parent = new ParentPolicy(true, List.of(link), List.of(denied), List.of(), List.of(),
				HostAccess.NONE, false, List.of(harnessConfig));

			parent.requireTrusted(project.resolve(".harness/settings.json"));
			parent.requireTrusted(denied.resolve("settings.json"));
			parent.requireTrusted(root.resolve("user/settings.json"));
		}
	}

	/**
	 * Verifies that combining the policy that the launcher read when the parent started with the one that it reads
	 * for a request keeps only the writes, reads and hosts that both give.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void combinesSnapshotAndReread() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path build = Files.createDirectory(project.resolve("build"));
			Path cache = Files.createDirectory(root.resolve("cache"));
			Path added = Files.createDirectory(root.resolve("added"));
			Path home = Files.createDirectory(root.resolve("home"));
			Path docs = Files.createDirectory(home.resolve("docs"));
			Path notes = Files.createDirectory(home.resolve("notes"));
			ParentPolicy snapshot = new ParentPolicy(true, List.of(project, cache), List.of(), List.of(home),
				List.of(docs, notes), HostAccess.listed(Set.of("*.example.com", "model.test")), true, List.of());
			ParentPolicy reread = new ParentPolicy(true, List.of(build, added), List.of(), List.of(home),
				List.of(docs), HostAccess.listed(Set.of("api.example.com", "other.test")), false, List.of());

			ParentPolicy combined = snapshot.intersect(reread);

			requireThat(combined.sandboxed(), "sandboxed").isTrue();
			requireThat(combined.writeRoots(), "writeRoots").isEqualTo(List.of(build));
			requireThat(combined.readDenials(), "readDenials").isEqualTo(List.of(home));
			requireThat(combined.readReallowances(), "readReallowances").isEqualTo(List.of(docs));
			requireThat(combined.hosts().allows("api.example.com"), "allowsApi").isTrue();
			requireThat(combined.hosts().allows("www.example.com"), "allowsWww").isFalse();
			requireThat(combined.hosts().allows("model.test"), "allowsModel").isFalse();
			requireThat(combined.hosts().allows("other.test"), "allowsOther").isFalse();
			requireThat(combined.allowLocalBinding(), "allowLocalBinding").isFalse();
		}
	}

	/**
	 * Verifies that a settings source whose effect on the parent is uncertain contributes the read and write denials
	 * that it adds, and none of the writes or hosts that it adds, when a reader combines the policy without the source
	 * with the policy that includes it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void keepsOnlyNarrowingOfUncertainSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path secrets = Files.createDirectory(project.resolve("secrets"));
			Path generated = Files.createDirectory(project.resolve("generated"));
			Path extra = Files.createDirectory(root.resolve("extra"));
			ParentPolicy without = new ParentPolicy(true, List.of(project), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of("model.test")), false, List.of());
			ParentPolicy with = new ParentPolicy(true, List.of(project, extra), List.of(generated), List.of(secrets),
				List.of(), HostAccess.listed(Set.of("model.test", "packages.test")), false, List.of());

			ParentPolicy combined = without.intersect(with);

			requireThat(combined.writeRoots(), "writeRoots").isEqualTo(List.of(project));
			requireThat(combined.writeDenials(), "writeDenials").isEqualTo(List.of(generated));
			requireThat(combined.readDenials(), "readDenials").isEqualTo(List.of(secrets));
			requireThat(combined.hosts().allows("model.test"), "allowsModel").isTrue();
			requireThat(combined.hosts().allows("packages.test"), "allowsPackages").isFalse();
		}
	}
}
