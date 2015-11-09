package io.dazraf.vertx.maven.compiler;

import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class represents the primary interaction with the Maven runtime to build the project
 */
public class Compiler {
  private static final Logger logger = LoggerFactory.getLogger(Compiler.class);
  private static final Pattern ERROR_PATTERN = Pattern.compile("\\[ERROR\\] [^:]+:\\[\\d+,\\d+\\].*");
  private static final Pattern DEPENDENCY_RESOLUTION_PATTERN = Pattern.compile("^\\[INFO\\].*:compile:(.*)$");
  private static final List<String> GOALS = Collections.singletonList("dependency:resolve compile");
  private final Properties compilerProperties = new Properties();

  public Compiler() {
    compilerProperties.setProperty("outputAbsoluteArtifactFilename", "true");
  }


  /**
   * Compile the maven project, returning the list of classpath paths as reported by maven
   *
   * @param project
   * @return
   * @throws Exception
   */
  public CompileResult compile(MavenProject project) throws Exception {

    List<String> classPath = new ArrayList<>();
    // precendence to load from the resources folders rather than the build
    project.getResources().stream().map(Resource::getDirectory).forEach(classPath::add);
    classPath.add(project.getBuild().getOutputDirectory());

    Set<String> messages = new HashSet<>();
    InvocationRequest request = setupInvocationRequest(project, classPath, messages);

    return execute(request, messages, classPath);
  }

  private CompileResult execute(InvocationRequest request, Set<String> messages, List<String> classPath) throws Exception {
    try {
      InvocationResult result = new DefaultInvoker().execute(request);

      if (result.getExitCode() != 0) {
        String allMessages = messages.stream().collect(Collectors.joining("\n\n"));
        logger.error("Error with exit code {}", result.getExitCode());
        throw new Exception("Compiler failed with exit code: " + result.getExitCode() + "\n" + allMessages);
      }
      return new CompileResult(classPath);
    } catch (MavenInvocationException e) {
      logger.error("Maven invocation exception:", e);
      throw e;
    }
  }

  private InvocationRequest setupInvocationRequest(MavenProject project, List<String> classPath, Set<String> messages) {
    InvocationRequest request = new DefaultInvocationRequest();
    request.setPomFile(project.getFile());

    request.setOutputHandler(msg -> {
      collectResults(msg, messages, classPath);
    });

    request.setGoals(GOALS);
    request.setProperties(compilerProperties);
    return request;
  }

  private void collectResults(String msg, Set<String> messages, List<String> classPath) {
    Matcher matcher = DEPENDENCY_RESOLUTION_PATTERN.matcher(msg);
    if (matcher.matches()) {
      String dependency = matcher.group(1);
      classPath.add(dependency);
    }
    if (ERROR_PATTERN.matcher(msg).matches()) {
      System.out.println(msg);
      messages.add(msg);
    }
  }

//  private MavenProject createProject(File pomFile) {
//    MavenProject project;
//    // project.getProjectBuildingRequest().
//
//    ModelBuildingRequest req = new DefaultModelBuildingRequest();
//    req.setProcessPlugins(false);
//    req.setPomFile(pomFile);
//    ModelResolver resolver = new ;
//    req.setModelResolver(resolver);
//    new DefaultModelBuilderFactory().newInstance().build(req).getEffectiveModel();
//  }
}