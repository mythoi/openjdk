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

package java.lang.module;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReaderFactory;
import jdk.internal.module.Hasher;


/**
 * A factory for creating ModuleReference implementations where the modules are
 * located in the run-time image, packaged as jmod or modular JAR files, or
 * where the modules are exploded on the file system.
 */

class ModuleReferences {

    private ModuleReferences() { }

    /**
     * Creates a ModuleReference.
     */
    static ModuleReference newModuleReference(ModuleDescriptor md,
                                              URI location,
                                              Hasher.HashSupplier hasher)
    {
        String scheme = location.getScheme();
        if (scheme.equalsIgnoreCase("jrt"))
            return new JrtModuleReference(md, location, hasher);
        if (scheme.equalsIgnoreCase("jmod"))
            return new JModModuleReference(md, location, hasher);
        if (scheme.equalsIgnoreCase("jar"))
            return new JarModuleReference(md, location, hasher);
        if (scheme.equalsIgnoreCase("file"))
            return new ExplodedModuleReference(md, location, hasher);

        throw new InternalError("Should not get here");
    }

    /**
     * A ModuleReference for a module that is linked into the run-time image.
     */
    static class JrtModuleReference extends ModuleReference {
        JrtModuleReference(ModuleDescriptor descriptor,
                          URI location,
                          Hasher.HashSupplier hasher) {
            super(descriptor, location, hasher);
        }

        public ModuleReader open() throws IOException {
            return new JrtModuleReader(this);
        }
    }

    /**
     * A ModuleReference for a module that is exploded on the file system.
     */
    static class ExplodedModuleReference extends ModuleReference {
        ExplodedModuleReference(ModuleDescriptor descriptor,
                               URI location,
                               Hasher.HashSupplier hasher) {
            super(descriptor, location, hasher);
        }

        public ModuleReader open() throws IOException {
            return new ExplodedModuleReader(this);
        }
    }

    /**
     * A ModuleReference for a module that is packaged as jmod file.
     */
    static class JModModuleReference extends ModuleReference {
        JModModuleReference(ModuleDescriptor descriptor,
                            URI location,
                            Hasher.HashSupplier hasher) {
            super(descriptor, location, hasher);
        }

        public ModuleReader open() throws IOException {
            return new JModModuleReader(this);
        }
    }

    /**
     * A ModuleReference for a module that is packaged as a modular JAR file.
     */
    static class JarModuleReference extends ModuleReference {
        JarModuleReference(ModuleDescriptor descriptor,
                          URI location,
                          Hasher.HashSupplier hasher) {
            super(descriptor, location, hasher);
        }

        @Override
        public ModuleReader open() throws IOException {
            return new JarModuleReader(this);
        }
    }

    /**
     * A ModuleReader for reading resources from a module linked into the
     * run-time image.
     */
    static class JrtModuleReader implements ModuleReader {
        // ImageReader, shared between instances of this module reader
        private static ImageReader imageReader;
        static {
            // detect image or exploded build
            String home = System.getProperty("java.home");
            Path libModules = Paths.get(home, "lib", "modules");
            if (Files.isDirectory(libModules)) {
                // this can throw UncheckedIOException
                imageReader = ImageReaderFactory.getImageReader();
            } else {
                imageReader = null;
            }
        }

        private final String module;
        private volatile boolean closed;

        JrtModuleReader(ModuleReference mref) throws IOException {
            assert mref.location().isPresent();
            // when running with a security manager then check that the caller
            // has access to the run-time image
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                URLConnection uc = mref.location().get().toURL().openConnection();
                sm.checkPermission(uc.getPermission());
            }
            this.module = mref.descriptor().name();
        }

        /**
         * Returns the ImageLocation for the given resource, {@code null}
         * if not found.
         */
        private ImageLocation findImageLocation(String name) {
            if (imageReader != null) {
                return imageReader.findLocation(module, name);
            } else {
                // not an images build
                return null;
            }
        }

        @Override
        public Optional<InputStream> open(String name) throws IOException {
            return read(name).map(this::toInputStream);
        }

        private InputStream toInputStream(ByteBuffer bb) { // ## -> ByteBuffer?
            try {
                int rem = bb.remaining();
                byte[] bytes = new byte[rem];
                bb.get(bytes);
                return new ByteArrayInputStream(bytes);
            } finally {
                release(bb);
            }
        }

        @Override
        public Optional<ByteBuffer> read(String name) throws IOException {
            if (closed) {
                throw new IOException("ModuleReader is closed");
            } else {
                ImageLocation location = findImageLocation(name);
                if (location != null) {
                    return Optional.of(imageReader.getResourceBuffer(location));
                } else {
                    return Optional.empty();
                }
            }
        }

        @Override
        public void release(ByteBuffer bb) {
            ImageReader.releaseByteBuffer(bb);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * A ModuleReader for an exploded module.
     */
    static class ExplodedModuleReader implements ModuleReader {
        private final Path dir;
        private volatile boolean closed;

        ExplodedModuleReader(ModuleReference mref) {
            dir = Paths.get(mref.location().get());
        }

        /**
         * Returns a Path to access to the given resource.
         */
        private Path toPath(String name) {
            Path path = Paths.get(name.replace('/', File.separatorChar));
            if (path.getRoot() == null) {
                return dir.resolve(path);
            } else {
                // drop the root component so that the resource is
                // locate relative to the module directory
                int n = path.getNameCount();
                return (n > 0) ? dir.resolve(path.subpath(0, n)) : null;
            }
        }

        /**
         * Throws IOException if the module reader is closed;
         */
        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("ModuleReader is closed");
        }

        @Override
        public Optional<InputStream> open(String name) throws IOException {
            ensureOpen();
            Path path = toPath(name);
            if (path != null && Files.isRegularFile(path)) {
                return Optional.of(Files.newInputStream(path));
            } else {
                return Optional.empty();
            }
        }

        @Override
        public Optional<ByteBuffer> read(String name) throws IOException {
            ensureOpen();
            Path path = toPath(name);
            if (path != null && Files.isRegularFile(path)) {
                return Optional.of(ByteBuffer.wrap(Files.readAllBytes(path)));
            } else {
                return Optional.empty();
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * A base module reader that encapsulates machinery required to close the
     * module reader safely.
     */
    static abstract class SafeCloseModuleReader implements ModuleReader {

        // RW lock to support safe close
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final Lock readLock = lock.readLock();
        private final Lock writeLock = lock.writeLock();
        private volatile boolean closed;

        SafeCloseModuleReader() { }

        /**
         * Returns an input stream for reading a resource. This method is
         * invoked by the open method to do the actual work of opening
         * an input stream to the resource.
         */
        abstract Optional<InputStream> implOpen(String name) throws IOException;

        /**
         * Closes the module reader. This method is invoked by close to do the
         * actual work of closing the module reader.
         */
        abstract void implClose() throws IOException;

        @Override
        public final Optional<InputStream> open(String name) throws IOException {
            readLock.lock();
            try {
                if (!closed) {
                    return implOpen(name);
                } else {
                    throw new IOException("ModuleReader is closed");
                }
            } finally {
                readLock.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            writeLock.lock();
            try {
                if (!closed) {
                    closed = true;
                    implClose();
                }
            } finally {
                writeLock.unlock();
            }
        }
    }

    /**
     * A ModuleReader for a jmod file.
     */
    static class JModModuleReader extends SafeCloseModuleReader {
        private final ZipFile zf;

        JModModuleReader(ModuleReference mref) throws IOException {
            URI uri = mref.location().get();
            String s = uri.toString();
            String fileURIString = s.substring(5, s.length()-2);
            this.zf = new JarFile(Paths.get(URI.create(fileURIString)).toString());
        }

        private ZipEntry find(String name) {
            return zf.getEntry("classes/" + Objects.requireNonNull(name));
        }

        @Override
        Optional<InputStream> implOpen(String name) throws IOException {
            ZipEntry ze = find(name);
            if (ze != null) {
                return Optional.of(zf.getInputStream(ze));
            } else {
                return Optional.empty();
            }
        }

        @Override
        void implClose() throws IOException {
            zf.close();
        }
    }

    /**
     * A ModuleReader for a modular JAR file.
     */
    static class JarModuleReader extends SafeCloseModuleReader {
        private final JarFile jf;

        JarModuleReader(ModuleReference mref) throws IOException {
            URI uri = mref.location().get();
            String s = uri.toString();
            String fileURIString = s.substring(4, s.length()-2);
            this.jf = new JarFile(Paths.get(URI.create(fileURIString)).toString());
        }

        private JarEntry find(String name) {
            return jf.getJarEntry(name);
        }

        @Override
        Optional<InputStream> implOpen(String name) throws IOException {
            JarEntry je = find(name);
            if (je != null) {
                return Optional.of(jf.getInputStream(je));
            } else {
                return Optional.empty();
            }
        }

        @Override
        void implClose() throws IOException {
            jf.close();
        }
    }

}