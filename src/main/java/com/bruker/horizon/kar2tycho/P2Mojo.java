package com.bruker.horizon.kar2tycho;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import lombok.Getter;
import lombok.Setter;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.sisu.equinox.launching.internal.P2ApplicationLauncher;
import org.reficio.p2.EclipseArtifact;
import org.reficio.p2.P2Artifact;
import org.reficio.p2.P2Validator;
import org.reficio.p2.bundler.ArtifactBundler;
import org.reficio.p2.bundler.ArtifactBundlerInstructions;
import org.reficio.p2.bundler.ArtifactBundlerRequest;
import org.reficio.p2.bundler.impl.AquteBundler;
import org.reficio.p2.logger.Logger;
import org.reficio.p2.publisher.CategoryPublisher;
import org.reficio.p2.resolver.eclipse.EclipseResolutionRequest;
import org.reficio.p2.resolver.eclipse.impl.DefaultEclipseResolver;
import org.reficio.p2.resolver.maven.Artifact;
import org.reficio.p2.resolver.maven.ArtifactResolutionRequest;
import org.reficio.p2.resolver.maven.ArtifactResolutionResult;
import org.reficio.p2.resolver.maven.ArtifactResolver;
import org.reficio.p2.resolver.maven.ResolvedArtifact;
import org.reficio.p2.resolver.maven.impl.AetherResolver;
import org.reficio.p2.utils.BundleUtils;
import org.reficio.p2.utils.JarUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * Main plugin class
 *
 * @author Tom Bujok (tom.bujok@gmail.com)<br>
 *         Reficio (TM) - Reestablish your software!<br>
 *         http://www.reficio.org
 * @since 1.0.0
 */
@Mojo(name = "site", defaultPhase = LifecyclePhase.COMPILE,
		requiresDependencyResolution = ResolutionScope.RUNTIME,
		requiresDependencyCollection = ResolutionScope.RUNTIME)
public class P2Mojo {

	private static final String TYCHO_VERSION = "1.0.0";
	private static final String BUNDLES_TOP_FOLDER = "/source";
	private static final String FEATURES_DESTINATION_FOLDER = BUNDLES_TOP_FOLDER + "/features";
	private static final String BUNDLES_DESTINATION_FOLDER = BUNDLES_TOP_FOLDER + "/plugins";
	private static final String DEFAULT_CATEGORY_FILE = "category.xml";
	private static final String DEFAULT_CATEGORY_CLASSPATH_LOCATION = "/";

	@Getter
	@Setter
	private MavenProject project;
	@Getter
	@Setter
	private MavenSession session;
	@Getter
	@Setter
	private BuildPluginManager pluginManager;
	@Getter
	@Setter
	private String buildDirectory;
	@Getter
	@Setter
	private String destinationDirectory;
	@Getter
	@Setter
	private P2ApplicationLauncher launcher;
	@Getter
	@Setter
	private String categoryFileURL;
	@Getter
	@Setter
	private boolean pedantic;
	@Getter
	@Setter
	private boolean skipInvalidArtifacts;
	@Getter
	@Setter
	private boolean compressSite;
	@Getter
	@Setter
	private int forkedProcessTimeoutInSeconds;
	@Getter
	@Setter
	private boolean reuseSnapshotVersionFromArtifact;
	@Getter
	@Setter
	private String additionalArgs;

	@Setter
	private Kar2TychoP2Feature kar2TychoP2Feature;

	@Setter
	private Kar2TychoP2Feature sourceKar2TychoP2Feature;

	@Setter
	private MavenArchiveConfiguration archive;

	@Setter
	private PlexusContainer plexus;

	/**
	 * Dependency injection container - used to get some components programatically
	 */
	@Getter
	@Setter
	private PlexusContainer container;

	/**
	 * Aether Repository System Declared as raw Object type as different objects are injected in
	 * different Maven versions: * 3.0.0 and above -> org.sonatype.aether... * 3.1.0 and above ->
	 * org.eclipse.aether...
	 */
	private Object repoSystem;

	/**
	 * The current repository/network configuration of Maven.
	 */
	@Getter
	@Setter
	private Object repoSession;

	/**
	 * The project's remote repositories to use for the resolution of project dependencies.
	 */
	@Getter
	@Setter
	private List<Object> projectRepos;

	@Getter
	@Setter
	private List<P2Artifact> artifacts;

	/**
	 * A list of artifacts that define eclipse features
	 */
	@Getter
	@Setter
	private List<P2Artifact> features;

	/**
	 * A list of Eclipse artifacts that should be downloaded from P2 repositories
	 */
	@Getter
	@Setter
	private List<EclipseArtifact> p2;

	/**
	 * Logger retrieved from the Maven internals. It's the recommended way to do it...
	 */
	@Setter
	private Log log;

	/**
	 * Folder which the jar files bundled by the ArtifactBundler will be copied to
	 */
	@Getter
	@Setter
	private File bundlesDestinationFolder;

	/**
	 * Folder which the feature jar files bundled by the ArtifactBundler will be copied to
	 */
	private File featuresDestinationFolder;

	/**
	 * Processing entry point. Method that orchestrates the execution of the plugin.
	 */
	public void execute() {
		try {
			initializeEnvironment();
			initializeRepositorySystem();
			processArtifacts();
			createFeatures();
			processFeatures();
			processEclipseArtifacts();
			executeP2PublisherPlugin();
			executeCategoryPublisher();
			cleanupEnvironment();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void createFeatures() {
		String targetDirectory = buildDirectory + "/p2Features";
		try {
			// create p2 feature artifact
			kar2TychoP2Feature.writeXMLFile(targetDirectory);
			archiveFeature(kar2TychoP2Feature);
			installAndAddFeatureToP2Repository(kar2TychoP2Feature);

			// create p2 source feature artifact
			sourceKar2TychoP2Feature.writeXMLFile(targetDirectory);
			archiveFeature(sourceKar2TychoP2Feature);
			installAndAddFeatureToP2Repository(sourceKar2TychoP2Feature);

		}
		catch (Exception e) {
			log.error(e);
		}

	}

	private void installAndAddFeatureToP2Repository(Kar2TychoP2Feature feature)
			throws MojoExecutionException {
		executeMojo(
				plugin(groupId("org.apache.maven.plugins"), artifactId("maven-install-plugin"),
						version("2.4")),
				goal("install-file"),
				configuration(element(name("file"), feature.getFeatureArchive().getAbsolutePath()),
						element(name("groupId"), "${project.groupId}"),
						element(name("artifactId"), feature.getFeatureId()),
						element(name("version"), "${project.version}"), element(name("packaging"), "jar")),
				executionEnvironment(project, session, pluginManager));
		P2Artifact p2Feature = new P2Artifact();
		p2Feature
				.setId(project.getGroupId() + ":" + feature.getFeatureId() + ":" + project.getVersion());
		p2Feature.setTransitive(false);
		features.add(p2Feature);

	}

	private void archiveFeature(Kar2TychoP2Feature kar2TychoP2Feature) throws MojoExecutionException {
		JarArchiver jarArchiver = getJarArchiver();
		String parentDirectory = kar2TychoP2Feature.getFeatureFile().getParentFile().getParent();
		File outputJar = new File(parentDirectory + "/" + kar2TychoP2Feature.getFeatureArchiveName());
		jarArchiver.setDestFile(outputJar);

		try {
			jarArchiver.addFile(kar2TychoP2Feature.getFeatureFile(), kar2TychoP2Feature.FEATURE_XML_NAME);
			jarArchiver.createArchive();
			kar2TychoP2Feature.setFeatureArchive(outputJar);

		}
		catch (Exception e) {
			throw new MojoExecutionException("Error creating feature package", e);
		}

	}

	private JarArchiver getJarArchiver() throws MojoExecutionException {
		try {
			return (JarArchiver) plexus.lookup(Archiver.ROLE, "jar");
		}
		catch (ComponentLookupException e) {
			throw new MojoExecutionException("Unable to get JarArchiver", e);
		}
	}

	private void initializeEnvironment() throws IOException {
		Logger.initialize(log);
		bundlesDestinationFolder = new File(buildDirectory, BUNDLES_DESTINATION_FOLDER);
		featuresDestinationFolder = new File(buildDirectory, FEATURES_DESTINATION_FOLDER);
		FileUtils.deleteDirectory(new File(buildDirectory, BUNDLES_TOP_FOLDER));
		FileUtils.forceMkdir(bundlesDestinationFolder);
		FileUtils.forceMkdir(featuresDestinationFolder);
		artifacts = artifacts != null ? artifacts : new ArrayList<P2Artifact>();
		features = features != null ? features : new ArrayList<P2Artifact>();
		p2 = p2 != null ? p2 : new ArrayList<EclipseArtifact>();
	}

	private void initializeRepositorySystem() {
		if (repoSystem == null) {
			repoSystem = lookup("org.eclipse.aether.RepositorySystem");
		}
		if (repoSystem == null) {
			repoSystem = lookup("org.sonatype.aether.RepositorySystem");
		}
		Preconditions.checkNotNull(repoSystem, "Could not initialize RepositorySystem");
	}

	private Object lookup(String role) {
		try {
			return container.lookup(role);
		}
		catch (ComponentLookupException ex) {
		}
		return null;
	}

	private void processArtifacts() {
		BundleUtils.INSTANCE.setReuseSnapshotVersionFromArtifact(reuseSnapshotVersionFromArtifact);
		Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = resolveArtifacts();
		Set<Artifact> processedArtifacts = processRootArtifacts(resolvedArtifacts);
		processTransitiveArtifacts(resolvedArtifacts, processedArtifacts);
	}

	private Set<Artifact> processRootArtifacts(
			Multimap<P2Artifact, ResolvedArtifact> processedArtifacts) {
		Set<Artifact> bundledArtifacts = Sets.newHashSet();
		for (P2Artifact p2Artifact : artifacts) {
			for (ResolvedArtifact resolvedArtifact : processedArtifacts.get(p2Artifact)) {
				if (resolvedArtifact.isRoot()) {
					if (bundledArtifacts.add(resolvedArtifact.getArtifact())) {
						bundleArtifact(p2Artifact, resolvedArtifact);
					}
					else {
						String message = String.format(
								"p2-maven-plugin misconfiguration"
										+ "\n\n\tJar [%s] is configured as an artifact multiple times. "
										+ "\n\tRemove the duplicate artifact definitions.\n",
								resolvedArtifact.getArtifact());
						throw new RuntimeException(message);
					}
				}
			}
		}
		return bundledArtifacts;
	}

	private void processTransitiveArtifacts(Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts,
			Set<Artifact> bundledArtifacts) {
		// then bundle transitive artifacts
		for (P2Artifact p2Artifact : artifacts) {
			for (ResolvedArtifact resolvedArtifact : resolvedArtifacts.get(p2Artifact)) {
				if (!resolvedArtifact.isRoot()) {
					if (!bundledArtifacts.contains(resolvedArtifact.getArtifact())) {
						try {
							bundleArtifact(p2Artifact, resolvedArtifact);
							bundledArtifacts.add(resolvedArtifact.getArtifact());
						}
						catch (final RuntimeException ex) {
							if (skipInvalidArtifacts) {
								log.warn(
										String.format("Skip artifact=[%s]: %s", p2Artifact.getId(), ex.getMessage()));
							}
							else {
								throw ex;
							}
						}
					}
					else {
						log.debug(String.format(
								"Not bundling transitive dependency since it has already been bundled [%s]",
								resolvedArtifact.getArtifact()));
					}
				}
			}
		}
	}

	private void processFeatures() {
		// artifacts should already have been resolved by processArtifacts()
		Multimap<P2Artifact, ResolvedArtifact> resolvedFeatures = resolveFeatures();
		// then bundle the artifacts including the transitive dependencies (if
		// specified so)
		log.info("Resolved " + resolvedFeatures.size() + " features");
		for (P2Artifact p2Artifact : features) {
			for (ResolvedArtifact resolvedArtifact : resolvedFeatures.get(p2Artifact)) {
				handleFeature(p2Artifact, resolvedArtifact);
			}
		}
	}

	private Multimap<P2Artifact, ResolvedArtifact> resolveArtifacts() {
		Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = ArrayListMultimap.create();
		for (P2Artifact p2Artifact : artifacts) {
			logResolving(p2Artifact);
			ArtifactResolutionResult resolutionResult = resolveArtifact(p2Artifact);
			resolvedArtifacts.putAll(p2Artifact, resolutionResult.getResolvedArtifacts());
		}
		return resolvedArtifacts;
	}

	private Multimap<P2Artifact, ResolvedArtifact> resolveFeatures() {
		Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = ArrayListMultimap.create();
		for (P2Artifact p2Artifact : features) {
			logResolving(p2Artifact);
			ArtifactResolutionResult resolutionResult = resolveArtifact(p2Artifact);
			resolvedArtifacts.putAll(p2Artifact, resolutionResult.getResolvedArtifacts());
		}
		return resolvedArtifacts;
	}

	private void logResolving(EclipseArtifact p2) {
		log.info(String.format("Resolving artifact=[%s] source=[%s]", p2.getId(),
				p2.shouldIncludeSources()));
	}

	private void logResolving(P2Artifact p2) {
		log.info(String.format("Resolving artifact=[%s] transitive=[%s] source=[%s]", p2.getId(),
				p2.shouldIncludeTransitive(), p2.shouldIncludeSources()));
	}

	private ArtifactResolutionResult resolveArtifact(P2Artifact p2Artifact) {
		ArtifactResolutionRequest resolutionRequest = ArtifactResolutionRequest.builder()
				.rootArtifactId(p2Artifact.getId()).resolveSource(p2Artifact.shouldIncludeSources())
				.resolveTransitive(p2Artifact.shouldIncludeTransitive()).excludes(p2Artifact.getExcludes())
				.build();
		ArtifactResolutionResult resolutionResult = getArtifactResolver().resolve(resolutionRequest);
		logResolved(resolutionRequest, resolutionResult);
		for (ResolvedArtifact resolvedArtifact : resolutionResult.getResolvedArtifacts()) {
			try {
				String bsn = P2Helper.calculateSymbolicName(p2Artifact, resolvedArtifact);
				String ssn = P2Helper.calculateSourceSymbolicName(bsn);
				String version = P2Helper.calculateVersion(p2Artifact, resolvedArtifact);
				kar2TychoP2Feature.addPlugin(resolvedArtifact.getArtifact(), bsn, version);
				if (resolvedArtifact.getSourceArtifact() != null)
					sourceKar2TychoP2Feature.addPlugin(resolvedArtifact.getSourceArtifact(), ssn, version);
			}
			catch (IOException e) {
				log.error(e);
			}

		}

		return resolutionResult;
	}

	private ArtifactResolver getArtifactResolver() {
		return new AetherResolver(repoSystem, repoSession, projectRepos);
	}

	private void logResolved(ArtifactResolutionRequest resolutionRequest,
			ArtifactResolutionResult resolutionResult) {
		for (ResolvedArtifact resolvedArtifact : resolutionResult.getResolvedArtifacts()) {
			log.info("\t [JAR] " + resolvedArtifact.getArtifact());
			if (resolvedArtifact.getSourceArtifact() != null) {
				log.info("\t [SRC] " + resolvedArtifact.getSourceArtifact().toString());
			}
			else if (resolutionRequest.isResolveSource()) {
				log.warn("\t [SRC] Failed to resolve source for artifact "
						+ resolvedArtifact.getArtifact().toString());
			}
		}
	}

	private void bundleArtifact(P2Artifact p2Artifact, ResolvedArtifact resolvedArtifact) {
		P2Validator.validateBundleRequest(p2Artifact, resolvedArtifact);
		ArtifactBundler bundler = getArtifactBundler();
		ArtifactBundlerInstructions bundlerInstructions =
				P2Helper.createBundlerInstructions(p2Artifact, resolvedArtifact);
		ArtifactBundlerRequest bundlerRequest =
				P2Helper.createBundlerRequest(p2Artifact, resolvedArtifact, bundlesDestinationFolder);
		bundler.execute(bundlerRequest, bundlerInstructions);
	}

	private void handleFeature(P2Artifact p2Artifact, ResolvedArtifact resolvedArtifact) {
		log.debug("Handling feature " + p2Artifact.getId());
		ArtifactBundlerRequest bundlerRequest =
				P2Helper.createBundlerRequest(p2Artifact, resolvedArtifact, featuresDestinationFolder);
		try {
			File inputFile = bundlerRequest.getBinaryInputFile();
			File outputFile = bundlerRequest.getBinaryOutputFile();
			// This will also copy the input to the output
			JarUtils.adjustFeatureQualifierVersionWithTimestamp(inputFile, outputFile);
			log.info("Copied " + inputFile + " to " + outputFile);
		}
		catch (Exception ex) {
			throw new RuntimeException(
					"Error while bundling jar or source: " + bundlerRequest.getBinaryInputFile().getName(),
					ex);
		}
	}

	private void processEclipseArtifacts() {
		DefaultEclipseResolver resolver =
				new DefaultEclipseResolver(projectRepos, bundlesDestinationFolder);
		for (EclipseArtifact artifact : p2) {
			logResolving(artifact);
			String[] tokens = artifact.getId().split(":");
			if (tokens.length != 2) {
				throw new RuntimeException("Wrong format " + artifact.getId());
			}
			EclipseResolutionRequest request =
					new EclipseResolutionRequest(tokens[0], tokens[1], artifact.shouldIncludeSources());
			resolver.resolve(request);
		}
	}

	private ArtifactBundler getArtifactBundler() {
		return new AquteBundler(pedantic);
	}

	private void executeP2PublisherPlugin() throws IOException, MojoExecutionException {
		prepareDestinationDirectory();
		executeMojo(
				plugin(groupId("org.eclipse.tycho.extras"), artifactId("tycho-p2-extras-plugin"),
						version(TYCHO_VERSION)),
				goal("publish-features-and-bundles"),
				configuration(element(name("compress"), Boolean.toString(compressSite)),
						element(name("additionalArgs"), additionalArgs)),
				executionEnvironment(project, session, pluginManager));
	}

	private void prepareDestinationDirectory() throws IOException {
		FileUtils.deleteDirectory(new File(destinationDirectory));
	}

	private void executeCategoryPublisher() throws AbstractMojoExecutionException, IOException {
		prepareCategoryLocationFile();
		CategoryPublisher publisher = CategoryPublisher.builder().p2ApplicationLauncher(launcher)
				.additionalArgs(additionalArgs).forkedProcessTimeoutInSeconds(forkedProcessTimeoutInSeconds)
				.categoryFileLocation(categoryFileURL).metadataRepositoryLocation(destinationDirectory)
				.build();
		publisher.execute();
	}

	private void prepareCategoryLocationFile() throws IOException {
		if (StringUtils.isBlank(categoryFileURL)) {
			InputStream is = getClass()
					.getResourceAsStream(DEFAULT_CATEGORY_CLASSPATH_LOCATION + DEFAULT_CATEGORY_FILE);
			File destinationFolder = new File(destinationDirectory);
			destinationFolder.mkdirs();
			File categoryDefinitionFile = new File(destinationFolder, DEFAULT_CATEGORY_FILE);
			FileWriter writer = new FileWriter(categoryDefinitionFile);
			IOUtils.copy(is, writer, "UTF-8");
			IOUtils.closeQuietly(writer);
			categoryFileURL = categoryDefinitionFile.getAbsolutePath();
		}
	}

	private void cleanupEnvironment() throws IOException {
		File workFolder = new File(buildDirectory, BUNDLES_TOP_FOLDER);
		try {
			FileUtils.deleteDirectory(workFolder);
		}
		catch (IOException ex) {
			log.warn("Cannot cleanup the work folder " + workFolder.getAbsolutePath());
		}
	}
}
