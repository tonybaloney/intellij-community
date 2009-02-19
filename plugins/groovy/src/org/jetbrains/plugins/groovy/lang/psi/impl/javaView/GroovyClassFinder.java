/*
 * Copyright 2000-2007 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.lang.psi.impl.javaView;

import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFileBase;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author ven
 */
public class GroovyClassFinder implements ProjectComponent, PsiElementFinder {
  private final Project myProject;

  public GroovyClassFinder(Project project) {
    myProject = project;
  }

  @Nullable
  public PsiClass findClass(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
    return GroovyPsiManager.getInstance(myProject).getNamesCache().getClassByFQName(qualifiedName, scope);
  }

  @NotNull
  public PsiClass[] findClasses(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
    return GroovyPsiManager.getInstance(myProject).getNamesCache().getClassesByFQName(qualifiedName, scope);
  }

  @Nullable
  public PsiPackage findPackage(@NotNull String qualifiedName) {
    return null;
  }

  @NotNull
  public PsiPackage[] getSubPackages(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
    return new PsiPackage[0];
  }

  @NotNull
  public PsiClass[] getClasses(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
    List<PsiClass> result = new ArrayList<PsiClass>();
    for (final PsiDirectory dir : psiPackage.getDirectories(scope)) {
      for (final PsiFile file : dir.getFiles()) {
        if (file instanceof GroovyFileBase) {
          result.addAll(Arrays.asList(((GroovyFileBase) file).getTypeDefinitions()));
        }
      }
    }

    return result.toArray(new PsiClass[result.size()]);
  }

  public void projectOpened() {
  }

  public void projectClosed() {
  }

  @NonNls
  @NotNull
  public String getComponentName() {
    return "Groovy class finder";
  }

  public void initComponent() {
  }

  public void disposeComponent() {
  }
}
