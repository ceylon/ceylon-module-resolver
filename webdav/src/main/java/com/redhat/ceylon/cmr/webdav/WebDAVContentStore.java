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

package com.redhat.ceylon.cmr.webdav;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.http.ProtocolException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.config.Registry;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.SardineImpl;
import com.github.sardine.impl.io.ContentLengthInputStream;
import com.redhat.ceylon.cmr.impl.NodeUtils;
import com.redhat.ceylon.cmr.impl.URLContentStore;
import com.redhat.ceylon.cmr.spi.ContentHandle;
import com.redhat.ceylon.cmr.spi.ContentOptions;
import com.redhat.ceylon.cmr.spi.Node;
import com.redhat.ceylon.cmr.spi.OpenNode;
import com.redhat.ceylon.cmr.spi.SizedInputStream;
import com.redhat.ceylon.common.log.Logger;
import com.redhat.ceylon.model.cmr.RepositoryException;

/**
 * WebDAV content store.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 * @author Stef Epardaud
 */
public class WebDAVContentStore extends URLContentStore {

    private volatile SardineImpl sardine;
    private boolean forcedAuthenticationForPutOnHerd = false;

    /**
     * For tests only!!!
     */
    public WebDAVContentStore(String root, Logger log, boolean offline, int timeout, Proxy proxy, String apiVersion) {
        super(root, log, offline, timeout, proxy, apiVersion);
    }

    public WebDAVContentStore(String root, Logger log, boolean offline, int timeout, Proxy proxy) {
        super(root, log, offline, timeout, proxy);
    }

    protected SardineImpl getSardine() {
        if (sardine == null) {
            synchronized (this) {
                if (sardine == null) {
                    sardine = new SardineImpl(username, password, null) {
                        @Override
                        protected HttpClientConnectionManager createDefaultConnectionManager(Registry<ConnectionSocketFactory> schemeRegistry) {
                            HttpClientConnectionManager connMan = super.createDefaultConnectionManager(schemeRegistry);
                            if (connMan instanceof PoolingHttpClientConnectionManager) {
                                @SuppressWarnings("resource")
                                PoolingHttpClientConnectionManager phccm = (PoolingHttpClientConnectionManager)connMan;
                                SocketConfig config = SocketConfig.custom().setSoTimeout(timeout).build();
                                phccm.setDefaultSocketConfig(config);
                            }
                            return connMan;
                        }
                    };
                }
            }
        }
        return sardine;
    }

    public OpenNode create(Node parent, String child) {
        if (!connectionAllowed()) {
            return null;
        }
        try {
            if (!isHerd())
                mkdirs(getSardine(), parent);
            return createNode(child);
        } catch (IOException e) {
            throw convertIOException(e);
        }
    }

    public ContentHandle peekContent(Node node) {
        if (!connectionAllowed()) {
            return null;
        }
        try {
            final String url = getUrlAsString(node);
            return (getSardine().exists(url) ? new WebDAVContentHandle(url) : null);
        } catch (IOException e) {
            return null;
        }
    }

    public ContentHandle getContent(Node node) throws IOException {
        return new WebDAVContentHandle(getUrlAsString(node));
    }

    public ContentHandle putContent(Node node, InputStream stream, ContentOptions options) throws IOException {
        if (!connectionAllowed()) {
            return null;
        }
        final Sardine s = getSardine();
        try {
            /*
             * Most disgusting trick ever. Stef failed to set up Sardine to do preemptive auth on all hosts
             * and ports (may only work on port 80, reading the code), so when not using Herd we generate a ton
             * of requests that will trigger auth, but not for Herd. So we start with a PUT and that replies with
             * an UNAUTHORIZED response, which Sardine can't handle because the InputStream is not "restartable".
             * By making an extra HEAD request (restartable because no entity body) we force the auth to happen.
             * Yuk.
             */
            if (isHerd() && !forcedAuthenticationForPutOnHerd) {
                s.exists(getUrlAsString(node));
                forcedAuthenticationForPutOnHerd = true;
            }
            final Node parent = NodeUtils.firstParent(node);
            if (!isHerd())
                mkdirs(s, parent);

            final String pUrl = getUrlAsString(parent);
            String token = null;
            if (!isHerd())
                token = s.lock(pUrl); // local parent
            final String url = getUrlAsString(node);
            try {
                s.put(url, stream);
                return new WebDAVContentHandle(url);
            } catch (SocketTimeoutException x) {
                SocketTimeoutException ret = new SocketTimeoutException("Timed out writing to "+url);
                ret.initCause(x);
                throw ret;
            } finally {
                if (!isHerd())
                    s.unlock(pUrl, token);
            }
        } catch (IOException x) {
            throw convertIOException(x);
        }
    }

    public RepositoryException convertIOException(IOException x) {
        if (x instanceof SardineException) {
            // hide this from callers because its getMessage() is borked
            SardineException sx = (SardineException) x;
            return new RepositoryException(sx.getMessage() + ": " + sx.getResponsePhrase() + " " + sx.getStatusCode());
        }
        if (x instanceof ClientProtocolException) {
            // in case of protocol exception (invalid response) we get this sort of
            // chain set up with a null message, so unwrap it for better messages
            if (x.getCause() != null && x.getCause() instanceof ProtocolException)
                return new RepositoryException(x.getCause().getMessage());
        }
        return new RepositoryException(x);
    }

    protected void mkdirs(Sardine s, Node parent) throws IOException {
        if (parent == null)
            return;

        mkdirs(s, NodeUtils.firstParent(parent));

        final String url = getUrlAsString(parent);
        if (s.exists(url) == false) {
            s.createDirectory(url);
        }
    }

    protected ContentHandle createContentHandle(Node parent, String child, String path, Node node) {
        return new WebDAVContentHandle(root + path);
    }

    public Iterable<? extends OpenNode> find(Node parent) {
        if (!connectionAllowed()) {
            return Collections.emptyList();
        }
        final String url = getUrlAsString(parent);
        try {
            final List<OpenNode> nodes = new ArrayList<>();
            final List<DavResource> resources = getSardine().list(url);
            for (DavResource dr : resources) {
                final String label = dr.getName();
                final RemoteNode node = new RemoteNode(label);
                if (dr.isDirectory())
                    node.setContentMarker();
                else
                    node.setHandle(new WebDAVContentHandle(url + label));
                nodes.add(node);
            }
            return nodes;
        } catch (IOException e) {
            log.debug("Failed to list url: " + url);
            return Collections.emptyList();
        }
    }

    @Override
    protected boolean urlExists(String path) {
        if (!connectionAllowed()) {
            return false;
        }
        try {
            return getSardine().exists(getUrlAsString(path));
        } catch (IOException e) {
            log.debug("Failed to check url: " + path);
            return false;
        }
    }

    protected boolean urlExists(URL url) {
        if (!connectionAllowed()) {
            return false;
        }
        try {
            return getSardine().exists(url.toExternalForm());
        } catch (IOException e) {
            log.debug("Failed to check url: " + url);
            return false;
        }
    }

    @Override
    public String toString() {
        return "WebDAV content store: " + root;
    }

    private class WebDAVContentHandle implements ContentHandle {

        private final String url;

        private WebDAVContentHandle(String url) {
            this.url = url;
        }

        public boolean hasBinaries() {
            if (!connectionAllowed()) {
                return false;
            }
            try {
                final List<DavResource> list = getSardine().list(url);
                return list.size() == 1 && list.get(0).isDirectory() == false;
            } catch (IOException e) {
                log.warning("Cannot list resources: " + url + "; error - " + e);
                return false;
            }
        }

        public InputStream getBinariesAsStream() throws IOException {
            SizedInputStream ret = getBinariesAsSizedStream();
            return ret != null ? ret.inputStream : null;
        }
        
        public SizedInputStream getBinariesAsSizedStream() throws IOException {
            if (!connectionAllowed()) {
                return null;
            }
            ContentLengthInputStream inputStream = getSardine().get(url);
            Long length = inputStream.getLength();
            return new SizedInputStream(inputStream, length != null ? length.longValue() : -1);
        }

        public File getContentAsFile() throws IOException {
            return null;
        }

        public long getSize() throws IOException {
            if (connectionAllowed()) {
                if (isHerd()) {
                    return size(new URL(url));
                }

                final List<DavResource> list = getSardine().list(url);
                if (list.isEmpty() == false && list.get(0).isDirectory() == false) {
                    Long length = list.get(0).getContentLength();
                    if (length != null) {
                        return length;
                    }
                }
            }
            return -1L;
        }

        public long getLastModified() throws IOException {
            if (connectionAllowed()) {
                if (isHerd()) {
                    return lastModified(new URL(url));
                }

                final List<DavResource> list = getSardine().list(url);
                if (list.isEmpty() == false && list.get(0).isDirectory() == false) {
                    Date modified = list.get(0).getModified();
                    if (modified != null) {
                        return modified.getTime();
                    }
                }
            }
            return -1L;
        }

        public void clean() {
        }
    }
}
