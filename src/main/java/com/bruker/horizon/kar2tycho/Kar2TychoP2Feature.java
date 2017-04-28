package com.bruker.horizon.kar2tycho;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.maven.plugin.logging.Log;
import org.reficio.p2.resolver.maven.Artifact;
import org.reficio.p2.utils.JarUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import lombok.Getter;
import lombok.Setter;

/**
 * The Class Kar2TychoP2Feature.
 */
public class Kar2TychoP2Feature {

	/** The is source feature. */
	@Getter
	@Setter
	private boolean isSourceFeature;

	/** The feature label. */
	@Getter
	@Setter
	private String featureLabel;

	/** The feature id. */
	@Getter
	@Setter
	private String featureId;

	/** The feature version. */
	@Getter
	@Setter
	private String featureVersion;

	/** The feature file. */
	@Getter
	@Setter
	private File featureFile;

	/** The feature document. */
	@Getter
	@Setter
	private Document featureDocument;

	@Setter
	private Log log;

	public final String FEATURE_XML_NAME = "feature.xml";

	@Getter
	@Setter
	private File featureArchive;

	/**
	 * Instantiates a new kar2tycho P2feature.
	 *
	 * @param featureId the feature id
	 * @param featureVersion the feature version
	 * @param isSourceFeature the is source feature
	 */
	public Kar2TychoP2Feature(String featureId, String featureVersion, boolean isSourceFeature) {
		this(featureId, featureId, featureVersion, isSourceFeature);
	}

	/**
	 * Instantiates a new kar2tycho P2feature.
	 *
	 * @param featureId the feature id
	 * @param featureLabel the feature label
	 */
	public Kar2TychoP2Feature(String featureId, String featureLabel, String featureVersion,
			boolean isSourceFeature) {
		this.featureId = featureId;
		this.featureLabel = featureLabel;
		this.featureVersion = featureVersion;
		this.isSourceFeature = isSourceFeature;
	}

	/**
	 * Creates the feature document.
	 */
	public void createFeatureDocument() {

		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		sb.append(System.lineSeparator());
		sb.append("<feature/>");
		InputStream is = new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
		featureDocument = JarUtils.parseXml(is);

		Element featureElement = featureDocument.getDocumentElement();
		featureElement.setAttribute("id", featureId);
		featureElement.setAttribute("label", featureLabel);
		featureElement.setAttribute("version", featureVersion);
	}

	/**
	 * Adds the plugin to the feature.
	 * 
	 * @param version
	 * @param bundleSymbolicName
	 *
	 * @param artifact = groupId.artifactId_version
	 */
	public void addPlugin(Artifact resolvedArtifact, String bundleSymbolicName, String version) {

		// add plugin to the feature.xml
		Element plugin = featureDocument.createElement("plugin");
		featureDocument.getDocumentElement().appendChild(plugin);

		plugin.setAttribute("id", bundleSymbolicName);
		plugin.setAttribute("download-size", "0");
		plugin.setAttribute("install-size", "0");
		plugin.setAttribute("version", version);
		plugin.setAttribute("unpack", "false");
	}

	public void writeXMLFile(String targetDirectory) throws IOException {
		// write feature xmls
		if (isSourceFeature) {
			targetDirectory = targetDirectory + "/source";
		}
		else {
			targetDirectory = targetDirectory + "/feature";
		}
		Path featurePath = Paths.get(targetDirectory, FEATURE_XML_NAME);
		featureFile = new File(featurePath.toString());
		featureFile.getParentFile().getParentFile().mkdirs();
		featureFile.getParentFile().mkdirs();
		featureFile.createNewFile();
		JarUtils.writeXml(featureDocument, featureFile);

		// log feature content
		String content = new String(Files.readAllBytes(featurePath));
		log.debug("created feature file for " + featureId + ":");
		log.debug(content);
	}

	public String getFeatureArchiveName() {
		StringBuilder sb = new StringBuilder();
		sb.append(featureId);
		sb.append("_");
		sb.append(featureVersion);
		sb.append(".jar");
		return sb.toString();
	}

}
