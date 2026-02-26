/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.launcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import com.formdev.flatlaf.util.SystemInfo;
import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.awt.desktop.OpenFilesEvent;
import java.awt.desktop.OpenURIEvent;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.weasis.pref.ConfigData;

public class AppLauncher extends WeasisLauncher implements Singleton.SingletonApp {

  static {
    String home = System.getProperty("user.home", "");
    File bootLog = new File(home + File.separator + ".weasis" + File.separator + "log");
    bootLog.mkdirs();

    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    loggerContext.reset();

    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("%d{dd.MM.yyyy HH:mm:ss.SSS} *%-5level* %msg%n"); // NON-NLS
    encoder.start();

    ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
    consoleAppender.setContext(loggerContext);
    consoleAppender.setName("CONSOLE");
    consoleAppender.setEncoder(encoder);
    consoleAppender.start();

    RollingFileAppender<ILoggingEvent> rollingFileAppender = new RollingFileAppender<>();
    rollingFileAppender.setContext(loggerContext);
    rollingFileAppender.setName("BOOT_ROLLING_FILE"); // NON-NLS
    rollingFileAppender.setEncoder(encoder);
    rollingFileAppender.setFile(bootLog.getPath() + "/boot.log"); // NON-NLS

    FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
    rollingPolicy.setContext(loggerContext);
    rollingPolicy.setParent(rollingFileAppender);
    rollingPolicy.setFileNamePattern(bootLog.getPath() + "/boot.%i.log.zip"); // NON-NLS
    rollingPolicy.setMinIndex(1);
    rollingPolicy.setMaxIndex(3);
    rollingPolicy.start();

    SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy = new SizeBasedTriggeringPolicy<>();
    triggeringPolicy.setMaxFileSize(FileSize.valueOf("3MB")); // NON-NLS
    triggeringPolicy.start();

    rollingFileAppender.setRollingPolicy(rollingPolicy);
    rollingFileAppender.setTriggeringPolicy(triggeringPolicy);
    rollingFileAppender.start();

    ch.qos.logback.classic.Logger logger = loggerContext.getLogger("ROOT");
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    logger.addAppender(consoleAppender);
    logger.addAppender(rollingFileAppender);
  }

  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(AppLauncher.class);

  public AppLauncher(ConfigData configData) {
    super(configData);
  }

  public static void main(String[] argv) throws Exception {
    LOGGER.info(
        "*PERF* JVM start, type:INIT time:{}",
        (System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime()));

    long startTime = System.currentTimeMillis();
    try {
      PlatformCertificateLoader.setupDefaultSSLContext();
      LOGGER.info(
          "*PERF* Loading system trust store, type:INIT time:{}",
          (System.currentTimeMillis() - startTime));
    } catch (Exception e) {
      LOGGER.error("Cannot setup default SSL context by merging with system certificates", e);
    }

    final Type launchType = Type.NATIVE;

    startTime = System.currentTimeMillis();
    ConfigData configData = new ConfigData(argv);
    LOGGER.info(
        "*PERF* Loading config, type:INIT time:{}", (System.currentTimeMillis() - startTime));
    if (!Singleton.invoke(configData)) {
      AppLauncher instance = new AppLauncher(configData);

      // Init handler for open URI and open file events
      Desktop app = Desktop.getDesktop();
      if (app.isSupported(Action.APP_OPEN_URI)) {
        long time = System.currentTimeMillis();
        boolean noArgs = argv.length == 0;
        app.setOpenURIHandler(e -> handleOpenURI(e, time, noArgs, instance));
      }
      if (app.isSupported(Desktop.Action.APP_OPEN_FILE)) {
        app.setOpenFileHandler(e -> handleOpenFile(e, instance));
      }

      Singleton.start(instance, configData.getSourceID());
      instance.launch(launchType);
    }
  }

  private static void handleOpenURI(
      OpenURIEvent e, long time, boolean noArgs, AppLauncher instance) {
    String uri = e.getURI().toString();
    LOGGER.info("Get URI event from OS. URI: {}", uri);
    int index = Utils.getWeasisProtocolIndex(uri);
    List<String> commands;
    if (index < 0) {
      commands = List.of("dicom:get -r \"" + uri + "\""); // NON-NLS
    } else {
      // Decode the zenviewer:// URI and extract commands
      String decoded = java.net.URLDecoder.decode(uri, java.nio.charset.StandardCharsets.UTF_8);
      String[] cmds = decoded.split("\\$");
      commands = new java.util.ArrayList<>();
      for (int i = 1; i < cmds.length; i++) {
        commands.add(cmds[i]);
      }
    }
    // Transform zen:open commands to dicom:get -w format
    commands = transformZenCommands(commands);
    LOGGER.info("Commands from URI: {}", commands);

    if (instance.frameworkLoaded) {
      // Framework already running — execute commands directly
      LOGGER.info("Framework loaded, executing commands immediately");
      instance.executeCommands(commands, null);
    } else {
      // Framework not yet loaded — add commands to configData so they get picked up
      // by the normal launch() flow after OSGI bundles are ready
      LOGGER.info("Framework not loaded yet, queuing commands for startup");
      instance.configData.getArguments().addAll(commands);
    }
  }

  private static String[] getArgsForURI(String uri) {
    if (SystemInfo.isMacOS) {
      return new String[] {"open", "-n", "-b", "com.zenpacs.zenviewer", "--args", uri}; // NON-NLS
    } else if (SystemInfo.isWindows) {
      return new String[] {"cmd", "/c", "start", uri}; // NON-NLS
    } else {
      return new String[] {"xdg-open", uri}; // NON-NLS
    }
  }

  private static void launchProcess(
      String[] args, boolean sameInstance, boolean noArgs, AppLauncher instance) {
    try {
      ProcessBuilder pb = new ProcessBuilder(args);
      pb.start().waitFor(15, TimeUnit.SECONDS);
      if (sameInstance && noArgs) {
        LOGGER.info("Configuration with URI is different, restart Weasis with new configuration");
        instance.shutdownHook();
      }
    } catch (Exception ex) {
      LOGGER.error("Cannot start Weasis from URI", ex);
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Transform zen:open commands to dicom:get -w format.
   * zen:open -t <token> [-s <baseUrl>] -p <id1> -p <id2>...
   * becomes: dicom:get -w "<baseUrl>/v2/patients/weasis-xml?caseIDs=<ids>&token=<token>"
   */
  private static List<String> transformZenCommands(List<String> commands) {
    List<String> result = new java.util.ArrayList<>();
    for (String cmd : commands) {
      if (cmd.startsWith("zen:open ")) {
        String token = null;
        String baseUrl = null;
        List<String> caseIds = new java.util.ArrayList<>();
        String[] parts = cmd.split("\\s+");
        for (int i = 1; i < parts.length; i++) {
          switch (parts[i]) {
            case "-t" -> { if (i + 1 < parts.length) token = parts[++i]; }
            case "-s" -> { if (i + 1 < parts.length) baseUrl = parts[++i]; }
            case "-p" -> { if (i + 1 < parts.length) caseIds.add(parts[++i]); }
            default -> LOGGER.debug("Unknown zen:open flag: {}", parts[i]);
          }
        }
        if (token != null && !caseIds.isEmpty()) {
          if (baseUrl == null) {
            baseUrl =
                System.getProperty(
                    "zenpacs.api.base.url", "https://api.zenpacs.com.tr/api"); // NON-NLS
          }
          String ids = String.join(",", caseIds);
          String transformed =
              "dicom:get -w \""
                  + baseUrl
                  + "/v2/patients/weasis-xml?caseIDs="
                  + ids
                  + "&token="
                  + token
                  + "\""; // NON-NLS
          LOGGER.info("Transformed zen:open to: {}", transformed);
          result.add(transformed);
          continue;
        }
        LOGGER.warn("Invalid zen:open command (missing token or caseIds): {}", cmd);
      }
      result.add(cmd);
    }
    return result;
  }

  private static void handleOpenFile(OpenFilesEvent e, AppLauncher instance) {
    List<String> files =
        e.getFiles().stream()
            .map(f -> "dicom:get -l \"" + f.getPath() + "\"") // NON-NLS
            .toList();
    LOGGER.info("Get open file event from OS. Files: {}", files);
    instance.executeCommands(files, null);
  }

  @Override
  public void newActivation(List<String> arguments) {
    waitWhenStarted();
    if (mTracker != null) {
      executeCommands(arguments, null);
    }
  }

  private void waitWhenStarted() {
    synchronized (this) {
      int loop = 0;
      boolean runLoop = true;
      while (runLoop && !frameworkLoaded) {
        try {
          TimeUnit.MILLISECONDS.sleep(100);
          loop++;
          if (loop > 300) { // Let 30s max to set up Felix framework
            runLoop = false;
          }
        } catch (InterruptedException e) {
          runLoop = false;
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Override
  public boolean canStartNewActivation(Properties prop) {
    boolean sameUser =
        configData.isPropertyValueSimilar(
            ConfigData.P_WEASIS_USER, prop.getProperty(ConfigData.P_WEASIS_USER));
    boolean sameConfig =
        configData.isPropertyValueSimilar(
            ConfigData.P_WEASIS_CONFIG_HASH, prop.getProperty(ConfigData.P_WEASIS_CONFIG_HASH));
    return sameUser && sameConfig;
  }

  @Override
  protected void stopSingletonServer() {
    Singleton.stop();
  }
}
