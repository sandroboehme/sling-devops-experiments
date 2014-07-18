package org.apache.sling.devops.orchestrator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.crankstart.api.CrankstartConstants;
import org.apache.sling.devops.Instance;
import org.apache.sling.devops.orchestrator.git.GitFileMonitor;
import org.apache.sling.devops.orchestrator.git.LocalGitFileMonitor;
import org.apache.sling.devops.orchestrator.git.RemoteGitFileMonitor;
import org.apache.sling.settings.SlingSettingsService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;

@Component(immediate=true)
@Service
public class DefaultOrchestrator implements Orchestrator {

	private static final Logger logger = LoggerFactory.getLogger(DefaultOrchestrator.class);

	private static final SimpleDateFormat LOG_APPENDER_DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss.SSS");

	public static final String DEVOPS_DIR = "devops";
	public static final String GIT_WORKING_COPY_DIR = DEVOPS_DIR + "/repo";

	/* Properties without default values */

	@Property(label = "Path to the monitored Git repository")
	public static final String GIT_REPO_PATH_PROP = "sling.devops.git.repo";

	@Property(label = "Path to the monitored file within the Git repository")
	public static final String GIT_REPO_FILE_PATH_PROP = "sling.devops.git.file";

	@Property(label = "ZooKeeper connection string")
	public static final String ZK_CONNECTION_STRING_PROP = "sling.devops.zookeeper.connString";

	@Property(label = "Password for sudo command")
	public static final String SUDO_PASSWORD_PROP = "sudo.password";

	/* Properties with default values */

	public static final int GIT_PERIOD_DEFAULT = 1;
	public static final String GIT_PERIOD_UNIT_DEFAULT = "MINUTES";
	public static final int N_DEFAULT = 2;

	@Property(label = "Period for Git repository polling", intValue = GIT_PERIOD_DEFAULT)
	public static final String GIT_PERIOD_PROP = "sling.devops.git.period";

	@Property(label = "Period unit for Git repository polling", value = GIT_PERIOD_UNIT_DEFAULT)
	public static final String GIT_PERIOD_UNIT_PROP = "sling.devops.git.period.unit";

	@Property(label = "N, the number of Minions running a config must be available before it is transitioned to", intValue = N_DEFAULT)
	public static final String N_PROP = "sling.devops.orchestrator.n";

	@Reference
	private SlingSettingsService slingSettingsService;
	
	@Reference
	private ConfigTransitioner configTransitioner;

	private File devopsDirectory;
	private int n;
	private InstanceMonitor instanceMonitor;
	private InstanceManager instanceManager;
	private GitFileMonitor gitFileMonitor;
	private MinionController minionController;
	private String activeConfig = "";
	private String targetConfig = "";
	private final Set<String> configsUnderTest = new HashSet<>();
	private final Set<String> configsStarted = new HashSet<>();
	private ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

	@Activate
	public void onActivate(final ComponentContext componentContext) throws GitAPIException, IOException, InterruptedException {
		final BundleContext bundleContext = componentContext.getBundleContext();

		final Dictionary<?, ?> properties = componentContext.getProperties();
		this.n = PropertiesUtil.toInteger(properties.get(N_PROP), N_DEFAULT);
		this.instanceManager = new InstanceManager();

		// Create devops directory
		this.devopsDirectory = new File(this.slingSettingsService.getAbsolutePathWithinSlingHome(DEVOPS_DIR));
		if (!this.devopsDirectory.exists()) this.devopsDirectory.mkdir();

		// Setup minion controller
		final String crankstartJar = bundleContext.getProperty(CrankstartConstants.CRANKSTART_JAR_PATH);
		if (crankstartJar != null) this.minionController = new CrankstartMinionController(crankstartJar);
		else this.minionController = new ManualMinionController();

		// Setup instance listener
		this.instanceMonitor = new ZooKeeperInstanceMonitor(PropertiesUtil.toString(properties.get(ZK_CONNECTION_STRING_PROP), null));
		this.instanceMonitor.addInstanceListener(new InstanceMonitor.InstanceListener() {

			@Override
			public void onInstanceAdded(Instance instance) {
				DefaultOrchestrator.this.instanceManager.addInstance(instance);
				DefaultOrchestrator.this.tryTransition(instance.getConfig());
			}

			@Override
			public void onInstanceChanged(Instance instance) {
				this.onInstanceRemoved(instance.getId());
				this.onInstanceAdded(instance);
			}

			@Override
			public void onInstanceRemoved(String slingId) {
				DefaultOrchestrator.this.instanceManager.removeInstance(slingId);
			}
		});

		// Setup Git monitor
		final String gitRepoPath = PropertiesUtil.toString(properties.get(GIT_REPO_PATH_PROP), null);
		final String gitRepoFilePath = PropertiesUtil.toString(properties.get(GIT_REPO_FILE_PATH_PROP), null);
		final int gitRepoPeriod = PropertiesUtil.toInteger(properties.get(GIT_PERIOD_PROP), GIT_PERIOD_DEFAULT);
		final TimeUnit gitRepoTimeUnit = TimeUnit.valueOf(
				PropertiesUtil.toString(properties.get(GIT_PERIOD_UNIT_PROP), GIT_PERIOD_UNIT_DEFAULT));
		if (gitRepoPath.contains("://")) { // assume remote
			this.gitFileMonitor = new RemoteGitFileMonitor(
					gitRepoPath,
					this.slingSettingsService.getAbsolutePathWithinSlingHome(GIT_WORKING_COPY_DIR),
					gitRepoFilePath,
					gitRepoPeriod,
					gitRepoTimeUnit
					);
		} else {
			this.gitFileMonitor = new LocalGitFileMonitor(
					gitRepoPath,
					gitRepoFilePath,
					gitRepoPeriod,
					gitRepoTimeUnit
					);
		}
		this.gitFileMonitor.addListener(new GitFileMonitor.GitFileListener() {
			@Override
			public synchronized void onModified(long time, ByteBuffer content) {
				final String config = "C" + time / 1000;
				if (DefaultOrchestrator.this.isConfigOutdated(config)) {
					logger.info("Config {} is outdated, ignored.", config);
				} else { // not outdated, so new target
					final File configFile = DefaultOrchestrator.this.getConfigFile(config);
					try (final FileChannel fileChannel = new FileOutputStream(configFile, false).getChannel()) {
						fileChannel.write(content);
						DefaultOrchestrator.this.targetConfig = config;
						DefaultOrchestrator.this.tryTransition(config);
					} catch (IOException e) {
						logger.error("Could not write config file.", e);
					}
				}
			}
		});

		// Configure log appender
		final Dictionary<String, Object> logAppenderProperties = new Hashtable<>();
		logAppenderProperties.put("loggers", new String[]{
				this.getClass().getName(),
				this.instanceMonitor.getClass().getName(),
				this.gitFileMonitor.getClass().getSuperclass().getName(),
				this.minionController.getClass().getName(),
				this.configTransitioner.getClass().getName()
				});
		bundleContext.registerService(Appender.class.getName(), this.logAppender, logAppenderProperties);

		// Let's roll!
		this.gitFileMonitor.start();
	}

	@Deactivate
	public void onDeactivate() throws Exception {
		this.configTransitioner.close();
		this.minionController.close();
		this.gitFileMonitor.close();
		this.instanceMonitor.close();
	}

	@Override
	public int getN() {
		return this.n;
	}

	@Override
	public String getActiveConfig() {
		return this.activeConfig;
	}

	@Override
	public String getTargetConfig() {
		return this.targetConfig;
	}

	@Override
	public Map<String, Set<String>> getConfigs() {
		return this.instanceManager.getConfigs();
	}

	@Override
	public List<String> getLog() {
		final List<String> list = new LinkedList<>();
		for (final ILoggingEvent e : logAppender.list) list.add(String.format(
				"%s *%s* %s %s",
				LOG_APPENDER_DATE_FORMAT.format(new Date(e.getTimeStamp())),
				e.getLevel(),
				e.getLoggerName().substring(e.getLoggerName().lastIndexOf('.') + 1),
				e.getFormattedMessage()
				));
		return list;
	}

	private File getConfigFile(final String config) {
		return new File(this.devopsDirectory, config + ".crank.txt");
	}

	/**
	 * Tries to transition to the specified config, starting and stopping
	 * Minions as necessary.
	 *
	 * In order for a transition to occur, the following conditions must be met:
	 * <ol>
	 * 	<li>The config must not be outdated.
	 * 	<li>The config must have been tested.
	 * 	<li>The config must not be currently under test.
	 * 	<li>The config must be satisfied.
	 * </ol>
	 *
	 * If condition 1 is not met, no further actions are performed.
	 * If condition 2 is not met, one test Minion instance is started.
	 * If condition 3 is not met, the test has succeeded, so additional Minion instances are started.
	 * If condition 4 is not met, we only need to wait.
	 *
	 * @param newConfig config to try to transition to
	 */
	private synchronized void tryTransition(final String newConfig) {
		if (this.isConfigOutdated(newConfig)) { // condition 1
			logger.info("Config {} is outdated, instances ignored.", newConfig);
		} else {
			if (!this.isConfigTested(newConfig)) { // condition 2
				logger.info("Config {} is not yet tested, starting a test Minion...", newConfig);
				try {
					this.minionController.startMinions(
							newConfig,
							this.getConfigFile(newConfig).getAbsolutePath(),
							1
							);
					this.configsUnderTest.add(newConfig);
				} catch (Exception e) {
					logger.error("Could not start a test Minion.", e);
				}
			} else {
				if (this.configsUnderTest.contains(newConfig)) { // condition 3
					logger.info("Config {} tested successfully.", newConfig);
					try {
						this.minionController.startMinions(
								newConfig,
								this.getConfigFile(newConfig).getAbsolutePath(),
								this.n - this.instanceManager.getEndpoints(newConfig).size()
								);
						this.configsUnderTest.remove(newConfig);
						this.configsStarted.add(newConfig);
					} catch (Exception e) {
						logger.error("Could not start Minions.", e);
					}
				} else {
					if (this.isConfigSatisfied(newConfig)) { // condition 4
						logger.info("Config {} satisfied, transitioning...", newConfig);
						try {
							this.configTransitioner.transition(
									newConfig,
									this.instanceManager.getEndpoints(newConfig)
									);
							this.activeConfig = newConfig;

							// Stop all outdated configs
							for (final Iterator<String> it = this.configsStarted.iterator(); it.hasNext(); ) {
								final String config = it.next();
								if (this.isConfigOutdated(config) && !this.getActiveConfig().equals(config)) {
									try {
										this.minionController.stopMinions(config);
										it.remove();
									} catch (Exception e) {
										logger.error("Could not stop Minions.", e);
									}
								}
							}
						} catch (Exception e) {
							// TODO Auto-generated catch block
							logger.error("Transition failed.", e);
						}
					}
				}
			}
		}
	}

	private boolean isConfigOutdated(final String newConfig) {
		return newConfig.compareTo(this.getTargetConfig()) < 0;
	}

	private boolean isConfigSatisfied(final String newConfig) {
		return newConfig.equals(this.getTargetConfig())
				&& this.instanceManager.getEndpoints(newConfig).size() >= this.n;
	}

	private boolean isConfigTested(final String config) {
		return this.instanceManager.getConfigs().containsKey(config);
	}
}
