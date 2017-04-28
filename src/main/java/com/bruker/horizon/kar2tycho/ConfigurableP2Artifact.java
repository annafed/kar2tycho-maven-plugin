package com.bruker.horizon.kar2tycho;

import org.reficio.p2.P2Artifact;

/**
 * This class enhance the @org.reficio.p2.P2Artifact class to get its source flag changeable
 */
public class ConfigurableP2Artifact extends P2Artifact {

	private boolean shouldIncludeSources = false;

	public ConfigurableP2Artifact(boolean shouldIncludeSources) {
		this.shouldIncludeSources = shouldIncludeSources;
	}

	@Override
	public boolean shouldIncludeSources() {
		return shouldIncludeSources;
	}

}
