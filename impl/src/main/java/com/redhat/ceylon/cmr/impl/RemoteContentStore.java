/*
 * Copyright 2011 Red Hat inc. and third party contributors as noted 
 * by the author tags.
 * 
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;

import com.redhat.ceylon.cmr.spi.ContentHandle;
import com.redhat.ceylon.cmr.spi.ContentOptions;
import com.redhat.ceylon.cmr.spi.Node;
import com.redhat.ceylon.cmr.spi.OpenNode;
import com.redhat.ceylon.cmr.spi.SizedInputStream;
import com.redhat.ceylon.common.Constants;
import com.redhat.ceylon.common.log.Logger;

/**
 * Remote content store.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class RemoteContentStore extends URLContentStore {

    public RemoteContentStore(String root, Logger log, boolean offline, int timeout, Proxy proxy) {
        super(root, log, offline, timeout, proxy);
    }

    protected SizedInputStream openSizedStream(final URL url) throws IOException {
        if (connectionAllowed()) {
            final URLConnection conn;
            if (proxy != null) {
                conn = url.openConnection(proxy);
            } else {
                conn = url.openConnection();
            }
            if (conn instanceof HttpURLConnection) {
                HttpURLConnection huc = (HttpURLConnection) conn;
                huc.setConnectTimeout(timeout);
                huc.setReadTimeout(timeout * Constants.READ_TIMEOUT_MULTIPLIER);
                addCredentials(huc);
                try{
                    InputStream stream = conn.getInputStream();
                    int code = huc.getResponseCode();
                    if (code != -1 && code != 200) {
                        log.info("Got " + code + " for url: " + url);
                        return null;
                    }
                    log.debug("Got " + code + " for url: " + url);
                    long contentLength = huc.getContentLengthLong();
                    return new SizedInputStream(stream, contentLength);
                }catch(SocketTimeoutException timeoutException){
                    SocketTimeoutException newException = new SocketTimeoutException("Timed out during connection to "+url);
                    newException.initCause(timeoutException);
                    throw newException;
                }
            }
        }
        return null;
    }

    protected boolean exists(final URL url) throws IOException {
        return head(url) != null;
    }

    public ContentHandle peekContent(Node node) {
        return urlExists(node) ? createContentHandle(null, null, null, node) : null;
    }

    public ContentHandle getContent(Node node) throws IOException {
        return createContentHandle(null, null, null, node);
    }

    public ContentHandle putContent(Node node, InputStream stream, ContentOptions options) throws IOException {
        return null; // cannot write
    }

    protected RemoteNode createNode(String label) {
        return new ImmutableRemoteNode(label);
    }

    public OpenNode create(Node parent, String child) {
        return null;
    }

    protected ContentHandle createContentHandle(Node parent, String child, String path, Node node) {
        return new RemoteContentHandle(node);
    }

    public Iterable<? extends OpenNode> find(Node parent) {
        return Collections.emptyList(); // cannot find all children
    }

    protected boolean urlExists(URL url) {
        if (url == null)
            return false;

        try {
            return exists(url);
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "RemoteContentStore: " + root;
    }

    private class RemoteContentHandle implements ContentHandle {
        private final Node node;

        private RemoteContentHandle(Node node) {
            this.node = node;
        }

        public boolean hasBinaries() {
            return true; // we assume we have bins behind the url
        }

        public InputStream getBinariesAsStream() throws IOException {
            SizedInputStream ret = getBinariesAsSizedStream();
            return ret != null ? ret.inputStream : null;
        }

        public SizedInputStream getBinariesAsSizedStream() throws IOException {
            final URL url = getURL(compatiblePath(NodeUtils.getFullPath(node, SEPARATOR)));
            log.debug("Fetching resource: " + url);
            return openSizedStream(url);
        }

        public File getContentAsFile() throws IOException {
            return null;  // unsupported
        }

        public long getLastModified() throws IOException {
            final URL url = getURL(compatiblePath(NodeUtils.getFullPath(node, SEPARATOR)));
            return lastModified(url);
        }

        public long getSize() throws IOException {
            final URL url = getURL(compatiblePath(NodeUtils.getFullPath(node, SEPARATOR)));
            return size(url);
        }

        public void clean() {
        }
    }

    private static class ImmutableRemoteNode extends RemoteNode {
        private ImmutableRemoteNode(String label) {
            super(label);
        }

        @Override
        public OpenNode addContent(String label, InputStream content, ContentOptions options) throws IOException {
            return null; // cannot add content
        }

        @Override
        public <T extends Serializable> OpenNode addContent(String label, T content, ContentOptions options) throws IOException {
            return null; // cannot add content
        }
    }
}
