package org.intellij.sonar.analysis;

import com.google.common.base.*;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import org.intellij.sonar.configuration.WorkingDirs;
import org.intellij.sonar.console.SonarConsole;
import org.intellij.sonar.console.StreamGobbler;
import org.intellij.sonar.index.IssuesByFileIndexer;
import org.intellij.sonar.index.SonarIssue;
import org.intellij.sonar.persistence.*;
import org.intellij.sonar.sonarreport.data.Component;
import org.intellij.sonar.sonarreport.data.SonarReport;
import org.intellij.sonar.util.SettingsUtil;
import org.intellij.sonar.util.TemplateProcessor;
import org.sonar.wsclient.services.Resource;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.intellij.sonar.console.ConsoleLogLevel.ERROR;
import static org.intellij.sonar.console.ConsoleLogLevel.INFO;

public class RunLocalAnalysisScriptTask implements Runnable {
  private final String sourceCode;
  private final String pathToSonarReport;
  private final SonarQubeInspectionContext.EnrichedSettings enrichedSettings;
  private final File workingDir;
  private final SonarConsole sonarConsole;
  private final ImmutableList<PsiFile> psiFiles;

  public static Optional<RunLocalAnalysisScriptTask> from(SonarQubeInspectionContext.EnrichedSettings enrichedSettings, ImmutableList<PsiFile> psiFiles) {
    enrichedSettings.settings = SettingsUtil.process(enrichedSettings.project, enrichedSettings.settings);
    final String scripName = enrichedSettings.settings.getLocalAnalysisScripName();
    final Optional<LocalAnalysisScript> localAnalysisScript = LocalAnalysisScripts.get(scripName);
    if (!localAnalysisScript.isPresent()) return Optional.absent();
    final String sourceCodeTemplate = localAnalysisScript.get().getSourceCode();
    final String serverName = enrichedSettings.settings.getServerName();
    final Optional<SonarServerConfig> serverConfiguration = SonarServers.get(serverName);

    final TemplateProcessor sourceCodeTemplateProcessor = TemplateProcessor.of(sourceCodeTemplate);
    sourceCodeTemplateProcessor
        .withProject(enrichedSettings.project)
        .withModule(enrichedSettings.module);

    String pathToSonarReportTemplate = localAnalysisScript.get().getPathToSonarReport();
    final TemplateProcessor pathToSonarReportTemplateProcessor = TemplateProcessor.of(pathToSonarReportTemplate)
        .withProject(enrichedSettings.project)
        .withModule(enrichedSettings.module);

    if (serverConfiguration.isPresent()) {
      sourceCodeTemplateProcessor.withSonarServerConfiguration(serverConfiguration.get());
      pathToSonarReportTemplateProcessor.withSonarServerConfiguration(serverConfiguration.get());
    }

    File workingDir = WorkingDirs.computeFrom(enrichedSettings);

    sourceCodeTemplateProcessor.withWorkingDir(workingDir);
    pathToSonarReportTemplateProcessor.withWorkingDir(workingDir);

    final String sourceCode = sourceCodeTemplateProcessor.process();
    final String pathToSonarReport = pathToSonarReportTemplateProcessor.process();

    return Optional.of(new RunLocalAnalysisScriptTask(
        enrichedSettings, sourceCode, pathToSonarReport, workingDir,
        psiFiles));
  }

  public RunLocalAnalysisScriptTask(SonarQubeInspectionContext.EnrichedSettings enrichedSettings, String sourceCode, String pathToSonarReport, File workingDir, ImmutableList<PsiFile> psiFiles) {
    this.enrichedSettings = enrichedSettings;
    this.sourceCode = sourceCode;
    this.pathToSonarReport = pathToSonarReport;
    this.workingDir = workingDir;
    this.psiFiles = psiFiles;
    this.sonarConsole = SonarConsole.get(enrichedSettings.project);
  }

  public void run() {

    ProgressManager.getInstance().getProgressIndicator().setText(this.workingDir.getName());
    ProgressManager.getInstance().getProgressIndicator().setText2(this.sourceCode);
    sonarConsole.info("working dir: " + this.workingDir.getPath());
    sonarConsole.info("run: " + this.sourceCode);

    final long startTime = System.currentTimeMillis();

    final Process process;
    try {
      process = Runtime.getRuntime().exec(this.sourceCode.split("[\\s]+"), null, this.workingDir);
    } catch (IOException e) {
      sonarConsole.error(Throwables.getStackTraceAsString(e));
      return;
    }
    final StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), sonarConsole, ERROR);
    final StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), sonarConsole, INFO);
    errorGobbler.start();
    outputGobbler.start();

    final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    indicator.setIndeterminate(true);

    while (outputGobbler.isAlive()) {
      if (indicator.isCanceled()) {
        process.destroy();
        break;
      }
    }

    try {
      int exitCode = process.exitValue();
      sonarConsole.info(String.format("finished with exit code %s in %d ms", exitCode, System.currentTimeMillis() - startTime));
      if (exitCode != 0) {
        Notifications.Bus.notify(
            new Notification(
                "SonarQube", "SonarQube",
                String.format("Local analysis failed (%d)", exitCode), NotificationType.WARNING), enrichedSettings.project);
      } else {
        readIssuesFromSonarReport();
      }
    } catch (IllegalThreadStateException ignore) {
      // do nothing, if the script execution is aborted
    }

  }

  private void readIssuesFromSonarReport() {
    sonarConsole.info("report: " + this.pathToSonarReport);
    String sonarReportContent;
    try {
      sonarReportContent = Files.toString(new File(pathToSonarReport), Charsets.UTF_8);
    } catch (IOException e) {
      sonarConsole.info(Throwables.getStackTraceAsString(e));
      return;
    }
    final SonarReport sonarReport = SonarReport.fromJson(sonarReportContent);
    final int issuesCount = sonarReport != null && sonarReport.getIssues() != null ? sonarReport.getIssues().size() : 0;
    sonarConsole.info(String.format("Found %d issues in the SonarQube report", issuesCount));

    if (enrichedSettings.settings.getResources().isEmpty()) {
      createIndexFrom(sonarReport, new Resource());
    } else {
      for (Resource resource : enrichedSettings.settings.getResources()) {
        createIndexFrom(sonarReport, resource);
      }
    }

  }

  private void createIndexFrom(SonarReport sonarReport, Resource resource) {
    final Optional<IssuesByFileIndexProjectComponent> indexComponent = IssuesByFileIndexProjectComponent.getInstance(enrichedSettings.project);
    if (!indexComponent.isPresent()) {
      return;
    }

    removeFilesAffectedByReportFromIndex(sonarReport, indexComponent);

    final Map<String, Set<SonarIssue>> index = new IssuesByFileIndexer(psiFiles)
        .withSonarReportIssues(sonarReport.getIssues())
        .create();



    if (!index.isEmpty()) {
      final int issuesCount = FluentIterable.from(index.values()).transformAndConcat(new Function<Set<SonarIssue>, Iterable<SonarIssue>>() {
        @Override
        public Iterable<SonarIssue> apply(Set<SonarIssue> sonarIssues) {
          return sonarIssues;
        }
      }).size();
      sonarConsole.info(String.format("Created index with %d issues from SonarQube report", issuesCount));
      final int newIssuesCount = FluentIterable.from(index.values()).transformAndConcat(new Function<Set<SonarIssue>, Iterable<SonarIssue>>() {
        @Override
        public Iterable<SonarIssue> apply(Set<SonarIssue> sonarIssues) {
          return sonarIssues;
        }
      }).filter(new Predicate<SonarIssue>() {
        @Override
        public boolean apply(SonarIssue sonarIssue) {
          return sonarIssue.getIsNew();
        }
      }).size();
      if (newIssuesCount > 0) sonarConsole.info(String.format("%d issues are new!", newIssuesCount));
      indexComponent.get().getIndex().putAll(index);
    }
  }

  private void removeFilesAffectedByReportFromIndex(SonarReport sonarReport, Optional<IssuesByFileIndexProjectComponent> indexComponent) {
    if (sonarReport.getComponents() != null) {
      for (Component component : sonarReport.getComponents()) {
        final String path = component.getPath();
        if (path != null) {
          final String componentFullPath = new File(workingDir, path).toString();
          indexComponent.get().getIndex().remove(componentFullPath);
        }
      }
    }
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("sourceCode", sourceCode)
        .add("pathToSonarReport", pathToSonarReport)
        .toString();
  }
}
