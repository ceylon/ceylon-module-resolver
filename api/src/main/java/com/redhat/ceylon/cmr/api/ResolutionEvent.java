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

package com.redhat.ceylon.cmr.api;

/**
 * A CMR event that consumers may be interested in
 * Consumers interested in specific details can get more information
 * by casting this to a known event implementation, or by using
 * a factory method in a default implementation.
 */
public interface ResolutionEvent {
    
    /**
     * A task event may be something that the repository manager plans to do, like
     * search in 5 different repositories, or only search local as it is off-line
     * 
     * A repository event happens if it is not reachable, or not the right format,
     * or something communicated by the repository, e.g. Herd message
     * 
     * An artifact event may be a request to resolve an artifact,
     * or a resolved artifact, even when hidden behind other
     * higher-level constructs such as a compiler, packager, type-checker etc.
     * 
     * Transfer events happen when the repository manager moves things around,
     * including download, upload and copy.
     *
     */
    public enum EventType {
        CMR_TASK_EVENT,
        CMR_REPO_EVENT,
        CMR_ARTIFACT_EVENT,
        CMR_TRANSFER_EVENT
    }

    public String getRepository();
    
    public ArtifactResult getArtifact();
    
    /**
     * Most consumers would be interested in this only.
     * Specifics of the event can be available in methods or fields
     * exposed by implementations
     * @return String
     */
    public String getMessage();
    
    public EventType getEventType();
}
