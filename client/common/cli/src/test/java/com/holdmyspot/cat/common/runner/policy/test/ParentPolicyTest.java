package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that combining two policies of one parent gives only the accesses that both give, and which files a parent's
 * commands may read. Each test's policies name folders in a
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
	 * Verifies that combining two policies of one parent, such as the policy of a Codex session's configuration and the
	 * policy that its turn records, keeps only the writes, reads and hosts that both give.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void combinesTwoPolicies() throws IOException
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
			ParentPolicy first = new ParentPolicy(true, List.of(project, cache), List.of(), List.of(home),
				List.of(docs, notes), HostAccess.listed(Set.of("*.example.com", "model.test")), true);
			ParentPolicy second = new ParentPolicy(true, List.of(build, added), List.of(), List.of(home),
				List.of(docs), HostAccess.listed(Set.of("api.example.com", "other.test")), false);

			ParentPolicy combined = first.intersect(second);

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
				HostAccess.listed(Set.of("model.test")), false);
			ParentPolicy with = new ParentPolicy(true, List.of(project, extra), List.of(generated), List.of(secrets),
				List.of(), HostAccess.listed(Set.of("model.test", "packages.test")), false);

			ParentPolicy combined = without.intersect(with);

			requireThat(combined.writeRoots(), "writeRoots").isEqualTo(List.of(project));
			requireThat(combined.writeDenials(), "writeDenials").isEqualTo(List.of(generated));
			requireThat(combined.readDenials(), "readDenials").isEqualTo(List.of(secrets));
			requireThat(combined.hosts().allows("model.test"), "allowsModel").isTrue();
			requireThat(combined.hosts().allows("packages.test"), "allowsPackages").isFalse();
		}
	}

	/**
	 * Verifies that the parent's commands may read a file outside its read denials or inside a re-allowance, and not a
	 * file inside a denial, also through a link, and that an unsandboxed parent may read every file.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void readsOutsideDenials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path home = Files.createDirectory(root.resolve("home"));
			Path reallowed = Files.createDirectory(home.resolve("reallowed"));
			Path login = Files.writeString(home.resolve("login.json"), "{}");
			Path link = Files.createSymbolicLink(root.resolve("link.json"), login);
			ParentPolicy parent = new ParentPolicy(true, List.of(), List.of(), List.of(home), List.of(reallowed),
				HostAccess.NONE, false);

			requireThat(parent.mayRead(root.resolve("other.json")), "readsOutside").isTrue();
			requireThat(parent.mayRead(reallowed.resolve("login.json")), "readsReallowed").isTrue();
			requireThat(parent.mayRead(login), "readsDenied").isFalse();
			requireThat(parent.mayRead(link), "readsDeniedThroughLink").isFalse();
			requireThat(ParentPolicy.UNSANDBOXED.mayRead(login), "unsandboxedReadsDenied").isTrue();
		}
	}
}
