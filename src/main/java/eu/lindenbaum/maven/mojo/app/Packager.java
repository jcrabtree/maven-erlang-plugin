package eu.lindenbaum.maven.mojo.app;

import static eu.lindenbaum.maven.util.FileUtils.APP_FILTER;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.lindenbaum.maven.ErlangMojo;
import eu.lindenbaum.maven.Properties;
import eu.lindenbaum.maven.archiver.TarGzArchiver;
import eu.lindenbaum.maven.erlang.CheckAppResult;
import eu.lindenbaum.maven.erlang.CheckAppScript;
import eu.lindenbaum.maven.erlang.CheckAppUpScript;
import eu.lindenbaum.maven.erlang.GetAttributesScript;
import eu.lindenbaum.maven.erlang.MavenSelf;
import eu.lindenbaum.maven.erlang.Script;
import eu.lindenbaum.maven.util.ErlConstants;
import eu.lindenbaum.maven.util.ErlUtils;
import eu.lindenbaum.maven.util.FileUtils;
import eu.lindenbaum.maven.util.MavenUtils;
import eu.lindenbaum.maven.util.MavenUtils.LogLevel;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;

/**
 * <p>
 * This {@link Mojo} packages all application artifacts into a single
 * {@code .tar.gz} package. This includes {@code .beam} files, the {@code .hrl}
 * include files and private data from the {@code priv} directory.
 * </p>
 * <p>
 * Besides that this {@link Mojo} also copies the erlang application resource
 * file. In order to manage the project over the project pom there is the
 * possibility to let the {@link Mojo} automatically fill in values from the
 * project pom into the {@code .app} and {@code .appup} files. This can be done
 * by using one of the supported variables into the application resource files.
 * Below is a list of supported variables and their substitutions:
 * </p>
 * <ul>
 * <li><code>${ARTIFACT}</code>: the projects artifact id (atom)</li>
 * <li><code>${DESCRIPTION}</code>: the projects description (string)</li>
 * <li><code>${ID}</code>: the projects id (string)</li>
 * <li><code>${VERSION}</code>: the projects version (string)</li>
 * <li><code>${MODULES}</code>: all compiled {@code .beam} files found in the
 * target ebin folder (list)</li>
 * <li><code>${REGISTERED}</code>: all registered names, based on the
 * {@code -registered(Names).} attribute retrieved from the compiled
 * {@code .beam} files (list)</li>
 * <li><code>${APPLICATIONS}</code>: all dependency applications of the project
 * as configured in the project's {@code pom.xml} (string)</li>
 * </ul>
 * <p>
 * The resulting application resource file as well as the application upgrade
 * file will be checked for plausability.
 * </p>
 * 
 * @goal package
 * @phase package
 * @author Olivier Sambourg
 * @author Tobias Schlager <tobias.schlager@lindenbaum.eu>
 * @author Gregory Haskins <ghaskins@novell.com>
 */
public final class Packager extends ErlangMojo {
  @Override
  protected void execute(Log log, Properties p) throws MojoExecutionException, MojoFailureException {
    log.info(MavenUtils.SEPARATOR);
    log.info(" P A C K A G E R");
    log.info(MavenUtils.SEPARATOR);

    String projectVersion = p.project().getVersion();

    Set<Artifact> dependencies = MavenUtils.getErlangDependenciesToPackage(p.project());
    List<File> modules = FileUtils.getFilesRecursive(p.targetEbin(), ErlConstants.BEAM_SUFFIX);
    Script<String> registeredScript = new GetAttributesScript(modules, "registered");
    String registeredNames = MavenSelf.get(p.cookie()).exec(p.node(), registeredScript);

    Map<String, String> replacements = new HashMap<String, String>();
    replacements.put("${ARTIFACT}", "\'" + p.project().getArtifactId() + "\'");
    replacements.put("${DESCRIPTION}", "\"" + p.project().getDescription() + "\"");
    replacements.put("${ID}", "\"" + p.project().getId() + "\"");
    replacements.put("${VERSION}", "\"" + projectVersion + "\"");
    replacements.put("${MODULES}", ErlUtils.toModuleList(modules, "'", "'"));
    replacements.put("${REGISTERED}", registeredNames);
    replacements.put("${APPLICATIONS}", ErlUtils.toArtifactIdListing(dependencies));

    // copy application resource files
    FileUtils.ensureDirectories(p.targetEbin());
    int copied = FileUtils.copyDirectory(p.ebin(), p.targetEbin(), APP_FILTER, replacements);
    log.debug("Copied " + copied + " application resource files");

    File appFile = p.targetAppFile();
    if (!appFile.exists()) {
      log.error(appFile.getName() + " does not exist.");
      log.error("Use 'mvn erlang:setup' to create a default library application .app file");
      throw new MojoFailureException("No .app file found.");
    }

    // parse .app file
    Script<CheckAppResult> appScript = new CheckAppScript(appFile);
    CheckAppResult appResult = MavenSelf.get(p.cookie()).exec(p.node(), appScript);
    if (!appResult.success()) {
      log.error("Failed to consult file");
      MavenUtils.logContent(log, LogLevel.ERROR, appFile);
      throw new MojoFailureException("Failed to consult .app file.");
    }

    checkApplicationName(log, appFile, p.project().getArtifactId(), appResult.getName());
    checkApplicationVersion(log, appFile, projectVersion, appResult.getVersion());
    checkModules(log, modules, appResult.getModules());
    checkApplications(log, dependencies, appResult.getApplications());
    checkStartModule(log, p, appResult);

    File appUpFile = p.targetAppupFile();
    if (!appUpFile.exists()) {
      log.warn(appUpFile.getName() + " does not exist.");
      log.warn("Use 'mvn erlang:setup' to create a default .appup file");
      log.warn("You must edit your .appup file according to http://www.erlang.org/doc/man/appup.html.");
    }
    else {
      // check .appup file
      Script<String> appUpScript = new CheckAppUpScript(appUpFile, projectVersion);
      String error = MavenSelf.get(p.cookie()).exec(p.node(), appUpScript);
      if (error != null) {
        MavenUtils.logMultiLineString(log, LogLevel.ERROR, error);
        MavenUtils.logContent(log, LogLevel.ERROR, appUpFile);
        log.error("You must edit your .appup file according to http://www.erlang.org/doc/man/appup.html.");
        throw new MojoFailureException("Failed to verify .appup file.");
      }
    }

    // create .tar.gz package
    File toFile = p.projectArtifactFile();
    try {
      TarGzArchiver archiver = new TarGzArchiver(p.node(), p.cookie(), toFile);
      archiver.addFile(p.targetProject());
      archiver.createArchive();
      p.project().getArtifact().setFile(toFile);
    }
    catch (IOException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
    log.info("Successfully packaged application:");
    log.info(toFile.toString());
  }

  /**
   * Checks whether the application resource files application name equals the
   * projects artifact id.
   */
  private static void checkApplicationName(Log log, File appFile, String expected, String actual) throws MojoFailureException {
    if (!expected.equals(actual)) {
      log.error("Name mismatch.");
      log.error("Project name is " + expected + " while .app name is " + actual);
      MavenUtils.logContent(log, LogLevel.ERROR, appFile);
      throw new MojoFailureException("Name mismatch " + expected + " != " + actual + ".");
    }
  }

  /**
   * Checks whether the application resource files application version equals
   * the projects version.
   */
  private static void checkApplicationVersion(Log log, File appFile, String expected, String actual) throws MojoFailureException {
    if (!expected.equals(actual)) {
      log.error("Version mismatch.");
      log.error("Project version is " + expected + " while .app version is " + actual);
      MavenUtils.logContent(log, LogLevel.ERROR, appFile);
      throw new MojoFailureException("Version mismatch " + expected + " != " + actual + ".");
    }
  }

  /**
   * Checks the start module configured in the application resource file for
   * existence, implemented application behaviour and the sasl application
   * dependency.
   */
  private static void checkStartModule(Log log, Properties p, CheckAppResult r) throws MojoExecutionException,
                                                                               MojoFailureException {
    String startModule = r.getStartModule();
    if (!"omitted".equals(startModule)) {
      File beamFile = new File(p.targetEbin(), startModule + ErlConstants.BEAM_SUFFIX);
      if (beamFile.isFile()) {
        List<File> list = Arrays.asList(beamFile);
        Script<String> behaviourScript = new GetAttributesScript(list, "behaviour", "behavior");
        String behaviours = MavenSelf.get(p.cookie()).exec(p.node(), behaviourScript);
        if (behaviours.contains("application")) {
          if (!r.getApplications().contains("sasl")) {
            log.error("Application dependency to 'sasl' is missing in .app file.");
            throw new MojoFailureException("Dependency to sasl is missing.");
          }
        }
        else {
          log.error("Configured start module \'" + startModule
                    + "\' does not implement the application behaviour");
          throw new MojoFailureException("Configured start module does not implement the application behaviour.");
        }
      }
      else {
        log.error("Configured start module \'" + startModule + "\' does not exist.");
        throw new MojoFailureException("Configured start module does not exist.");
      }
    }
  }

  /**
   * Checks whether the modules to be packaged are declared in the erlang
   * application file.
   */
  private static void checkModules(Log log, Collection<File> expected, List<String> actual) throws MojoFailureException {
    Set<String> m = new HashSet<String>();
    for (File module : expected) {
      m.add(module.getName().replace(ErlConstants.BEAM_SUFFIX, "").replace(ErlConstants.ERL_SUFFIX, ""));
    }
    if (!m.containsAll(actual) || !actual.containsAll(m)) {
      Set<String> undeclared = new HashSet<String>(m);
      undeclared.removeAll(actual);
      log.error("Undeclared modules (not in .app file): " + undeclared.toString());
      Set<String> unbacked = new HashSet<String>(actual);
      unbacked.removeAll(m);
      log.error("Unbacked modules (no .beam file): " + unbacked.toString());
      throw new MojoFailureException("Module mismatch found, see previous output for details.");
    }
  }

  /**
   * Checks whether all erlang-otp or erlang-std dependecies defined in the
   * project pom are correctly configured as application dependencies in the
   * application resource file.
   */
  private static void checkApplications(Log log, Collection<Artifact> expected, List<String> actual) throws MojoFailureException {
    boolean missingDependencies = false;
    for (Artifact artifact : expected) {
      String artifactId = artifact.getArtifactId();
      if (!actual.contains(artifactId)) {
        log.error("Application dependency to '" + artifactId + "' is missing in .app file.");
        missingDependencies = true;
      }
    }
    if (!actual.containsAll(Arrays.asList("kernel", "stdlib"))) {
      log.error("Vital application dependency to either 'kernel' or 'stdlib' is missing in .app file.");
      missingDependencies = true;
    }
    if (missingDependencies) {
      throw new MojoFailureException("Missing application dependencies.");
    }
  }
}
