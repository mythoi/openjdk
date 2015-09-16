/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

package jdk.tools.jlink;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.module.Configuration;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ResolutionException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Layer;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.internal.jimage.Archive;
import jdk.internal.module.ConfigurableModuleFinder;
import jdk.internal.module.ConfigurableModuleFinder.Phase;
import jdk.tools.jlink.TaskHelper.BadArgs;
import jdk.tools.jlink.TaskHelper.HiddenOption;
import jdk.tools.jlink.TaskHelper.Option;
import jdk.tools.jlink.TaskHelper.OptionsHelper;
import jdk.tools.jlink.internal.ModularJarArchive;
import jdk.tools.jlink.internal.JmodArchive;
import jdk.tools.jlink.internal.DirArchive;
import jdk.tools.jlink.internal.ImageFileCreator;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.ImagePluginStack;
import jdk.tools.jlink.plugins.Jlink.JlinkConfiguration;
import jdk.tools.jlink.plugins.Jlink.PluginConfiguration;
import jdk.tools.jlink.plugins.Jlink.PluginsConfiguration;
import jdk.tools.jlink.plugins.Jlink.StackedPluginConfiguration;


/**
 * Implementation for the jlink tool.
 *
 * ## Should use jdk.joptsimple some day.
 */
public class JlinkTask {

    static <T extends Throwable> void fail(Class<T> type,
                                           String format,
                                           Object... args) throws T {
        String msg = new Formatter().format(format, args).toString();
        try {
            T t = type.getConstructor(String.class).newInstance(msg);
            throw t;
        } catch (InstantiationException |
                 InvocationTargetException |
                 NoSuchMethodException |
                 IllegalAccessException e) {
            throw new InternalError("Unable to create an instance of " + type, e);
        }
    }

    private static final TaskHelper taskHelper
            = new TaskHelper("jdk.tools.jlink.resources.jlink");

    static Option<?>[] recognizedOptions = {
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.help = true;
        }, "--help"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            String[] dirs = arg.split(File.pathSeparator);
            task.options.modulePath = new Path[dirs.length];
            int i = 0;
            for (String dir : dirs) {
                task.options.modulePath[i++] = Paths.get(dir);
            }
        }, "--modulepath", "--mp"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            for (String mn : arg.split(",")) {
                if (mn.isEmpty()) {
                    throw taskHelper.newBadArgs("err.mods.must.be.specified",
                                                "--limitmods");
                }
                task.options.limitMods.add(mn);
            }
        }, "--limitmods"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            for (String mn : arg.split(",")) {
                if (mn.isEmpty()) {
                    throw taskHelper.newBadArgs("err.mods.must.be.specified",
                                                "--addmods");
                }
                task.options.addMods.add(mn);
            }
        }, "--addmods"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            Path path = Paths.get(arg);
            task.options.output = path;
        }, "--output"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            if ("little".equals(arg)) {
                task.options.endian = ByteOrder.LITTLE_ENDIAN;
            } else {
                if ("big".equals(arg)) {
                    task.options.endian = ByteOrder.BIG_ENDIAN;
                } else {
                    throw taskHelper.newBadArgs("err.unknown.byte.order", arg);
                }
            }
        }, "--endian"),
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.version = true;
        }, "--version"),
        new HiddenOption<JlinkTask>(false, (task, opt, arg) -> {
            task.options.fullVersion = true;
        }, "--fullversion"),
    };

    private static final String PROGNAME = "jlink";
    private final OptionsValues options = new OptionsValues();

    private static final OptionsHelper<JlinkTask> optionsHelper =
            taskHelper.newOptionsHelper(JlinkTask.class, recognizedOptions);
    private PrintWriter log;
    void setLog(PrintWriter out) {
        log = out;
        taskHelper.setLog(log);
    }

    /**
     * Result codes.
     */
    static final int EXIT_OK = 0, // Completed with no errors.
                     EXIT_ERROR = 1, // Completed but reported errors.
                     EXIT_CMDERR = 2, // Bad command-line arguments
                     EXIT_SYSERR = 3, // System error or resource exhaustion.
                     EXIT_ABNORMAL = 4;// terminated abnormally

    static class OptionsValues {
        boolean help;
        boolean version;
        boolean fullVersion;
        Path[] modulePath;
        Set<String> limitMods = new HashSet<>();
        Set<String> addMods = new HashSet<>();
        Path output;
        ByteOrder endian = ByteOrder.nativeOrder();
    }

    int run(String[] args) {
        if (log == null) {
            setLog(new PrintWriter(System.err));
        }
        try {
            optionsHelper.handleOptions(this, args);
            if (options.help) {
                optionsHelper.showHelp(PROGNAME, "jimage creation only options:", true);
                return EXIT_OK;
            }
            if (options.version || options.fullVersion) {
                taskHelper.showVersion(options.fullVersion);
                return EXIT_OK;
            }
            if(optionsHelper.listPlugins()) {
                optionsHelper.showPlugins(log, true);
                 return EXIT_OK;
            }
            if (options.modulePath == null || options.modulePath.length == 0)
                throw taskHelper.newBadArgs("err.modulepath.must.be.specified").showUsage(true);

            createImage();

            return EXIT_OK;
        } catch (IOException | ResolutionException e) {
            log.println(taskHelper.getMessage("error.prefix") + " " + e.getMessage());
            log.println(taskHelper.getMessage("main.usage.summary", PROGNAME));
            return EXIT_ERROR;
        } catch (BadArgs e) {
            taskHelper.reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(taskHelper.getMessage("main.usage.summary", PROGNAME));
            }
            return EXIT_CMDERR;
        } catch (Throwable x) {
            log.println(taskHelper.getMessage("main.msg.bug"));
            x.printStackTrace(log);
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private static Map<String, Path> modulesToPath(ModuleFinder finder,
                                                   Set<ModuleDescriptor> modules)
    {
        Map<String,Path> modPaths = new HashMap<>();
        for (ModuleDescriptor m : modules) {
            String name = m.name();

            Optional<ModuleReference> omref = finder.find(name);
            if (!omref.isPresent()) {
                // this should not happen, module path bug?
                fail(InternalError.class,
                     "Selected module %s not on module path",
                     name);
            }

            URI location = omref.get().location().get();
            String scheme = location.getScheme();
            if (!scheme.equalsIgnoreCase("jmod") && !scheme.equalsIgnoreCase("jar")
                    && !scheme.equalsIgnoreCase("file")) {
                fail(RuntimeException.class,
                        "Selected module %s (%s) not in jmod, modular jar or directory format",
                        name,
                        location);
            }

            // convert to file URIs
            URI fileURI;
            if (scheme.equalsIgnoreCase("jmod")) {
                // jmod:file:/home/duke/duke.jmod!/ -> file:/home/duke/duke.jmod
                String s = location.toString();
                fileURI = URI.create(s.substring(5, s.length()-2));
            } else {
                if (scheme.equalsIgnoreCase("jar")) {
                    // jar:file:/home/duke/duke.jar!/ -> file:/home/duke/duke.jar
                    String s = location.toString();
                    fileURI = URI.create(s.substring(4, s.length() - 2));
                } else {
                    fileURI = URI.create(location.toString());
                }
            }

            modPaths.put(name, Paths.get(fileURI));
        }
        return modPaths;
    }

    /*
     * Jlink API entry point.
     */
    public static void createImage(JlinkConfiguration config,
                                   PluginsConfiguration plugins)
        throws Exception
    {
        Objects.requireNonNull(config);
        Objects.requireNonNull(config.getOutput());
        createOutputDirectory(config.getOutput());
        plugins = plugins == null ? new PluginsConfiguration() : plugins;

        if (config.getModulepaths().isEmpty()) {
            throw new Exception("Empty module paths");
        }
        Path[] arr = new Path[config.getModulepaths().size()];
        arr = config.getModulepaths().toArray(arr);
        ModuleFinder finder = newModuleFinder(arr, config.getLimitmods());

        Path[] pluginsPath = new Path[config.getPluginpaths().size()];
        pluginsPath = config.getPluginpaths().toArray(pluginsPath);

        ImageFileHelper imageHelper
            = createImageFileHelper(config.getOutput(),
                        finder,
                        checkAddMods(config.getModules(), config.getLimitmods()),
                        config.getLimitmods(),
                        TaskHelper.createPluginsLayer(pluginsPath),
                        genBOMContent(config, plugins), config.getByteOrder());
        imageHelper.createModularImage(plugins);
    }

    private void createImage() throws Exception {
        if (options.output == null) {
            throw taskHelper.newBadArgs("err.output.must.be.specified").showUsage(true);
        }
        try {
            createOutputDirectory(options.output);
        } catch (IllegalArgumentException ex) {
            throw taskHelper.newBadArgs("err.dir.not.empty", options.output);
        }
        ModuleFinder finder = newModuleFinder(options.modulePath, options.limitMods);
        try {
            options.addMods = checkAddMods(options.addMods, options.limitMods);
        } catch (IllegalArgumentException ex) {
            throw taskHelper.newBadArgs("err.mods.must.be.specified", "--addmods")
                    .showUsage(true);
        }
        ImageFileHelper imageHelper
            = createImageFileHelper(options.output,
                                    finder,
                                    options.addMods,
                                    options.limitMods,
                                    optionsHelper.getPluginsLayer(),
                                    genBOMContent(),
                                    options.endian);

        imageHelper.createModularImage(taskHelper.getPluginsProperties());
    }


    private static void createOutputDirectory(Path output)
        throws IOException, BadArgs
    {
        if (Files.exists(output) && !Files.isDirectory(output)) {
            throw taskHelper.newBadArgs("err.file.already.exists", output).showUsage(true);
        }
        Files.createDirectories(output);
        if (Files.list(output).findFirst().isPresent()) {
            throw new IllegalArgumentException(output + " already exists");
        }
    }

    private static Set<String> checkAddMods(Set<String> addMods, Set<String> limitMods) {
        if (addMods.isEmpty()) {
            if (limitMods.isEmpty()) {
                throw new IllegalArgumentException("empty modules and limitmodules");
            }
            addMods = limitMods;
        }
        return addMods;
    }

    private static ModuleFinder newModuleFinder(Path[] paths, Set<String> limitMods) {
        ModuleFinder finder = ModuleFinder.of(paths);

        // jmods are located at link-time
        if (finder instanceof ConfigurableModuleFinder)
            ((ConfigurableModuleFinder)finder).configurePhase(Phase.LINK_TIME);

        // if limitmods is specified then limit the universe
        if (!limitMods.isEmpty()) {
            finder = limitFinder(finder, limitMods);
        }
        return finder;
    }

    private static ImageFileHelper createImageFileHelper(Path output,
                                                         ModuleFinder finder,
                                                         Set<String> addMods,
                                                         Set<String> limitMods,
                                                         Layer pluginsLayer,
                                                         String bom,
                                                         ByteOrder order)
        throws IOException
    {
        if (addMods.isEmpty()) {
            if (limitMods.isEmpty()) {
                throw new IllegalArgumentException("empty modules and limitmods");
            }
            addMods = limitMods;
        }
        Configuration cf
            = Configuration.resolve(finder,
                    Layer.empty(),
                    ModuleFinder.empty(),
                    addMods);
        cf = cf.bind();
        Map<String, Path> mods = modulesToPath(finder, cf.descriptors());
        return new ImageFileHelper(cf, mods, output, pluginsLayer, bom, order);
    }


    /**
     * Returns a ModuleFinder that locates modules via the given ModuleFinder
     * but limits what can be found to the given modules and their transitive
     * dependences.
     */
    private static ModuleFinder limitFinder(ModuleFinder finder, Set<String> mods) {
        Configuration cf
            = Configuration.resolve(finder,
                Layer.empty(),
                ModuleFinder.empty(),
                mods);

        // module name -> reference
        Map<String, ModuleReference> map = new HashMap<>();
        cf.descriptors().forEach(md -> {
            String name = md.name();
            map.put(name, finder.find(name).get());
        });

        Set<ModuleReference> mrefs = new HashSet<>(map.values());

        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(map.get(name));
            }
            @Override
            public Set<ModuleReference> findAll() {
                return mrefs;
            }
        };
    }

    private static String getBomHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(new Date()).append("\n");
        sb.append("#Please DO NOT Modify this file").append("\n");
        return sb.toString();
    }

    private String genBOMContent() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(getBomHeader());
        StringBuilder command = new StringBuilder();
        for (String c : optionsHelper.getInputCommand()) {
            command.append(c).append(" ");
        }
        sb.append("command").append(" = ").append(command);
        sb.append("\n");

        // Expanded command
        String[] expanded = optionsHelper.getExpandedCommand();
        if (expanded != null) {
            String defaults = optionsHelper.getDefaults();
            sb.append("\n").append("#Defaults").append("\n");
            sb.append("defaults = ").append(defaults).append("\n");

            StringBuilder builder = new StringBuilder();
            for (String c : expanded) {
                builder.append(c).append(" ");
            }
            sb.append("expanded command").append(" = ").append(builder);
            sb.append("\n");
        }

        String pluginsContent = optionsHelper.getPluginsConfig();
        if (pluginsContent != null) {
            sb.append("\n").append("# Plugins configuration\n");
            sb.append(pluginsContent);
        }
        return sb.toString();
    }

    private static String genBOMContent(JlinkConfiguration config,
                                        PluginsConfiguration plugins)
        throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getBomHeader());
        sb.append(config);
        sb.append(plugins);
        return sb.toString();
    }

    private static class ImageFileHelper {
        final Set<ModuleDescriptor> modules;
        final Map<String, Path> modsPaths;
        final Path output;
        final Set<Archive> archives;
        final Layer pluginsLayer;
        final String bom;
        final ByteOrder order;

        ImageFileHelper(Configuration cf,
                        Map<String, Path> modsPaths,
                        Path output,
                        Layer pluginsLayer,
                        String bom,
                        ByteOrder order)
            throws IOException
        {
            this.modules = cf.descriptors();
            this.modsPaths = modsPaths;
            this.output = output;
            this.pluginsLayer = pluginsLayer;
            this.bom = bom;
            archives = modsPaths.entrySet().stream()
                    .map(e -> newArchive(e.getKey(), e.getValue()))
                    .collect(Collectors.toSet());
            this.order = order;
        }

        void createModularImage(Properties properties) throws Exception {
            ImagePluginStack pc = ImagePluginConfiguration.
                    parseConfiguration(output, modsPaths,
                            properties, pluginsLayer,
                            bom);
            ImageFileCreator.create(archives, order, pc);
        }

        void createModularImage(PluginsConfiguration plugins) throws Exception {
            ImagePluginStack pc = ImagePluginConfiguration.
                    parseConfiguration(output, modsPaths, plugins, pluginsLayer, bom);
            ImageFileCreator.create(archives, order, pc);
        }

        private Archive newArchive(String module, Path path) {
            if (path.toString().endsWith(".jmod")) {
                return new JmodArchive(module, path);
            } else {
                if (path.toString().endsWith(".jar")) {
                    return new ModularJarArchive(module, path);
                } else {
                    if (Files.isDirectory(path)) {
                        return new DirArchive(path);
                    } else {
                        fail(RuntimeException.class,
                                "Selected module %s (%s) not in jmod or modular jar format",
                                module,
                                path);
                    }
                }
            }
            return null;
        }
    }

    private static enum Section {
        NATIVE_LIBS("native", nativeDir()),
        NATIVE_CMDS("bin", "bin"),
        CLASSES("classes", "classes"),
        CONFIG("conf", "conf"),
        UNKNOWN("unknown", "unknown");

        private static String nativeDir() {
            if (System.getProperty("os.name").startsWith("Windows")) {
                return "bin";
            } else {
                return "lib";
            }
        }

        private final String jmodDir;
        private final String imageDir;

        Section(String jmodDir, String imageDir) {
            this.jmodDir = jmodDir;
            this.imageDir = imageDir;
        }

        String imageDir() { return imageDir; }
        String jmodDir() { return jmodDir; }

        boolean matches(String path) {
            return path.startsWith(jmodDir);
        }

        static Section getSectionFromName(String dir) {
            if (Section.NATIVE_LIBS.matches(dir))
                return Section.NATIVE_LIBS;
            else if (Section.NATIVE_CMDS.matches(dir))
                return Section.NATIVE_CMDS;
            else if (Section.CLASSES.matches(dir))
                return Section.CLASSES;
            else if (Section.CONFIG.matches(dir))
                return Section.CONFIG;
            else
                return Section.UNKNOWN;
        }
    }
}