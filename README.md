# kar2tycho-maven-plugin

This plugin creates a new p2 repository from a karaf archive.

* based on [p2-maven-plugin](https://github.com/reficio/p2-maven-plugin) and [karaf-maven-plugin](https://github.com/apache/karaf/blob/master/manual/src/main/asciidoc/developer-guide/karaf-maven-plugin.adoc) 
* use with <code> &lt;packaging&gt;kar&lt;/packaging&gt;</code>
* dependencies with "provided" and "test" scope will be not included
* to include additional dependency to p2 repository (which should not be included in the karaf feature) use the <code>&lt;configuration&gt;&lt;artifacts&gt;</code> block (see example)
* to include source artifacts to the p2 source feature use the <code>&lt;configuration&gt;&lt;sources&gt;</code> block (see example)

```xml

	<!-- specify your dependencies here -->
	<dependencies>
		<dependency>
			<groupId>my.group.id</groupId>
			<artifactId>artifact.id</artifactId>
			<version>1.0</version>
		</dependency>
	</dependencies>
	
	<build>
		<plugins>
			<plugin>
				<groupId>org.apache.karaf.tooling</groupId>
				<artifactId>karaf-maven-plugin</artifactId>
				<version>4.1.0<version>
				<configuration>
					<startLevel>81</startLevel>
					<aggregateFeatures>true</aggregateFeatures>
					<includeTransitiveDependency>false</includeTransitiveDependency>
				</configuration>
			</plugin>
			<plugin>
				<groupId>com.bruker.horizon</groupId>
				<artifactId>kar2tycho-maven-plugin</artifactId>
				<version>1.0.0<version>
				<executions>
					<execution>
						<id>convert</id>
						<phase>verify</phase>
						<goals>
							<goal>kar2tycho</goal>
						</goals>
					</execution>
				</executions>
				<configuration>
					<artifacts>
						<!-- The following bundles are needed for the eclipse RCP client, 
								but should not be included into the karaf feature -->
						<!-- groupId:artifactId:version -->
						<artifact>
							<id>com.eclipsesource.jaxrs:jersey-all:2.22.2</id>
							<transitive>false</transitive>
						</artifact>
					</artifacts>
					<sources>
						<!-- specify your source dependencies here -->
						<!-- groupId:artifactId:type:version -->
						<source>my.group.id:artifact.id:jar:1.0</source>
					</sources>
				</configuration>
			</plugin>
		</plugins>
	</build>
```