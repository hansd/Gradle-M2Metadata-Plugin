package org.gradle.plugin.maven;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.*;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.clean.CleanMojo;
import org.apache.maven.project.*;
import org.apache.maven.project.artifact.ProjectArtifact;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency;
import org.gradle.api.internal.artifacts.publish.ArchivePublishArtifact;
import org.gradle.api.internal.project.AbstractProject;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.Jar;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.google.common.collect.ImmutableMap.of;
import static com.google.common.collect.Iterables.*;
import static com.google.common.collect.Multimaps.index;
import static org.gradle.api.artifacts.Dependency.ARCHIVES_CONFIGURATION;


/**
 * Created by IntelliJ IDEA.
 *
 * @author JBaruch
 * @since 03-Aug-2010
 */
@SuppressWarnings({"UnusedDeclaration"})
public class MavenEmbedderPlugin implements Plugin<Project> {

    private static final String MAVEN_COMPILER_PLUGIN_KEY = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final String MAVEN_SOURCE_PLUGIN_KEY = "org.apache.maven.plugins:maven-source-plugin";
    private static final String MAVEN_CLEAN_PLUGIN_KEY = "org.apache.maven.plugins:maven-clean-plugin";
    private static final String SOURCES_CLASSIFIER = "sources";
    private static final String SOURCES_JAR_TASK_NAME = "sourcesJar";
    private static final String SOURCE_LEVEL_COMPILE_PLUGIN_SETTING = "source";
    private static final String TARGET_LEVEL_COMPILE_PLUGIN_SETTING = "target";
    private static final String JAVA_PLUGIN_CONVENTION_NAME = "java";
    private File defaultUserSettingsFile;
    private File defaultGlobalSettingsFile;

    private MavenProject mavenProject;
    private Project project;
    private Settings mavenSettings;
    private DefaultPlexusContainer container;
    private static final String MAVEN_CLEAN_PHASE = "clean";
    private static final String MAVEN_CLEAN_GOAL = "clean";
    private MavenSession session;
    private Iterable<MavenProject> reactorProjects;

    public void apply(Project project) {
        this.project = project;
        defaultUserSettingsFile = new File(new File(System.getProperty("user.home"), ".m2"), "settings.xml");
        defaultGlobalSettingsFile = new File(System.getProperty("maven.home", System.getProperty("user.dir", "")), "conf/settings.xml");
        try {
            project.getLogger().lifecycle("Reading maven project...");
            buildContainer();
            readSettings();
            readMavenProject();
            project.getLogger().lifecycle("Configuring general settings...");
            configureSettings();
            project.getLogger().lifecycle("Applying Gradle plugins according to packaging type...");
            applyGradlePlugins();
            project.getLogger().lifecycle("Applying known Maven plugins...");
            applyMavenPlugins();
            project.getLogger().lifecycle("Applying Maven repositories...");
            addRepositorties();
            project.getLogger().lifecycle("Adding project dependencies...");
            addDependencies();
            project.getLogger().lifecycle("Joining common Maven tasks to build...");
            joinTasks();
        } catch (Exception e) {
            throw new GradleException("failed to read Maven project", e);
        }
    }

    private void joinTasks() {
        joinClean();
    }

    private void joinClean() {
        org.apache.maven.model.Plugin cleanPlugin = mavenProject.getPlugin(MAVEN_CLEAN_PLUGIN_KEY);
        List<PluginExecution> executions = cleanPlugin.getExecutions();
        try {
            PluginExecution cleanExecution = find(executions, new Predicate<PluginExecution>() {
                public boolean apply(PluginExecution input) {
                    return input.getPhase().equals(MAVEN_CLEAN_PHASE);
                }
            });
            Task cleanTask = project.getTasks().findByName("clean");
            if (cleanTask != null) {
                cleanTask.doLast(new MavenMojoRunner(container, CleanMojo.class, cleanPlugin, cleanExecution, MAVEN_CLEAN_GOAL, session));
            }
        } catch (NoSuchElementException ignored) {
        }
    }

    private void applyMavenPlugins() {
        JavaPluginConvention javaConvention = (JavaPluginConvention) project.getConvention().getPlugins().get(JAVA_PLUGIN_CONVENTION_NAME);
        if (javaConvention != null) {
            org.apache.maven.model.Plugin mavenCompilerPlugin = mavenProject.getPlugin(MAVEN_COMPILER_PLUGIN_KEY);
            Xpp3Dom configuration = (Xpp3Dom) mavenCompilerPlugin.getConfiguration();
            Xpp3Dom source = configuration.getChild(SOURCE_LEVEL_COMPILE_PLUGIN_SETTING);
            if (source != null) {
                javaConvention.setSourceCompatibility(source.getValue());

            }
            Xpp3Dom target = configuration.getChild(TARGET_LEVEL_COMPILE_PLUGIN_SETTING);
            if (target != null) {
                javaConvention.setTargetCompatibility(target.getValue());
            }

            org.apache.maven.model.Plugin mavenSourcePlugin = mavenProject.getPlugin(MAVEN_SOURCE_PLUGIN_KEY);
            if (mavenSourcePlugin != null) {
                Jar sourcesJar = project.getTasks().add(SOURCES_JAR_TASK_NAME, Jar.class);
                sourcesJar.setDescription("Generates a  jar archive with all the source classes.");
                sourcesJar.dependsOn(project.getTasksByName(JavaPlugin.COMPILE_JAVA_TASK_NAME, false));
                sourcesJar.setClassifier(SOURCES_CLASSIFIER);
                sourcesJar.from(javaConvention.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME).getAllSource());
                project.getConfigurations().getByName(ARCHIVES_CONFIGURATION).addArtifact(new ArchivePublishArtifact(sourcesJar));
            }
        }
    }

    private void configureSettings() {
        AbstractProject abstractProject = (AbstractProject) project;
        abstractProject.setVersion(mavenProject.getVersion());
        abstractProject.setGroup(mavenProject.getGroupId());
        Artifact projectArtifact = new ProjectArtifact(mavenProject);
        abstractProject.setStatus(projectArtifact.isSnapshot() ? Artifact.SNAPSHOT_VERSION : Project.DEFAULT_STATUS);
    }

    private void readSettings() throws IOException, ComponentLookupException, SettingsBuildingException {
        Properties props = new Properties();
        props.putAll(System.getProperties());
        Properties envVars = CommandLineUtils.getSystemEnvVars();
        for (Map.Entry<Object, Object> objectObjectEntry : envVars.entrySet()) {
            props.setProperty("env." + objectObjectEntry.getKey().toString(), objectObjectEntry.getValue().toString());
        }
        SettingsBuildingRequest request = new DefaultSettingsBuildingRequest();
        request.setGlobalSettingsFile(defaultGlobalSettingsFile);
        request.setUserSettingsFile(defaultUserSettingsFile);
        request.setSystemProperties(props);
        this.mavenSettings = container.lookup(SettingsBuilder.class).build(request).getEffectiveSettings();
    }

    private void addRepositorties() {
        List<Repository> mavenRepositories = mavenProject.getRepositories();
        RepositoryHandler repositoryHandler = project.getRepositories();
        for (Repository mavenRepository : mavenRepositories) {
            repositoryHandler.mavenRepo(of("name", mavenRepository.getId(), "urls", mavenRepository.getUrl()));
        }
    }

    private void applyGradlePlugins() {
//    TODO    project.apply(of("plugin", "maven")); - can't do it because Maven2 dependencies in gradle classloader
        String pluginName = ObjectConverter.packaging2Plugin(mavenProject.getPackaging());
        if (pluginName != null) {
            project.apply(of("plugin", pluginName));
        }
    }

    private void addDependencies() {
        List<Dependency> dependencies = mavenProject.getDependencies();
        Multimap<String, Dependency> dependenciesByScope = index(dependencies, new Function<Dependency, String>() {
            public String apply(Dependency from) {
                return from.getScope();
            }
        });
        ConfigurationContainer configurations = project.getConfigurations();
        for (String scope : dependenciesByScope.keySet()) {
            org.gradle.api.artifacts.Configuration configuration = configurations.getByName(ObjectConverter.scope2Configuration(scope, mavenProject.getPackaging()));
            Collection<Dependency> scopeDependencies = dependenciesByScope.get(scope);
            for (final Dependency mavenDependency : scopeDependencies) {
                AbstractModuleDependency dependency;
                if (any(reactorProjects, new Predicate<MavenProject>() {
                    public boolean apply(MavenProject input) {
                        return (input.getGroupId().equals(mavenDependency.getGroupId()) &&
                                input.getArtifactId().equals(mavenDependency.getArtifactId()) &&
                                input.getVersion().equals(mavenDependency.getVersion()));
                    }
                })) {
                    // this is a concrete gradle project, it probably has parent in which the plugin is applied in subprojects closure
                    Project parent = project.getParent();
                    Set<Project> allProjects;
                    if (parent != null) {
                        allProjects = parent.getAllprojects();
                    } else { //if not, maybe parent project itself has code and this plugin is applied in allprojects closure
                        allProjects = project.getAllprojects();
                    }
                    Project projectDependency = find(allProjects, new Predicate<Project>() {
                        public boolean apply(Project input) {
                            Splitter splitter = Splitter.on(':');
                            Iterable<String> nameParts = splitter.split(input.getName());
                            return any(nameParts, new Predicate<String>() {
                                public boolean apply(String input) {
                                    return input.equalsIgnoreCase(mavenDependency.getArtifactId());
                                }
                            });
                        }
                    });
                    dependency = new DefaultProjectDependency(projectDependency, project.getGradle().getStartParameter().getProjectDependenciesBuildInstruction());
                } else {
                    dependency = new DefaultExternalModuleDependency(mavenDependency.getGroupId(), mavenDependency.getArtifactId(), mavenDependency.getVersion());
                    List<Exclusion> exclusions = mavenDependency.getExclusions();
                    for (Exclusion exclusion : exclusions) {
                        dependency.exclude(of("group", exclusion.getGroupId(), "module", exclusion.getArtifactId()));
                    }
                }
                configuration.addDependency(dependency);
            }
        }
    }


    private void readMavenProject() throws ComponentLookupException, MavenExecutionRequestPopulationException, ProjectBuildingException {
        ProjectBuilder builder = container.lookup(ProjectBuilder.class);
        MavenExecutionRequest executionRequest = new DefaultMavenExecutionRequest();
        MavenExecutionRequestPopulator populator = container.lookup(MavenExecutionRequestPopulator.class);
        populator.populateFromSettings(executionRequest, mavenSettings);
        populator.populateDefaults(executionRequest);
        ProjectBuildingRequest buildingRequest = executionRequest.getProjectBuildingRequest();
        buildingRequest.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        mavenProject = builder.build(new File(project.getProjectDir(), "pom.xml"), buildingRequest).getProject();
        reactorProjects = transform(builder.build(ImmutableList.of(new File("pom.xml")), true, buildingRequest), new Function<ProjectBuildingResult, MavenProject>() {
            public MavenProject apply(ProjectBuildingResult from) {
                return from.getProject();
            }
        });
        MavenExecutionResult result = new DefaultMavenExecutionResult();
        result.setProject(mavenProject);
        session = new MavenSession(container, executionRequest, result);
        session.setCurrentProject(mavenProject);
    }

    private void buildContainer() throws PlexusContainerException {
        ContainerConfiguration dpcreq = new DefaultContainerConfiguration()
                .setClassWorld(new ClassWorld("plexus.core", this.getClass().getClassLoader()))
                .setName("mavenCore");
        container = new DefaultPlexusContainer(dpcreq);
    }
}
