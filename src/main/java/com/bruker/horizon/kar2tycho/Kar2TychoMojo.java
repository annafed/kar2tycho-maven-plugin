package com.bruker.horizon.kar2tycho;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.karaf.features.BundleInfo;
import org.apache.karaf.features.ConfigFileInfo;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.internal.model.Features;
import org.apache.karaf.features.internal.model.JaxbUtil;
import org.apache.karaf.tooling.KarMojo;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.eclipse.sisu.equinox.launching.internal.P2ApplicationLauncher;
import org.reficio.p2.EclipseArtifact;
import org.reficio.p2.P2Artifact;
import org.reficio.p2.utils.JarUtils;
import org.w3c.dom.DOMException;

/**
 * Goal which touches a timestamp file.
 *
 */
@Mojo(name = "kar2tycho", defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
		requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class Kar2TychoMojo extends KarMojo implements Contextualizable {

	@Parameter
	private MavenArchiveConfiguration archive;

	@Component
	private PlexusContainer plexus;

	// copied from karaf-maven-plugin
	@Parameter(defaultValue = "${project.build.directory}/feature/feature.xml")
	private String featuresFile;

	// copied from karaf-maven-plugin
	/**
	 * Ignore the dependency flag on the bundles in the features XML
	 */
	@Parameter(defaultValue = "true")
	private boolean ignoreDependencyFlag;

	@Parameter
	private List<P2Artifact> artifacts;

	/**
	 * Id List of the source artifacts, which should be included to the source feature
	 */
	@Parameter
	private List<String> sources;

	// copied from karaf-maven-plugin
	private static final Pattern mvnPattern =
			Pattern.compile("mvn:([^/ ]+)/([^/ ]+)/([^/ ]*)(/([^/ ]+)(/([^/ ]+))?)?");

	// parameter for reficio plugin

	@Parameter(defaultValue = "${project}", required = true, readonly = true)
	private MavenProject project;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	private MavenSession session;

	@Component
	@Requirement
	private BuildPluginManager pluginManager;

	@Parameter(defaultValue = "${project.build.directory}", required = true)
	private String buildDirectory;

	@Parameter(defaultValue = "${project.build.directory}/repository", required = true)
	private String destinationDirectory;

	@Component
	@Requirement
	private P2ApplicationLauncher launcher;

	/**
	 * Specifies a file containing category definitions.
	 */
	@Parameter(defaultValue = "")
	private String categoryFileURL;

	/**
	 * Optional line of additional arguments passed to the p2 application launcher.
	 */
	@Parameter(defaultValue = "false")
	private boolean pedantic;

	/**
	 * Skip invalid arguments.
	 *
	 * <p>
	 * This flag controls if the processing should be continued on invalid artifacts. It defaults to
	 * false to keep the old behavior (break on invalid artifacts).
	 */
	@Parameter(defaultValue = "false")
	private boolean skipInvalidArtifacts;

	/**
	 * Specifies whether to compress generated update site.
	 */
	@Parameter(defaultValue = "true")
	private boolean compressSite;

	/**
	 * Kill the forked process after a certain number of seconds. If set to 0, wait forever for the
	 * process, never timing out.
	 */
	@Parameter(defaultValue = "0", alias = "p2.timeout")
	private int forkedProcessTimeoutInSeconds;

	/**
	 * Specifies whether snapshot artifact timestamps should be reused This can result in inhomogenous
	 * naming of artifacts
	 */
	@Parameter(defaultValue = "true")
	private boolean reuseSnapshotVersionFromArtifact;

	/**
	 * Specifies additional arguments to p2Launcher, for example -consoleLog -debug -verbose
	 */
	@Parameter(defaultValue = "")
	private String additionalArgs;

	/**
	 * Dependency injection container - used to get some components programatically
	 */
	private PlexusContainer container;

	/**
	 * The current repository/network configuration of Maven.
	 */
	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
	private Object repoSession;

	/**
	 * The project's remote repositories to use for the resolution of project dependencies.
	 */
	@Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true,
			required = true)
	private List<Object> projectRepos;

	/**
	 * A list of artifacts that define eclipse features
	 */
	@Parameter(readonly = true)
	private List<P2Artifact> features;

	/**
	 * A list of Eclipse artifacts that should be downloaded from P2 repositories
	 */
	@Parameter(readonly = true)
	private List<EclipseArtifact> p2;

	@Component
	private MojoExecution execution;

	/**
	 * Maven ProjectHelper.
	 */
	@Component
	private MavenProjectHelper projectHelper;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		getLog().info("kar2tycho execution");

		// resolve karaf feature
		try {
			File featuresFileResolved = resolveFile(featuresFile);
			List<Artifact> mavenArtifacts = readResources(featuresFileResolved);
			convert2P2Artifacts(mavenArtifacts);
		}
		catch (Exception e) {
			getLog().error(e);
		}

		// create feature.xml files
		getLog().info("create feature files");
		StringBuilder sb = new StringBuilder();
		sb.append(project.getGroupId());
		sb.append(".");
		sb.append(project.getArtifactId());
		sb.append(".feature");

		String tychoVersion = JarUtils.replaceSnapshotWithTimestamp(project.getVersion());
		tychoVersion = tychoVersion.replace("-", ".");

		// create xml document for core feature
		Kar2TychoP2Feature kar2TychoP2Feature =
				new Kar2TychoP2Feature(sb.toString(), tychoVersion, false);
		kar2TychoP2Feature.setLog(getLog());
		kar2TychoP2Feature.createFeatureDocument();

		// create xml document for source feature
		sb.append(".source");
		Kar2TychoP2Feature sourceKar2TychoP2Feature =
				new Kar2TychoP2Feature(sb.toString(), tychoVersion, true);
		sourceKar2TychoP2Feature.setLog(getLog());
		sourceKar2TychoP2Feature.createFeatureDocument();

		// resolve artifacts from karaf feature
		getLog().info("execute reficio p2-maven-plugin");
		P2Mojo p2Mojo = new P2Mojo();
		setP2Variables(p2Mojo);
		p2Mojo.setKar2TychoP2Feature(kar2TychoP2Feature);
		p2Mojo.setSourceKar2TychoP2Feature(sourceKar2TychoP2Feature);

		p2Mojo.execute();

		getLog().info(
				"archive the created p2 repository and attach it as install/deploy artifact to this project");
		archiveAndInstallP2Repository();
	}

	private void archiveAndInstallP2Repository() throws MojoExecutionException {
		ZipArchiver zipArchiver = getZipArchiver();

		StringBuilder sb = new StringBuilder();
		sb.append(buildDirectory);
		sb.append("/");
		sb.append(project.getArtifactId());
		sb.append(project.getVersion());
		sb.append("-p2.zip");

		File outputZip = new File(sb.toString());
		zipArchiver.setDestFile(outputZip);

		try {
			zipArchiver.addDirectory(new File(buildDirectory + "/repository"));
			zipArchiver.createArchive();
			projectHelper.attachArtifact(this.project, "zip", "p2", outputZip);

		}
		catch (Exception e) {
			throw new MojoExecutionException("Error creating p2 package", e);
		}
	}

	private void setP2Variables(P2Mojo p2Mojo) {
		p2Mojo.setLog(getLog());
		p2Mojo.setProject(project);
		p2Mojo.setSession(session);
		p2Mojo.setPluginManager(pluginManager);
		p2Mojo.setBuildDirectory(buildDirectory);
		p2Mojo.setDestinationDirectory(destinationDirectory);
		p2Mojo.setLauncher(launcher);
		p2Mojo.setCategoryFileURL(categoryFileURL);
		p2Mojo.setPedantic(pedantic);
		p2Mojo.setSkipInvalidArtifacts(skipInvalidArtifacts);
		p2Mojo.setCompressSite(compressSite);
		p2Mojo.setForkedProcessTimeoutInSeconds(forkedProcessTimeoutInSeconds);
		p2Mojo.setReuseSnapshotVersionFromArtifact(reuseSnapshotVersionFromArtifact);
		p2Mojo.setAdditionalArgs(additionalArgs);
		p2Mojo.setRepoSession(repoSession);
		p2Mojo.setProjectRepos(projectRepos);
		p2Mojo.setFeatures(features);
		p2Mojo.setP2(p2);
		p2Mojo.setArtifacts(artifacts);
		p2Mojo.setContainer(container);
		p2Mojo.setArchive(archive);
		p2Mojo.setPlexus(plexus);
	}

	private void convert2P2Artifacts(List<Artifact> mavenArtifacts) throws DOMException, IOException {
		artifacts = artifacts != null ? artifacts : new ArrayList<P2Artifact>();
		sources = sources != null ? sources : new ArrayList<String>();
		Set<String> ids = new HashSet<String>();
		for (Artifact art : mavenArtifacts) {
			String id = art.getId();
			getLog().debug("add artifact to feature: " + id);
			if (ids.add(id)) {
				P2Artifact p2Artifact = new ConfigurableP2Artifact(
						sources.contains(id) || "sources".equals(art.getClassifier()));
				p2Artifact.setId(id);
				p2Artifact.setTransitive(false);
				artifacts.add(p2Artifact);
			}
		}
	}

	// copied from karaf-maven-plugin
	private static String fromMaven(String name) {
		Matcher m = mvnPattern.matcher(name);
		if (!m.matches()) {
			return name;
		}

		StringBuilder b = new StringBuilder();
		b.append(m.group(1));
		for (int i = 0; i < b.length(); i++) {
			if (b.charAt(i) == '.') {
				b.setCharAt(i, '/');
			}
		}
		b.append("/"); // groupId
		String artifactId = m.group(2);
		String version = m.group(3);
		String extension = m.group(5);
		String classifier = m.group(7);
		b.append(artifactId).append("/"); // artifactId
		b.append(version).append("/"); // version
		b.append(artifactId).append("-").append(version);
		if (present(classifier)) {
			b.append("-").append(classifier);
		}
		if (present(classifier)) {
			b.append(".").append(extension);
		}
		else {
			b.append(".jar");
		}
		return b.toString();
	}

	// copied from karaf-maven-plugin
	private static boolean present(String part) {
		return part != null && !part.isEmpty();
	}

	// copied from karaf-maven-plugin
	private File resolveFile(String file) {
		File fileResolved = null;

		if (isMavenUrl(file)) {
			fileResolved = new File(fromMaven(file));
			try {
				Artifact artifactTemp = resourceToArtifact(file, false);
				if (!fileResolved.exists()) {
					try {
						artifactResolver.resolve(artifactTemp, remoteRepos, localRepo);
						fileResolved = artifactTemp.getFile();
					}
					catch (ArtifactResolutionException e) {
						getLog().error("Artifact was not resolved", e);
					}
					catch (ArtifactNotFoundException e) {
						getLog().error("Artifact was not found", e);
					}
				}
			}
			catch (MojoExecutionException e) {
				getLog().error(e);
			}
		}
		else {
			fileResolved = new File(file);
		}

		return fileResolved;
	}

	// copied from karaf-maven-plugin
	/**
	 * Read bundles and configuration files in the features file.
	 *
	 * @return
	 * @throws MojoExecutionException
	 */
	private List<Artifact> readResources(File featuresFile) throws MojoExecutionException {
		List<Artifact> resources = new ArrayList<Artifact>();
		try {
			Features features = JaxbUtil.unmarshal(featuresFile.toURI().toASCIIString(), false);
			for (Feature feature : features.getFeature()) {
				for (BundleInfo bundle : feature.getBundles()) {
					if (ignoreDependencyFlag || (!ignoreDependencyFlag && !bundle.isDependency())) {
						resources.add(resourceToArtifact(bundle.getLocation(), false));
					}
				}
				for (ConfigFileInfo configFile : feature.getConfigurationFiles()) {
					resources.add(resourceToArtifact(configFile.getLocation(), false));
				}
			}
			return resources;
		}
		catch (MojoExecutionException e) {
			throw e;
		}
		catch (Exception e) {
			throw new MojoExecutionException("Could not interpret features.xml", e);
		}
	}

	public void contextualize(Context context) throws ContextException {
		this.container = (PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY);

	}

	private ZipArchiver getZipArchiver() throws MojoExecutionException {
		try {
			return (ZipArchiver) plexus.lookup(Archiver.ROLE, "zip");
		}
		catch (ComponentLookupException e) {
			throw new MojoExecutionException("Unable to get ZipArchiver", e);
		}
	}

}
