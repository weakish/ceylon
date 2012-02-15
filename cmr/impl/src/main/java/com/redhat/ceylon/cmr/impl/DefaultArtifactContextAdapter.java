/*
 * Copyright 2011 Red Hat inc. and third party contributors as noted 
 * by the author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.ceylon.cmr.impl;

import com.redhat.ceylon.cmr.api.ArtifactContext;
import com.redhat.ceylon.cmr.api.ArtifactResult;
import com.redhat.ceylon.cmr.api.Repository;
import com.redhat.ceylon.cmr.spi.Node;
import com.redhat.ceylon.cmr.spi.OpenNode;

import java.io.File;
import java.io.IOException;

/**
 * Default.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class DefaultArtifactContextAdapter extends AbstractArtifactContextAdapter {

    public DefaultArtifactContextAdapter(OpenNode root) {
        super(root);
    }

    public ArtifactResult getArtifactResult(Repository repository, Node node) {
        return new DefaultArtifactResult(repository, node);
    }

    private static class DefaultArtifactResult extends AbstractArtifactResult {

        private Node node;

        private DefaultArtifactResult(Repository repository, Node node) {
            super(repository, ArtifactContext.fromNode(node).getName());
            this.node = node;
        }

        public File artifact() throws IOException {
            return node.getContent(File.class);
        }
    }
}
