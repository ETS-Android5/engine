/*
 * Copyright 2012, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib2;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.DexBackedDexFile.NotADexFile;
import org.jf.dexlib2.dexbacked.DexBackedOdexFile;
import org.jf.dexlib2.dexbacked.OatFile;
import org.jf.dexlib2.dexbacked.OatFile.NotAnOatFileException;
import org.jf.dexlib2.dexbacked.OatFile.OatDexFile;
import org.jf.dexlib2.dexbacked.OatFile.VdexProvider;
import org.jf.dexlib2.dexbacked.ZipDexContainer;
import org.jf.dexlib2.dexbacked.ZipDexContainer.NotAZipFileException;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.util.ExceptionWithContext;

import java.io.*;
import java.util.List;

public final class DexFileFactory {


    private DexFileFactory() {
    }

    public static DexBackedDexFile loadDexFile(String path, Opcodes opcodes) throws IOException {
        return loadDexFile(new File(path), opcodes);
    }

    /**
     * Loads a dex/apk/odex/oat file.
     * <p>
     * For oat files with multiple dex files, the first will be opened. For zip/apk files, the "classes.dex" entry
     * will be opened.
     *
     * @param file    The file to open
     * @param opcodes The set of opcodes to use
     * @return A DexBackedDexFile for the given file
     * @throws UnsupportedOatVersionException If file refers to an unsupported oat file
     * @throws DexFileNotFoundException       If file does not exist, if file is a zip file but does not have a "classes.dex"
     *                                        entry, or if file is an oat file that has no dex entries.
     * @throws UnsupportedFileTypeException   If file is not a valid dex/zip/odex/oat file, or if the "classes.dex" entry
     *                                        in a zip file is not a valid dex file
     */

    public static DexBackedDexFile loadDexFile(File file, Opcodes opcodes) throws IOException {
        if (!file.exists()) {
            throw new DexFileNotFoundException("%s does not exist", file.getName());
        }

        try {
            ZipDexContainer container = new ZipDexContainer(file, opcodes);
            return new DexEntryFinder(file.getPath(), container).findEntry("classes.dex", true);
        } catch (NotAZipFileException ex) {
            // eat it and continue
        }

        InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
        try {
            try {
                return DexBackedDexFile.fromInputStream(opcodes, inputStream);
            } catch (DexBackedDexFile.NotADexFile ex) {
                // just eat it
            }

            try {
                return DexBackedOdexFile.fromInputStream(opcodes, inputStream);
            } catch (DexBackedOdexFile.NotAnOdexFile ex) {
                // just eat it
            }

            // Note: DexBackedDexFile.fromInputStream and DexBackedOdexFile.fromInputStream will reset inputStream
            // back to the same position, if they fails

            OatFile oatFile = null;
            try {
                oatFile = OatFile.fromInputStream(inputStream, new FilenameVdexProvider(file));
            } catch (NotAnOatFileException ex) {
                // just eat it
            }

            if (oatFile != null) {
                if (oatFile.isSupportedVersion() == OatFile.UNSUPPORTED) {
                    throw new UnsupportedOatVersionException(oatFile);
                }

                List<OatDexFile> oatDexFiles = oatFile.getDexFiles();

                if (oatDexFiles.size() == 0) {
                    throw new DexFileNotFoundException("Oat file %s contains no dex files", file.getName());
                }

                return oatDexFiles.get(0);
            }
        } finally {
            inputStream.close();
        }

        throw new UnsupportedFileTypeException("%s is not an apk, dex, odex or oat file.", file.getPath());
    }

    /**
     * Loads a dex entry from a container format (zip/oat)
     * <p>
     * This has two modes of operation, depending on the exactMatch parameter. When exactMatch is true, it will only
     * load an entry whose name exactly matches that provided by the dexEntry parameter.
     * <p>
     * When exactMatch is false, then it will search for any entry that dexEntry is a path suffix of. "path suffix"
     * meaning all the path components in dexEntry must fully match the corresponding path components in the entry name,
     * but some path components at the beginning of entry name can be missing.
     * <p>
     * For example, if an oat file contains a "/system/framework/framework.jar:classes2.dex" entry, then the following
     * will match (not an exhaustive list):
     * <p>
     * "/system/framework/framework.jar:classes2.dex"
     * "system/framework/framework.jar:classes2.dex"
     * "framework/framework.jar:classes2.dex"
     * "framework.jar:classes2.dex"
     * "classes2.dex"
     * <p>
     * Note that partial path components specifically don't match. So something like "work/framework.jar:classes2.dex"
     * would not match.
     * <p>
     * If dexEntry contains an initial slash, it will be ignored for purposes of this suffix match -- but not when
     * performing an exact match.
     * <p>
     * If multiple entries match the given dexEntry, a MultipleMatchingDexEntriesException will be thrown
     *
     * @param file       The container file. This must be either a zip (apk) file or an oat file.
     * @param dexEntry   The name of the entry to load. This can either be the exact entry name, if exactMatch is true,
     *                   or it can be a path suffix.
     * @param exactMatch If true, dexE
     * @param opcodes    The set of opcodes to use
     * @return A DexBackedDexFile for the given entry
     * @throws UnsupportedOatVersionException      If file refers to an unsupported oat file
     * @throws DexFileNotFoundException            If the file does not exist, or if no matching entry could be found
     * @throws UnsupportedFileTypeException        If file is not a valid zip/oat file, or if the matching entry is not a
     *                                             valid dex file
     * @throws MultipleMatchingDexEntriesException If multiple entries match the given dexEntry
     */
    public static DexBackedDexFile loadDexEntry(File file, String dexEntry,
                                                boolean exactMatch, Opcodes opcodes) throws IOException {
        if (!file.exists()) {
            throw new DexFileNotFoundException("Container file %s does not exist", file.getName());
        }

        try {
            ZipDexContainer container = new ZipDexContainer(file, opcodes);
            return new DexEntryFinder(file.getPath(), container).findEntry(dexEntry, exactMatch);
        } catch (NotAZipFileException ex) {
            // eat it and continue
        }

        InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
        try {
            OatFile oatFile = null;
            try {
                oatFile = OatFile.fromInputStream(inputStream, new FilenameVdexProvider(file));
            } catch (NotAnOatFileException ex) {
                // just eat it
            }

            if (oatFile != null) {
                if (oatFile.isSupportedVersion() == OatFile.UNSUPPORTED) {
                    throw new UnsupportedOatVersionException(oatFile);
                }

                List<OatDexFile> oatDexFiles = oatFile.getDexFiles();

                if (oatDexFiles.size() == 0) {
                    throw new DexFileNotFoundException("Oat file %s contains no dex files", file.getName());
                }

                return new DexEntryFinder(file.getPath(), oatFile).findEntry(dexEntry, exactMatch);
            }
        } finally {
            inputStream.close();
        }

        throw new UnsupportedFileTypeException("%s is not an apk or oat file.", file.getPath());
    }

    /**
     * Loads a file containing 1 or more dex files
     * <p>
     * If the given file is a dex or odex file, it will return a MultiDexContainer containing that single entry.
     * Otherwise, for an oat or zip file, it will return an OatFile or ZipDexContainer respectively.
     *
     * @param file    The file to open
     * @param opcodes The set of opcodes to use
     * @return A MultiDexContainer
     * @throws DexFileNotFoundException     If the given file does not exist
     * @throws UnsupportedFileTypeException If the given file is not a valid dex/zip/odex/oat file
     */
    public static MultiDexContainer<? extends DexBackedDexFile> loadDexContainer(
            File file, final Opcodes opcodes) throws IOException {
        if (!file.exists()) {
            throw new DexFileNotFoundException("%s does not exist", file.getName());
        }

        ZipDexContainer zipDexContainer = new ZipDexContainer(file, opcodes);
        if (zipDexContainer.isZipFile()) {
            return zipDexContainer;
        }

        InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
        try {
            try {
                DexBackedDexFile dexFile = DexBackedDexFile.fromInputStream(opcodes, inputStream);
                return new SingletonMultiDexContainer(file.getPath(), dexFile);
            } catch (DexBackedDexFile.NotADexFile ex) {
                // just eat it
            }

            try {
                DexBackedOdexFile odexFile = DexBackedOdexFile.fromInputStream(opcodes, inputStream);
                return new SingletonMultiDexContainer(file.getPath(), odexFile);
            } catch (DexBackedOdexFile.NotAnOdexFile ex) {
                // just eat it
            }

            // Note: DexBackedDexFile.fromInputStream and DexBackedOdexFile.fromInputStream will reset inputStream
            // back to the same position, if they fails

            OatFile oatFile = null;
            try {
                oatFile = OatFile.fromInputStream(inputStream, new FilenameVdexProvider(file));
            } catch (NotAnOatFileException ex) {
                // just eat it
            }

            if (oatFile != null) {
                // TODO: we should support loading earlier oat files, just not deodexing them
                if (oatFile.isSupportedVersion() == OatFile.UNSUPPORTED) {
                    throw new UnsupportedOatVersionException(oatFile);
                }
                return oatFile;
            }
        } finally {
            inputStream.close();
        }

        throw new UnsupportedFileTypeException("%s is not an apk, dex, odex or oat file.", file.getPath());
    }

    /**
     * Writes a DexFile out to disk
     *
     * @param path    The path to write the dex file to
     * @param dexFile a DexFile to write
     */
    public static void writeDexFile(String path, DexFile dexFile) throws IOException {
        DexPool.writeTo(path, dexFile);
    }

    /**
     * Matches two entries fully, ignoring any initial slash, if any
     */
    private static boolean fullEntryMatch(String entry, String targetEntry) {
        if (entry.equals(targetEntry)) {
            return true;
        }

        if (entry.charAt(0) == '/') {
            entry = entry.substring(1);
        }

        if (targetEntry.charAt(0) == '/') {
            targetEntry = targetEntry.substring(1);
        }

        return entry.equals(targetEntry);
    }

    /**
     * Performs a partial match against entry and targetEntry.
     * <p>
     * This is considered a partial match if targetEntry is a suffix of entry, and if the suffix starts
     * on a path "part" (ignoring the initial separator, if any). Both '/' and ':' are considered separators for this.
     * <p>
     * So entry="/blah/blah/something.dex" and targetEntry="lah/something.dex" shouldn't match, but
     * both targetEntry="blah/something.dex" and "/blah/something.dex" should match.
     */
    private static boolean partialEntryMatch(String entry, String targetEntry) {
        if (entry.equals(targetEntry)) {
            return true;
        }

        if (!entry.endsWith(targetEntry)) {
            return false;
        }

        // Make sure the first matching part is a full entry. We don't want to match "/blah/blah/something.dex" with
        // "lah/something.dex", but both "/blah/something.dex" and "blah/something.dex" should match
        char precedingChar = entry.charAt(entry.length() - targetEntry.length() - 1);
        char firstTargetChar = targetEntry.charAt(0);
        // This is a device path, so we should always use the linux separator '/', rather than the current platform's
        // separator
        return firstTargetChar == ':' || firstTargetChar == '/' || precedingChar == ':' || precedingChar == '/';
    }

    public static class DexFileNotFoundException extends ExceptionWithContext {
        public DexFileNotFoundException(String message, Object... formatArgs) {
            super(message, formatArgs);
        }
    }

    public static class UnsupportedOatVersionException extends ExceptionWithContext {
        public final OatFile oatFile;

        public UnsupportedOatVersionException(OatFile oatFile) {
            super("Unsupported oat version: %d", oatFile.getOatVersion());
            this.oatFile = oatFile;
        }
    }

    public static class MultipleMatchingDexEntriesException extends ExceptionWithContext {
        public MultipleMatchingDexEntriesException(String message, Object... formatArgs) {
            super(String.format(message, formatArgs));
        }
    }

    public static class UnsupportedFileTypeException extends ExceptionWithContext {
        public UnsupportedFileTypeException(String message, Object... formatArgs) {
            super(String.format(message, formatArgs));
        }
    }

    protected static class DexEntryFinder {
        private final String filename;
        private final MultiDexContainer<? extends DexBackedDexFile> dexContainer;

        public DexEntryFinder(String filename,
                              MultiDexContainer<? extends DexBackedDexFile> dexContainer) {
            this.filename = filename;
            this.dexContainer = dexContainer;
        }


        public DexBackedDexFile findEntry(String targetEntry, boolean exactMatch) throws IOException {
            if (exactMatch) {
                try {
                    DexBackedDexFile dexFile = dexContainer.getEntry(targetEntry);
                    if (dexFile == null) {
                        throw new DexFileNotFoundException("Could not find entry %s in %s.", targetEntry, filename);
                    }
                    return dexFile;
                } catch (NotADexFile ex) {
                    throw new UnsupportedFileTypeException("Entry %s in %s is not a dex file", targetEntry, filename);
                }
            }

            // find all full and partial matches
            List<String> fullMatches = Lists.newArrayList();
            List<DexBackedDexFile> fullEntries = Lists.newArrayList();
            List<String> partialMatches = Lists.newArrayList();
            List<DexBackedDexFile> partialEntries = Lists.newArrayList();
            for (String entry : dexContainer.getDexEntryNames()) {
                if (fullEntryMatch(entry, targetEntry)) {
                    // We want to grab all full matches, regardless of whether they're actually a dex file.
                    fullMatches.add(entry);
                    fullEntries.add(dexContainer.getEntry(entry));
                } else if (partialEntryMatch(entry, targetEntry)) {
                    partialMatches.add(entry);
                    partialEntries.add(dexContainer.getEntry(entry));
                }
            }

            // full matches always take priority
            if (fullEntries.size() == 1) {
                try {
                    DexBackedDexFile dexFile = fullEntries.get(0);
                    assert dexFile != null;
                    return dexFile;
                } catch (NotADexFile ex) {
                    throw new UnsupportedFileTypeException("Entry %s in %s is not a dex file",
                            fullMatches.get(0), filename);
                }
            }
            if (fullEntries.size() > 1) {
                // This should be quite rare. This would only happen if an oat file has two entries that differ
                // only by an initial path separator. e.g. "/blah/blah.dex" and "blah/blah.dex"
                throw new MultipleMatchingDexEntriesException(String.format(
                        "Multiple entries in %s match %s: %s", filename, targetEntry,
                        Joiner.on(", ").join(fullMatches)));
            }

            if (partialEntries.size() == 0) {
                throw new DexFileNotFoundException("Could not find a dex entry in %s matching %s",
                        filename, targetEntry);
            }
            if (partialEntries.size() > 1) {
                throw new MultipleMatchingDexEntriesException(String.format(
                        "Multiple dex entries in %s match %s: %s", filename, targetEntry,
                        Joiner.on(", ").join(partialMatches)));
            }
            return partialEntries.get(0);
        }
    }

    private static class SingletonMultiDexContainer implements MultiDexContainer<DexBackedDexFile> {
        private final String entryName;
        private final DexBackedDexFile dexFile;

        public SingletonMultiDexContainer(String entryName, DexBackedDexFile dexFile) {
            this.entryName = entryName;
            this.dexFile = dexFile;
        }

        @Override
        public List<String> getDexEntryNames() throws IOException {
            return ImmutableList.of(entryName);
        }

        @Override
        public DexBackedDexFile getEntry(String entryName) throws IOException {
            if (entryName.equals(this.entryName)) {
                return dexFile;
            }
            return null;
        }

        @Override
        public Opcodes getOpcodes() {
            return dexFile.getOpcodes();
        }
    }

    public static class FilenameVdexProvider implements VdexProvider {
        private final File vdexFile;


        private byte[] buf = null;
        private boolean loadedVdex = false;

        public FilenameVdexProvider(File oatFile) {
            File oatParent = oatFile.getAbsoluteFile().getParentFile();
            String baseName = Files.getNameWithoutExtension(oatFile.getAbsolutePath());
            vdexFile = new File(oatParent, baseName + ".vdex");
        }

        @Override
        public byte[] getVdex() {
            if (!loadedVdex) {
                if (vdexFile.exists()) {
                    try {
                        buf = ByteStreams.toByteArray(new FileInputStream(vdexFile));
                    } catch (FileNotFoundException e) {
                        buf = null;
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                loadedVdex = true;
            }

            return buf;
        }
    }
}
