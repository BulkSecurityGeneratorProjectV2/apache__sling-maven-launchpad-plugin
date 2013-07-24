/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.maven.projectsupport;

import static org.apache.felix.framework.util.FelixConstants.LOG_LEVEL_PROP;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.felix.framework.Logger;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.PropertyUtils;
import org.apache.sling.launchpad.api.LaunchpadContentProvider;
import org.apache.sling.launchpad.base.impl.Sling;
import org.apache.sling.launchpad.base.shared.Notifiable;
import org.apache.sling.launchpad.base.shared.SharedConstants;
import org.apache.sling.maven.projectsupport.bundlelist.v1_0_0.Bundle;
import org.apache.sling.maven.projectsupport.bundlelist.v1_0_0.BundleList;
import org.apache.sling.maven.projectsupport.bundlelist.v1_0_0.StartLevel;
import org.osgi.framework.BundleException;

public abstract class AbstractLaunchpadStartingMojo extends AbstractUsingBundleListMojo implements Notifiable {

    /** Default log level setting if no set on command line (value is "INFO"). */
    private static final int DEFAULT_LOG_LEVEL = Logger.LOG_INFO;

    /** Mapping between log level numbers and names */
    private static final String[] logLevels = { "FATAL", "ERROR", "WARN", "INFO", "DEBUG" };

    /**
     * The configuration property setting the port on which the HTTP service
     * listens
     */
    private static final String PROP_PORT = "org.osgi.service.http.port";

    /** Return the log level code for the string */
    private static int toLogLevelInt(String level, int defaultLevel) {
        for (int i = 0; i < logLevels.length; i++) {
            if (logLevels[i].equalsIgnoreCase(level)) {
                return i;
            }
        }

        return defaultLevel;
    }

    /**
     * @parameter expression="${http.port}" default-value="8080"
     */
    private int httpPort;

    /**
     * The definition of the package to be included to provide web support for
     * JAR-packaged projects (i.e. pax-web).
     *
     * @parameter
     */
    private ArtifactDefinition jarWebSupport;

    /**
     * @parameter expression="${felix.log.level}"
     */
    private String logLevel;

    /**
     * @parameter expression="${propertiesFile}"
     *            default-value="src/test/config/sling.properties"
     */
    private File propertiesFile;

    /**
     * @parameter expression="${resourceProviderRoot}"
     *            default-value="src/test/resources"
     */
    private File resourceProviderRoot;

    private LaunchpadContentProvider resourceProvider = new LaunchpadContentProvider() {

        public Iterator<String> getChildren(String path) {
            if (path.equals(BUNDLE_PATH_PREFIX)) {
                final Set<String> levels = new HashSet<String>();
                for (final StartLevel level : getInitializedBundleList().getStartLevels()) {
                    // we treat the boot level as level 1
                    if ( level.getStartLevel() == -1 ) {
                        levels.add(BUNDLE_PATH_PREFIX + "/1/");
                    } else {
                        levels.add(BUNDLE_PATH_PREFIX + "/" + level.getLevel() + "/");
                    }
                }
                return levels.iterator();
            } else if (path.equals("resources/corebundles")) {
                List<String> empty = Collections.emptyList();
                return empty.iterator();
            } else if (path.equals(CONFIG_PATH_PREFIX)) {
                if (getConfigDirectory().exists() && getConfigDirectory().isDirectory()) {
                    File[] configFiles = getConfigDirectory().listFiles(new FileFilter() {

                        public boolean accept(File file) {
                            return file.isFile();
                        }
                    });

                    List<String> fileNames = new ArrayList<String>();
                    for (File cfgFile : configFiles) {
                        if (cfgFile.isFile()) {
                            fileNames.add(CONFIG_PATH_PREFIX + "/" + cfgFile.getName());
                        }
                    }

                    return fileNames.iterator();

                } else {
                    List<String> empty = Collections.emptyList();
                    return empty.iterator();
                }
            } else if (path.startsWith(BUNDLE_PATH_PREFIX)) {
                final String startLevelInfo = path.substring(BUNDLE_PATH_PREFIX.length() + 1);
                try {
                    final int startLevel = Integer.parseInt(startLevelInfo);

                    final List<String> bundles = new ArrayList<String>();
                    for (final StartLevel level : getInitializedBundleList().getStartLevels()) {
                        if (level.getStartLevel() == startLevel || (startLevel == 1 && level.getStartLevel() == -1)) {
                            for (final Bundle bundle : level.getBundles()) {
                                final ArtifactDefinition d = new ArtifactDefinition(bundle, startLevel);
                                try {
                                    final Artifact artifact = getArtifact(d);
                                    bundles.add(artifact.getFile().toURI().toURL().toExternalForm());
                                } catch (Exception e) {
                                    getLog().error("Unable to resolve artifact ", e);
                                }
                            }
                        }
                    }
                    return bundles.iterator();

                } catch (NumberFormatException e) {
                    // we ignore this
                }
            } else if (path.equals("resources") ) {
                final Set<String> subDirs = new HashSet<String>();
                subDirs.add(BUNDLE_PATH_PREFIX);
                subDirs.add(CONFIG_PATH_PREFIX);
                subDirs.add("resources/corebundles");
                return subDirs.iterator();
            }

            getLog().warn("un-handlable path " + path);
            return null;
        }

        public URL getResource(String path) {
            if (path.startsWith(CONFIG_PATH_PREFIX)) {
                File configFile = new File(getConfigDirectory(), path.substring(CONFIG_PATH_PREFIX.length() + 1));
                if (configFile.exists()) {
                    try {
                        return configFile.toURI().toURL();
                    } catch (MalformedURLException e) {
                        // ignore this one
                    }
                }
            }

            File resourceFile = new File(resourceProviderRoot, path);
            if (resourceFile.exists()) {
                try {
                    return resourceFile.toURI().toURL();
                } catch (MalformedURLException e) {
                    getLog().error("Unable to create URL for file", e);
                    return null;
                }
            } else {
                URL fromClasspath = getClass().getResource("/" + path);
                if (fromClasspath != null) {
                    return fromClasspath;
                }

                try {
                    return new URL(path);
                } catch (MalformedURLException e) {
                    return null;
                }
            }

        }

        public InputStream getResourceAsStream(String path) {
            URL res = this.getResource(path);
            if (res != null) {
                try {
                    return res.openStream();
                } catch (IOException ioe) {
                    // ignore this one
                }
            }

            // no resource
            return null;

        }
    };

    private Sling sling;

    /**
     * @parameter expression="${sling.home}" default-value="${basedir}/sling"
     */
    private String slingHome;

    /**
     * @parameter default-value="true"
     */
    private boolean forceBundleLoad;

    public void stopped() {
        sling = null;
    }

    public void updated(File updateFile) {
        // clear the reference to the framework
        sling = null;

        if (updateFile != null) {
            getLog().warn("Maven Launchpad Plugin doesn't support updating the framework bundle.");
        }

        getLog().info("Restarting Framework and Sling");

        try {
            executeWithArtifacts();
        } catch (MojoExecutionException e) {
            getLog().error("Unable to restart Framework and Sling", e);
            System.exit(1);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void executeWithArtifacts() throws MojoExecutionException {
        try {
            final Map<String, String> props = new HashMap<String, String>();

            props.put(SharedConstants.SLING_HOME, slingHome);

            // ensure launchpad is set
            props.put(SharedConstants.SLING_LAUNCHPAD, slingHome);

            if (forceBundleLoad) {
                props.put(SharedConstants.FORCE_PACKAGE_BUNDLE_LOADING, "true");
            }

            // set up and configure Felix Logger
            int logLevelNum;
            if (logLevel == null) {
                logLevelNum = DEFAULT_LOG_LEVEL;
            } else {
                logLevelNum = toLogLevelInt(logLevel, DEFAULT_LOG_LEVEL);
            }
            props.put(LOG_LEVEL_PROP, String.valueOf(logLevelNum));
            // Display port number on console, in case HttpService doesn't
            getLog().info("HTTP server port: " + httpPort);
            props.put(PROP_PORT, String.valueOf(httpPort));

            // prevent tons of needless WARN from the framework
            Logger logger = new Logger();
            logger.setLogLevel(Logger.LOG_ERROR);

            if (propertiesFile.exists()) {
                File tmp = null;
                try {
                    tmp = File.createTempFile("sling", "props");
                    mavenFileFilter.copyFile(propertiesFile, tmp, true, project, null, true,
                            System.getProperty("file.encoding"), mavenSession);
                    Properties loadedProps = PropertyUtils.loadPropertyFile(tmp, null);
                    for (Object key : loadedProps.keySet()) {
                        props.put((String) key, (String) loadedProps.get(key));
                    }
                } catch (IOException e) {
                    throw new MojoExecutionException("Unable to create filtered properties file", e);
                } catch (MavenFilteringException e) {
                    throw new MojoExecutionException("Unable to create filtered properties file", e);
                } finally {
                    if (tmp != null) {
                        tmp.delete();
                    }
                }
            }

            sling = startSling(resourceProvider, props, logger);

        } catch (BundleException be) {
            getLog().error("Failed to Start OSGi framework", be);
        }

    }

    protected abstract Sling startSling(LaunchpadContentProvider resourceProvider, Map<String, String> props,
            Logger logger) throws BundleException;

    protected void stopSling() {
        if (sling != null) {
            sling.destroy();
        }
    }

    @Override
    protected void initArtifactDefinitions(Properties dependencies) {
        if (jarWebSupport == null) {
            jarWebSupport = new ArtifactDefinition();
        }
        jarWebSupport.initDefaults(dependencies.getProperty("jarWebSupport"));
    }

    /**
     * Add the JAR Web Support bundle to the bundle list.
     */
    @Override
    protected void initBundleList(BundleList bundleList) {
        bundleList.add(jarWebSupport.toBundle());
    }
}
