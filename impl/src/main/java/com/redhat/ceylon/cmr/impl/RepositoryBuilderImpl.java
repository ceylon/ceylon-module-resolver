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

import com.redhat.ceylon.cmr.api.Logger;
import com.redhat.ceylon.cmr.api.Repository;
import com.redhat.ceylon.cmr.api.RepositoryBuilder;
import com.redhat.ceylon.cmr.spi.StructureBuilder;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;

/**
 * Repository builder.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
class RepositoryBuilderImpl implements RepositoryBuilder {

    private Logger log;

    RepositoryBuilderImpl(Logger log) {
        this.log = log;
    }

    public Repository buildRepository(String token) throws Exception {
        if (token == null)
            throw new IllegalArgumentException("Null repository");

        final String key = (token.startsWith("${") ? token.substring(2, token.length() - 1) : token);
        final String temp = SecurityActions.getProperty(key);
        if (temp != null)
            token = temp;

        // IMPORTANT Make sure the below list is consistent with CeylonUtils.isRemote() !
        
        StructureBuilder structureBuilder;
        if (token.startsWith("http:") || token.startsWith("https:")) {
            structureBuilder = new RemoteContentStore(token, log);
        } else if (token.startsWith("mvn:")) {
            return MavenRepositoryHelper.getMavenRepository(token.substring("mvn:".length()), log);
        } else if (token.equals("mvn")) {
            return MavenRepositoryHelper.getMavenRepository();
        } else if (token.equals("jdk")) {
            return new JDKRepository();
        } else if (token.equals("aether") || token.startsWith("aether:")) {
            Class<?> aetherRepositoryClass = Class.forName("com.redhat.ceylon.cmr.maven.AetherRepository");
            if (token.equals("aether")) {
                Method createRepository = aetherRepositoryClass.getMethod("createRepository", Logger.class);
                return (Repository) createRepository.invoke(null, log);
            }
            else {
                String settingsXml = token.substring("aether:".length());
                Method createRepository = aetherRepositoryClass.getMethod("createRepository", Logger.class, String.class);
                return (Repository) createRepository.invoke(null, log, settingsXml);
            }
        } else {
            final File file = (token.startsWith("file:") ? new File(new URI(token)) : new File(token));
            if (file.exists() == false)
                throw new IllegalArgumentException("Directory does not exist: " + token);
            if (file.isDirectory() == false)
                throw new IllegalArgumentException("Repository exists but is not a directory: " + token);

            structureBuilder = new FileContentStore(file);
        }
        return new DefaultRepository(structureBuilder.createRoot());
    }
}
