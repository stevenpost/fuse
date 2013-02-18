/*
 * Copyright (C) FuseSource, Inc.
 *   http://fusesource.com
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.fusesource.fabric.git.http;

import org.eclipse.jgit.api.InitCommand;
import org.fusesource.fabric.groups.ChangeListener;
import org.fusesource.fabric.groups.ClusteredSingleton;
import org.fusesource.fabric.groups.Group;
import org.fusesource.fabric.groups.ZooKeeperGroupFactory;
import org.fusesource.fabric.utils.SystemProperties;
import org.fusesource.fabric.zookeeper.IZKClient;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.linkedin.zookeeper.client.LifecycleListener;
import org.osgi.framework.Constants;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.api.Git;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Dictionary;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class GitHttpServerRegistrationHandler implements LifecycleListener, ConfigurationListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(GitHttpServerRegistrationHandler.class);

	private final ClusteredSingleton<GitNode> singleton = new ClusteredSingleton<GitNode>(GitNode.class);
	private final AtomicBoolean started = new AtomicBoolean();
	private IZKClient zookeeper = null;
	private boolean connected = false;
	private final String name = System.getProperty(SystemProperties.KARAF_NAME);

	private Group group;

	private HttpService httpService;
	private GitServlet gitServlet;
	private String port;
	private String realm;
	private String role;

	private ConfigurationAdmin configurationAdmin;

	public GitHttpServerRegistrationHandler() {
	}


	public void init() {
	}

	public void destroy() {
		unregister();
		try {
			if (httpService != null) {
				httpService.unregister("/git");
			}
		} catch (Exception ex) {
			LOGGER.warn("Http service returned error on servlet unregister. Possibly the service has already been stopped");
		}
	}

	public synchronized void bindHttpService(HttpService httpService) {
		this.httpService = httpService;
		this.port = getPortFromConfig();
		register();
	}

	public synchronized void unbindHttpService(HttpService httpService) {
		unregister();
		this.httpService = null;
	}

	public synchronized void bindZooKeeper(IZKClient zookeeper) {
		this.zookeeper = zookeeper;
		if (zookeeper != null) {
			zookeeper.registerListener(this);
		}
		if (group == null) {
			group = ZooKeeperGroupFactory.create(zookeeper, ZkPath.GIT.getPath());
			singleton.start(group);
		}
	}

	public synchronized void unbindZooKeeper(IZKClient zookeeper) {
		if (zookeeper != null) {
			zookeeper.removeListener(this);
		}
		this.connected = false;
		this.zookeeper = null;
		this.singleton.stop();
		this.group = null;
	}


	@Override
	public void onConnected() {
		connected = true;
		register();
	}


	@Override
	public void onDisconnected() {
		connected = false;
	}

	public synchronized void register() {
		unregister();
		try {
			if (connected && httpService != null && group != null) {
				singleton.join(createState());
				singleton.add(new ChangeListener() {

					@Override
					public void changed() {
						if (singleton.isMaster()) {
							if (started.compareAndSet(false, true)) {
								LOGGER.info("Git server {}  is now the master, starting the context.", name);
								try {
									HttpContext base = httpService.createDefaultHttpContext();
									HttpContext secure = new SecureHttpContext(base, realm, role);
									String basePath = System.getProperty("karaf.home") + File.separator + "fabric" + File.separator + "git" + File.separator;
									String fabricGitPath = basePath + "fabric";
									File fabricRoot = new File(fabricGitPath);
									if (!fabricRoot.exists() && !fabricRoot.mkdirs()) {
										throw new FileNotFoundException("Could not found git root:" + basePath);
									}
									InitCommand init = Git.init();
									init.setDirectory(fabricRoot);
									init.call();

									Dictionary initParams = new Properties();
									initParams.put("base-path", basePath);
									initParams.put("repository-root", basePath);
									initParams.put("export-all", "true");
									httpService.registerServlet("/git", gitServlet, initParams, secure);
									// Update the state of the master since he is now running.
									singleton.update(createState());
								} catch (Exception e) {
									group.close();
								}
							}
						} else {
							if (started.compareAndSet(true, false)) {
								LOGGER.info("Camel context {} is now a slave, stopping the context.", name);
								try {
									if (httpService != null) {
										httpService.unregister("/git");
									}
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
						}
					}

					@Override
					public void connected() {
						changed();
					}

					@Override
					public void disconnected() {
						changed();
					}
				});
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to register git server.", e);
		}
	}

	public synchronized void unregister() {
		try {
			singleton.leave();
		} catch (Exception e) {
			//noop
		}

		try {
			if (group != null) {
				group.close();
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to remove git server from registry.", e);
		}
	}

	@Override
	public void configurationEvent(ConfigurationEvent event) {
		if (event.getPid().equals("org.ops4j.pax.web") && event.getType() == ConfigurationEvent.CM_UPDATED) {
			this.port = getPortFromConfig();
			register();
		}
	}

	GitNode createState() {
		String fabricRepoUrl = "http://${zk:" + name + "/ip}:" + getPortSafe() + "/git/fabric/";
		GitNode state = new GitNode();
		state.setId(name);
		state.setUrl(fabricRepoUrl);
		return state;
	}

	public String getPortFromConfig() {
		String port = "8181";
		try {
			Configuration[] configurations = configurationAdmin.listConfigurations("(" + Constants.SERVICE_PID + "=org.ops4j.pax.web)");
			if (configurations != null && configurations.length > 0) {
				Configuration configuration = configurations[0];
				Dictionary properties = configuration.getProperties();
				if (properties != null && properties.get("org.osgi.service.http.port") != null) {
					port = String.valueOf(properties.get("org.osgi.service.http.port"));
				}
			}
		} catch (Exception e) {
			//noop
		}
		return port;
	}

	private int getPortSafe() {
		int port = 8181;
		try {
			port = Integer.parseInt(getPort());
		} catch (NumberFormatException ex) {
			//noop
		}
		return port;
	}

	public String getPort() {
		return port;
	}

	public String getRealm() {
		return realm;
	}

	public void setRealm(String realm) {
		this.realm = realm;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public GitServlet getGitServlet() {
		return gitServlet;
	}

	public void setGitServlet(GitServlet gitServlet) {
		this.gitServlet = gitServlet;
	}

	public ConfigurationAdmin getConfigurationAdmin() {
		return configurationAdmin;
	}

	public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
		this.configurationAdmin = configurationAdmin;
	}
}
