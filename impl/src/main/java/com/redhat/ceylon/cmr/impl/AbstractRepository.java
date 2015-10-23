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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import com.redhat.ceylon.cmr.api.ArtifactContext;
import com.redhat.ceylon.cmr.api.CmrRepository;
import com.redhat.ceylon.cmr.api.ContentFinderDelegate;
import com.redhat.ceylon.cmr.api.ModuleQuery;
import com.redhat.ceylon.cmr.api.ModuleQuery.Retrieval;
import com.redhat.ceylon.cmr.api.ModuleSearchResult;
import com.redhat.ceylon.cmr.api.ModuleVersionArtifact;
import com.redhat.ceylon.cmr.api.ModuleVersionDetails;
import com.redhat.ceylon.cmr.api.ModuleVersionQuery;
import com.redhat.ceylon.cmr.api.ModuleVersionResult;
import com.redhat.ceylon.cmr.api.Overrides;
import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.cmr.spi.Node;
import com.redhat.ceylon.cmr.spi.OpenNode;
import com.redhat.ceylon.common.Versions;
import com.redhat.ceylon.model.cmr.ArtifactResult;

/**
 * Abstract repository.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public abstract class AbstractRepository implements CmrRepository {

    private static final Comparator<? super Node> AlphabeticalNodeComparator = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            return a.getLabel().compareToIgnoreCase(b.getLabel());
        }
    };

    private OpenNode root;

    public AbstractRepository(OpenNode root) {
        this.root = root;
    }

    protected List<String> getDefaultParentPathInternal(ArtifactContext context) {
        final String name = context.getName();
        final List<String> tokens = new ArrayList<String>();
        tokens.addAll(Arrays.asList(name.split("\\.")));
        final String version = context.getVersion();
        if (RepositoryManager.DEFAULT_MODULE.equals(name) == false && version != null)
            tokens.add(version); // add version
        return tokens;
    }

    protected List<String> getDefaultParentPath(ArtifactContext context) {
        List<String> tokens = LookupCaching.getTokens(getClass());
        if (tokens == null) {
            tokens = getDefaultParentPathInternal(context);
            if (LookupCaching.isEnabled()) {
                LookupCaching.setTokens(getClass(), tokens);
            }
        }
        return tokens;
    }

    protected static String[] getArtifactNames(String name, String version, String[] suffixes) {
        String[] names = new String[suffixes.length];
        for (int i = 0; i < suffixes.length; i++) {
            names[i] = getArtifactName(name, version, suffixes[i]);
        }
        return names;
    }

    private static String getArtifactName(String name, String version, String suffix) {
        return ArtifactContext.getArtifactName(name, version, suffix);
    }

    public OpenNode getRoot() {
        return root;
    }

    public Node findParent(ArtifactContext context) {
        final List<String> tokens = getDefaultParentPath(context);
        return NodeUtils.getNode(root, tokens);
    }

    public String[] getArtifactNames(ArtifactContext context) {
        return getArtifactNames(context.getName(), context.getVersion(), context.getSuffixes());
    }

    public OpenNode createParent(ArtifactContext context) {
        final List<String> tokens = getDefaultParentPath(context);
        OpenNode current = root;
        for (String token : tokens) {
            current = current.addNode(token);
        }
        return current;
    }

    protected abstract ArtifactResult getArtifactResultInternal(RepositoryManager manager, Node node);

    public ArtifactResult getArtifactResult(RepositoryManager manager, Node node) {
        return (node != null) ? getArtifactResultInternal(manager, node) : null;
    }

    @Override
    public int hashCode() {
        return root.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof CmrRepository == false)
            return false;

        final CmrRepository daca = (CmrRepository) obj;
        return root.equals(daca.getRoot());
    }

    @Override
    public String toString() {
        return "Repository (" + getClass().getName() + ") for root: " + root;
    }

    @Override
    public String getDisplayString() {
        return root.getDisplayString();
    }

    @Override
    public void refresh(boolean recurse) {
        root.refresh(recurse);
    }

    @Override
    public void completeModules(ModuleQuery query, ModuleSearchResult result) {
        // check for delegate
        ContentFinderDelegate delegate = root.getService(ContentFinderDelegate.class);
        if (delegate != null) {
            delegate.completeModules(query, result, getOverrides());
            return;
        }
        // we NEED the -1 limit here to get empty tokens
        String[] paths = query.getName().split("\\.", -1);
        // find the right parent
        Node parent = root;
        for (int i = 0; i < paths.length - 1; i++) {
            parent = parent.getChild(paths[i]);
            // no completion from here
            if (parent == null)
                return;
        }
        String lastPart = paths[paths.length - 1];
        // now find a matching child
        for (Node child : parent.getChildren()) {
            if (child.getLabel().startsWith(lastPart)) {
                collectArtifacts(child, query, result);
            }
        }
    }

    private void collectArtifacts(Node node, ModuleQuery lookup, ModuleSearchResult result) {
        // Winner of the less aptly-named method
        boolean isFolder = !node.hasBinaries();
        if (isFolder) {
            if (ArtifactContext.isDirectoryName(node.getLabel()))
                return;
            Ret ret = new Ret();
            if (hasChildrenContainingArtifact(node, lookup, ret)) {
                // we have artifact children, are they of the right type?
                if (ret.foundRightType) {
                    // collect them
                    String moduleName = toModuleName(node);
                    ModuleVersionDetails mvd = getSearchResult(moduleName, node, lookup);
                    if (mvd != null) {
                        result.addResult(moduleName, mvd);
                    }
                }
            } else {
                // collect in the children
                List<Node> sortedChildren = new ArrayList<Node>();
                for (Node child : node.getChildren())
                    sortedChildren.add(child);
                Collections.sort(sortedChildren, AlphabeticalNodeComparator);
                for (Node child : sortedChildren) {
                    collectArtifacts(child, lookup, result);
                }
            }
        }
    }

    private boolean hasChildrenContainingArtifact(Node node, ModuleQuery lookup, Ret ret) {
        // We don't look directly at our children, we want the children's children, because if there's
        // nothing in those children it means either this is an empty folder, or its children contain
        // artifacts (in which case we don't want to match it since its name must be a version component),
        // or we could only find artifacts of the wrong type.

        // This allows us to never match the default module, since it's at "/default/default.car" which
        // cannot match this rule. Normal modules always have at least one "/name/version/bla.car".
        boolean found = false;
        for (Node child : node.getChildren()) {
            String name = child.getLabel();
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            if (isFolder && !ArtifactContext.isDirectoryName(name) && containsArtifact(node, child, lookup, ret)){
                // stop if we found the right type
                if(ret.foundRightType)
                    return true;
                // keep looking for the other versions, perhaps we will find the right type
                found = true;
            }
        }
        return found;
    }

    private boolean containsArtifact(Node moduleNode, Node versionNode, ModuleQuery lookup, Ret ret) {
        String module = toModuleName(moduleNode);
        String version = versionNode.getLabel();
        boolean foundArtifact = false;
        for (Node child : versionNode.getChildren()) {
            String name = child.getLabel();
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            if (isFolder) {
                // do not recurse
            } else if (isArtifactOfType(name, child, module, version, lookup)) {
                // we found what we were looking for
                ret.foundRightType = true;
                return true;
            } else if (isArtifact(name, module, version)) {
                // we found something, but not the type we wanted
                foundArtifact = true;
            }
        }
        return foundArtifact;
    }

    private boolean isArtifactOfType(String name, Node node, String module, String version, ModuleQuery lookup) {
        if (lookup.getType() == ModuleQuery.Type.ALL) {
            return true;
        }
        for (String suffix : lookup.getType().getSuffixes()) {
            if (getArtifactName(module, version, suffix).equals(name)) {
                if (suffix.equals(ArtifactContext.CAR) || suffix.equals(ArtifactContext.JS)) {
                    return checkBinaryVersion(module, version, node, lookup);
                }
                return true;
            }
        }
        return false;
    }

    private boolean checkBinaryVersion(String module, String version, Node node, ModuleQuery lookup) {
        if (lookup.getBinaryMajor() == null && lookup.getBinaryMinor() == null)
            return true;
        try {
            File file = node.getContent(File.class);
            if (file == null)
                return false; // can't verify

            String suffix = ArtifactContext.getSuffixFromNode(node);
            ModuleInfoReader reader = getModuleInfoReader(suffix);
            if (reader != null) {
                int[] versions = reader.getBinaryVersions(module, version, file);
                if (versions == null)
                    return false; // can't verify
                if (lookup.getBinaryMajor() != null
                        && lookup.getBinaryMinor() != null 
                        && !Versions.isBinaryVersionCompatible(lookup.getBinaryMajor(), lookup.getBinaryMinor(), versions[0], versions[1]))
                    return false;
                return true;
            }
        } catch (Exception x) {
            // can't verify
        }
        return false;
    }

    /*
     * This method is almost like hasChildrenContainingArtifact but it scans for any type of artifact and records
     * the specific one we want in an out param
     */
    private boolean hasChildrenContainingAnyArtifact(Node moduleNode, ModuleQuery query, Ret ret) {
        // We don't look directly at our children, we want the children's children, because if there's
        // nothing in those children it means either this is an empty folder, or its children contain
        // artifacts (in which case we don't want to match it since its name must be a version component),
        // or we could only find artifacts of the wrong type.

        // This allows us to never match the default module, since it's at "/default/default.car" which
        // cannot match this rule. Normal modules always have at least one "/name/version/bla.car".
        for (Node versionNode : moduleNode.getChildren()) {
            String name = versionNode.getLabel();
            // Winner of the less aptly-named method
            boolean isFolder = !versionNode.hasBinaries();
            if (isFolder
                    && !ArtifactContext.isDirectoryName(name)
                    && containsAnyArtifact(moduleNode, versionNode, query, ret))
                return true;
        }
        // could not find any
        return false;
    }

    /*
     * This method is almost like containsArtifact but it scans for any type of artifact and records
     * the specific one we want in an out param. It's also not recursive so it only scans the current children.
     */
    private boolean containsAnyArtifact(Node moduleNode, Node versionNode, ModuleQuery query, Ret ret) {
        boolean foundArtifact = false;
        String version = versionNode.getLabel();
        String module = toModuleName(moduleNode);
        for (Node child : versionNode.getChildren()) {
            String name = child.getLabel();
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            if (isFolder) {
                // we don't recurse
            } else if (isArtifactOfType(name, child, module, version, query)
                    && matchesSearch(name, child, versionNode, query)) {
                // we found what we were looking for
                ret.foundRightType = true;
                return true;
            } else if (isArtifact(name, module, version)) {
                // we found something, but not the type we wanted
                foundArtifact = true;
            }
        }
        return foundArtifact;
    }

    private boolean matchesSearch(String name, Node artifact, Node versionNode, ModuleQuery query) {
        // match on the module name first
        Node moduleNode = NodeUtils.firstParent(versionNode);
        // can't happen but hey
        if (moduleNode == null)
            return false;
        String moduleName = toModuleName(moduleNode);
        if (moduleName.toLowerCase().contains(query.getName()))
            return true;
        // now search on the metadata
        Node infoArtifact = getBestInfoArtifact(versionNode);
        return matchFromCar(infoArtifact, moduleName, versionNode.getLabel(), query.getName());
    }

    private boolean matchFromCar(Node artifact, String moduleName, String version, String query) {
        try {
            File file = artifact.getContent(File.class);
            if (file != null) {
                ModuleInfoReader reader = getModuleInfoReader(artifact);
                if (reader != null) {
                    return reader.matchesModuleInfo(moduleName, version, file, query, getOverrides());
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    private boolean isArtifact(String name, String module, String version) {
        return getArtifactName(module, version, ArtifactContext.JS).equals(name)
                || getArtifactName(module, version, ArtifactContext.CAR).equals(name)
                || getArtifactName(module, version, ArtifactContext.JAR).equals(name)
                || getArtifactName(module, version, ArtifactContext.SRC).equals(name);
    }

    protected String toModuleName(Node node) {
        String path = NodeUtils.getFullPath(node, ".");
        // That's sort of an invariant, but let's be safe
        if (path.startsWith("."))
            path = path.substring(1);
        return path;
    }

    @Override
    public void completeVersions(ModuleVersionQuery lookup, ModuleVersionResult result) {
        // check for delegate
        ContentFinderDelegate delegate = root.getService(ContentFinderDelegate.class);
        if (delegate != null) {
            delegate.completeVersions(lookup, result, getOverrides());
            return;
        }
        // FIXME: handle default module
        // FIXME: we should really get this splitting done somewhere in common
        String name = lookup.getName();
        Node namePart = NodeUtils.getNode(root, Arrays.asList(name.split("\\.")));
        if (namePart == null)
            return;
        String memberName = lookup.getMemberName();
        // now each child is supposed to be a version part, let's verify that
        for (Node child : namePart.getChildren()) {
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            // ignore non-folders
            if (!isFolder)
                continue;
            // now make sure we can find the artifact we're looking for in there
            String version = child.getLabel();
            // optional filter on version
            if (lookup.getVersion() != null && !version.startsWith(lookup.getVersion()))
                continue;
            // avoid duplicates
            if (result.hasVersion(version))
                continue;
            // try every known suffix
            boolean found = false;
            boolean foundInfo = false;
            boolean binaryMatch = false;
            ModuleVersionDetails mvd = new ModuleVersionDetails(name, version);
            String[] suffixes = lookup.getType().getSuffixes();
            // When we need to find ALL requested suffixes we maintain a set of those not found yet
            HashSet<String> suffixesToFind = null;
            if (lookup.getRetrieval() == Retrieval.ALL) {
                suffixesToFind = new HashSet<String>(Arrays.asList(suffixes));
            }
            // Now try to retrieve information for each of the suffixes
            for (String suffix : suffixes) {
                String artifactName = getArtifactName(name, version, suffix);
                Node artifact = child.getChild(artifactName);
                if (artifact == null) {
                    if (lookup.getRetrieval() == Retrieval.ALL) {
                        break;
                    } else {
                        continue;
                    }
                }
                if (suffix.equals(ArtifactContext.CAR)
                        || suffix.equals(ArtifactContext.JS_MODEL) 
                        || suffix.equals(ArtifactContext.JS)
                        || suffix.equals(ArtifactContext.DART)
                        || suffix.equals(ArtifactContext.DART_MODEL)) {
                    if (!checkBinaryVersion(name, version, artifact, lookup)) {
                        if (lookup.getRetrieval() == Retrieval.ALL) {
                            break;
                        } else {
                            continue;
                        }
                    }
                    binaryMatch = true;
                }
                // we found the artifact: let's notify
                found = true;
                if (lookup.getRetrieval() == Retrieval.ALL) {
                    suffixesToFind.remove(suffix);
                }
                // let's see if we can extract some information
                switch(addArtifactInfo(artifact, name, version, suffix, memberName, mvd, lookup)){
                case INFO_FOUND:
                    foundInfo = true;
                    break;
                case NO_MATCH:
                    continue;
                case OTHER:
                    // nothing;
                    break;
                }
            }
            // NB: When searching for members it's not enough to have found
            // just any artifact, we need to make sure we were able to
            // read the artifact's information
            if (((found && memberName == null) || foundInfo)
                    && (lookup.getRetrieval() == Retrieval.ANY || suffixesToFind.isEmpty())
                    && ((lookup.getBinaryMajor() == null && lookup.getBinaryMinor() == null) || binaryMatch)) {
                mvd.setRemote(root.isRemote());
                mvd.setOrigin(getDisplayString());
                result.addVersion(mvd);
            }
        }
    }

    private static class Ret {
        public boolean foundRightType;
        public long found;
        public boolean stopSearching;
    }

    @SuppressWarnings("serial")
    private static class GetOut extends Exception {
    }

    @Override
    public boolean isSearchable() {
        // check for delegate
        ContentFinderDelegate delegate = root.getService(ContentFinderDelegate.class);
        if (delegate != null) {
            return delegate.isSearchable();
        }
        return true;
    }
    
    @Override
    public void searchModules(ModuleQuery query, ModuleSearchResult result) {
        // check for delegate
        ContentFinderDelegate delegate = root.getService(ContentFinderDelegate.class);
        if (delegate != null) {
            delegate.searchModules(query, result, getOverrides());
            return;
        }
        // do the searching the hard way
        try {
            searchModules(root, query, result, new Ret());
        } catch (GetOut e) {
            // easy out
        }
    }

    private void searchModules(Node parent, ModuleQuery query, ModuleSearchResult result, Ret ret) throws GetOut {
        List<Node> sortedChildren = new ArrayList<Node>();
        for (Node child : parent.getChildren())
            sortedChildren.add(child);
        Collections.sort(sortedChildren, AlphabeticalNodeComparator);
        for (Node child : sortedChildren) {
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            // ignore non-folders
            if (!isFolder)
                continue;
            // is it a module? does it contains artifacts?
            ret.foundRightType = false;
            if (!ArtifactContext.isDirectoryName(child.getLabel())) { // safety check
                if (hasChildrenContainingAnyArtifact(child, query, ret)) {
                    // does it contain an artifact of the type we're looking for?
                    if (ret.foundRightType) {
                        // check if we were already done but were checking for a next results
                        if (ret.stopSearching) {
                            // we already found enough results but were checking if there
                            // were more results to be found for paging, so record that
                            // and stop
                            result.setHasMoreResults(true);
                            throw new GetOut();
                        }
                        // are we interested in this result or did we need to skip it?
                        String moduleName = toModuleName(child);
                        ModuleVersionDetails mvd = getSearchResult(moduleName, child, query);
                        if (mvd != null) {
                            if (query.getStart() == null || ret.found++ >= query.getStart()) {
                                result.addResult(moduleName, mvd);
                                // stop if we're done searching
                                if (query.getStart() != null
                                        && query.getCount() != null
                                        && ret.found >= query.getStart() + query.getCount()) {
                                    // we're done, but we want to see if there's at least one more result
                                    // to be found so we can tell clients there's a next page
                                    ret.stopSearching = true;
                                }
                            }
                        }
                    }
                } else {
                    // it doesn't contain artifacts, it's probably leading to modules
                    searchModules(child, query, result, ret);
                }
            }
        }
    }

    private ModuleVersionDetails getSearchResult(String moduleName, Node namePart, ModuleQuery query) {
        SortedSet<String> versions = new TreeSet<String>();
        String[] suffixes = query.getType().getSuffixes();
        for (Node child : namePart.getChildren()) {
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            // ignore non-folders
            if (!isFolder)
                continue;
            // now make sure at least one of the artifacts we're looking for is in there
            String version = child.getLabel();
            // When we need to find ALL requested suffixes we maintain a set of those not found yet
            HashSet<String> suffixesToFind = null;
            if (query.getRetrieval() == Retrieval.ALL) {
                suffixesToFind = new HashSet<String>(Arrays.asList(suffixes));
            }
            // try every requested suffix
            for (String suffix : suffixes) {
                String artifactName = getArtifactName(moduleName, version, suffix);
                Node artifact = child.getChild(artifactName);
                if (artifact != null) {
                    if (query.getRetrieval() == Retrieval.ANY) {
                        // we found the artifact: store it
                        versions.add(version);
                        break;
                    } else { // Retrieval.ALL
                        suffixesToFind.remove(suffix);
                    }
                }
            }
            if (query.getRetrieval() == Retrieval.ALL && suffixesToFind.isEmpty()) {
                // we found the artifact and all of the requested suffixes: store it
                versions.add(version);
            }
        }
        // sanity check
        if (versions.isEmpty()) {
            // We didn't  find any versions so we silently skip this result
            return null;
        }
        // find the latest version
        String latestVersion = versions.last();
        Node versionChild = namePart.getChild(latestVersion);
        if (versionChild == null)
            throw new RuntimeException("Assertion failed: we didn't find the version child for " + moduleName + "/" + latestVersion);

        String memberName = query.getMemberName();
        ModuleVersionDetails mvd = new ModuleVersionDetails(moduleName, latestVersion);
        boolean found = false;
        // Now try to retrieve information for each of the suffixes
        for (String suffix : suffixes) {
            Node artifact;
            // make sure we don't try to read info from source artifacts
            if(ArtifactContext.SRC.equals(suffix)){
                artifact = getBestInfoArtifact(versionChild);
                if(artifact == null)
                    continue;
                suffix = ArtifactContext.getSuffixFromNode(artifact);
            }else{
                String artifactName = getArtifactName(moduleName, latestVersion, suffix);
                artifact = versionChild.getChild(artifactName);
            }
            if(artifact == null)
                continue;
            // let's see if we can extract some information
            switch(addArtifactInfo(artifact, moduleName, latestVersion, suffix, memberName, mvd, query)){
            case INFO_FOUND:
                found = true;
                // cool, go on
                break;
            case NO_MATCH:
                return null;
            case OTHER:
                // nothing;
                break;
            }
        }

        if(!found)
            return null;
        mvd.setRemote(root.isRemote());
        mvd.setOrigin(getDisplayString());

        return mvd;
    }

    private enum ArtifactInfoResult {
        INFO_FOUND, NO_MATCH, OTHER;
    }
    
    private ArtifactInfoResult addArtifactInfo(Node artifact, String name, String version, String suffix,
            String memberName, ModuleVersionDetails mvd, ModuleQuery lookup){
        // let's see if we can extract some information
        try {
            File file = artifact.getContent(File.class);
            if (file != null) {
                ModuleInfoReader reader = getModuleInfoReader(suffix);
                if (reader != null) {
                    ModuleVersionDetails mvd2 = reader.readModuleInfo(name, version, file, memberName != null, getOverrides());
                    Set<String> matchingMembers = null;
                    if (memberName != null) {
                        matchingMembers = matchMembers(mvd2, lookup);
                        if (matchingMembers.isEmpty()) {
                            // We haven't found a matching member in the module so we
                            // just continue to the next suffix/artifact if any
                            return ArtifactInfoResult.NO_MATCH;
                        }
                        mvd.getMembers().addAll(matchingMembers);
                    }
                    if (mvd2.getDoc() != null) {
                        mvd.setDoc(mvd2.getDoc());
                    }
                    if (mvd2.getLicense() != null) {
                        mvd.setLicense(mvd2.getLicense());
                    }
                    mvd.getAuthors().addAll(mvd2.getAuthors());
                    mvd.getDependencies().addAll(mvd2.getDependencies());
                    mvd.getArtifactTypes().addAll(mvd2.getArtifactTypes());
                    return ArtifactInfoResult.INFO_FOUND;
                } else {
                    if (memberName == null) {
                        // We didn't get any information but we'll at least add the artifact type to the result
                        mvd.getArtifactTypes().add(new ModuleVersionArtifact(suffix, null, null));
                    }
                }
            }
        } catch (Exception e) {
            // bah
        }
        return ArtifactInfoResult.OTHER;
    }
    private Set<String> matchMembers(ModuleVersionDetails mvd, ModuleQuery query) {
        return matchNames(mvd.getMembers(), query, false);
    }

    public static Set<String> matchNames(Set<String> names, ModuleQuery query, boolean namesArePackages) {
        // We're actually looking for a module containing a specific member
        SortedSet<String> found = new TreeSet<String>();
        String member = query.getMemberName();
        boolean matchPackagePart = !namesArePackages && query.isMemberSearchPackageOnly();
        if (query.isMemberSearchExact()) {
            for (String name : names) {
                if (matchPackagePart) {
                    name = packageName(name);
                }
                if (name.equals(member)) {
                    found.add(name);
                }
            }
        } else {
            member = member.toLowerCase();
            for (String name : names) {
                if (matchPackagePart) {
                    name = packageName(name);
                }
                if (name.toLowerCase().contains(member)) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    // Given a fully qualified member name return its package
    // (or an empty string if it's not part of any package)
    private static String packageName(String memberName) {
        int p = memberName.lastIndexOf("::");
        if (p >= 0) {
            return memberName.substring(0, p);
        } else {
            return "";
        }
    }
    
    private Node getBestInfoArtifact(Node versionNode) {
        String moduleName = toModuleName(NodeUtils.firstParent(versionNode));
        String version = versionNode.getLabel();
        String artifactName = getArtifactName(moduleName, version, ArtifactContext.CAR);
        Node artifact = versionNode.getChild(artifactName);
        if (artifact == null) {
            artifactName = getArtifactName(moduleName, version, ArtifactContext.JS);
            artifact = versionNode.getChild(artifactName);
            if (artifact == null) {
                artifactName = getArtifactName(moduleName, version, ArtifactContext.JAR);
                artifact = versionNode.getChild(artifactName);
            }
        }
        return artifact;
    }
    
    private ModuleInfoReader getModuleInfoReader(Node infoNode) {
        String suffix = ArtifactContext.getSuffixFromNode(infoNode);
        return getModuleInfoReader(suffix);
    }
    
    private ModuleInfoReader getModuleInfoReader(String suffix) {
        if (ArtifactContext.CAR.equalsIgnoreCase(suffix)) {
            return BytecodeUtils.INSTANCE;
        } else if (ArtifactContext.JAR.equalsIgnoreCase(suffix)) {
            return JarUtils.INSTANCE;
        } else if (ArtifactContext.JS.equalsIgnoreCase(suffix) || ArtifactContext.JS_MODEL.equalsIgnoreCase(suffix)) {
            return JSUtils.INSTANCE;
        } else {
            return null;
        }
    }
    
    protected Overrides getOverrides(){
        return getRoot().getService(Overrides.class);
    }
}
