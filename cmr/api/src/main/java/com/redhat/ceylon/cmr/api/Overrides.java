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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.TransformerException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.redhat.ceylon.cmr.api.DependencyOverride.Type;
import com.redhat.ceylon.common.Backends;
import com.redhat.ceylon.common.ModuleUtil;
import com.redhat.ceylon.common.Versions;
import com.redhat.ceylon.model.cmr.ModuleScope;

/**
 * FIXME: we still need to define how add/remove/set works with replace or recursive replacements.
 * 
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 * @author Stef Epardaud
 */
public class Overrides {
    
    private static final Logger log = Logger.getLogger(Overrides.class.getName());
    
    public static class OverrideException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        
        public OverrideException(String message) {
            super(message);
        }

        public OverrideException(Exception e) {
            super(e);
        }
    }
    
    public static class OverrideNotFoundException extends OverrideException {
        private static final long serialVersionUID = 1L;
        
        public OverrideNotFoundException(String message) {
            super(message);
        }
    }
    
    public static class InvalidOverrideException extends OverrideException {
        private static final long serialVersionUID = 1L;
        public int line = -1;
        public int column = -1;
        InvalidOverrideException(String message, Element element) {
            super(message);
            Object data = null;
            data = element.getUserData(LINE_NUMBER_KEY_NAME);
            this.line = data == null ? -1 : Integer.parseInt((String) data);
            data = element.getUserData(COLUMN_NUMBER_KEY_NAME);
            this.column = data == null ? -1 : Integer.parseInt((String) data);
        }
        private InvalidOverrideException(InvalidOverrideException e, String file) {
            super(file + ":" +e.line +":"+e.column+ ": " + e.getMessage());
            this.line = e.line;
            this.column = e.column;
            this.setStackTrace(e.getStackTrace());
        }
    }
    
    private Map<ArtifactContext, ArtifactOverrides> overrides = new HashMap<>();
    private Map<String, ArtifactOverrides> overridesNoVersion = new HashMap<>();
    private Set<DependencyOverride> removed = new HashSet<DependencyOverride>();
    private Set<ArtifactContext> added = new HashSet<ArtifactContext>();

    private Map<ArtifactContext, ArtifactContext> replaced = new HashMap<>();
    private Map<String, ArtifactContext> replacedNoVersion = new HashMap<>();

    private Map<String, String> setVersions = new HashMap<>();

    private String source = null;
    
    private Overrides() {}
    
    /**
     * Creates an empty overrides. Mostly useful to create a dynamic overrides.
     */
    public static Overrides create() {
        return new Overrides();
    }

    public static Overrides getDistOverrides() {
        return getDistOverrides(true);
    }
    
    public static Overrides getDistOverrides(boolean upgradeDist) {
        try {
            return parseDistOverrides(upgradeDist);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public Overrides append(String overridesFileName) throws FileNotFoundException, Exception {
        return parse(overridesFileName, this);
    }
    
    public String toString() {
        return this.source == null ? super.toString() : "Overrides("+source+")";
    }
    
    public String getSource() {
        return source;
    }
    
    public void addArtifactOverride(ArtifactOverrides ao) {
        overrides.put(ao.getOwner(), ao);
        if(ao.getOwner().getVersion() == null)
            overridesNoVersion.put(ao.getOwner().getName(), ao);
    }

    public ArtifactOverrides getArtifactOverrides(ArtifactContext mc) {
        ArtifactOverrides ao = overrides.get(mc);
        if(ao == null){
            // fall-back to overrides with no version specified
            return overridesNoVersion.get(mc.getName());
        }
        return ao;
    }

    public void addAddedArtifact(ArtifactContext context) {
        added.add(context);
    }

    public void addRemovedArtifact(DependencyOverride context) {
        removed.add(context);
    }

    public boolean isRemoved(ArtifactContext context){
        for(DependencyOverride ro : removed){
            if(ro.matches(context))
                return true;
        }
        return false;
    }

    public ArtifactContext getReplacement(ArtifactContext mc) {
        ArtifactContext ao = replaced.get(mc);
        if(ao == null){
            // fall-back to overrides with no version specified
            ao = replacedNoVersion.get(mc.getName());
        }
        return ao;
    }

    public ArtifactContext replace(ArtifactContext context) {
        ArtifactOverrides artifactOverrides = getArtifactOverrides(context);
        if(artifactOverrides != null && artifactOverrides.getReplace() != null){
            ArtifactContext replacingContext = artifactOverrides.getReplace().getArtifactContext();
            return replace(context, replacingContext);
        }
        ArtifactContext replacingContext = getReplacement(context);
        if(replacingContext != null){
            return replace(context, replacingContext);
        }
        return null;
    }

    private ArtifactContext replace(ArtifactContext context, ArtifactContext replacingContext) {
        // FIXME: perhaps something smarter? I don't want to use replace.getContext() as it will be missing
        // query info such as what type of artifact we're looking for 
        ArtifactContext ret = context.copy();
        ret.setName(replacingContext.getName());
        // inherit version from the artifact we replace
        if(replacingContext.getVersion() != null)
            ret.setVersion(replacingContext.getVersion());
        else
            ret.setVersion(context.getVersion());
        // even if we replace, respect the set version
        ret.setVersion(getVersionOverride(ret));
        return ret;
    }

    private void addReplacedArtifact(ArtifactContext context, ArtifactContext withContext) {
        if(context.getVersion() == null)
            replacedNoVersion.put(context.getName(), withContext);
        else
            replaced.put(context, withContext);
    }

    private void addSetArtifact(ArtifactContext context) {
        setVersions.put(context.getName(), context.getVersion());
    }

    public void addSetArtifact(String module, String version) {
        setVersions.put(module, version);
    }

    public String getVersionOverride(ArtifactContext context){
        String overriddenVersion = setVersions.get(context.getName());
        if(overriddenVersion != null)
            return overriddenVersion;
        return context.getVersion();
    }
    
    public boolean isVersionOverridden(ArtifactContext context){
        return setVersions.containsKey(context.getName());
    }

    public ModuleInfo applyOverrides(String module, String version, ModuleInfo source) {
        ArtifactOverrides artifactOverrides = getArtifactOverrides(new ArtifactContext(null, module, version));
        Set<ModuleDependencyInfo> result = new HashSet<ModuleDependencyInfo>();
        for (ModuleDependencyInfo dep : source.getDependencies()) {
            String depNamespace = dep.getNamespace();
            String depName = dep.getName();
            String depVersion = dep.getVersion();
            boolean optional = dep.isOptional();
            boolean export = dep.isExport();
            ModuleScope scope = dep.getModuleScope();
            Backends backends = dep.getNativeBackends();
            ArtifactContext ctx = new ArtifactContext(depNamespace, depName, depVersion);
            if((artifactOverrides != null && artifactOverrides.isRemoved(ctx))
                    || isRemoved(ctx))
                continue;
            if(artifactOverrides != null && artifactOverrides.isAddedOrUpdated(ctx))
                continue;
            ArtifactContext replacement = replace(ctx);
            if(replacement != null){
                depNamespace = replacement.getNamespace();
                depName = replacement.getName();
                depVersion = replacement.getVersion();
                ctx = replacement;
            }
            if(isVersionOverridden(ctx))
                depVersion = getVersionOverride(ctx);
            if(artifactOverrides != null){
                if(artifactOverrides.isShareOverridden(ctx))
                    export = artifactOverrides.isShared(ctx);
                if(artifactOverrides.isOptionalOverridden(ctx))
                    optional = artifactOverrides.isOptional(ctx);
            }

            result.add(new ModuleDependencyInfo(depNamespace, depName, depVersion,
                    optional, export, backends, scope));
        }
        String filter = source.getFilter();
        if(artifactOverrides != null){
            if(artifactOverrides.getFilter() != null)
                filter = artifactOverrides.getFilter();
            for(DependencyOverride add : artifactOverrides.getAdd()){
                ArtifactContext addContext = add.getArtifactContext();
                result.add(new ModuleDependencyInfo(addContext.getNamespace(), 
                        addContext.getName(), addContext.getVersion(),
                        add.isOptional(),
                        add.isShared()));
            }
        }
        return new ModuleInfo(module, version, source.getGroupId(), source.getArtifactId(), source.getClassifier(), filter, result);
    }

    private static Overrides parse(String overridesFileName, Overrides overrides) throws OverrideException{
        File overridesFile = new File(overridesFileName);
        if (overridesFile.exists() == false) {
            throw new OverrideNotFoundException("No such overrides file: " + overridesFile);
        }
        try(InputStream is = new FileInputStream(overridesFile)){
            Overrides.parse(is, overrides);
            overrides.source = overridesFile.getAbsolutePath();
            return overrides;
        } catch (InvalidOverrideException e ) {
            throw new InvalidOverrideException(e, overridesFileName);
        } catch (FileNotFoundException e) {
            throw new OverrideNotFoundException("No such overrides file: " + overridesFile);
        } catch (Exception e) {
            throw new OverrideException(e.getMessage());
        }
    }
    
    public static Overrides parseDistOverrides(boolean upgradeDist) throws OverrideException {
        URL resource = Overrides.class.getResource("/com/redhat/ceylon/cmr/api/dist-overrides.xml");
        try(InputStream is = resource.openStream()){
            try {
                Document document = parseXml(is);
                String elementName = upgradeDist ? "dist-upgrade" : "dist-downgrade";
                List<Element> e = getChildren(document.getDocumentElement(), elementName);
                if (e.size() == 1) {
                    Overrides overrides = new Overrides();
                    parseOverrides(overrides, e.get(0));
                    overrides.source = resource.toString();
                    return overrides;
                } else {
                    throw new InvalidOverrideException(String.format("Expected exactly one '%s' element in dist-overrides.xml, but found %d", elementName, e.size()), document.getDocumentElement());
                }
                
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
        catch (FileNotFoundException e) {
            throw new OverrideNotFoundException("No such overrides file: " + resource);
        } catch (Exception e) {
            throw new OverrideException(e);
        }
    }
    
    
    static void parse(InputStream is, Overrides result) throws Exception {
        try {
            Document document = parseXml(is);
            
            Element rootElement = document.getDocumentElement();
            parseOverrides(result, rootElement);
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Parse the overrides in the given root element (usually called 
     * {@code <overrides>}, but the name doesn't actually matter) 
     * updating the given result.
     * @param result
     * @param rootElement
     * @return
     * @throws TransformerException
     */
    protected static void parseOverrides(final Overrides result, Element rootElement) throws TransformerException {
        Map<String,String> interpolation = new HashMap<>();
        List<Element> defines = getChildren(rootElement, "define");
        for (Element define : defines) {
            // do not interpolate while we're defining things
            String name = getRequiredAttribute(define, "name", null);
            String value = getRequiredAttribute(define, "value", null);
            interpolation.put(name, value);
        }
        
        // old name
        List<Element> artifacts = getChildren(rootElement, "artifact");
        parseArtifacts(artifacts, result, interpolation);
        // new name
        List<Element> modules = getChildren(rootElement, "module");
        parseArtifacts(modules, result, interpolation);

        List<Element> removedArtifacts = getChildren(rootElement, "remove");
        for (Element artifact : removedArtifacts) {
            ArtifactContext context = getArtifactContext(artifact, true, interpolation);
            DependencyOverride doo = new DependencyOverride(context, Type.REMOVE, false, false);
            result.addRemovedArtifact(doo);
        }
        List<Element> addedArtifacts = getChildren(rootElement, "add");
        for (Element artifact : addedArtifacts) {
            ArtifactContext context = getArtifactContext(artifact, false, interpolation);
            result.addAddedArtifact(context);
        }
        List<Element> replacedArtifacts = getChildren(rootElement, "replace");
        for (Element artifact : replacedArtifacts) {
            ArtifactContext context = getArtifactContext(artifact, true, interpolation);
            List<Element> withs = getChildren(artifact, "with");
            for (Element with : withs) {
                ArtifactContext withContext = getArtifactContext(with, true, interpolation);
                result.addReplacedArtifact(context, withContext);
            }
        }
        List<Element> setArtifacts = getChildren(rootElement, "set");
        for (Element artifact : setArtifacts) {
            ArtifactContext context = getArtifactContext(artifact, true, interpolation);
            result.addSetArtifact(context);
        }
    }

    private static void parseArtifacts(List<Element> artifacts, Overrides result, Map<String, String> interpolation) throws TransformerException {
        for (Element artifact : artifacts) {
            ArtifactContext mc = getArtifactContext(artifact, true, interpolation); // version is optional
            ArtifactOverrides ao = new ArtifactOverrides(mc);
            result.addArtifactOverride(ao);
            addOverrides(ao, artifact, DependencyOverride.Type.ADD, interpolation);
            addOverrides(ao, artifact, DependencyOverride.Type.REMOVE, interpolation);
            addOverrides(ao, artifact, DependencyOverride.Type.REPLACE, interpolation);
            // filter
            List<Element> filterNode = getChildren(artifact, "filter");
            if (filterNode != null && !filterNode.isEmpty()) {
                Node node = filterNode.get(0);
                ao.setFilter(interpolate(PathFilterParser.convertNodeToString(node), interpolation));
            }
            List<Element> versionNode = getChildren(artifact, "version");
            if (versionNode != null && !versionNode.isEmpty()) {
                Node node = versionNode.get(0);
                ao.setVersion(interpolate(node.getTextContent(), interpolation));
            }
            List<Element> classifierNode = getChildren(artifact, "classifier");
            if (classifierNode != null && !classifierNode.isEmpty()) {
                Node node = classifierNode.get(0);
                ao.setClassifier(interpolate(node.getTextContent(), interpolation));
            }
            List<Element> shareArtifacts = getChildren(artifact, "share");
            for (Element share : shareArtifacts) {
                ArtifactContext context = getArtifactContext(share, true, interpolation);
                ao.addShareOverride(context, true);
            }
            List<Element> unshareArtifacts = getChildren(artifact, "unshare");
            for (Element unshare : unshareArtifacts) {
                ArtifactContext context = getArtifactContext(unshare, true, interpolation);
                ao.addShareOverride(context, false);
            }
            List<Element> optionalArtifacts = getChildren(artifact, "optional");
            for (Element optional : optionalArtifacts) {
                ArtifactContext context = getArtifactContext(optional, true, interpolation);
                ao.addOptionalOverride(context, true);
            }
            List<Element> requireArtifacts = getChildren(artifact, "require");
            for (Element require : requireArtifacts) {
                ArtifactContext context = getArtifactContext(require, true, interpolation);
                ao.addOptionalOverride(context, false);
            }
        }
    }

    public static String interpolate(String string, Map<String, String> interpolation) {
        if(interpolation == null || string == null || string.isEmpty())
            return string;
        int firstReplacement = string.indexOf("${");
        if(firstReplacement == -1)
            return string;
        StringBuffer strbuf = new StringBuffer(string.length());
        int start = 0;
        int[] end = new int[1];
        while(firstReplacement != -1){
            // put the start
            strbuf.append(string, start, firstReplacement);
            String part = replace(string, firstReplacement+2, end, interpolation);
            strbuf.append(part);
            // move to the end of replacement
            start = Math.min(end[0]+1, string.length());
            firstReplacement = string.indexOf("${", start);
        }
        // now put whatever remains
        strbuf.append(string, start, string.length());
        return strbuf.toString();
    }

    private static String replace(String string, int start, int[] end, Map<String, String> interpolation) {
        StringBuffer strbufName = new StringBuffer(string.length());
        // now find the end
        boolean seenDollar = false;
        for(int i=start;i<string.length();i++){
            char c = string.charAt(i);
            // new subst second char
            if(seenDollar){
                if(c == '{'){
                    String replacement = replace(string, i+1, end, interpolation);
                    strbufName.append(replacement);
                    // move to the end of the variable
                    i = end[0];
                    seenDollar = false;
                    continue;
                }
                // previous dollar was not a pattern match, add it
                strbufName.append('$');
            }
            // new subst first char
            if(c == '$'){
                seenDollar = true;
                continue;
            }else{
                seenDollar = false;
            }
            // end of subst
            if(c == '}'){
                String name = strbufName.toString();
                // we are done: now return
                end[0] = i;
                if ("CEYLON_VERSION".equals(name)) {
                    return Versions.CEYLON_VERSION_NUMBER;
                } else if(interpolation.containsKey((name))){
                    String value = interpolation.get(name);
                    // do not forget to interpolate values too
                    return interpolate(value, interpolation);
                }else{
                    // missing interpolation
                    return "${" + name + '}';
                }
            }else{
                strbufName.append(c);
            }
        }
        // if we've gone to the end without finding the end of "}" then let's no substitute
        end[0] = string.length();
        return "${" + strbufName.toString();
    }

    protected static ArtifactContext getArtifactContext(Element element, boolean optionalVersion, Map<String, String> interpolation) {
        String groupId = getAttribute(element, "groupId", interpolation);
        if(groupId != null){
            String artifactId = getRequiredAttribute(element, "artifactId", interpolation);
            String version = optionalVersion ? getAttribute(element, "version", interpolation) : getRequiredAttribute(element, "version", interpolation);
            String packaging = getAttribute(element, "packaging", interpolation);
            String classifier = getAttribute(element, "classifier", interpolation);
            return new MavenArtifactContext(groupId, artifactId, classifier, version, packaging);
        }else{
            String moduleUri = getRequiredAttribute(element, "module", interpolation);
            String namespace = ModuleUtil.getNamespaceFromUri(moduleUri);
            String module = ModuleUtil.getModuleNameFromUri(moduleUri);
            String version = optionalVersion ? getAttribute(element, "version", interpolation) : getRequiredAttribute(element, "version", interpolation);
            return new ArtifactContext(namespace, module, version);
        }
    }

    protected static void addOverrides(ArtifactOverrides ao, Element artifact, DependencyOverride.Type type, Map<String, String> interpolation) {
        List<Element> overrides = getChildren(artifact, type.name().toLowerCase());
        for (Element override : overrides) {
            ArtifactContext dep = getArtifactContext(override, type == Type.REMOVE, interpolation);
            boolean shared = getBooleanAttribute(override, "shared", interpolation);
            boolean optional = getBooleanAttribute(override, "optional", interpolation);
            DependencyOverride doo = new DependencyOverride(dep, type, shared, optional);
            ao.addOverride(doo);
        }
    }

    protected static boolean getBooleanAttribute(Element element, String name, Map<String, String> interpolation) {
        String val = getAttribute(element, name, interpolation);
        return val != null && val.toLowerCase().equals("true");
    }

    protected static String getAttribute(Element element, String name, Map<String, String> interpolation) {
        String value = interpolate(element.getAttribute(name), interpolation);
        return (value == null || value.length() == 0) ? null : value;
    }

    protected static String getRequiredAttribute(Element element, String name, Map<String, String> interpolation) {
        String value = getAttribute(element, name, interpolation);
        if (value == null) {
            throw new InvalidOverrideException(String.format("Missing '%s' attribute in element %s.", name, element), element);
        }
        return value;
    }

    final static String LINE_NUMBER_KEY_NAME = "lineNumber";
    final static String COLUMN_NUMBER_KEY_NAME = "columnNumber";

    /**
     * Parse the given input stream to a Document
     */
    protected static Document parseXml(InputStream inputStream) throws ParserConfigurationException, SAXException, IOException {
        // We parse using SAX so that we can add location info to the DOM
        // for error reporting
        final Document doc;
        SAXParser parser;
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        parser = factory.newSAXParser();
        final DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
        final DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
        doc = docBuilder.newDocument();

        final Stack<Element> elementStack = new Stack<Element>();
        final StringBuilder textBuffer = new StringBuilder();
        final DefaultHandler handler = new DefaultHandler() {
            private Locator locator;

            @Override
            public void setDocumentLocator(final Locator locator) {
                this.locator = locator; // Save the locator, so that it can be used later for line tracking when traversing nodes.
            }

            @Override
            public void startElement(final String uri, final String localName, final String qName, final Attributes attributes)
                    throws SAXException {
                addTextIfNeeded();
                final Element el = doc.createElement(qName);
                for (int i = 0; i < attributes.getLength(); i++) {
                    el.setAttribute(attributes.getQName(i), attributes.getValue(i));
                }
                el.setUserData(LINE_NUMBER_KEY_NAME, String.valueOf(this.locator.getLineNumber()), null);
                el.setUserData(COLUMN_NUMBER_KEY_NAME, String.valueOf(this.locator.getColumnNumber()), null);
                elementStack.push(el);
            }

            @Override
            public void endElement(final String uri, final String localName, final String qName) {
                addTextIfNeeded();
                final Element closedEl = elementStack.pop();
                if (elementStack.isEmpty()) { // Is this the root element?
                    doc.appendChild(closedEl);
                } else {
                    final Element parentEl = elementStack.peek();
                    parentEl.appendChild(closedEl);
                }
            }

            @Override
            public void characters(final char ch[], final int start, final int length) throws SAXException {
                textBuffer.append(ch, start, length);
            }

            // Outputs text accumulated under the current node
            private void addTextIfNeeded() {
                if (textBuffer.length() > 0) {
                    final Element el = elementStack.peek();
                    final Node textNode = doc.createTextNode(textBuffer.toString());
                    el.appendChild(textNode);
                    textBuffer.delete(0, textBuffer.length());
                }
            }
        };
        parser.parse(inputStream, handler);
        doc.getDocumentElement().normalize();
        return doc;
    }

    private static List<Element> getChildren(Element element, String tagName) {
        NodeList nodes = element.getChildNodes();
        List<Element> ret = new ArrayList<>(nodes.getLength());
        for(int i=0;i<nodes.getLength();i++){
            Node item = nodes.item(i);
            if(item.getNodeType() == Node.ELEMENT_NODE
                    && ((Element)item).getTagName().equals(tagName))
                ret.add((Element) item);
        }
        return ret;
    }

    public ArtifactContext applyOverrides(final ArtifactContext sought) {
        ArtifactContext replacedContext = this.replace(sought);
        if(replacedContext == null) {
            replacedContext = sought;
        } else {
            log.fine(this + ": " + sought + " -> " + replacedContext);
        }
        String versionOverride = this.getVersionOverride(replacedContext);
        if (versionOverride != null // only possible to default module, and we can't override that 
                && !versionOverride.equals(replacedContext.getVersion())) {
            log.fine(this + ": " + replacedContext + " -> version " + versionOverride);
            replacedContext.setVersion(versionOverride);
        }
        return replacedContext;
    }

    public Set<ArtifactContext> getAddedArtifacts() {
        return added;
    }
}
