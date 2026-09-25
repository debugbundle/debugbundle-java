<%@ page contentType="text/plain" import="java.util.logging.Logger,java.util.logging.Level,com.debugbundle.sdk.DebugBundle,com.debugbundle.sdk.DebugBundleConfig" %>
<%
  DebugBundle.init(DebugBundleConfig.builder()
      .projectToken("smoke-token")
      .projectMode("local-only")
      .localEventsDir("/opt/jboss/wildfly/standalone/debugbundle-events")
      .service("wildfly-stack-smoke")
      .environment("smoke")
      .batchSize(1)
      .build());
  Logger logger = Logger.getLogger("stderr");
  DebugBundle.captureJavaUtilLogging(logger);
  try {
    logger.log(Level.SEVERE, "com.debugbundle.smoke.SyntheticSmokeException: synthetic WildFly stack");
    for (int index = 0; index < 89; index++) {
      logger.log(Level.SEVERE, "\tat com.debugbundle.smoke.ChartService.render(ChartService.java:" + (100 + index) + ")");
    }
    Thread.sleep(17_000L);
    logger.log(Level.SEVERE, "Caused by: java.lang.IllegalStateException: synthetic cause");
    for (int index = 0; index < 3; index++) {
      logger.log(Level.SEVERE, "\tat com.debugbundle.smoke.ChartRepository.load(ChartRepository.java:" + (200 + index) + ")");
    }
    logger.log(Level.WARNING, "synthetic stack complete");
    Thread.sleep(1_500L);
    DebugBundle.flush().join();
  } finally {
    DebugBundle.shutdown();
  }
%>
stack smoke
