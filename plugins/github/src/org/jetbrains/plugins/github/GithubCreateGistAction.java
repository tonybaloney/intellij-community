/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
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
package org.jetbrains.plugins.github;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import git4idea.Notificator;
import icons.GithubIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.github.ui.GitHubCreateGistDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author oleg
 * @date 9/27/11
 */
public class GithubCreateGistAction extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance(GithubCreateGistAction.class);
  private static final String FAILED_TO_CREATE_GIST = "Can't create Gist";

  protected GithubCreateGistAction() {
    super("Create Gist...", "Create github gist", GithubIcons.Github_icon);
  }

  @Override
  public void update(final AnActionEvent e) {
    final long startTime = System.nanoTime();
    try {
      final Project project = e.getData(PlatformDataKeys.PROJECT);
      if (project == null || project.isDefault()) {
        e.getPresentation().setVisible(false);
        e.getPresentation().setEnabled(false);
        return;
      }
      final Editor editor = e.getData(PlatformDataKeys.EDITOR);
      final VirtualFile file = e.getData(PlatformDataKeys.VIRTUAL_FILE);
      final VirtualFile[] files = e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);

      if (editor == null && file == null && files == null) {
        e.getPresentation().setVisible(false);
        e.getPresentation().setEnabled(false);
        return;
      }

      if (editor != null && editor.getDocument().getTextLength() == 0) {
        e.getPresentation().setVisible(false);
        e.getPresentation().setEnabled(false);
        return;
      }

      e.getPresentation().setVisible(true);
      e.getPresentation().setEnabled(true);
    }
    finally {
      if (LOG.isDebugEnabled()) {
        LOG.debug("GithubCreateGistAction#update finished in: " + (System.nanoTime() - startTime) / 10e6 + "ms");
      }
    }
  }

  @Override
  public void actionPerformed(final AnActionEvent e) {
    final Project project = e.getData(PlatformDataKeys.PROJECT);
    if (project == null || project.isDefault()) {
      return;
    }

    final Editor editor = e.getData(PlatformDataKeys.EDITOR);
    final VirtualFile file = e.getData(PlatformDataKeys.VIRTUAL_FILE);
    final VirtualFile[] files = e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
    if (editor == null && file == null && files == null) {
      return;
    }

    // Ask for description and other params
    final GitHubCreateGistDialog dialog = new GitHubCreateGistDialog(project);
    dialog.show();
    if (!dialog.isOK()) {
      return;
    }

    GithubAuthData auth = null;
    if (!dialog.isAnonymous()) {
      final Ref<GithubAuthData> authDataRef = new Ref<GithubAuthData>();
      ProgressManager.getInstance().run(new Task.Modal(project, "Access to GitHub", true) {
        public void run(@NotNull ProgressIndicator indicator) {
          authDataRef.set(GithubUtil.getValidAuthData(project, indicator));
        }
      });
      if (authDataRef.isNull()) {
        GithubNotifications.showWarning(project, FAILED_TO_CREATE_GIST, "You have to login to GitHub to create non-anonymous Gists.");
        return;
      }
      auth = authDataRef.get();
    }

    createGistWithProgress(project, editor, file, files, auth, dialog.getDescription(), dialog.isPrivate(), new Consumer<String>() {

      @Override
      public void consume(String url) {
        if (url == null) {
          return;
        }

        if (dialog.isOpenInBrowser()) {
          BrowserUtil.launchBrowser(url);
        }
        else {
          GithubNotifications.showInfoURL(project, "Gist Created Successfully", "Your gist url", url);
        }
      }
    });
  }

  private static void createGistWithProgress(@NotNull final Project project,
                                             @Nullable final Editor editor,
                                             @Nullable final VirtualFile file,
                                             @Nullable final VirtualFile[] files,
                                             @Nullable final GithubAuthData auth,
                                             @NotNull final String description,
                                             final boolean aPrivate,
                                             @NotNull final Consumer<String> resultHandler) {
    final Ref<String> url = new Ref<String>();
    new Task.Backgroundable(project, "Creating Gist") {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        List<NamedContent> contents = collectContents(project, editor, file, files);
        String gistUrl = createGist(project, auth, contents, aPrivate, description);
        url.set(gistUrl);
      }

      @Override
      public void onSuccess() {
        resultHandler.consume(url.get());
      }
    }.queue();
  }

  @NotNull
  private static List<NamedContent> collectContents(@NotNull Project project,
                                                    @Nullable Editor editor,
                                                    @Nullable VirtualFile file,
                                                    @Nullable VirtualFile[] files) {
    if (editor != null) {
      NamedContent content = getContentFromEditor(editor, file);
      return content == null ? Collections.<NamedContent>emptyList() : Collections.singletonList(content);
    }
    if (files != null) {
      List<NamedContent> contents = new ArrayList<NamedContent>();
      for (VirtualFile vf : files) {
        contents.addAll(getContentFromFile(vf, project, null));
      }
      return contents;
    }

    if (file != null) {
      return getContentFromFile(file, project, null);
    }

    LOG.error("File, files and editor can't be null all at once!");
    return null;
  }

  @Nullable
  private static String createGist(@NotNull Project project,
                                   @Nullable GithubAuthData auth,
                                   @NotNull List<NamedContent> contents,
                                   boolean isPrivate,
                                   @NotNull String description) {
    if (contents.isEmpty()) {
      GithubNotifications.showWarning(project, "Failed to create gist", "Can't create empty gist");
      return null;
    }
    String requestBody = prepareJsonRequest(description, isPrivate, contents);
    try {
      JsonElement jsonElement;
      if (auth == null) {
        jsonElement = GithubApiUtil.anonymousPostRequest(GithubApiUtil.getApiUrl(), "/gists", requestBody);
      }
      else {
        jsonElement = GithubApiUtil.postRequest(GithubApiUtil.getApiUrl(), auth, "/gists", requestBody);
      }
      if (jsonElement == null) {
        LOG.info("Null JSON response returned by GitHub");
        showError(project, "Failed to create gist", "Empty JSON response returned by GitHub", null, null);
        return null;
      }
      if (!jsonElement.isJsonObject()) {
        LOG.error(String.format("Unexpected JSON result format: %s", jsonElement));
        return null;
      }
      JsonElement htmlUrl = jsonElement.getAsJsonObject().get("html_url");
      if (htmlUrl == null) {
        LOG.info("Invalid JSON response: " + jsonElement);
        showError(project, "Invalid GitHub response", "No html_url property", jsonElement.toString(), null);
        return null;
      }
      return htmlUrl.getAsString();
    }
    catch (IOException e) {
      LOG.info("Exception when creating a Gist", e);
      showError(project, "Failed to create gist", "", null, e);
      return null;
    }
  }

  @Nullable
  private static String readFile(@NotNull final VirtualFile file) {
    return ApplicationManager.getApplication().runReadAction(new Computable<String>() {
      @Nullable
      @Override
      public String compute() {
        try {
          return new String(file.contentsToByteArray(), file.getCharset());
        }
        catch (IOException e) {
          LOG.info("Couldn't read contents of the file " + file, e);
          return null;
        }
      }
    });
  }

  private static void showError(@NotNull Project project,
                                @NotNull String title,
                                @NotNull String content,
                                @Nullable String details,
                                @Nullable Exception e) {
    Notificator.getInstance(project).notifyError(title, content);
    LOG.info("Couldn't parse response as json data: \n" + content + "\n" + details, e);
  }

  @NotNull
  private static String prepareJsonRequest(@NotNull String description, boolean isPrivate, @NotNull List<NamedContent> contents) {
    JsonObject json = new JsonObject();
    json.addProperty("description", description);
    json.addProperty("public", Boolean.toString(!isPrivate));

    JsonObject files = new JsonObject();
    for (NamedContent content : contents) {
      JsonObject file = new JsonObject();
      file.addProperty("content", content.getText());
      files.add(content.getName(), file);
    }

    json.add("files", files);
    return json.toString();
  }

  @NotNull
  private static List<NamedContent> getContentFromFile(@NotNull VirtualFile file, @NotNull Project project, @Nullable String prefix) {
    if (file.isDirectory()) {
      return getContentFromDirectory(file, project, prefix);
    }
    String content = readFile(file);
    if (content == null) {
      showError(project, FAILED_TO_CREATE_GIST, "Couldn't read the contents of the file " + file, null, null);
      LOG.info("Couldn't read the contents of the file " + file);
      return Collections.emptyList();
    }
    if (StringUtil.isEmptyOrSpaces(content)) {
      return Collections.emptyList();
    }
    return Collections.singletonList(new NamedContent(addPrefix(file.getName(), prefix, false), content));
  }

  @NotNull
  private static List<NamedContent> getContentFromDirectory(@NotNull VirtualFile dir, @NotNull Project project, @Nullable String prefix) {
    List<NamedContent> contents = new ArrayList<NamedContent>();
    for (VirtualFile file : dir.getChildren()) {
      if (!isFileIgnored(file, project)) {
        String pref = addPrefix(dir.getName(), prefix, true);
        contents.addAll(getContentFromFile(file, project, pref));
      }
    }
    return contents;
  }

  private static String addPrefix(@NotNull String name, @Nullable String prefix, boolean addTrailingSlash) {
    String pref = prefix == null ? "" : prefix;
    pref += name;
    if (addTrailingSlash) {
      pref += "_";
    }
    return pref;
  }

  private static boolean isFileIgnored(@NotNull VirtualFile file, @NotNull Project project) {
    ChangeListManager manager = ChangeListManager.getInstance(project);
    return manager.isIgnoredFile(file) || FileTypeManager.getInstance().isFileIgnored(file);
  }

  @Nullable
  private static NamedContent getContentFromEditor(@NotNull final Editor editor,
                                                   @Nullable VirtualFile selectedFile) {
    String text = ApplicationManager.getApplication().runReadAction(new Computable<String>() {
      @Nullable
      @Override
      public String compute() {
        return editor.getSelectionModel().getSelectedText();
      }
    });

    if (text == null) {
      text = editor.getDocument().getText();
    }

    if (StringUtil.isEmptyOrSpaces(text)) {
      return null;
    }

    String name;
    if (selectedFile == null) {
      name = "";
    }
    else {
      name = selectedFile.getName();
    }
    return new NamedContent(name, text);
  }

  private static class NamedContent {
    @NotNull private final String myName;
    @NotNull private final String myText;

    private NamedContent(@NotNull String name, @NotNull String text) {
      myName = name;
      myText = text;
    }

    @NotNull
    public String getName() {
      return myName;
    }

    @NotNull
    public String getText() {
      return myText;
    }

    @Override
    public String toString() {
      return myName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      NamedContent content = (NamedContent)o;

      if (!myName.equals(content.myName)) return false;
      if (!myText.equals(content.myText)) return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = myName.hashCode();
      result = 31 * result + myText.hashCode();
      return result;
    }
  }

}
