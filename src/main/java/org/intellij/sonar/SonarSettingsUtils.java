package org.intellij.sonar;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

public class SonarSettingsUtils {

  /*public static SonarSettingsBean getSonarSettingsBeanForFile(VirtualFile virtualFile, Project project) {
    SonarSettingsBean sonarSettingsBean = null;
    if (null != project) {
      if (null != virtualFile) {
        Module module = ModuleUtil.findModuleForFile(virtualFile, project);
        if (null != module) {
//          SonarSettingsComponent component = module.getComponent(SonarSettingsModuleComponent.class);
//          sonarSettingsBean = getSonarSettingsBeanFromSonarComponent(component);
        }
      }
      if (null == sonarSettingsBean) {
        sonarSettingsBean = getSonarSettingsBeanFromProject(project);
      }
    }

    return sonarSettingsBean;
  }*/

 /* public static SonarSettingsBean getSonarSettingsBeanFromSonarComponent(SonarSettingsComponent sonarSettingsComponent) {
    return sonarSettingsComponent.getState();
  }*/

  /*public static SonarSettingsBean getSonarSettingsBeanFromProject(Project project) {
//    SonarSettingsProjectComponent sonarProjectComponent = project.getComponent(SonarSettingsProjectComponent.class);
//    return sonarProjectComponent.getState();
    return null;
  }*/
}
