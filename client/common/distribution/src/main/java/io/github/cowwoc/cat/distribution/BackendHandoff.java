package io.github.cowwoc.cat.distribution;

import io.github.cowwoc.cat.common.Fingerprints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the immutable release facts that the account service needs after every frontend launch gate passes.
 */
public final class BackendHandoff
{
	private static final String SCHEMA_VERSION = "cat-backend-handoff-v1";
	private static final String PERSONAL_REPOSITORY = "https://github.com/catsforbots/cat/";
	private static final String COMMERCIAL_REPOSITORY = "https://github.com/catsforbots/cat-work/";

	/**
	 * Prevents construction of this static utility owner.
	 */
	private BackendHandoff()
	{
	}

	/**
	 * Writes the backend handoff only after every required launch gate passed.
	 *
	 * @param json the JSON codec
	 * @param report the passed launch-gate report
	 * @param destination the nonexistent handoff path
	 * @param gateReport the immutable aggregate launch-gate report
	 * @param personalTree the complete Personal staged release tree
	 * @param commercialTree the complete Commercial staged release tree
	 * @param commit the source commit that produced the release
	 * @param licenseVersion the published license version
	 * @param offerCatalog the published commercial-offer document
	 * @throws IOException if a required input is absent, unsafe, or cannot be written
	 */
	public static void write(JsonMapper json, FrontendLaunchGate.Report report, Path destination, Path gateReport,
		Path personalTree, Path commercialTree, String commit, String licenseVersion, Path offerCatalog) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(report, "report must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(gateReport, "gateReport must not be null");
		Objects.requireNonNull(personalTree, "personalTree must not be null");
		Objects.requireNonNull(commercialTree, "commercialTree must not be null");
		Objects.requireNonNull(offerCatalog, "offerCatalog must not be null");
		if (!report.entries().stream().allMatch(entry -> entry.status() == FrontendLaunchGate.Status.PASSED))
			throw new IllegalArgumentException("Backend handoff requires every frontend launch gate to pass");
		String normalizedCommit = requireSha256(commit, "commit");
		String normalizedLicenseVersion = requireText(licenseVersion, "licenseVersion");
		Path normalizedDestination = destination.toAbsolutePath().normalize();
		if (Files.exists(normalizedDestination, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Backend handoff already exists: " + normalizedDestination);

		Path personalManifest = manifest(personalTree, ReleaseManifest.Edition.PERSONAL);
		Path commercialManifest = manifest(commercialTree, ReleaseManifest.Edition.COMMERCIAL);
		Path normalizedGateReport = regularFile(gateReport, "launch-gate report");
		Path normalizedOffer = regularFile(offerCatalog, "offer catalog");
		ObjectNode document = json.createObjectNode();
		document.put("schema_version", SCHEMA_VERSION);
		document.put("release_version", report.version());
		ObjectNode repositories = document.putObject("repositories");
		repositories.put("personal", PERSONAL_REPOSITORY);
		repositories.put("commercial", COMMERCIAL_REPOSITORY);
		document.put("commit", normalizedCommit);
		document.put("tag", report.version());
		ObjectNode manifests = document.putObject("manifest_sha256");
		manifests.put("personal", Fingerprints.ofFile(personalManifest));
		manifests.put("commercial", Fingerprints.ofFile(commercialManifest));
		ArrayNode targets = document.putArray("supported_tuples");
		for (ReleaseManifest.ReleaseTarget target : ReleaseManifest.targets())
			targets.add(target.identifier());
		ObjectNode commands = document.putObject("install_update_commands");
		commands.put("personal", installCommand(PERSONAL_REPOSITORY, report.version()));
		commands.put("commercial", installCommand(COMMERCIAL_REPOSITORY, report.version()));
		document.put("license_version", normalizedLicenseVersion);
		document.put("offer_catalog_sha256", Fingerprints.ofFile(normalizedOffer));
		document.put("gate_report_sha256", Fingerprints.ofFile(normalizedGateReport));
		try
		{
			Files.createDirectories(normalizedDestination.getParent());
			Files.writeString(normalizedDestination, json.writerWithDefaultPrettyPrinter().writeValueAsString(document) +
				System.lineSeparator());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize backend handoff: " + normalizedDestination, e);
		}
	}

	/**
	 * Creates the documented shell template for either edition's immutable release tag.
	 *
	 * <p>The caller selects {@code <harness>} as {@code codex} or {@code claude}. The Linux x86_64 artifact supplies
	 * the portable bootstrap shell script; the script itself detects the caller's platform before selecting a native
	 * target. The template intentionally does not name a nonexistent root bootstrap file.
	 *
	 * @param repository the edition repository
	 * @param version the immutable release tag
	 * @return the bootstrap command template
	 */
	private static String installCommand(String repository, String version)
	{
		assert repository != null;
		assert version != null;
		return "git clone --depth 1 --branch " + version + " " + repository +
			" cat && CAT_BOOTSTRAP_RELEASE_TREE=$PWD/cat sh $PWD/cat/<harness>/linux-x86_64/install/bootstrap-<harness>.sh " +
			version;
	}

	/**
	 * Resolves one safe edition manifest.
	 *
	 * @param tree the staged edition tree
	 * @param edition the expected edition
	 * @return the safe manifest path
	 * @throws IOException if the tree does not describe the expected edition
	 */
	private static Path manifest(Path tree, ReleaseManifest.Edition edition) throws IOException
	{
		Path root = tree.toAbsolutePath().normalize();
		Path result = regularFile(root.resolve("release-manifest.json"), edition.externalValue() + " manifest");
		String text = Files.readString(result);
		if (!text.contains("\"edition\" : \"" + edition.externalValue() + "\""))
			throw new IOException("Release manifest does not describe " + edition.externalValue() + ": " + result);
		return result;
	}

	/**
	 * Validates a safe regular input file.
	 *
	 * @param path the candidate path
	 * @param description the input description
	 * @return the normalized regular path
	 * @throws IOException if the input is not a safe regular file
	 */
	private static Path regularFile(Path path, String description) throws IOException
	{
		Path normalized = path.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized))
			throw new IOException("Expected safe " + description + " file: " + normalized);
		return normalized;
	}

	/**
	 * Validates one nonblank string.
	 *
	 * @param value the candidate value
	 * @param name the value name
	 * @return the validated value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Validates a lowercase SHA-256 fingerprint.
	 *
	 * @param value the candidate fingerprint
	 * @param name the value name
	 * @return the validated fingerprint
	 */
	private static String requireSha256(String value, String name)
	{
		String result = requireText(value, name);
		if (!result.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
		return result;
	}
}
