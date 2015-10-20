package io.dazraf.vertx.maven;

import io.vertx.core.eventbus.MessageProducer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscription;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class HotDeploy {
  public enum DeployStatus {
    COMPILING,
    DEPLOYING,
    DEPLOYED,
    STOPPED
  }

  private static final Logger logger = LoggerFactory.getLogger(HotDeploy.class);
  private final String verticalClassName;
  private final VerticleDeployer vertxManager = new VerticleDeployer();
  private final MessageProducer<JsonObject> statusProducer = vertxManager.createEventProducer();
  private final AtomicReference<Closeable> currentDeployment = new AtomicReference<>();
  private final List<String> classPaths;
  private final Optional<String> config;
  private final List<String> sourcePaths;
  private final File pomFile;
  private long startTime;
  private String clientHttpService = "http://localhost:8888";

  public static void run(File pomFile, String verticleClassName, List<String> classPaths, Optional<String> config, List<String> sourcePaths
  ) throws Exception {
    logger.info("Running HOTDEPLOY with {}", verticleClassName);
    new HotDeploy(pomFile, verticleClassName, classPaths, config, sourcePaths).run();
  }

  private HotDeploy(File pomFile, String clazzName, List<String> classPaths, Optional<String> config, List<String> sourcePaths) throws Exception {
    this.pomFile = pomFile;
    this.verticalClassName = clazzName;
    this.classPaths = classPaths;
    this.config = config;
    this.sourcePaths = sourcePaths;
  }

  private void run() throws Exception {
    logger.info("Starting up file watchers");
    Observable<Path> fileWatch = getFileWatchObservable();
    Subscription subscription = fileWatch.buffer(1, TimeUnit.SECONDS).subscribe(
      this::onFileChangeDetected,
      this::onError,
      this::onComplete);

    compileAndDeploy();
    waitForNewLine();

    logger.info("shutting down ...");
    sendStatus(DeployStatus.STOPPED);
    subscription.unsubscribe();
    vertxManager.close();
    logger.info("done");
  }

  private Observable<Path> getFileWatchObservable() {
    return Observable.merge(
      sourcePaths.stream().map(path -> {
        try {
          return PathWatcher.create(Paths.get(path));
        } catch (Exception e) {
          logger.error("Error in creating path watcher for path: " + path, e);
          throw new RuntimeException(e);
        }
      }).collect(Collectors.toList()));
  }

  private void waitForNewLine() throws IOException {
    new BufferedReader(new InputStreamReader(System.in)).readLine();
  }

  private void printLastMessage() {
    System.out.println("Press ENTER to finish");
  }

  private void onComplete() {
    logger.info("Stopping...");
  }

  private void onError(Throwable throwable) {
    logger.error("Error during hot deploy", throwable);
  }

  private void onFileChangeDetected(List<Path> paths) {
    if (paths == null || paths.size() > 0) {
      if (paths != null) {
        logger.info("file change detected:");
        paths.stream().forEach(path -> logger.info(path.toString()));
      }
      compileAndDeploy();
    }
  }

  private void compileAndDeploy() {
    markFileDetected();
    sendStatus(DeployStatus.COMPILING);
    Compiler.compile(pomFile);
    loadApp(classPaths);

    markRedeployed();
    printLastMessage();
  }

  private void markFileDetected() {
    this.startTime = System.nanoTime();
  }

  private void markRedeployed() {
    long nanos = System.nanoTime() - startTime;
    logger.info("Compiled and redeployed in {}s", String.format("%1.3f", nanos * 1E-9));
  }

  private void loadApp(List<String> classPaths) {
    // atomic
    currentDeployment.getAndUpdate(c -> {
      sendStatus(DeployStatus.DEPLOYING);
      // if we have a deployment, shut it down
      if (c != null) {
        try {
          logger.info("Shutting down existing deployment");
          c.close();
          logger.info("Deployment shutdown");
        } catch (IOException e) {
          logger.error("Error shutting down existing deployment", e);
          throw new RuntimeException(e);
        }
      }

      // deploy
      try {
        logger.info("Starting deployment");
        Closeable closeable =  vertxManager.deploy(verticalClassName, classPaths, config);
        sendStatus(DeployStatus.DEPLOYED);
        return closeable;
      } catch (Throwable e) {
        logger.error("Error in deployment", e);
        return null;
      }
    });
  }

  private void sendStatus(DeployStatus deployStatus) {
    statusProducer.write(
      new JsonObject()
        .put("status", deployStatus.toString())
        .put("url", clientHttpService));
  }
}
