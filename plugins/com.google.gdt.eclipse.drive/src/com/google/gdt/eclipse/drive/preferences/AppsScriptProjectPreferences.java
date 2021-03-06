/*******************************************************************************
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.google.gdt.eclipse.drive.preferences;

import static com.google.gdt.eclipse.drive.preferences.PreferencesConstants.CURRENT_PREFERENCES_VERSION;
import static com.google.gdt.eclipse.drive.preferences.PreferencesConstants.DRIVE_FILE_ID_KEY;
import static com.google.gdt.eclipse.drive.preferences.PreferencesConstants.DRIVE_IMPORT_NAME_KEY_PREFIX;
import static com.google.gdt.eclipse.drive.preferences.PreferencesConstants.DRIVE_SCRIPT_ID_KEY_PREFIX;
import static com.google.gdt.eclipse.drive.preferences.PreferencesConstants.DRIVE_TYPE_KEY_PREFIX;
import static com.google.gdt.eclipse.drive.preferences.PreferencesConstants.DRIVE_VERSION_FINGERPRINT_KEY;
import static com.google.gdt.eclipse.drive.preferences.PreferencesConstants.PREFERENCES_VERSION_KEY;
import static com.google.gdt.eclipse.drive.preferences.PreferencesConstants.UNSAVED_KEY_PREFIX;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.gdt.eclipse.drive.DrivePlugin;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.osgi.service.prefs.BackingStoreException;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

/**
 * Provides static methods for reading and writing persistent information about Apps Script
 * projects. The following information is stored persistently:
 * <ul>
 *   <li>The Drive file ID of the project, accessed through the {@link #writeDriveFileId} and
 *       {@link #readDriveFileId} methods.
 *   <li>For each file in the project,
 *     <ul>
 *       <li>the Drive document ID for that file (absent if the file has been created in Eclipse and
 *           not yet written to Drive), accessed through the {@link #writeDriveScriptId},
 *           {@link #readDriveScriptId}, {@link #removeDriveScriptId}, and
 *           {@link #getFileNamesWithDocumentIds} methods;
 *       <li>the Drive import name for that file (the file name with its extension removed),
 *           accessed through the {@link #writeDriveImportName}, {@link #readDriveImportName}, and
 *           {@link #removeDriveImportName} methods;
 *       <li>and the Drive type for that file (one of the constants
 *           {@code DRIVE_APPS_SCRIPT_PROJECT_}...{@code _FILE_TYPE} declared in
 *           {@link com.google.gdt.eclipse.drive.driveapi.FileTypes}), accessed through the
 *           {@link #writeDriveType}, {@link #readDriveType}, and {@link #removeDriveType} methods.
 *     </ul>
 *   <li>A collection of the names of files that have been saved in Eclipse but not on Drive,
 *       accessed through the {@link #addUnsavedFileName}, {@link #removeUnsavedFileName} and
 *       {@link #getUnsavedFileNames} methods.
 *   <li>A hash of the project's contents at the time it was last synced with Drive, accessed
 *       through the {@link #writeDriveVersionFingerprint} and {@link #readDriveVersionFingerprint}
 *       methods.
 * </ul>
 * The {@link #removeFileMetadata} method is a convenience method that, given a file name, calls the
 * removal methods for each kind of per-file data. The {@link #initializeProjectPreferences} method
 * should be called before any of the aforementioned methods. The {@link #setMockFactories} method
 * is called only in unit tests.
 * 
 * <p>These properties are stored as Eclipse project preferences. Conceptually, Eclipse project
 * preferences are a set of name/value pairs. The Drive file ID of the project is stored in a
 * name/value pair whose name is given by {@link DRIVE_FILE_ID_KEY}. For a file whose name
 * (excluding a directly path but including an extension) is <i>filename</i>, the Drive document ID
 * (if there is one), the Drive import name, and the Drive type are stored in name/value pairs whose
 * names are given by {@link DRIVE_SCRIPT_ID_KEY_PREFIX}+<i>filename</i>,
 * {@link DRIVE_IMPORT_NAME_KEY_PREFIX}+<i>filename</i>, and
 * {@link DRIVE_TYPE_KEY_PREFIX}+<i>filename</i>, respectively. For a file with the name
 * <i>filename</i> that has been saved in Eclipse but not on Drive, there is a name/value pair
 * whose name is given by {@link UNSAVED_KEY_PREFIX}+<i>filename</i> and whose value is
 * {@code true}.
 * 
 * <p>Physically, Eclipse project preferences for a project are stored in a file named
 * {@code .settings/com.google.gdt.eclipse.drive.prefs} (relative to the top level of the project).
 * (This file appears in the Navigator view, but is hidden in the Package Explorer view.) Each
 * name/value pair is stored by a line of the form
 * <pre>
 *    <i>name</i>=<i>value</i>
 * </pre>
 * in which non-ISO-8859 characters in the name are replaced by Java-style Unicode escape codes
 * (consisting of a backslash, a 'u', and four hexadecimal characters) and ' ' and '=' characters in
 * the name are escaped with a preceding backslash. All escaping and unescaping is handled by the
 * Eclipse preferences API, so the caller of that API need not worry about it; but the escaping
 * conventions must be understood by developers reading the
 * {@code .settings/com.google.gdt.eclipse.drive.prefs} file.
 */
public class AppsScriptProjectPreferences {
  
  // All direct access to the Eclipse preferences store is through the private low-level methods
  // readPreference, writePreference, writeIntPreference, erasePreference, and getKeys.
  // Higher-level public methods concerned with particular persistent properties are implemented
  // on top of the low-level methods.
  //
  // The low-level methods, in turn, all accomplish their work by submitting calls to the
  // single-thread executor preferencesAccessSerializer. In effect, the single thread executes a
  // loop of the following form:
  //
  // while (true) {
  //   remove the next submitted call from the queue; // may block until a call is submitted
  //   execute the call; // if the call includes an invocation of Preferences.flush() that conflicts
  //                     // with a workspace operation currently in progress, blocks until
  //                     // no conflict exists
  // }
  //
  // This achieves two purposes:
  //
  // (1) The public methods of this class are sometimes called from a thread executing an
  //     IResourceChangeListener, at which time the resource tree is locked. Since flushing the
  //     preferences would require write access to the resource tree, we submit a callable that does
  //     the flush when the lock is released.
  //
  // (2) Using a single-thread executor ensures that accesses to the preferences on behalf of
  //     public-method invocations in different threads do not overlap in time, and occur in an
  //     order consistent with the order of the method invocations.  
  private static final ExecutorService preferencesAccessSerializer =
      Executors.newSingleThreadExecutor();
  
  /**
   * An interface for obtaining the {@link IScopeContext} for a particular {@link IProject}.
   * In tests, the standard implementation is replaced by one that does not depend on the Eclipse
   * environment.
   */
  @VisibleForTesting
  public interface IScopeContextFactory {
    IScopeContext scopeForProject(IProject project);
  }
  
  // The value of scopeFactory is replaced during tests by a call on setMockFactories.
  private static IScopeContextFactory scopeFactory =
      new IScopeContextFactory() {
        @Override public IScopeContext scopeForProject(IProject project) {
          return new ProjectScope(project);
        }
      };

  /**
   * Initializes the project preferences for a particular project. This method should be called
   * before any other methods are called for a given project.
   * 
   * @param project the project
   */
  public static void initializeProjectPreferences(IProject project) {
    writeIntPreference(project, PREFERENCES_VERSION_KEY, CURRENT_PREFERENCES_VERSION);
  }
  
  /**
   * Persistently associates a specified Drive file ID with a specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param fileId the specified Drive file ID
   */
  public static void writeDriveFileId(IProject project, String fileId)
      throws BackingStoreException {
    writePreference(project, DRIVE_FILE_ID_KEY, fileId);
  }
  
  /**
   * Obtains the Drive file ID persistently associated with a specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @return
   *     if there is a Drive file ID persistently associated with the specified Eclipse project,
   *     the file ID; otherwise {@code null}
   */
  @Nullable
  public static String readDriveFileId(IProject project) throws BackingStoreException {
    return readPreference(project, DRIVE_FILE_ID_KEY);
  }
    
  /**
   * Obtains the Drive version fingerprint persistently associated with a specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @return
   *     if there is a Drive version fingerprint persistently associated with the specified
   *     Eclipse project, the timestamp; otherwise {@code null}
   */
  @Nullable
  public static String readDriveVersionFingerprint(IProject project) throws BackingStoreException {
    return readPreference(project, DRIVE_VERSION_FINGERPRINT_KEY);
  }
  
  /**
   * Persistently associates a specified Drive version ID with a specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param driveVersionFingerprint the specified Drive version fingerprint
   */
  public static void writeDriveVersionFingerprint(
      IProject project, String driveVersionFingerprint) throws BackingStoreException {
    writePreference(project, DRIVE_VERSION_FINGERPRINT_KEY, driveVersionFingerprint);
  }

  /**
   * Persistently associates a specified Drive import name with a specified file name in a
   * specified Eclipse project. At the time of this writing, the Eclipse file name consists of the
   * import name followed by the extension ".gs", but we do not rely on that always being the case.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified Eclipse file name
   * @param driveImportName the specified Drive import name
   */
  public static void writeDriveImportName(
      IProject project, String fileName, String driveImportName) throws BackingStoreException {
    writePreference(
        project, keyForStringValue(DRIVE_IMPORT_NAME_KEY_PREFIX, fileName), driveImportName);
  }
  
  /**
   * Obtains the Drive import name persistently associated with a specified file name in a
   * specified Eclipse project. At the time of this writing, the Eclipse file name consists of the
   * import name followed by the extension ".gs", but we do not rely on that always being the case.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified file name
   * @return
   *     if there is a Drive import name persistently associated with the specified file name in
   *     the specified Eclipse project, that import name; otherwise {@code null}
   */
  @Nullable
  public static String readDriveImportName(IProject project, String fileName)
      throws BackingStoreException {
    return readPreference(project, keyForStringValue(DRIVE_IMPORT_NAME_KEY_PREFIX, fileName));
  }
  
  /**
   * Removes the persistent association of a Drive import name with a specified file name in a
   * specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified file name
   */
  public static void removeDriveImportName(IProject project, String fileName) {
    erasePreference(project, keyForStringValue(DRIVE_IMPORT_NAME_KEY_PREFIX, fileName));
  }
    
  /**
   * Persistently associates a specified Drive script ID with a specified file name in a specified
   * Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified file name
   * @param scriptId the specified Drive script ID
   */
  public static void writeDriveScriptId(IProject project, String fileName, String scriptId)
      throws BackingStoreException {
    writePreference(project, keyForStringValue(DRIVE_SCRIPT_ID_KEY_PREFIX, fileName), scriptId);
  }
  
  /**
   * Obtains the Drive script ID persistently associated with a specified file name in a
   * specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified file name
   * @return
   *     if there is a Drive script ID persistently associated with the specified file name in the
   *     specified Eclipse project, that script ID; otherwise {@code null}
   */
  @Nullable
  public static String readDriveScriptId(IProject project, String fileName)
      throws BackingStoreException {
    return readPreference(project, keyForStringValue(DRIVE_SCRIPT_ID_KEY_PREFIX, fileName));
  }
  
  /**
   * Removes the persistent association of a Drive script ID with a specified file name in a
   * specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified file name
   */
  public static void removeDriveScriptId(IProject project, String fileName) {
    erasePreference(project, keyForStringValue(DRIVE_SCRIPT_ID_KEY_PREFIX, fileName));
  }
  
  /**
   * Persistently associates a specified Drive file type with a specified file name in a
   * specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified file name
   * @param type the specified Drive file type
   */
  public static void writeDriveType(IProject project, String fileName, String type)
      throws BackingStoreException {
    writePreference(project, keyForStringValue(DRIVE_TYPE_KEY_PREFIX, fileName), type);
  }
  
  /**
   * Obtains the Drive file type persistently associated with a specified file name in a
   * specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified file name
   * @return
   *     if there is a Drive file type persistently associated with the specified file name in
   *     the specified Eclipse project, that type; otherwise {@code null}
   */
  @Nullable
  public static String readDriveType(IProject project, String fileName)
      throws BackingStoreException {
    return readPreference(project, keyForStringValue(DRIVE_TYPE_KEY_PREFIX, fileName));
  }
  
  /**
   * Removes the persistent association of a Drive file type with a specified file name in a
   * specified Eclipse project.
   * 
   * @param project the specified Eclipse project
   * @param fileName the specified file name
   */
  public static void removeDriveType(IProject project, String fileName) {
    erasePreference(project, keyForStringValue(DRIVE_TYPE_KEY_PREFIX, fileName));
  }
  
  /**
   * Persistently records a given Eclipse file as being unsaved in Drive.
   * 
   * @param project the project containing the file
   * @param fileName the name of the file, including its extension but not a path
   */
  public static void addUnsavedFileName(IProject project, String fileName)
      throws BackingStoreException {
    writePreference(project, keyForStringValue(UNSAVED_KEY_PREFIX, fileName), "true");
  }
  
  /**
   * Removes the persistent record that a given Eclipse file is unsaved in Drive.
   * 
   * @param project the project containing the file
   * @param fileName the name of the file, including its extension but not a path
   */
  public static void removeUnsavedFileName(IProject project, String fileName) {
    erasePreference(project, keyForStringValue(UNSAVED_KEY_PREFIX, fileName));
  }
  
  /**
   * Obtains the names of files in a given project that are persistently recorded as being unsaved
   * in Drive.
   * 
   * @param project the given project
   * @return the file names, including extensions but no paths
   * @throws BackingStoreException if there is an error retrieving the keys in the preferences file
   */
  public static Set<String> getUnsavedFileNames(IProject project) throws BackingStoreException {
    return fileNamesAssociatedWithPrefix(project, UNSAVED_KEY_PREFIX);
  }
  
  /**
   * Obtains the names of files in a given project for which there is a persistently recorded
   * document ID. This includes files present in the Eclipse project and files that were deleted
   * from the Eclipse project but designated by the user to be retained in the Drive project.
   * 
   * @param project the given project
   * @return the file names, including extensions but no paths
   * @throws BackingStoreException if there is an error retrieving the keys in the preferences file
   */
  public static Set<String> getFileNamesWithDocumentIds(IProject project)
      throws BackingStoreException {
    return fileNamesAssociatedWithPrefix(project, DRIVE_SCRIPT_ID_KEY_PREFIX);
  }

  /**
   * Removes all metadata in a given project for a specified file name.
   * 
   * @param project the specified project
   * @param fileNamethe specified file name
   */
  public static void removeFileMetadata(IProject project, String fileName) {
    removeDriveImportName(project, fileName);
    removeDriveType(project, fileName);
    removeDriveScriptId(project, fileName);
  }

  private static Set<String> fileNamesAssociatedWithPrefix(IProject project, String prefix)
      throws BackingStoreException {
    IEclipsePreferences prefs = getProjectPreferences(project);
    PreferencesVersionManager.ensureCurrentVersion(prefs);
    Set<String> result = Sets.newHashSet();
    for (String key : getKeys(project)) {
      if (key.startsWith(prefix)) {
        result.add(key.substring(prefix.length()));
      }
    }
    return result;
  }
  
  private static String[] getKeys(final IProject project) throws BackingStoreException {
    return futureValue(
        preferencesAccessSerializer.submit(
            new Callable<String[]>() {
              @Override
              public String[] call() throws BackingStoreException {
                return getProjectPreferences(project).keys();
              }
            }));
  }

  private static String readPreference(final IProject project, final String key)
      throws BackingStoreException {
    return futureValue(
        preferencesAccessSerializer.submit(
            new Callable<String>() {
              @Override
              public String call() {
                IEclipsePreferences prefs = getProjectPreferences(project);
                PreferencesVersionManager.ensureCurrentVersion(prefs);
                return prefs.get(key, null);
              }
            }));
  }
  
  private static void writePreference(final IProject project, final String key, final String value)
      throws BackingStoreException {
    preferencesAccessSerializer.submit(
        new Callable<Void>(){
          @Override public Void call() throws BackingStoreException {
            IEclipsePreferences prefs = getProjectPreferences(project);
            PreferencesVersionManager.ensureCurrentVersion(prefs);
            prefs.put(key, value);
            prefs.flush();
            return null;
          }
        });
  }
  
  private static void writeIntPreference(
      final IProject project, final String key, final int value) {
    preferencesAccessSerializer.submit(
        new Callable<Void>(){
          @Override public Void call() throws BackingStoreException {
            IEclipsePreferences prefs = getProjectPreferences(project);
            PreferencesVersionManager.ensureCurrentVersion(prefs);
            prefs.putInt(key, value);
            prefs.flush();
            return null;
          }
        });
  }
  
  private static void erasePreference(final IProject project, final String key) {
    preferencesAccessSerializer.submit(
        new Callable<Void>(){
          @Override public Void call() throws BackingStoreException {
            IEclipsePreferences prefs = getProjectPreferences(project);
            prefs.remove(key);
            prefs.flush();
            return null;
          }
        });
  }
  
  private static <T> T futureValue(Future<T> future) throws BackingStoreException {
    T result;
    try {
      result = future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BackingStoreException("preferenceAccessSerializer interrupted");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof BackingStoreException) {
        throw (BackingStoreException) cause;
      } else {
        throw new BackingStoreException("Error in preferenceAccessSerializer operation", cause);
      }
    }
    return result;
  }

  private static IEclipsePreferences getProjectPreferences(IProject project) {
    IScopeContext scope = scopeFactory.scopeForProject(project);
    return scope.getNode(DrivePlugin.PLUGIN_ID);
  }

  private static String keyForStringValue(String keyPrefix, String stringValue) {
    return keyPrefix + stringValue; // Eclipse will handle all necessary escaping.
  }
  
  /**
   * Places this {@code AppsScriptProjectPreferences} object in a state suitable for testing,
   * in which the default {@link IScopeContextFactory} is replaced by a specified mock factory.
   * 
   * @param mockScopeContextFactory the mock {@code IScopeFactory}
   */
  @VisibleForTesting
  public static void setMockScopeContextFactory(IScopeContextFactory mockScopeContextFactory) {
    scopeFactory = mockScopeContextFactory;
  }
  
  private AppsScriptProjectPreferences() {
    // prevent instantiation
  }

}