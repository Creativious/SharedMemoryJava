package net.creativious;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class SharedMemory {
    private final String name;
    private final int size;
    private final boolean is_create;
    protected MemorySegment segment;
    private MemorySegment h_map_file;
    private static MethodHandle createFileMappingWindows, openFileMappingWindows, closeHandleWindows, mapViewOfFileWindows, unmapViewOfFileWindows;
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

    public SharedMemory(String name, int size, boolean is_create) throws Throwable {
        this.name = name;
        this.size = size;
        this.is_create = is_create;
        if (SharedMemoryJava.isWindows()) {
            if (is_create) {
                h_map_file = (MemorySegment) createFileMappingWindows.invokeExact(
                        (MemorySegment) MemoryAddress.ofLong(-1),
                        (MemorySegment) MemoryAddress.NULL,
                        0x04 | 0x08000000,
                        0,
                        size,
                        MemorySession.global().allocateUtf8String(name)
                );
            } else {
                h_map_file = (MemorySegment) openFileMappingWindows.invokeExact(
                        0x0002 | 0x0004,
                        0,
                        name
                );
            }
            if (h_map_file.address().toRawLongValue() == 0) throw new IllegalStateException("CreateFileMapping failed");
            try {
                segment = (MemorySegment) mapViewOfFileWindows.invokeExact(
                        h_map_file,
                        0x0002 | 0x0004,
                        0,
                        0,
                        size
                );
                if (segment.address().toRawLongValue() == 0) throw new IllegalStateException("MapViewOfFile failed");
            } catch (Throwable th) {
                closeHandleWindows.invokeExact(h_map_file);
                throw th;
            }
            segment = MemorySegment.ofAddress(MemoryAddress.ofLong(segment.address().toRawLongValue()), size, MemorySession.global());

        }
    }
    public void close() throws Exception {
        try {
            if (segment != null) unmapViewOfFileWindows.invokeExact(segment);
            if (h_map_file != null) closeHandleWindows.invokeExact(h_map_file);
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    static {
        if (SharedMemoryJava.isWindows()) {
            Linker linker = Linker.nativeLinker();
            var kernel = SymbolLookup.libraryLookup("kernel32.dll", MemorySession.global());
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
    }
}
