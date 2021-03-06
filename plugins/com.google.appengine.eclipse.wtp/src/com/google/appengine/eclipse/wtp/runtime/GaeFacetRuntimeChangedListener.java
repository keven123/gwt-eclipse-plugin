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
package com.google.appengine.eclipse.wtp.runtime;

import com.google.appengine.eclipse.wtp.AppEnginePlugin;
import com.google.appengine.eclipse.wtp.classpath.GaeWtpClasspathContainer;
import com.google.appengine.eclipse.wtp.facet.IGaeFacetConstants;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.wst.common.project.facet.core.IFacetedProject;
import org.eclipse.wst.common.project.facet.core.IProjectFacet;
import org.eclipse.wst.common.project.facet.core.ProjectFacetsManager;
import org.eclipse.wst.common.project.facet.core.events.IFacetedProjectEvent;
import org.eclipse.wst.common.project.facet.core.events.IFacetedProjectListener;

/**
 * Listener for primary runtime changing.
 */
public final class GaeFacetRuntimeChangedListener implements IFacetedProjectListener {
  @Override
  public void handleEvent(IFacetedProjectEvent event) {
    IFacetedProject facetedProject = event.getProject();
    IProjectFacet facet = ProjectFacetsManager.getProjectFacet(IGaeFacetConstants.GAE_FACET_ID);
    if (facetedProject.hasProjectFacet(facet)) {
      try {
        // reset the GAE dependency container
        IProject project = facetedProject.getProject();
        IJavaProject javaProject = JavaCore.create(project);
        // set container to null will cause it to get re-initialized (appropriate
        // ClasspathContainerInitializer invoked)
        JavaCore.setClasspathContainer(GaeWtpClasspathContainer.CONTAINER_PATH,
            new IJavaProject[] {javaProject}, new IClasspathContainer[] {null},
            new NullProgressMonitor());
      } catch (CoreException e) {
        AppEnginePlugin.logMessage(e);
      }
    }
  }
}
