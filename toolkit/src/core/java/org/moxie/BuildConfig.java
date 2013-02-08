/*
 * Copyright 2012 James Moger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.moxie;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.moxie.Constants.MavenCacheStrategy;
import org.moxie.maxml.MaxmlException;
import org.moxie.maxml.MaxmlMap;
import org.moxie.utils.Base64;
import org.moxie.utils.StringUtils;


/**
 * Represents the effective build configuration (build.moxie, settings.moxie).
 */
public class BuildConfig {

	private final Set<Proxy> proxies;
	private final Set<Repository> repositories;
	private final Map<String, Dependency> aliases;
	private final ToolkitConfig toolkitConfig;
	private final ToolkitConfig projectConfig;
	
	private final File moxieRoot;
	private final File projectConfigFile;
	private final File projectDirectory;
	private boolean verbose;
	
	public BuildConfig(File configFile, File basedir) throws MaxmlException, IOException {
		this.projectConfigFile = configFile;
		if (basedir == null) {
			this.projectDirectory = configFile.getAbsoluteFile().getParentFile();
		} else {
			this.projectDirectory = basedir;
		}
		
		// allow specifying Moxie root folder
		File root = new File(System.getProperty("user.home") + "/.moxie");
		if (System.getProperty(Toolkit.MX_ROOT) != null) {
			String value = System.getProperty(Toolkit.MX_ROOT);
			if (!StringUtils.isEmpty(value)) {
				root = new File(value);
			}
		}
		root.mkdirs();
		this.moxieRoot = root;
		
		this.toolkitConfig = new ToolkitConfig(new File(moxieRoot, Toolkit.MOXIE_SETTINGS), projectDirectory, Toolkit.MOXIE_SETTINGS);
		this.projectConfig = new ToolkitConfig(configFile, projectDirectory, Toolkit.MOXIE_DEFAULTS);
		
		this.proxies = new LinkedHashSet<Proxy>();
		this.repositories = new LinkedHashSet<Repository>();
		this.aliases = new HashMap<String, Dependency>();

		determineProxies();
		determineRepositories();
		determineAliases();
	}
	
	@Override
	public int hashCode() {
		return 11 + projectConfigFile.hashCode();
	}
	
	@Override
	public boolean equals(Object o) {
		if (o instanceof BuildConfig) {
			return projectConfigFile.equals(((BuildConfig) o).projectConfigFile);
		}
		return false;
	}
	
	public boolean isColor() {
		String mxColor = System.getenv("MX_COLOR");
		mxColor = System.getProperty(Toolkit.MX_COLOR, mxColor);
		if (StringUtils.isEmpty(mxColor)) {
			// use Moxie apply setting
			return toolkitConfig.apply(Toolkit.APPLY_COLOR) || projectConfig.apply(Toolkit.APPLY_COLOR);
		} else {
			// use system property to determine color
			return Boolean.parseBoolean(mxColor);
		}
	}
	
	public boolean isDebug() {
		String mxDebug = System.getenv("MX_DEBUG");
		mxDebug = System.getProperty(Toolkit.MX_DEBUG, mxDebug);
		if (StringUtils.isEmpty(mxDebug)) {
			// use Moxie apply setting
			return toolkitConfig.apply(Toolkit.APPLY_DEBUG) || projectConfig.apply(Toolkit.APPLY_DEBUG);
		} else {
			// use system property to determine debug
			return Boolean.parseBoolean(mxDebug);
		}
	}

	public boolean isVerbose() {
		return verbose;
	}
	
	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}
	
	public boolean isFailFastOnArtifactResolution() {
		return projectConfig.failFastOnArtifactResolution;
	}
	
	public MavenCacheStrategy getMavenCacheStrategy() {
		if (projectConfig.mavenCacheStrategy != null) {
			return projectConfig.mavenCacheStrategy;
		}
		if (toolkitConfig.mavenCacheStrategy != null) {
			return toolkitConfig.mavenCacheStrategy;
		}
		return MavenCacheStrategy.IGNORE;
	}
	
	private void determineProxies() {		
		proxies.addAll(projectConfig.getActiveProxies());
		proxies.addAll(toolkitConfig.getActiveProxies());
		
		// add M2 defined proxies last since they can only define host exclusions
		File m2Settings = new File(System.getProperty("user.home"), "/.m2/settings.xml");
		if (m2Settings.exists()) {
			Settings settings = SettingsReader.readSettings(m2Settings);
			proxies.addAll(settings.getActiveProxies());
		}
	}
	
	private void determineRepositories() {
		List<RemoteRepository> registrations = new ArrayList<RemoteRepository>();
		registrations.addAll(projectConfig.registeredRepositories);
		registrations.addAll(toolkitConfig.registeredRepositories);
		
		for (String url : projectConfig.repositories) {
			if (url.equalsIgnoreCase("googlecode")) {
				// GoogleCode-sourced artifact
				repositories.add(new GoogleCode());
				continue;
			}
			boolean added = false;
			for (RemoteRepository definition : registrations) {
				if (definition.url.equalsIgnoreCase(url) || definition.id.equalsIgnoreCase(url)) {
					repositories.add(new Repository(definition));
					added = true;
					break;
				}	
			}
			
			if (!added) {
				// just add url and use hostname as name
				String name = url.substring(url.indexOf("://") + 3);
				if (name.indexOf('/') > -1) {
					name = name.substring(0,  name.lastIndexOf('/'));
				}
				repositories.add(new Repository(name, url));
			}
		}
	}
	
	private void determineAliases() {
		aliases.clear();
		aliases.putAll(toolkitConfig.dependencyAliases);
		aliases.putAll(projectConfig.dependencyAliases);
	}
	
	public File getMoxieRoot() {
		return moxieRoot;
	}
	
	public ToolkitConfig getMoxieConfig() {
		return toolkitConfig;
	}

	public ToolkitConfig getProjectConfig() {
		return projectConfig;
	}

	public Pom getPom() {
		return projectConfig.pom;
	}

	public MaxmlMap getTaskAttributes(String taskname) {
		return projectConfig.tasks.getMap(taskname);
	}

	public Map<String, String> getExternalProperties() {
		return projectConfig.externalProperties;
	}
	
	public Map<String, Dependency> getAliases() {
		return aliases;
	}
	
	public List<SourceDirectory> getSourceDirectories() {
		return projectConfig.sourceDirectories;
	}

	public List<File> getSourceDirectories(Scope scope) {
		List<File> folders = new ArrayList<File>();
		for (SourceDirectory sourceFolder : projectConfig.sourceDirectories) {
			if (scope == null || sourceFolder.scope.equals(scope)) {				
				folders.add(sourceFolder.getSources());
			}
		}
		return folders;
	}
	
	public List<Module> getModules() {
		return projectConfig.modules;
	}
	
	public Collection<Repository> getRepositories() {
		return new ArrayList<Repository>(repositories);
	}

	/**
	 * Return a list of repositories to check for the dependency.  Origin and
	 * repository preference are considered for ordering the repositories.
	 * 
	 * @param dep
	 * @return a list of repositories
	 */
	public Collection<Repository> getRepositories(Dependency dep) {
		if (repositories.size() == 1 || 
				(StringUtils.isEmpty(dep.origin) && StringUtils.isEmpty(dep.preferredRepositoryId))) {
			return repositories;
		}
		
		Repository boostedRepository = null;
		List<Repository> list = new ArrayList<Repository>();
		for (Repository repository : repositories) {
			list.add(repository);
			if (boostedRepository == null) {
				if (!StringUtils.isEmpty(dep.origin)) {
					// origin preference
					if (dep.origin.equalsIgnoreCase(repository.name)) {
						boostedRepository = repository;
					}
				} else if (!StringUtils.isEmpty(dep.preferredRepositoryId)) {
					// repository preference
					if (dep.preferredRepositoryId.equalsIgnoreCase(repository.name)) {
						boostedRepository = repository;
					}
				}
			}
		}
		
		if (boostedRepository != null) {
			// reorder repositories with preferred repository first
			list.remove(boostedRepository);
			list.add(0, boostedRepository);
		}
		return list;
	}

	public java.net.Proxy getProxy(String repositoryId, String url) {
		if (proxies.size() == 0) {
			return java.net.Proxy.NO_PROXY;
		}
		for (Proxy proxy : proxies) {
			if (proxy.active && proxy.matches(repositoryId, url)) {
				return new java.net.Proxy(java.net.Proxy.Type.HTTP, proxy.getSocketAddress());
			}
		}
		return java.net.Proxy.NO_PROXY;
	}
	
	public String getProxyAuthorization(String repositoryId, String url) {
		for (Proxy proxy : proxies) {
			if (proxy.active && proxy.matches(repositoryId, url)) {
				return "Basic " + Base64.encodeBytes((proxy.username + ":" + proxy.password).getBytes());
			}
		}
		return "";
	}
	
	public File getOutputDirectory(Scope scope) {
		if (scope == null) {
			return projectConfig.outputDirectory;
		}
		switch (scope) {
		case test:
			return new File(projectConfig.outputDirectory, "test-classes");
		default:
			return new File(projectConfig.outputDirectory, "classes");
		}
	}
	
	public File getTargetFile() {
		Pom pom = projectConfig.pom;
		String name = pom.groupId + "/" + pom.artifactId + "/" + pom.version + (pom.classifier == null ? "" : ("-" + pom.classifier));
		return new File(projectConfig.targetDirectory, name + ".jar");
	}

	public File getReportsTargetDirectory() {
		return new File(projectConfig.targetDirectory, "reports");
	}

	public File getJavadocTargetDirectory() {
		return new File(projectConfig.targetDirectory, "javadoc");
	}

	public File getTargetDirectory() {
		return projectConfig.targetDirectory;
	}
	
	public File getProjectDirectory() {
		return projectDirectory;
	}
	
	public File getSiteSourceDirectory() {
		for (SourceDirectory sourceFolder : projectConfig.sourceDirectories) {
			if (Scope.site.equals(sourceFolder.scope)) {
				return sourceFolder.getSources();
			}
		}
		// default site sources directory
		return new File(projectDirectory, "src/site");
	}

	public File getSiteTargetDirectory() {
		for (SourceDirectory sourceFolder : projectConfig.sourceDirectories) {
			if (Scope.site.equals(sourceFolder.scope)) {
				return sourceFolder.getOutputDirectory();
			}
		}
		// default site target directory
		return new File(projectConfig.targetDirectory, "site");
	}
	
	@Override
	public String toString() {
		return "Build (" + projectConfig.pom.toString() + ")";
	}
}
