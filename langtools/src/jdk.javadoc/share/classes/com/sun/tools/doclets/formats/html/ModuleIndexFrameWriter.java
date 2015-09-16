/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.doclets.formats.html;

import java.io.*;
import java.util.Map;
import java.util.Set;

import com.sun.javadoc.PackageDoc;
import com.sun.tools.doclets.formats.html.markup.*;
import com.sun.tools.doclets.internal.toolkit.*;
import com.sun.tools.doclets.internal.toolkit.util.*;

/**
 * Generate the module index for the left-hand frame in the generated output.
 * A click on the module name in this frame will update the page in the top
 * left hand frame with the listing of packages of the clicked module.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @author Bhavesh Patel
 */
public class ModuleIndexFrameWriter extends AbstractModuleIndexWriter {

    /**
     * Construct the ModuleIndexFrameWriter object.
     *
     * @param configuration the configuration object
     * @param filename Name of the module index file to be generated.
     */
    public ModuleIndexFrameWriter(ConfigurationImpl configuration,
                                   DocPath filename) throws IOException {
        super(configuration, filename);
    }

    /**
     * Generate the module index file named "module-overview-frame.html".
     * @throws DocletAbortException
     * @param configuration the configuration object
     */
    public static void generate(ConfigurationImpl configuration) {
        ModuleIndexFrameWriter modulegen;
        DocPath filename = DocPaths.MODULE_OVERVIEW_FRAME;
        try {
            modulegen = new ModuleIndexFrameWriter(configuration, filename);
            modulegen.buildModuleIndexFile("doclet.Window_Overview", false);
            modulegen.close();
        } catch (IOException exc) {
            configuration.standardmessage.error(
                        "doclet.exception_encountered",
                        exc.toString(), filename);
            throw new DocletAbortException(exc);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void addModulesList(Map<String, Set<PackageDoc>> modules, String text,
            String tableSummary, Content body) {
        Content heading = HtmlTree.HEADING(HtmlConstants.MODULE_HEADING, true,
                modulesLabel);
        HtmlTree htmlTree = (configuration.allowTag(HtmlTag.MAIN))
                ? HtmlTree.MAIN(HtmlStyle.indexContainer, heading)
                : HtmlTree.DIV(HtmlStyle.indexContainer, heading);
        HtmlTree ul = new HtmlTree(HtmlTag.UL);
        ul.setTitle(modulesLabel);
        for (String moduleName: modules.keySet()) {
            ul.addContent(getModuleLink(moduleName));
        }
        htmlTree.addContent(ul);
        body.addContent(htmlTree);
    }

    /**
     * Returns each module name as a separate link.
     *
     * @param moduleName the module being documented
     * @return content for the module link
     */
    protected Content getModuleLink(String moduleName) {
        Content moduleLinkContent;
        Content moduleLabel;
        moduleLabel = new StringContent(moduleName);
        moduleLinkContent = getHyperLink(DocPaths.moduleFrame(moduleName), moduleLabel, "",
                    "packageListFrame");
        Content li = HtmlTree.LI(moduleLinkContent);
        return li;
    }

    /**
     * {@inheritDoc}
     */
    protected void addNavigationBarHeader(Content body) {
        Content headerContent;
        if (configuration.packagesheader.length() > 0) {
            headerContent = new RawHtml(replaceDocRootDir(configuration.packagesheader));
        } else {
            headerContent = new RawHtml(replaceDocRootDir(configuration.header));
        }
        Content heading = HtmlTree.HEADING(HtmlConstants.TITLE_HEADING, true,
                HtmlStyle.bar, headerContent);
        body.addContent(heading);
    }

    /**
     * Do nothing as there is no overview information in this page.
     */
    protected void addOverviewHeader(Content body) {
    }

    /**
     * Adds "All Classes" link for the top of the left-hand frame page to the
     * documentation tree.
     *
     * @param ul the Content object to which the all classes link should be added
     */
    protected void addAllClassesLink(Content ul) {
        Content linkContent = getHyperLink(DocPaths.ALLCLASSES_FRAME,
                allclassesLabel, "", "packageFrame");
        Content li = HtmlTree.LI(linkContent);
        ul.addContent(li);
    }

    /**
     * Adds "All Packages" link for the top of the left-hand frame page to the
     * documentation tree.
     *
     * @param ul the Content object to which the all packages link should be added
     */
    protected void addAllPackagesLink(Content ul) {
        Content linkContent = getHyperLink(DocPaths.OVERVIEW_FRAME,
                allpackagesLabel, "", "packageListFrame");
        Content li = HtmlTree.LI(linkContent);
        ul.addContent(li);
    }

    /**
     * {@inheritDoc}
     */
    protected void addNavigationBarFooter(Content body) {
        Content p = HtmlTree.P(getSpace());
        body.addContent(p);
    }

    protected void addModulePackagesList(Map<String, Set<PackageDoc>> modules, String text,
            String tableSummary, Content body, String moduleName) {
    }
}