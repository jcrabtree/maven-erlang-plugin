package eu.lindenbaum.maven.util;

import static eu.lindenbaum.maven.util.CollectionUtils.filter;
import static eu.lindenbaum.maven.util.CollectionUtils.map;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.lindenbaum.maven.MavenComponents;
import eu.lindenbaum.maven.PackagingType;
import eu.lindenbaum.maven.mojo.DependencyExtractor;
import eu.lindenbaum.maven.util.CollectionUtils.MapFunction;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;

/**
 * Containing utilities related to maven plugins/projects.
 * 
 * @author Tobias Schlager <tobias.schlager@lindenbaum.eu>
 * @author Olle Törnström <olle.toernstroem@lindenbaum.eu>
 */
public final class MavenUtils {
  public static String SEPARATOR = "------------------------------------------------------------------------";
  public static String FAT_SEPARATOR = "========================================================================";

  /**
   * Corresponds to the log levels of {@link Log}. The mapping is:
   * <ul>
   * <li>{@link #DEBUG} will be logged using {@link Log#debug(CharSequence)}</li>
   * <li>{@link #INFO} will be logged using {@link Log#info(CharSequence)}</li>
   * <li>{@link #WARN} will be logged using {@link Log#warn(CharSequence)}</li>
   * <li>{@link #ERROR} will be logged using {@link Log#error(CharSequence)}</li>
   * </ul>
   */
  public enum LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR;

    /**
     * Creates a {@link LogLevel} from a string.
     * 
     * @param level to convert into a {@link LogLevel} value
     * @return "debug" will result in {@link #DEBUG}, "warn" will result in
     *         {@link #WARN}, "error" will result in {@link #ERROR} else
     *         {@link #INFO} is returned
     */
    public static LogLevel fromString(String level) {
      if ("debug".equals(level)) {
        return DEBUG;
      }
      if ("warn".equals(level)) {
        return WARN;
      }
      if ("error".equals(level)) {
        return ERROR;
      }
      return INFO;
    }
  }

  /**
   * Returns a {@link Set} of available versions for an artifact in the given
   * repositories.
   * 
   * @param artifact to lookup in the repository
   * @param components a bean holding maven specific components
   * @return a non-{@code null} {@link Set} of {@link ArtifactVersion}s
   * @throws MojoExecutionException
   */
  public static Set<ArtifactVersion> getAvailableVersions(Artifact artifact, MavenComponents components) throws MojoExecutionException {
    ArtifactMetadataSource source = components.metadataSource();
    ArtifactRepository local = components.localRepository();
    List<ArtifactRepository> remotes = components.remoteRepositories();
    try {
      @SuppressWarnings("unchecked")
      List<ArtifactVersion> retrieved = source.retrieveAvailableVersions(artifact, local, remotes);
      return new HashSet<ArtifactVersion>(retrieved);
    }
    catch (ArtifactMetadataRetrievalException e) {
      throw new MojoExecutionException("Failed to get available versions for artifact " + artifact + ".", e);
    }
  }

  /**
   * Returns an (existing) file pointing to an {@link Artifact} of an
   * {@link ArtifactRepository}. The artifact will first be resolved.
   * 
   * @param artifact to lookup in the repository
   * @param components a bean holding maven specific components
   * @return an existing file pointing to an artifact
   * @throws MojoExecutionException
   */
  public static File getArtifactFile(Artifact artifact, MavenComponents components) throws MojoExecutionException {
    ArtifactResolver artifactResolver = components.artifactResolver();
    List<ArtifactRepository> remoteRepositories = components.remoteRepositories();
    ArtifactRepository localRepository = components.localRepository();
    try {
      artifactResolver.resolve(artifact, remoteRepositories, localRepository);
    }
    catch (Exception e) {
      throw new MojoExecutionException("Failed to resolve artifact " + artifact + ".", e);
    }
    File file = new File(localRepository.getBasedir(), localRepository.pathOf(artifact));
    if (!file.isFile()) {
      throw new MojoExecutionException("Failed to resolve artifact " + artifact + ".");
    }
    return file;
  }

  /**
   * Returns an {@link Artifact} object of a specific {@link Artifact} with a
   * specific version.
   * 
   * @param from to clone
   * @param version of the returned artifact
   * @param components a bean holding maven specific components
   * @return a new {@link Artifact} with the requested version
   */
  public static Artifact getArtifact(Artifact from, String version, MavenComponents components) {
    ArtifactFactory factory = components.artifactFactory();
    return factory.createBuildArtifact(from.getGroupId(), from.getArtifactId(), version, from.getType());
  }

  /**
   * Returns a {@link VersionRange} from a given string specification.
   * 
   * @param versionSpec to create a {@link VersionRange} from
   * @return a version range object
   * @throws MojoExecutionException if specification is invalid
   */
  public static VersionRange createVersionRange(String versionSpec) throws MojoExecutionException {
    try {
      return VersionRange.createFromVersionSpec(versionSpec);
    }
    catch (InvalidVersionSpecificationException e) {
      throw new MojoExecutionException("Failed to create version range.", e);
    }
  }

  /**
   * Converts a {@link Collection} of {@link Artifact}s into a {@link Set}
   * containing their artifactIds.
   * 
   * @param artifacts to get the artifactIds from
   * @return a non-{@code null} {@link Set} object containing artifactIds.
   */
  public static Set<String> getArtifactIds(Collection<Artifact> artifacts) {
    return new HashSet<String>(map(new MapFunction<Artifact, String>() {
      @Override
      public String apply(Artifact a) {
        return a.getArtifactId();
      }
    }, artifacts));
  }

  /**
   * Returns the directory name for the given {@link Artifact}. This is used by
   * the {@link DependencyExtractor} to construct dependency directory names.
   */
  public static String getArtifactDirectory(Artifact artifact) {
    return artifact.getFile()
                   .getName()
                   .replace("." + artifact.getType(), "")
                   .replace(ErlConstants.TARGZ_SUFFIX, "");
  }

  /**
   * Returns the release name for the given {@link MavenProject}. The release
   * name consists of the project artifacts id and its version.
   * 
   * @param project to retrieve the release name from
   * @return a string containing the release name
   */
  public static String getReleaseName(MavenProject project) {
    return project.getArtifactId() + "-" + project.getVersion();
  }

  /**
   * Returns the transitive erlang artifacts of a project using
   * {@link MavenProject#getArtifacts()} filtered for
   * {@link PackagingType#ERLANG_OTP} and {@link PackagingType#ERLANG_STD}
   * packaged projects. This will return all {@link Artifact} with scopes other
   * than {@code test} and {@code provided}.
   * 
   * @param project to get the dependencies for
   * @return a non-{@code null} {@link Set} of dependency artifacts
   */
  public static Set<Artifact> getErlangReleaseArtifacts(MavenProject project) {
    return new HashSet<Artifact>(filter(new Predicate<Artifact>() {
      @Override
      public boolean pred(Artifact artifact) {
        String scope = artifact.getScope();
        return !Artifact.SCOPE_TEST.equals(scope) && !Artifact.SCOPE_PROVIDED.equals(scope);
      }
    }, getErlangArtifacts(project)));
  }

  /**
   * Returns the direct erlang dependencies of a project using
   * {@link MavenProject#getDependencyArtifacts()} filtered for
   * {@link PackagingType#ERLANG_OTP} and {@link PackagingType#ERLANG_STD}
   * packaged projects. This will return all {@link Artifact} with scopes other
   * than {@code test} and {@code provided}.
   * 
   * @param project to get the dependencies for
   * @return a non-{@code null} {@link Set} of dependency artifacts
   */
  public static Set<Artifact> getErlangDependenciesToPackage(MavenProject project) {
    return new HashSet<Artifact>(filter(new Predicate<Artifact>() {
      @Override
      public boolean pred(Artifact artifact) {
        String scope = artifact.getScope();
        return !Artifact.SCOPE_TEST.equals(scope) && !Artifact.SCOPE_PROVIDED.equals(scope);
      }
    }, getErlangDependencies(project)));
  }

  /**
   * Returns the transitive erlang artifacts of a project using
   * {@link MavenProject#getArtifacts()} filtered for
   * {@link PackagingType#ERLANG_OTP} and {@link PackagingType#ERLANG_STD}
   * packaged projects.
   * 
   * @param project to get the dependencies for
   * @return a non-{@code null} {@link Set} of dependency artifacts
   */
  public static Set<Artifact> getErlangArtifacts(MavenProject project) {
    return new HashSet<Artifact>(filter(new Predicate<Artifact>() {
      @Override
      public boolean pred(Artifact artifact) {
        String type = artifact.getType();
        return PackagingType.ERLANG_OTP.isA(type) || PackagingType.ERLANG_STD.isA(type);
      }
    }, getArtifacts(project)));
  }

  /**
   * Returns the direct erlang dependencies of a project using
   * {@link MavenProject#getDependencyArtifacts()} filtered for
   * {@link PackagingType#ERLANG_OTP} and {@link PackagingType#ERLANG_STD}
   * packaged projects.
   * 
   * @param project to get the dependencies for
   * @return a non-{@code null} {@link Set} of dependency artifacts
   */
  public static Set<Artifact> getErlangDependencies(MavenProject project) {
    return new HashSet<Artifact>(filter(new Predicate<Artifact>() {
      @Override
      public boolean pred(Artifact artifact) {
        String type = artifact.getType();
        return PackagingType.ERLANG_OTP.isA(type) || PackagingType.ERLANG_STD.isA(type);
      }
    }, getDependencies(project)));
  }

  /**
   * Returns the direct non-erlang dependencies of a project using
   * {@link MavenProject#getDependencyArtifacts()}. All erlang artifacts will be
   * filtered out. This will return all {@link Artifact}s with scopes other than
   * {@code test} and {@code provided}.
   * 
   * @param project to get the dependencies for
   * @return a non-{@code null} {@link Set} of dependency artifacts
   */
  public static Set<Artifact> getForeignDependenciesToPackage(MavenProject project) {
    return new HashSet<Artifact>(filter(new Predicate<Artifact>() {
      @Override
      public boolean pred(Artifact artifact) {
        String scope = artifact.getScope();
        return !Artifact.SCOPE_TEST.equals(scope) && !Artifact.SCOPE_PROVIDED.equals(scope);
      }
    }, getForeignDependencies(project)));
  }

  /**
   * Returns the direct non-erlang dependencies of a project using
   * {@link MavenProject#getDependencyArtifacts()}. All erlang artifacts will be
   * filtered out.
   * 
   * @param project to get the dependencies for
   * @return a non-{@code null} {@link Set} of dependency artifacts
   */
  public static Set<Artifact> getForeignDependencies(MavenProject project) {
    return new HashSet<Artifact>(filter(new Predicate<Artifact>() {
      @Override
      public boolean pred(Artifact artifact) {
        PackagingType type = PackagingType.fromString(artifact.getType());
        return type == PackagingType.UNSUPPORTED;
      }
    }, getDependencies(project)));
  }

  /**
   * Returns the direct dependency artifacts of a project using
   * {@link MavenProject#getDependencyArtifacts()}.
   * 
   * @param project to get the dependencies for
   * @return a non-{@code null} {@link Set} of dependency artifacts
   */
  public static Set<Artifact> getDependencies(MavenProject project) {
    @SuppressWarnings("unchecked")
    Set<Artifact> artifacts = project.getDependencyArtifacts();
    return new HashSet<Artifact>(artifacts);
  }

  /**
   * Returns the transitive dependency artifacts of a project using
   * {@link MavenProject#getArtifacts()}.
   * 
   * @param project to get the dependencies for
   * @return a non-{@code null} {@link Set} of dependency artifacts
   */
  public static Set<Artifact> getArtifacts(MavenProject project) {
    @SuppressWarnings("unchecked")
    Set<Artifact> artifacts = project.getArtifacts();
    return new HashSet<Artifact>(artifacts);
  }

  /**
   * Returns a mapping of string replacements for project related values:
   * <ul>
   * <li>"${ARTIFACT}" -> the projects artifact id</li>
   * <li>"${DESCRIPTION}" -> the projects description string</li>
   * <li>"${ID}" -> the project id</li>
   * <li>"${NAME}" -> the projects name</li>
   * <li>"${VERSION}" -> the projects version</li>
   * </ul>
   * 
   * @param project the project object to retrieve values from
   * @param artifactQuote the quote character used for the ${ARTIFACT} mapping
   * @param commonQuote the quote character used all other mappings
   * @return a non-{@code null} {@link Map} of string mappings
   */
  public static Map<String, String> getProjectReplacements(MavenProject project,
                                                           String artifactQuote,
                                                           String commonQuote) {
    Map<String, String> replacements = new HashMap<String, String>();
    replacements.put("${ARTIFACT}", artifactQuote + project.getArtifactId() + artifactQuote);
    replacements.put("${DESCRIPTION}", commonQuote + project.getDescription() + commonQuote);
    replacements.put("${ID}", commonQuote + project.getId() + commonQuote);
    replacements.put("${NAME}", commonQuote + project.getName() + commonQuote);
    replacements.put("${VERSION}", commonQuote + project.getVersion() + commonQuote);
    return replacements;
  }

  /**
   * Logs the absolute path of a file along with its content using a specific
   * logger.
   * 
   * @param log logger to use
   * @param level priority to log the file
   * @param file to read the content from
   * @param linePrefix prefix prepended to each line
   */
  public static void logContent(Log log, LogLevel level, File file, String linePrefix) {
    logMultiLineString(log, level, file.getAbsolutePath() + ":", linePrefix);
    try {
      logMultiLineString(log, level, FileUtils.fileRead(file), linePrefix);
    }
    catch (IOException e) {
      logMultiLineString(log, level, e.getMessage(), linePrefix);
    }
  }

  /**
   * Logs a multi line string containing either unix or windows style line
   * breaks using a specific logger.
   * 
   * @param log logger to use.
   * @param level priority to log the message
   * @param multiLineString to log
   */
  public static void logMultiLineString(Log log, LogLevel level, String multiLineString) {
    logMultiLineString(log, level, multiLineString, "");
  }

  /**
   * Logs a multi line string containing either unix or windows style line
   * breaks using a specific logger.
   * 
   * @param log logger to use.
   * @param level priority to log the message
   * @param multiLineString to log
   * @param linePrefix prefix prepended to each line
   */
  public static void logMultiLineString(Log log, LogLevel level, String multiLineString, String linePrefix) {
    String[] lines = multiLineString.split("\r?\n");
    for (String line : lines) {
      switch (level) {
        case DEBUG:
          log.debug(linePrefix + line);
          break;
        case INFO:
          log.info(linePrefix + line);
          break;
        case WARN:
          log.warn(linePrefix + line);
          break;
        case ERROR:
          log.error(linePrefix + line);
          break;
      }
    }
  }

  /**
   * Logs the content of the given {@link Collection} using a specific logger.
   * Each entry is placed in its own line.
   * 
   * @param log logger to use
   * @param level priority to log the file
   * @param collection to print
   * @param linePrefix prefix prepended to each line
   */
  public static <T> void logCollection(Log log, LogLevel level, Collection<T> collection, String linePrefix) {
    for (T entry : collection) {
      switch (level) {
        case DEBUG:
          log.debug(linePrefix + entry.toString());
          break;
        case INFO:
          log.info(linePrefix + entry.toString());
          break;
        case WARN:
          log.warn(linePrefix + entry.toString());
          break;
        case ERROR:
          log.error(linePrefix + entry.toString());
          break;
      }
    }
  }
}
