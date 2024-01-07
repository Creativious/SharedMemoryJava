package net.creativious;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class SharedMemory {
    private final String name;
    private final int size;
    private static final short S_IRUSR = 00400, S_IWUSR = 00200;
    private final boolean is_create;
    protected MemorySegment segment;
    private MemorySegment h_map_file;
    private static MethodHandle createFileMappingWindows, openFileMappingWindows, closeHandleWindows, mapViewOfFileWindows, unmapViewOfFileWindows;
    private static MethodHandle shm_open_linux, ftruncate_linux, mmap_linux, munmap_linux, shm_unlink_linux;
    public static SharedMemory create(String name, int size) {
        return init(name, size, true);
    }
    public static SharedMemory open(String name, int size) {
        return init(name, size, false);
    }

    private static SharedMemory init(String name, int size, boolean is_create) {
        try {
            return new SharedMemory(name, size, is_create);
        } catch (Throwable th) {
            throw new RuntimeException(th);
        }
    }

    public MemorySegment getMemorySegment() {
        return segment;
    }

    public ByteBuffer toByteBuffer() {
        return segment.asByteBuffer().order(ByteOrder.nativeOrder());
    }

    public void write_data(byte[] data) {
        ByteBuffer buffer = segment.asByteBuffer().order(ByteOrder.nativeOrder());
        buffer.put(data);

        // If the data size is less than the total size, write zeros to the remaining bytes
        if (data.length < size) {
            int remainingBytes = size - data.length;
            for (int i = 0; i < remainingBytes; i++) {
                buffer.put((byte) 0);
            }
        }
    }

    public void write_string(String str) {
        byte[] data = str.getBytes(StandardCharsets.UTF_8);
        write_data(data);
    }

    public String read_string() {
        byte[] data = read_data();
        return new String(data, StandardCharsets.UTF_8);
    }

    public byte[] read_data() {
        ByteBuffer buffer = segment.asByteBuffer().order(ByteOrder.nativeOrder());
        byte[] data = new byte[size];
        buffer.get(data);

        // Trim any empty bits at the end
        int lastNonZeroIndex = data.length - 1;
        while (lastNonZeroIndex >= 0 && data[lastNonZeroIndex] == 0) {
            lastNonZeroIndex--;
        }

        return Arrays.copyOfRange(data, 0, lastNonZeroIndex + 1);
    }

    public int getSize() {
        return size;
    }

    public String getName() {
        return name;
    }

    public SharedMemory(String name, int size, boolean is_create) throws Throwable {
        this.name = name;
        this.size = size;
        this.is_create = is_create;
        if (SharedMemoryJava.isWindows()) {
            if (this.is_create) {
                var temp_address = (MemoryAddress) createFileMappingWindows.invokeExact(
                        (Addressable) MemorySegment.ofAddress(MemoryAddress.ofLong(-1), 0, MemorySession.global()).address(),
                        (Addressable) MemorySegment.ofAddress(MemoryAddress.NULL, 0, MemorySession.global()).address(),
                        0x04 | 0x08000000,
                        0,
                        size,
                        (Addressable) MemorySession.global().allocateUtf8String(name).address());
                h_map_file = MemorySegment.ofAddress(temp_address.address(), size, MemorySession.global());
            } else {
                h_map_file = (MemorySegment) openFileMappingWindows.invokeExact(
                        0x0002 | 0x0004,
                        0,
                        name
                );
            }
            if (h_map_file.address().toRawLongValue() == 0) throw new IllegalStateException("CreateFileMapping failed");
            try {
                var address = (MemoryAddress) mapViewOfFileWindows.invokeExact(
                        (Addressable) h_map_file.address(),
                        0x0002 | 0x0004,
                        0,
                        0,
                        size
                );
                segment = MemorySegment.ofAddress(address, size, MemorySession.global());
                if (segment.address().toRawLongValue() == 0) throw new IllegalStateException("MapViewOfFile failed");
            } catch (Throwable th) {
                var t = (MemoryAddress) closeHandleWindows.invokeExact();
                throw th;
            }
            segment = MemorySegment.ofAddress(segment.address(), size, MemorySession.global());

        }
        else if (SharedMemoryJava.isMacOS() || SharedMemoryJava.isLinux()) {
            int mode = 0x0002;
            if (is_create) mode |= 0x00000200 | 0x00000800;
            int fd = (int) shm_open_linux.invokeExact(
                    (Addressable) MemorySession.global().allocateUtf8String(name).address(),
                    mode,
                    (int) (S_IRUSR | S_IWUSR)
            );
            if (fd == -1) throw new IllegalStateException("shm_open failed");
            try {
                if (is_create && (int) ftruncate_linux.invokeExact(fd, size) == -1) throw new IllegalStateException("ftruncate failed");
                var addr = (MemoryAddress) mmap_linux.invokeExact(
                        (Addressable) MemorySegment.ofAddress(MemoryAddress.NULL, 0, MemorySession.global()).address(),
                        size,
                        0x01 | 0x02,
                        0x01,
                        fd,
                        0
                );
                segment = MemorySegment.ofAddress(addr, size, MemorySession.global());
                if (segment.address().toRawLongValue() == -1) throw new IllegalStateException("mmap failed");
            } catch (Throwable th) {
                close();
                throw th;
            }
            segment = MemorySegment.ofAddress(segment.address(), size, MemorySession.global());
        }
        else {
            throw new IllegalStateException("Unsupported OS");
        }
    }
    public void close() throws Exception {
        try {
            if (SharedMemoryJava.isWindows()) {
                if (segment != null) unmapViewOfFileWindows.invokeExact(segment);
                if (h_map_file != null) closeHandleWindows.invokeExact(h_map_file);
            }
            else if (SharedMemoryJava.isMacOS() || SharedMemoryJava.isLinux()) {
                if (segment != null) munmap_linux.invokeExact(segment, getSize());
                if (is_create) shm_unlink_linux.invokeExact((Addressable) MemorySession.global().allocateUtf8String(name).address());
            }
            else {
                throw new IllegalStateException("Unsupported OS");
            }
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    static {
        if (SharedMemoryJava.isWindows()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", MemorySession.global());
            createFileMappingWindows = linker.downcallHandle(kernel.lookup("CreateFileMappingA").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
            ));
            openFileMappingWindows = linker.downcallHandle(kernel.lookup("OpenFileMappingA").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
            ));
            closeHandleWindows = linker.downcallHandle(kernel.lookup("CloseHandle").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS
            ));
            mapViewOfFileWindows = linker.downcallHandle(kernel.lookup("MapViewOfFile").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
            ));
            unmapViewOfFileWindows = linker.downcallHandle(kernel.lookup("UnmapViewOfFile").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS
            ));
        }
        else if (SharedMemoryJava.isMacOS() || SharedMemoryJava.isLinux()) {
            Linker linker = Linker.nativeLinker();
            var lookup = linker.defaultLookup();
            shm_open_linux = linker.downcallHandle(lookup.lookup("shm_open").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
            )).withVarargs(true);
            ftruncate_linux = linker.downcallHandle(lookup.lookup("ftruncate").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
            ));
            mmap_linux = linker.downcallHandle(lookup.lookup("mmap").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
            ));
            munmap_linux = linker.downcallHandle(lookup.lookup("munmap").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
            ));
            shm_unlink_linux = linker.downcallHandle(lookup.lookup("shm_unlink").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS
            ));

        }
        else {
            throw new IllegalStateException("Unsupported OS");
        }
    }
}
