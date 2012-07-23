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
	private final File projectFolder;
	private boolean verbose;
	
	public BuildConfig(File configFile, File basedir) throws MaxmlException, IOException {
		this.projectConfigFile = configFile;
		if (basedir == null) {
			this.projectFolder = configFile.getAbsoluteFile().getParentFile();
		} else {
			this.projectFolder = basedir;
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
		
		this.toolkitConfig = new ToolkitConfig(new File(moxieRoot, Toolkit.MOXIE_SETTINGS), projectFolder, Toolkit.MOXIE_SETTINGS);
		this.projectConfig = new ToolkitConfig(configFile, projectFolder, Toolkit.MOXIE_DEFAULTS);
		
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
		String mxColor = System.getProperty(Toolkit.MX_COLOR, null);
		if (StringUtils.isEmpty(mxColor)) {
			// use Moxie apply setting
			return toolkitConfig.apply(Toolkit.APPLY_COLOR) || projectConfig.apply(Toolkit.APPLY_COLOR);
		} else {
			// use system property to determine color
			return Boolean.parseBoolean(mxColor);
		}
	}
	
	public boolean isDebug() {
		String mxDebug = System.getProperty(Toolkit.MX_DEBUG, null);
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
	
	private void determineProxies() {
		proxies.addAll(projectConfig.getActiveProxies());
		proxies.addAll(toolkitConfig.getActiveProxies());
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
			for (RemoteRepository definition : registrations) {
				if (definition.url.equalsIgnoreCase(url) || definition.id.equalsIgnoreCase(url)) {
					repositories.add(new Repository(definition.id, definition.url));
					break;
				}	
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
	
	public MaxmlMap getMxJavacAttributes() {
		return projectConfig.mxjavac;
	}

	public MaxmlMap getMxJarAttributes() {
		return projectConfig.mxjar;
	}

	public MaxmlMap getMxReportAttributes() {
		return projectConfig.mxreport;
	}
	
	public Map<String, String> getExternalProperties() {
		return projectConfig.externalProperties;
	}
	
	public Map<String, Dependency> getAliases() {
		return aliases;
	}
	
	public List<SourceFolder> getSourceFolders() {
		return projectConfig.sourceFolders;
	}

	public List<File> getSourceFolders(Scope scope) {
		List<File> folders = new ArrayList<File>();
		for (SourceFolder sourceFolder : projectConfig.sourceFolders) {
			if (scope == null || sourceFolder.scope.equals(scope)) {				
				folders.add(sourceFolder.getSources());
			}
		}
		return folders;
	}
	
	public Collection<Repository> getRepositories() {
		return repositories;
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
	
	public File getOutputFolder(Scope scope) {
		if (scope == null) {
			return projectConfig.outputFolder;
		}
		switch (scope) {
		case test:
			return new File(projectConfig.outputFolder, "test-classes");
		default:
			return new File(projectConfig.outputFolder, "classes");
		}
	}
	
	public File getTargetFile() {
		Pom pom = projectConfig.pom;
		String name = pom.groupId + "/" + pom.artifactId + "/" + pom.version + (pom.classifier == null ? "" : ("-" + pom.classifier));
		return new File(projectConfig.targetFolder, name + ".jar");
	}

	public File getReportsFolder() {
		return new File(projectConfig.targetFolder, "reports");
	}

	public File getTargetFolder() {
		return projectConfig.targetFolder;
	}
	
	public File getProjectFolder() {
		return projectFolder;
	}
	
	public File getSiteSourceFolder() {
		for (SourceFolder sourceFolder : projectConfig.sourceFolders) {
			if (Scope.site.equals(sourceFolder.scope)) {
				return sourceFolder.getSources();
			}
		}
		// default site sources folder
		return new File(projectFolder, "src/site");
	}

	public File getSiteOutputFolder() {
		for (SourceFolder sourceFolder : projectConfig.sourceFolders) {
			if (Scope.site.equals(sourceFolder.scope)) {
				return sourceFolder.getOutputFolder();
			}
		}
		// default site output folder
		return new File(getTargetFolder(), "site");
	}
	
	@Override
	public String toString() {
		return "Build (" + projectConfig.pom.toString() + ")";
	}
}