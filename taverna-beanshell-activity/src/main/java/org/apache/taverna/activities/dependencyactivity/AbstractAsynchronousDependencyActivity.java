/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements. See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.taverna.activities.dependencyactivity;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.WeakHashMap;

import org.apache.taverna.facade.WorkflowInstanceFacade;
import org.apache.taverna.workflowmodel.Dataflow;
import org.apache.taverna.workflowmodel.Processor;
import org.apache.taverna.workflowmodel.processor.activity.AbstractAsynchronousActivity;
import org.apache.taverna.workflowmodel.processor.activity.Activity;
import org.apache.taverna.workflowmodel.processor.activity.NestedDataflow;

import org.apache.log4j.Logger;

import org.apache.taverna.configuration.app.ApplicationConfiguration;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A parent abstract class for activities that require dependency management, such as
 * API Consumer and Beanshell. Defines dependencies on local JAR files
 * and Raven artifacts.
 *
 * @author Alex Nenadic
 * @author Tom Oinn
 * @author Stian Soiland-Reyes
 */
public abstract class AbstractAsynchronousDependencyActivity extends AbstractAsynchronousActivity<JsonNode> {

	private static final String LOCAL_JARS = "Local jars";

	private static Logger logger = Logger.getLogger(AbstractAsynchronousDependencyActivity.class);

	/**
	 * For persisting class loaders across a whole workflow run (when classloader sharing
	 * is set to 'workflow'). The key in the map is the workflow run ID and we are using
	 * a WeakHashMap so we don't keep up references to classloaders of old workflow runs.
	 */
	private static WeakHashMap<String, ClassLoader> workflowClassLoaders =
		new WeakHashMap<String, ClassLoader>();

	/**
	 * System classloader, in case when classloader sharing is set to 'system'.
	 */
	private static ClassLoader systemClassLoader = null;

	/**
	 * Classloader to be used for 'executing' this activity, depending on the activity's
	 * class loader sharing policy.
	 */
	protected ClassLoader classLoader = null;

	/**
	 * The location of the <code>lib</code> directory in TAVERNA_HOME,
	 * where local JAR files the activity depends on should be located.
	 */
	public File libDir;

	/**
	 * Different ways to share a class loader among activities:
	 *
	 * <dl>
	 * <dt>workflow</dt>
	 * <dd>Same classloader for all activities using the <code>workflow</code> classloader sharing policy</dd>
	 * <dt>system</dt>
	 * <dd>System classloader</dd>
	 * </dl>
	 *
	 */
	public static enum ClassLoaderSharing {
	    workflow, system;
	    public static final ClassLoaderSharing DEFAULT = workflow;
	    public static ClassLoaderSharing fromString(String str) {
	        if (str == null || str.isEmpty()) {
	            return DEFAULT;
	        }
	        return valueOf(str.toLowerCase());
	    }
	}

	public AbstractAsynchronousDependencyActivity(ApplicationConfiguration applicationConfiguration) {
		if (applicationConfiguration != null) {
			libDir = applicationConfiguration.getApplicationHomeDir().resolve("lib").toFile();
		}
	}

	/**
	 * Finds or constructs the classloader. The classloader depends on the
	 * current classloader sharing policy as defined by {@link #ClassLoaderSharing}.
	 * <p>
	 * If the classloader sharing is {@link ClassLoaderSharing#workflow}, a
	 * common classloader will be used for the whole workflow for all activities
	 * with the same (i.e. {@link ClassLoaderSharing#workflow}) policy.
	 * The dependencies will be constructed as union of local and artifact dependencies
	 * of all 'workflow' classloader sharing activities at the point of the first
	 * call to {@link #getClassLoader()}.
 	 * <p>
	 * If the classloader sharing is {@link ClassLoaderSharing#system}, the
	 * system classloader will be used. Note that both local and artifact dependencies
	 * configured on the activity will be ignored. Local dependencies can be set by
	 * using <code>-classpath</code> when starting the workbench.
	 * This is useful in combination with JNI based libraries, which would also
	 * require <code>-Djava.library.path</code> and possibly the operating
	 * system's PATH / LD_LIBRARY_PATH / DYLD_LIBRARY_PATH environment variable.
	 * @param classLoaderSharing
	 *
	 * @return A new or existing {@link ClassLoader} according to the
	 *         classloader sharing policy
	 */
	protected ClassLoader findClassLoader(JsonNode json, String workflowRunID) throws RuntimeException{
		ClassLoaderSharing classLoaderSharing;
		if (json.has("classLoaderSharing")) {
			classLoaderSharing = ClassLoaderSharing.fromString(json.get("classLoaderSharing").textValue());
		} else {
			classLoaderSharing = ClassLoaderSharing.workflow;
		}

		if (classLoaderSharing == ClassLoaderSharing.workflow) {
			synchronized (workflowClassLoaders) {
				ClassLoader cl = workflowClassLoaders.get(workflowRunID);
				if (cl == null) {
					cl = makeClassLoader(json, workflowRunID);
					workflowClassLoaders.put(workflowRunID, cl);
				}
				return cl;
			}
		}
		if (classLoaderSharing == ClassLoaderSharing.system) {
//			if (systemClassLoader == null)
//				systemClassLoader = PreLauncher.getInstance().getLaunchingClassLoader();

//			if (systemClassLoader instanceof BootstrapClassLoader){
//				// Add local and artifact dependencies to the classloader
//				updateBootstrapClassLoader(
//						(BootstrapClassLoader) systemClassLoader,
//						configurationBean, workflowRunID);
//				return systemClassLoader;
//			}
//			else{
				// Local dependencies will have to be set with the -classpath option
				// We cannot have artifact dependencies in this case
				String message = "System classloader is not Taverna's BootstrapClassLoader, so local dependencies " +
						"have to defined with -classpath. Artifact dependencies are ignored completely.";
				logger.warn(message);
				return systemClassLoader;
//			}
		}
		String message = "Unknown classloader sharing policy named '"+ classLoaderSharing+ "' for " + this.getClass();
		logger.error(message);
		throw new RuntimeException(message);
	}

	/**
	 * Constructs a classloader capable of finding both local jar and artifact dependencies.
	 * Called when classloader sharing policy is set to 'workflow'.
	 *
	 * @return A {@link ClassLoader} capable of accessing all the dependencies (both local jar and artifact)
	 */
	private ClassLoader makeClassLoader(JsonNode json, String workflowID) {
		// Find all artifact dependencies
//		HashSet<URL> urls = findDependencies(ARTIFACTS, configurationBean, workflowID);

		// Add all local jar dependencies
		HashSet<URL> urls = findDependencies(LOCAL_JARS, json, workflowID);

		// Create the classloader capable of loading both local jar and artifact dependencies
		ClassLoader parent = this.getClass().getClassLoader(); // this will be a LocalArtifactClassLoader

		return new URLClassLoader(urls.toArray(new URL[0]), parent) {

			// For finding native libraries that have to be stored in TAVERNA_HOME/lib
			@Override
			protected String findLibrary(String libname) {
				String filename = System.mapLibraryName(libname);
				File libraryFile = new File(libDir, filename);
				if (libraryFile.isFile()) {
					logger.info("Found library " + libname + ": " + libraryFile.getAbsolutePath());
					return libraryFile.getAbsolutePath();
				}
				return super.findLibrary(libname);
			}
		};
	}

	/**
	 * Adds local or artifact dependencies identified by {@link #findDependencies()} to the
	 * {@link BootstrapClassLoader} system classloader.
	 * Called when classloader sharing policy is set to 'system'.
	 *
	 * @param loader The augmented BootstrapClassLoader system classloader
	 */
//	private void updateBootstrapClassLoader(BootstrapClassLoader loader,
//			DependencyActivityConfigurationBean configurationBean,
//			String workflowRunID) {
//
//		HashSet<URL> depsURLs = new HashSet<URL>();
//		depsURLs.addAll(findDependencies(LOCAL_JARS, configurationBean, workflowRunID));
//		depsURLs.addAll(findDependencies(ARTIFACTS, configurationBean, workflowRunID));
//
//		Set<URL> exists = new HashSet<URL>(Arrays.asList(loader.getURLs()));
//		for (URL url : depsURLs) {
//			if (exists.contains(url)) {
//				continue;
//			}
//			logger.info("Registering with system classloader: " + url);
//			loader.addURL(url);
//			exists.add(url);
//		}
//	}

	/**
	 * Finds either local jar or artifact dependencies' URLs for the given classloader
	 * sharing policy (passed inside configuration bean) and a workflowRunID (used to
	 * retrieve the workflow) that will be added to this activity classloader's list of URLs.
	 */
	private HashSet<URL> findDependencies(String dependencyType, JsonNode json, String workflowRunID) {
		ClassLoaderSharing classLoaderSharing;
		if (json.has("classLoaderSharing")) {
			classLoaderSharing = ClassLoaderSharing.fromString(json.get("classLoaderSharing").textValue());
		} else {
			classLoaderSharing = ClassLoaderSharing.workflow;
		}
 		// Get the WorkflowInstanceFacade which contains the current workflow
		WeakReference<WorkflowInstanceFacade> wfFacadeRef = WorkflowInstanceFacade.workflowRunFacades.get(workflowRunID);
		WorkflowInstanceFacade wfFacade = null;
		if (wfFacadeRef != null) {
			wfFacade = wfFacadeRef.get();
		}
		Dataflow wf = null;
		if (wfFacade != null) {
			wf = wfFacade.getDataflow();
		}

		// Files of dependencies for all activities in the workflow that share the classloading policy
		HashSet<File> dependencies = new HashSet<File>();
		// Urls of all dependencies
		HashSet<URL> dependenciesURLs = new HashSet<URL>();

		if (wf != null){
			// Merge in dependencies from all activities that have the same classloader-sharing
			// as this activity
			for (Processor proc : wf.getProcessors()) {
				// Nested workflow case
				if (!proc.getActivityList().isEmpty() && proc.getActivityList().get(0) instanceof NestedDataflow){
					// Get the nested workflow
					Dataflow nestedWorkflow = ((NestedDataflow) proc.getActivityList().get(0)).getNestedDataflow();
					dependenciesURLs.addAll(findNestedDependencies(dependencyType, json, nestedWorkflow));
				}
				else{ // Not nested - go through all of the processor's activities
					Activity<?> activity = proc.getActivityList().get(0);
					if (activity instanceof AbstractAsynchronousDependencyActivity){
						AbstractAsynchronousDependencyActivity dependencyActivity = (AbstractAsynchronousDependencyActivity) activity;
//							if (dependencyType.equals(LOCAL_JARS)){
								// Collect the files of all found local dependencies
							if (dependencyActivity.getConfiguration().has("localDependency")) {
								for (JsonNode jar : dependencyActivity.getConfiguration().get("localDependency")) {
									try {
										dependencies.add(new File(libDir, jar.textValue()));
									} catch (Exception ex) {
										logger.warn("Invalid URL for " + jar, ex);
										continue;
									}
								}
							}
//							} else if (dependencyType.equals(ARTIFACTS) && this.getClass().getClassLoader() instanceof LocalArtifactClassLoader){
//								LocalArtifactClassLoader cl = (LocalArtifactClassLoader) this.getClass().getClassLoader(); // this class is always loaded with LocalArtifactClassLoader
//								// Get the LocalReposotpry capable of finding artifact jar files
//								LocalRepository rep  = (LocalRepository) cl.getRepository();
//								for (BasicArtifact art : ((DependencyActivityConfigurationBean) activity
//												.getConfiguration())
//												.getArtifactDependencies()){
//									dependencies.add(rep.jarFile(art));
//								}
//							}
					}
				}
			}
		} else { // Just add dependencies for this activity since we can't get hold of the whole workflow
//			if (dependencyType.equals(LOCAL_JARS)){
			if (json.has("localDependency")) {
				for (JsonNode jar : json.get("localDependency")) {
					try {
						dependencies.add(new File(libDir, jar.textValue()));
					} catch (Exception ex) {
						logger.warn("Invalid URL for " + jar, ex);
						continue;
					}
				}
			}
//			}
//			else if (dependencyType.equals(ARTIFACTS)){
//				if (this.getClass().getClassLoader() instanceof LocalArtifactClassLoader){ // This should normally be the case
//					LocalArtifactClassLoader cl = (LocalArtifactClassLoader)this.getClass().getClassLoader();
//					LocalRepository rep  = (LocalRepository)cl.getRepository();
//					if (rep != null){
//						for (BasicArtifact art : configurationBean.getArtifactDependencies()){
//							dependencies.add(rep.jarFile(art));
//						}
//					}
//				}
//				else{
//					// Tests will not be loaded using the LocalArtifactClassLoader as athey are loaded
//					// outside Raven so there is nothing we can do about this - some tests
//					// with dependencies will probably fail
//				}
//			}
		}

		// Collect the URLs of all found dependencies
		for (File file: dependencies){
			try{
				dependenciesURLs.add(file.toURI().toURL());
			}
			catch(Exception ex){
				logger.warn("Invalid URL for " + file.getAbsolutePath(), ex);
				continue;
			}
		}
		return dependenciesURLs;
	}

	/**
	 * Finds dependencies for a nested workflow.
	 */
	private HashSet<URL> findNestedDependencies(String dependencyType, JsonNode json, Dataflow nestedWorkflow) {
		ClassLoaderSharing classLoaderSharing;
		if (json.has("classLoaderSharing")) {
			classLoaderSharing = ClassLoaderSharing.fromString(json.get("classLoaderSharing").textValue());
		} else {
			classLoaderSharing = ClassLoaderSharing.workflow;
		}

		// Files of dependencies for all activities in the nested workflow that share the classloading policy
		HashSet<File> dependencies = new HashSet<File>();
		// Urls of all dependencies
		HashSet<URL> dependenciesURLs = new HashSet<URL>();

		for (Processor proc : nestedWorkflow.getProcessors()) {
			// Another nested workflow
			if (!proc.getActivityList().isEmpty() && proc.getActivityList().get(0) instanceof NestedDataflow){
				// Get the nested workflow
				Dataflow nestedNestedWorkflow = ((NestedDataflow) proc.getActivityList().get(0)).getNestedDataflow();
				dependenciesURLs.addAll(findNestedDependencies(dependencyType, json, nestedNestedWorkflow));
			}
			else{ // Not nested - go through all of the processor's activities
				Activity<?> activity = proc.getActivityList().get(0);
				if (activity instanceof AbstractAsynchronousDependencyActivity){
					AbstractAsynchronousDependencyActivity dependencyActivity = (AbstractAsynchronousDependencyActivity) activity;
//						if (dependencyType.equals(LOCAL_JARS)){
							// Collect the files of all found local dependencies
							if (dependencyActivity.getConfiguration().has("localDependency")) {
								for (JsonNode jar : dependencyActivity.getConfiguration().get("localDependency")) {
									try {
										dependencies.add(new File(libDir, jar.textValue()));
									} catch (Exception ex) {
										logger.warn("Invalid URL for " + jar, ex);
										continue;
									}
								}
							}
//						} else if (dependencyType.equals(ARTIFACTS) && this.getClass().getClassLoader() instanceof LocalArtifactClassLoader){
//							LocalArtifactClassLoader cl = (LocalArtifactClassLoader) this.getClass().getClassLoader(); // this class is always loaded with LocalArtifactClassLoader
//							LocalRepository rep  = (LocalRepository) cl.getRepository();
//							for (BasicArtifact art : ((DependencyActivityConfigurationBean) activity
//											.getConfiguration())
//											.getArtifactDependencies()){
//								dependencies.add(rep.jarFile(art));
//							}
//						}
				}
			}
		}

		// Collect the URLs of all found dependencies
		for (File file: dependencies){
			try{
				dependenciesURLs.add(file.toURI().toURL());
			}
			catch(Exception ex){
				logger.warn("Invalid URL for " + file.getAbsolutePath(), ex);
				continue;
			}
		}
		return dependenciesURLs;
	}

	/**
	 * File filter.
	 */
	public static class FileExtFilter implements FilenameFilter {

		String ext = null;

		public FileExtFilter(String ext) {
			this.ext = ext;
		}

		public boolean accept(File dir, String name) {
			return name.endsWith(ext);
		}
	}

	/**
	 * @param classLoader the classLoader to set
	 */
	public void setClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	/**
	 * @return the classLoader
	 */
	public ClassLoader getClassLoader() {
		return classLoader;
	}
}


