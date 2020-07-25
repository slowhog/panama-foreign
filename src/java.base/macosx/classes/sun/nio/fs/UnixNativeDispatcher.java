/*
 * Copyright (c) 2008, 2019, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.fs;

import java.nio.ByteOrder;
import java.util.function.Supplier;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import jdk.incubator.foreign.MemorySegment;
import jdk.internal.panama.LibC;
import jdk.internal.panama.unistd_h;
import sun.nio.FFIUtils;

import static jdk.incubator.foreign.CSupport.C_CHAR;
import static jdk.incubator.foreign.CSupport.C_POINTER;
import static jdk.incubator.foreign.CSupport.C_LONG;
import static jdk.internal.panama.LibC.dirent;
import static jdk.internal.panama.LibC.group;
import static jdk.internal.panama.LibC.passwd;
import static jdk.internal.panama.LibC.stat;
import static jdk.internal.panama.LibC.stat64;
import static jdk.internal.panama.LibC.statvfs;
import static jdk.internal.panama.LibC.timespec;
import static jdk.internal.panama.LibC.timeval;
import static jdk.internal.panama.i386.limits_h.INT_MAX;
import static jdk.internal.panama.stdio_h.EOF;
import static jdk.internal.panama.sys.errno_h.EBADF;
import static jdk.internal.panama.sys.errno_h.EINTR;
import static jdk.internal.panama.sys.errno_h.ENAMETOOLONG;
import static jdk.internal.panama.sys.errno_h.ENOENT;
import static jdk.internal.panama.sys.errno_h.EOVERFLOW;
import static jdk.internal.panama.sys.errno_h.EPERM;
import static jdk.internal.panama.sys.errno_h.ERANGE;
import static jdk.internal.panama.sys.errno_h.ESRCH;
import static jdk.internal.panama.sys.unistd_h.F_OK;
import static sun.nio.FFIUtils.Scope;
import static sun.nio.FFIUtils.errno;
import static sun.nio.FFIUtils.localScope;
import static sun.nio.FFIUtils.setErrno;

/**
 * Unix system and library calls.
 */

class UnixNativeDispatcher {
    private static final int PATH_MAX = 1024;

    static final void throwUnixExceptionIf(boolean match) throws UnixException {
        if (match) {
            throw new UnixException(errno());
        }
    }

    static final void checkErrno(int expected) throws UnixException {
        int errno = errno();
        if (errno != expected) {
            throw new UnixException(errno);
        }
    }

    protected UnixNativeDispatcher() { }

    static MemorySegment copyToNativeBytes(byte[] ar) {
        long len = (ar[ar.length - 1] == '\0') ? ar.length : ar.length + 1;
        MemorySegment buf = MemorySegment.allocateNative(len);
        buf.copyFrom(MemorySegment.ofArray(ar));
        MemoryAccess.setByteAtOffset(buf.address(), len - 1, (byte) 0);
        return buf;
    }

    static MemorySegment copyToNativeBytes(UnixPath path) {
        return copyToNativeBytes(path.getByteArrayForSysCalls());
    }

    static MemoryAddress copyToNativeBytes(UnixPath path, FFIUtils.Scope s) {
        byte[] buf = path.getByteArrayForSysCalls();
        return s.copyToNativeBytes(buf);
    }

    /**
     * char *getcwd(char *buf, size_t size);
     */
    static byte[] getcwd() throws UnixException {
        try (MemorySegment buf = MemorySegment.allocateNative(PATH_MAX + 1)) {
             MemoryAddress cwd = LibC.getcwd(buf, PATH_MAX);
            if (FFIUtils.isNull(cwd)) {
                throw new UnixException(errno());
            }
            return FFIUtils.toByteArray(cwd.rebase(buf));
        }
    }

    static int restartable(Supplier<Integer> fn) throws UnixException {
        int rv;
        do {
            rv = fn.get();
            if (rv == -1) {
                checkErrno(EINTR);
            }
        } while (rv == -1);
        return rv;
    }

    /**
     * int dup(int filedes)
     */
    static int dup(int filedes) throws UnixException {
        return restartable(() -> LibC.dup(filedes));
    }

    /**
     * int open(const char* path, int oflag, mode_t mode)
     */
    static int open(UnixPath path, int flags, int mode) throws UnixException {
        try (MemorySegment buf = copyToNativeBytes(path)) {
            FFIUtils.setErrno(0);
            int fd = restartable(() -> LibC.open(buf, flags, mode));
            return fd;
        } catch (UnixException ue) {
            throw ue;
        }
    }

    /**
     * int openat(int dfd, const char* path, int oflag, mode_t mode)
     */
    static int openat(int dfd, byte[] path, int flags, int mode) throws UnixException {
        try (MemorySegment buf = copyToNativeBytes(path)) {
            return restartable(() -> LibC.openat(dfd,
                    buf, flags, mode));
        }
    }

    /**
     * close(int filedes). If fd is -1 this is a no-op.
     */
    static void close(int fd) {
        int res = LibC.close(fd);
        try {
            if (res == -1) {
                checkErrno(EINTR);
            }
        } catch (UnixException ex) {
            // was triggered in JNI and ignored here
        }
    }

    /**
     * FILE* fopen(const char *filename, const char* mode);
     */
    static long fopen(UnixPath filename, String mode) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress file;
            do {
                file = LibC.fopen(copyToNativeBytes(filename, s), s.allocateCString(mode));
                if (FFIUtils.isNull(file)) {
                    checkErrno(EINTR);
                }
            } while (FFIUtils.isNull(file));
            return file.toRawLongValue();
        }
    }

    /**
     * fclose(FILE* stream)
     */
    static void fclose(long stream) throws UnixException {
        MemoryAddress file = MemoryAddress.ofLong(stream);
        if (LibC.fclose(file) == EOF) {
            checkErrno(EINTR);
        }
    }

    /**
     * void rewind(FILE* stream);
     */
    static void rewind(long stream) throws UnixException {
        int saved_errno;
        MemoryAddress fp = MemoryAddress.ofLong(stream);

        FFIUtils.setErrno(0);
        LibC.rewind(fp);
        saved_errno = errno();
        if (LibC.ferror(fp) != 0) {
            throw new UnixException(saved_errno);
        }
    }

    /**
     * ssize_t getline(char **lineptr, size_t *n, FILE *stream);
     */
    static int getlinelen(long stream) throws UnixException {
        MemoryAddress fp = MemoryAddress.ofLong(stream);
        long lineSize = 0;
        try (Scope s = localScope()) {
            MemoryAddress ptrBuf = s.allocate(C_POINTER.byteSize());
            MemoryAddress ptrSize = s.allocate(C_LONG.byteSize());
            FFIUtils.CTypeAccess.writeLong(ptrBuf, 0);
            FFIUtils.CTypeAccess.writeLong(ptrSize, 0);
            int saved_errno;

            long res = LibC.getline(ptrBuf, ptrSize, fp);
            saved_errno = errno();

            /* Should free lineBuffer no matter result, according to man page */
            MemoryAddress buf = FFIUtils.CTypeAccess.readPointer(ptrBuf);
            if (! FFIUtils.isNull(buf)) {
                LibC.free(buf);
            }

            if (LibC.feof(fp) != 0)
                return -1;

            /* On successfull return res >= 0, otherwise res is -1 */
            if (res == -1)
                throw new UnixException(saved_errno);

            if (res > INT_MAX)
                throw new UnixException(EOVERFLOW);

            return (int) res;
        }
    }

    /**
     * link(const char* existing, const char* new)
     */
    static void link(UnixPath existing, UnixPath newfile) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress existingFile = copyToNativeBytes(existing, s);
            MemoryAddress newFile = copyToNativeBytes(newfile, s);

            restartable(() -> LibC.link(existingFile, newFile));
        }
    }

    /**
     * unlink(const char* path)
     */
    static void unlink(UnixPath path) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);
            throwUnixExceptionIf(LibC.unlink(file) == -1);
        }
    }

    /**
     * unlinkat(int dfd, const char* path, int flag)
     */
    static void unlinkat(int dfd, byte[] path, int flag) throws UnixException {
        try (MemorySegment file = copyToNativeBytes(path)) {
            throwUnixExceptionIf(LibC.unlinkat(dfd, file, flag) == -1);
        }
    }

    /**
     * mknod(const char* path, mode_t mode, dev_t dev)
     */
    static void mknod(UnixPath path, int mode, long dev) throws UnixException {
        try (MemorySegment file = copyToNativeBytes(path)) {
            restartable(() -> LibC.mknod(file, (short) mode, (int) dev));
        }
    }

    /**
     *  rename(const char* old, const char* new)
     */
    static void rename(UnixPath from, UnixPath to) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress fromFile = copyToNativeBytes(from, s);
            MemoryAddress toFile = copyToNativeBytes(to, s);
            throwUnixExceptionIf(LibC.rename(fromFile, toFile) == -1);
        }
    }

    /**
     *  renameat(int fromfd, const char* old, int tofd, const char* new)
     */
    static void renameat(int fromfd, byte[] from, int tofd, byte[] to) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress fromFile = s.copyToNativeBytes(from);
            MemoryAddress toFile = s.copyToNativeBytes(to);
            throwUnixExceptionIf(LibC.renameat(fromfd, fromFile, tofd, toFile) == -1);
        }
    }

    /**
     * mkdir(const char* path, mode_t mode)
     */
    static void mkdir(UnixPath path, int mode) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress p = copyToNativeBytes(path, s);
            throwUnixExceptionIf(LibC.mkdir(p, (short) mode) == -1);
        }
    }

    /**
     * rmdir(const char* path)
     */
    static void rmdir(UnixPath path) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress p = copyToNativeBytes(path, s);
            throwUnixExceptionIf(LibC.rmdir(p) == -1);
        }
    }

    /**
     * readlink(const char* path, char* buf, size_t bufsize)
     *
     * @return  link target
     */
    static byte[] readlink(UnixPath path) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress p = copyToNativeBytes(path, s);
            long size = PATH_MAX + 1;
            MemoryAddress buf = s.allocateArray(C_CHAR, size);
            long len = LibC.readlink(p, buf, size);
            throwUnixExceptionIf(len == -1);
            if (len == size) {
                throw new UnixException(ENAMETOOLONG);
            } else {
                return FFIUtils.toByteArray(buf, len);
            }
        }
    }

    /**
     * realpath(const char* path, char* resolved_name)
     *
     * @return  resolved path
     */
    static byte[] realpath(UnixPath path) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress p = copyToNativeBytes(path, s);
            MemoryAddress buf = s.allocateArray(C_CHAR, PATH_MAX + 1);
            throwUnixExceptionIf(FFIUtils.isNull(LibC.realpath(p, buf)));
            return FFIUtils.toByteArray(buf);
        }
    }

    /**
     * symlink(const char* name1, const char* name2)
     */
    static void symlink(byte[] name1, UnixPath name2) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress targetBuffer = s.copyToNativeBytes(name1);
            MemoryAddress linkBuffer = copyToNativeBytes(name2, s);
            throwUnixExceptionIf(LibC.symlink(targetBuffer, linkBuffer) == -1);
        }
    }

    /**
     * stat(const char* path, struct stat* buf)
     */
    static void stat(UnixPath path, UnixFileAttributes attrs) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);
            stat64 buffer = stat64.allocate(s::allocate);
            restartable(() -> LibC.stat64(file, buffer.ptr()));
            attrs.init(buffer);
        }
    }

    /**
     * stat(const char* path, struct stat* buf)
     *
     * @return st_mode (file type and mode) or 0 if an error occurs.
     */
    static int stat(UnixPath path) {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);
            stat64 buffer = stat64.allocate(s::allocate);
            restartable(() -> LibC.stat64(file, buffer.ptr()));
            return buffer.st_mode$get();
        } catch (UnixException ex) {
            return 0;
        }
    }


    /**
     * lstat(const char* path, struct stat* buf)
     */
    static void lstat(UnixPath path, UnixFileAttributes attrs) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);
            stat64 buffer = stat64.allocate(s::allocate);
            restartable(() -> LibC.lstat64(file, buffer.ptr()));
            attrs.init(buffer);
        }
    }

    /**
     * fstat(int filedes, struct stat* buf)
     */
    static void fstat(int fd, UnixFileAttributes attrs) throws UnixException {
        try (Scope s = localScope()) {
            stat64 buffer = stat64.allocate(s::allocate);
            restartable(() -> LibC.fstat64(fd, buffer.ptr()));
            attrs.init(buffer);
        }
    }

    /**
     * fstatat(int filedes,const char* path,  struct stat* buf, int flag)
     */
    static void fstatat(int dfd, byte[] path, int flag, UnixFileAttributes attrs)
        throws UnixException
    {
        try (Scope s = localScope()) {
            MemoryAddress file = s.copyToNativeBytes(path);
            stat buffer = LibC.stat.allocate(s::allocate);
            restartable(() -> LibC.fstatat(dfd, file, buffer.ptr(), flag));
            attrs.init(buffer);
        }
    }

    /**
     * chown(const char* path, uid_t owner, gid_t group)
     */
    static void chown(UnixPath path, int uid, int gid) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);

            restartable(() -> LibC.chown(file, uid, gid));
        }
    }

    /**
     * lchown(const char* path, uid_t owner, gid_t group)
     */
    static void lchown(UnixPath path, int uid, int gid) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);

            restartable(() -> LibC.lchown(file, uid, gid));
        }
    }

    /**
     * fchown(int filedes, uid_t owner, gid_t group)
     */
    static void fchown(int fd, int uid, int gid) throws UnixException {
        restartable(() -> LibC.fchown(fd, uid, gid));
    }

    /**
     * chmod(const char* path, mode_t mode)
     */
    static void chmod(UnixPath path, int mode) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);

            restartable(() -> LibC.chmod(file, (short) mode));
        }
    }

    /**
     * fchmod(int fildes, mode_t mode)
     */
    static void fchmod(int fd, int mode) throws UnixException {
        restartable(() -> LibC.fchmod(fd, (short) mode));
    }

    /**
     * utimes(const char* path, const struct timeval times[2])
     */
    static void utimes(UnixPath path, long times0, long times1)
        throws UnixException
    {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);
            timeval v0 = timeval.allocate(s::allocate, 2);
            timeval v1 = v0.offset(1);
            v0.tv_sec$set(times0 / 1000_000);
            v0.tv_usec$set((int) (times0 % 1000_000));
            v1.tv_sec$set(times1 / 1000_000);
            v1.tv_usec$set((int) (times1 % 1000_000));
            restartable(() -> LibC.utimes(file, v0.ptr()));
        }
    }

    /**
     * futimes(int fildes, const struct timeval times[2])
     */
    static void futimes(int fd, long times0, long times1) throws UnixException {
        try (Scope s = localScope()) {
            timeval v0 = timeval.allocate(s::allocate, 2);
            timeval v1 = v0.offset(1);
            v0.tv_sec$set(times0 / 1000_000);
            v0.tv_usec$set((int) (times0 % 1000_000));
            v1.tv_sec$set(times1 / 1000_000);
            v1.tv_usec$set((int) (times1 % 1000_000));
            restartable(() -> LibC.futimes(fd, v0.ptr()));
        }
    }

    /**
     * futimens(int fildes, const struct timespec times[2])
     */
    static void futimens(int fd, long times0, long times1) throws UnixException {
        try (Scope s = localScope()) {
            timespec v0 = timespec.allocate(s::allocate, 2);
            timespec v1 = v0.offset(1);
            v0.tv_sec$set(times0 / 1000_000_000);
            v0.tv_nsec$set((int) (times0 % 1000_000_000));
            v1.tv_sec$set(times1 / 1000_000_000);
            v1.tv_nsec$set((int) (times1 % 1000_000_000));
            restartable(() -> LibC.futimens(fd, v0.ptr()));
        }
    }

    /**
     * lutimes(const char* path, const struct timeval times[2])
     */
    static void lutimes(UnixPath path, long times0, long times1)
        throws UnixException
    {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);
            timeval v0 = timeval.allocate(s::allocate, 2);
            timeval v1 = v0.offset(1);
            v0.tv_sec$set(times0 / 1000_000);
            v0.tv_usec$set((int) (times0 % 1000_000));
            v1.tv_sec$set(times1 / 1000_000);
            v1.tv_usec$set((int) (times1 % 1000_000));
            restartable(() -> LibC.lutimes(file, v0.ptr()));
        }
    }

    /**
     * DIR *opendir(const char* dirname)
     */
    static long opendir(UnixPath path) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress dir = LibC.opendir(copyToNativeBytes(path, s));
            throwUnixExceptionIf(FFIUtils.isNull(dir));
            return dir.toRawLongValue();
        }
    }

    /**
     * DIR* fdopendir(int filedes)
     */
    static long fdopendir(int dfd) throws UnixException {
        MemoryAddress dir = LibC.fdopendir(dfd);
        throwUnixExceptionIf(FFIUtils.isNull(dir));
        return dir.toRawLongValue();
    }


    /**
     * closedir(DIR* dirp)
     */
    static void closedir(long dir) throws UnixException {
        MemoryAddress dirp = MemoryAddress.ofLong(dir);
        if (LibC.closedir(dirp) == -1) {
            checkErrno(EINTR);
        }
    }

    static String hexDump(MemoryAddress buffer, long size) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < size; i++) {
            sb.append(String.format("%02X", FFIUtils.CTypeAccess.readByte(buffer.addOffset(i))));
            if ((i % 32) == 31) {
                sb.append("\n");
            } else {
                sb.append((i % 8) == 7 ? "," : " ");
            }
        }
        return sb.toString();
    }

    /**
     * struct dirent* readdir(DIR *dirp)
     *
     * @return  dirent->d_name
     */
    static byte[] readdir(long dir) throws UnixException {
        MemoryAddress dirp = MemoryAddress.ofLong(dir);
        setErrno(0);

        MemoryAddress pdir = FFIUtils.resizePointer(LibC.readdir(dirp), dirent.sizeof());
        if (FFIUtils.isNull(pdir)) {
            checkErrno(0);
            return null;
        }

        return FFIUtils.toString(dirent.at(pdir).d_name$ptr()).getBytes();
    }

    /**
     * size_t read(int fildes, void* buf, size_t nbyte)
     */
    static int read(int fildes, MemoryAddress buf, int nbyte) throws UnixException {
        return restartable(() -> (int) LibC.read(fildes, buf, nbyte));
    }

    static int read(int fildes, long buf, int nbyte) throws UnixException {
        return read(fildes, MemoryAddress.ofLong(buf), nbyte);
    }

    /**
     * size_t writeint fildes, void* buf, size_t nbyte)
     */
    static int write(int fildes, MemoryAddress buf, int nbyte) throws UnixException {
        return restartable(() -> (int) LibC.write(fildes, buf, nbyte));
    }

    static int write(int fildes, long buf, int nbyte) throws UnixException {
        return write(fildes, MemoryAddress.ofLong(buf), nbyte);
    }

    /**
     * access(const char* path, int amode);
     */
    static void access(UnixPath path, int amode) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);
            restartable(() -> LibC.access(file, amode));
        }
    }

    /**
     * access(constant char* path, F_OK)
     *
     * @return true if the file exists, false otherwise
     */
    static boolean exists(UnixPath path) {
        try (Scope s = localScope()) {
            MemoryAddress file = copyToNativeBytes(path, s);
            try {
                return (0 == restartable(() -> LibC.access(file, F_OK)));
            } catch (UnixException ex) {
                return false;
            }
        }
    }


    /**
     * struct passwd *getpwuid(uid_t uid);
     *
     * @return  passwd->pw_name
     */
    static byte[] getpwuid(int uid) throws UnixException {
        long tmp = LibC.sysconf(unistd_h._SC_GETPW_R_SIZE_MAX);
        final long bufLen = (tmp == -1) ? 1024 : tmp;
        try (Scope s = localScope()) {
            MemoryAddress buf = s.allocateArray(C_CHAR, bufLen);
            passwd pwent = passwd.allocate(s::allocate);
            MemoryAddress result = s.allocate(C_POINTER);
            setErrno(0);
            int rv = restartable(() -> LibC.getpwuid_r(uid, pwent.ptr(), buf, bufLen, result));
            MemoryAddress ptr = (rv != 0) ? MemoryAddress.NULL : FFIUtils.CTypeAccess.readPointer(result);
            if (FFIUtils.isNull(ptr)) {
                int errno = errno();
                throw new UnixException(errno == 0 ? ENOENT : errno);
            } else {
                byte[] name = FFIUtils.toByteArray(pwent.pw_name$get().rebase(buf.segment()));
                if (name == null || name.length == 0) {
                    throw new UnixException(ENOENT);
                }
                return name;
            }
        }
    }

    /**
     * struct group *getgrgid(gid_t gid);
     *
     * @return  group->gr_name
     */
    static byte[] getgrgid(int gid) throws UnixException {
        long tmp = LibC.sysconf(unistd_h._SC_GETGR_R_SIZE_MAX);
        final long bufLen = (tmp == -1) ? 1024 : tmp;
        try (Scope s = localScope()) {
            MemoryAddress buf = s.allocateArray(C_CHAR, bufLen);
            group grent = group.allocate(s::allocate);
            MemoryAddress result = s.allocate(C_POINTER);
            setErrno(0);
            int rv = restartable(() -> LibC.getgrgid_r(gid, grent.ptr(), buf, bufLen, result));
            MemoryAddress ptr = (rv != 0) ? MemoryAddress.NULL : FFIUtils.CTypeAccess.readPointer(result);
            if (FFIUtils.isNull(ptr)) {
                int errno = errno();
                throw new UnixException(errno == 0 ? ENOENT : errno);
            } else {
                byte[] name = FFIUtils.toByteArray(grent.gr_name$get().rebase(buf.segment()));
                if (name == null || name.length == 0) {
                    throw new UnixException(ENOENT);
                }
                return name;
            }
        }
    }

    /**
     * struct passwd *getpwnam(const char *name);
     *
     * @return  passwd->pw_uid
     */
    static int getpwnam(String name) throws UnixException {
        long tmp = LibC.sysconf(unistd_h._SC_GETPW_R_SIZE_MAX);
        final long bufLen = (tmp == -1) ? 1024 : tmp;
        try (Scope s = localScope()) {
            MemoryAddress buf = s.allocateArray(C_CHAR, bufLen);
            passwd pwent = passwd.allocate(s::allocate);
            MemoryAddress result = s.allocate(C_POINTER);
            setErrno(0);
            int rv = restartable(() -> LibC.getpwnam_r(s.allocateCString(name),
                    pwent.ptr(), buf, bufLen, result));
            MemoryAddress ptr = (rv != 0) ? MemoryAddress.NULL : FFIUtils.CTypeAccess.readPointer(result);
            if (FFIUtils.isNull(ptr)) {
                int errno = errno();
                if (errno == 0 || errno == ENOENT || errno == ESRCH &&
                    errno != EBADF && errno != EPERM)
                {
                    return -1;
                } else {
                    throw new UnixException(errno);
                }
            } else {
                if (FFIUtils.CTypeAccess.isEmptyString(pwent.pw_name$get().rebase(buf.segment()))) {
                    return -1;
                }
                return pwent.pw_uid$get();
            }
        }
    }

    /**
     * struct group *getgrnam(const char *name);
     *
     * @return  group->gr_name
     */
    static int getgrnam(String name) throws UnixException {
        long tmp = LibC.sysconf(unistd_h._SC_GETGR_R_SIZE_MAX);
        long bufLen = (tmp == -1) ? 1024 : tmp;
        try (Scope s = localScope()) {
            boolean retry;
            do {
                MemoryAddress buf = s.allocateArray(C_CHAR, bufLen);
                group grent = group.allocate(s::allocate);
                MemoryAddress result = s.allocate(C_POINTER);
                setErrno(0);
                final long len = bufLen;
                int rv = restartable(() -> LibC.getgrnam_r(s.allocateCString(name),
                        grent.ptr(), buf, len, result));
                MemoryAddress ptr = (rv != 0) ? MemoryAddress.NULL : FFIUtils.CTypeAccess.readPointer(result);
                if (FFIUtils.isNull(ptr)) {
                    int errno = errno();
                    if (errno == 0 || errno == ENOENT || errno == ESRCH &&
                        errno != EBADF && errno != EPERM)
                    {
                        return -1;
                    } else if (errno == ERANGE) {
                        bufLen += 1024;
                        retry = true;
                    } else {
                        throw new UnixException(errno);
                    }
                } else {
                    if (FFIUtils.CTypeAccess.isEmptyString(grent.gr_name$get().rebase(buf.segment()))) {
                        return -1;
                    }
                    return grent.gr_gid$get();
                }
            } while (retry);
        }
        return -1;
    }

    /**
     * statvfs(const char* path, struct statvfs *buf)
     */
    static void statvfs(UnixPath path, UnixFileStoreAttributes attrs)
        throws UnixException
    {
        try (Scope s = localScope()) {
            MemoryAddress p = copyToNativeBytes(path, s);
            statvfs buffer = statvfs.allocate(s::allocate);
            restartable(() -> LibC.statvfs(p, buffer.ptr()));
            attrs.init(buffer);
        }
    }

    /**
     * long int pathconf(const char *path, int name);
     */
    static long pathconf(UnixPath path, int name) throws UnixException {
        try (Scope s = localScope()) {
            MemoryAddress p = copyToNativeBytes(path, s);
            long rv = LibC.pathconf(p, name);
            throwUnixExceptionIf(-1 == rv);
            return rv;
        }
    }

    /**
     * long fpathconf(int fildes, int name);
     */
    static long fpathconf(int filedes, int name) throws UnixException {
        long rv = LibC.fpathconf(filedes, name);
        throwUnixExceptionIf(-1 == rv);
        return rv;
    }

    /**
     * char* strerror(int errnum)
     */
    static byte[] strerror(int errnum) {
        try (Scope s = localScope()) {
            MemoryAddress buf = s.allocateArray(C_CHAR, 1024);
            LibC.strerror_r(errnum, buf, 1024);
            return FFIUtils.toString(buf).getBytes();
        }
    }

    /**
     * Supports openat and other *at calls.
     */
    static boolean openatSupported() {
        return true;
    }

    /**
     * Supports futimes or futimesat
     */
    static boolean futimesSupported() {
        return true;
    }

    /**
     * Supports futimens
     */
    static boolean futimensSupported() {
        return true;
    }

    /**
     * Supports lutimes
     */
    static boolean lutimesSupported() {
        return true;
    }

    /**
     * Supports file birth (creation) time attribute
     */
    static boolean birthtimeSupported() {
        return true;
    }
}
