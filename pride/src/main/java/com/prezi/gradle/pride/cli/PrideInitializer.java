package com.prezi.gradle.pride.cli;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.prezi.gradle.pride.Module;
import com.prezi.gradle.pride.Pride;
import com.prezi.gradle.pride.PrideException;
import com.prezi.gradle.pride.PrideProjectData;
import com.prezi.gradle.pride.RuntimeConfiguration;
import com.prezi.gradle.pride.cli.gradle.GradleConnectorManager;
import com.prezi.gradle.pride.cli.gradle.GradleProjectExecution;
import com.prezi.gradle.pride.model.PrideProjectModel;
import com.prezi.gradle.pride.vcs.VcsManager;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class PrideInitializer {

	private static final Logger logger = LoggerFactory.getLogger(PrideInitializer.class);
	private static final String DO_NOT_MODIFY_WARNING =
			"//\n" +
			"// DO NOT MODIFY -- This file is generated by Pride, and will be\n" +
			"// overwritten whenever the pride itself is changed.\n//\n";
	private final GradleConnectorManager gradleConnectorManager;
	private final boolean verbose;

	public PrideInitializer(GradleConnectorManager gradleConnectorManager, boolean verbose) {
		this.gradleConnectorManager = gradleConnectorManager;
		this.verbose = verbose;
	}

	public Pride create(File prideDirectory, RuntimeConfiguration globalConfig, Configuration prideConfig, VcsManager vcsManager) throws IOException, ConfigurationException {
		logger.info("Initializing {}", prideDirectory);
		FileUtils.forceMkdir(prideDirectory);

		File configDirectory = Pride.getPrideConfigDirectory(prideDirectory);
		FileUtils.deleteDirectory(configDirectory);
		FileUtils.forceMkdir(configDirectory);
		FileUtils.write(Pride.getPrideVersionFile(configDirectory), "0\n");

		// Create config file
		File configFile = Pride.getPrideConfigFile(configDirectory);
		PropertiesConfiguration prideFileConfig = new PropertiesConfiguration(configFile);
		boolean prideConfigModified = false;
		for (String key : Iterators.toArray(prideConfig.getKeys(), String.class)) {
			// Skip modules
			if (key.startsWith("modules.")) {
				continue;
			}
			prideFileConfig.setProperty(key, prideConfig.getProperty(key));
			prideConfigModified = true;
		}
		// Override Gradle details
		if (gradleConnectorManager.setGradleConfiguration(prideFileConfig)) {
			prideConfigModified = true;
		}
		if (prideConfigModified) {
			prideFileConfig.save();
		}

		Pride pride = new Pride(prideDirectory, globalConfig, prideFileConfig, vcsManager);
		reinitialize(pride);
		return pride;
	}

	public void reinitialize(Pride pride) {
		try {
			File buildFile = pride.getGradleBuildFile();
			FileUtils.deleteQuietly(buildFile);
			FileUtils.write(buildFile, DO_NOT_MODIFY_WARNING);
			FileOutputStream buildOut = new FileOutputStream(buildFile, true);
			try {
				IOUtils.copy(PrideInitializer.class.getResourceAsStream("/build.gradle"), buildOut);
			} finally {
				buildOut.close();
			}

			File modelInitFile = File.createTempFile("model-init-", ".gradle");
			Resources.asByteSource(Resources.getResource("model-init.gradle")).copyTo(Files.asByteSink(modelInitFile));
			Map<File, PrideProjectModel> rootProjects = Maps.newLinkedHashMap();
			for (Module module : pride.getModules()) {
				File moduleDirectory = new File(pride.getRootDirectory(), module.getName());
				if (Pride.isValidModuleDirectory(moduleDirectory)) {
					PrideProjectModel rootProject = getRootProjectModel(moduleDirectory, modelInitFile);
					rootProjects.put(moduleDirectory, rootProject);
				}
			}

			createSettingsFile(pride, rootProjects);
			createProjectsFile(pride, rootProjects);
		} catch (Exception ex) {
			throw new PrideException("There was a problem during the initialization of the pride. Fix the errors above, and try again with\n\n\tpride init --force", ex);
		}
	}

	private void createSettingsFile(Pride pride, Map<File, PrideProjectModel> rootProjects) throws IOException {
		File settingsFile = pride.getGradleSettingsFile();
		FileUtils.deleteQuietly(settingsFile);
		FileUtils.write(settingsFile, DO_NOT_MODIFY_WARNING);
		for (Map.Entry<File, PrideProjectModel> entry : rootProjects.entrySet()) {
			File moduleDirectory = entry.getKey();
			PrideProjectModel rootProject = entry.getValue();

			// Merge settings
			String relativePath = pride.getRootDirectory().toURI().relativize(moduleDirectory.toURI()).toString();
			FileUtils.write(settingsFile, "\n// Settings from project in directory /" + relativePath + "\n\n", true);
			// Write the root project
			FileUtils.write(settingsFile, "include \'" + rootProject.getName() + "\'\n", true);
			FileUtils.write(settingsFile, "project(\':" + rootProject.getName() + "\').projectDir = file(\'" + moduleDirectory.getName() + "\')\n", true);
			writeSettingsForChildren(pride.getRootDirectory(), settingsFile, rootProject.getName(), rootProject.getChildren());
		}
	}

	private void writeSettingsForChildren(File prideRootDir, File settingsFile, String rootProjectName, Set<PrideProjectModel> children) throws IOException {
		for (PrideProjectModel child : children) {
			FileUtils.write(settingsFile, "include \'" + rootProjectName + child.getPath() + "\'\n", true);
			String childProjectRelativePath = URI.create(prideRootDir.getCanonicalPath()).relativize(URI.create(child.getProjectDir())).toString();
			FileUtils.write(settingsFile, "project(\':" + rootProjectName + child.getPath() + "\').projectDir = file(\'" + childProjectRelativePath + "\')\n", true);
			writeSettingsForChildren(prideRootDir, settingsFile, rootProjectName, child.getChildren());
		}
	}

	private void createProjectsFile(Pride pride, Map<File, PrideProjectModel> rootProjects) throws IOException {
		File projectsFile = Pride.getPrideProjectsFile(Pride.getPrideConfigDirectory(pride.getRootDirectory()));
		Set<PrideProjectData> projects = Sets.newTreeSet();
		for (PrideProjectModel projectModel : rootProjects.values()) {
			addProjectData("", projectModel, projects);
		}
		Pride.saveProjects(projectsFile, projects);
	}

	private void addProjectData(String parentPath, PrideProjectModel projectModel, Collection<PrideProjectData> projects) {
		String group = projectModel.getGroup();
		String path = parentPath + ":" + projectModel.getName();
		if (group != null) {
			PrideProjectData projectData = new PrideProjectData(group, projectModel.getName(), path);
			logger.debug("Found project {}", projectData);
			projects.add(projectData);
		}
		for (PrideProjectModel child : projectModel.getChildren()) {
			addProjectData(path, child, projects);
		}
	}

	private PrideProjectModel getRootProjectModel(File moduleDirectory, final File modelInitFile) {
		return gradleConnectorManager.executeInProject(moduleDirectory, new GradleProjectExecution<PrideProjectModel, RuntimeException>() {
			@Override
			public PrideProjectModel execute(File moduleDirectory, ProjectConnection connection) {
				try {
					// Load the model for the build
					ModelBuilder<PrideProjectModel> builder = connection.model(PrideProjectModel.class);
					ImmutableList.Builder<String> arguments = ImmutableList.builder();
					if (verbose) {
						arguments.add("--info", "--stacktrace");
					} else {
						arguments.add("-q");
					}

					// Add gradle-pride-model-plugin
					// See https://github.com/prezi/pride/issues/94
					arguments.add("--init-script", modelInitFile.getAbsolutePath());

					// See https://github.com/prezi/pride/issues/91
					arguments.add("--no-search-upward");

					// See https://github.com/prezi/pride/issues/57
					arguments.add("-P", "pride.disable");

					//noinspection ToArrayCallWithZeroLengthArrayArgument
					builder.withArguments(arguments.build().toArray(new String[0]));

					return builder.get();
				} catch (Exception ex) {
					throw new PrideException("Could not parse module in " + moduleDirectory + ": " + ex, ex);
				}
			}
		});
	}
}
