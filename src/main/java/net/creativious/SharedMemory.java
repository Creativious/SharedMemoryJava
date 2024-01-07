package net.creativious;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class SharedMemory {
    private final String name;
    private final int size;
    private static final int O_CREAT = 0x00000200, O_EXCL = 0x00000800, O_RDWR = 0x0002,
            PROT_READ = 0x01, PROT_WRITE = 0x02, MAP_SHARED = 0x01;
    @SuppressWarnings("OctalInteger")
    private static final short S_IRUSR = 00400, S_IWUSR = 00200;
    private final boolean is_create;
    protected MemorySegment segment;
    private MemorySegment h_map_file;
    private static MethodHandle createFileMappingWindows, openFileMappingWindows, closeHandleWindows, mapViewOfFileWindows, unmapViewOfFileWindows;
    private static MethodHandle shm_open_linux, ftruncate_linux, mmap_linux, munmap_linux, shm_unlink_linux;

    private static final int SECTION_QUERY = 0x0001, SECTION_MAP_WRITE = 0x0002, SECTION_MAP_READ = 0x0004,
            SECTION_MAP_EXECUTE = 0x0008, SECTION_EXTEND_SIZE = 0x0010, SECTION_MAP_EXECUTE_EXPLICIT = 0x0020,
            SECTION_ALL_ACCESS = SECTION_QUERY | SECTION_MAP_WRITE | SECTION_MAP_READ | SECTION_MAP_EXECUTE | SECTION_EXTEND_SIZE | SECTION_MAP_EXECUTE_EXPLICIT;
    @SuppressWarnings("unused")
    private static final int PAGE_NOACCESS = 0x01, PAGE_READONLY = 0x02, PAGE_READWRITE = 0x04, PAGE_WRITECOPY = 0x08,
            PAGE_EXECUTE = 0x10, PAGE_EXECUTE_READ = 0x20, PAGE_EXECUTE_READWRITE = 0x40, PAGE_EXECUTE_WRITECOPY = 0x80,
            PAGE_GUARD = 0x100, PAGE_NOCACHE = 0x200, PAGE_WRITECOMBINE = 0x400;
    @SuppressWarnings("unused")
    private static final int SEC_COMMIT = 0x08000000, SEC_LARGE_PAGES = 0x80000000, FILE_MAP_LARGE_PAGES = 0x20000000;
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
                h_map_file = (MemorySegment) createFileMappingWindows.invokeExact(
                        (MemorySegment) MemorySegment.ofAddress(-1),
                        MemorySegment.NULL,
                        PAGE_READWRITE | SEC_COMMIT,
                        0,
                        size,
                        (MemorySegment) Arena.ofAuto().allocateUtf8String(name)
                );
            } else {
                h_map_file = (MemorySegment) openFileMappingWindows.invokeExact(
                        SECTION_MAP_WRITE | SECTION_MAP_READ, 0, name
                );
            }
            if (h_map_file.address() == 0) throw new IllegalStateException("CreateFileMapping failed");
            try {
                segment = (MemorySegment) mapViewOfFileWindows.invokeExact(h_map_file, SECTION_MAP_WRITE | SECTION_MAP_READ, 0, 0, size);
                if (segment.address() == 0) throw new IllegalStateException("MapViewOfFile failed");
            } catch (Throwable th) {
                closeHandleWindows.invokeExact(h_map_file);
                throw th;
            }
            segment = segment.reinterpret(size);

        }
        else if (SharedMemoryJava.isMacOS() || SharedMemoryJava.isLinux()) {
            int mode = O_RDWR;
            if (is_create) mode |= O_CREAT | O_EXCL;
            int fd = (int) shm_open_linux.invokeExact(Arena.ofAuto().allocateUtf8String(name), mode, (int) (S_IRUSR));
            if (fd == -1) throw new IllegalStateException("shm_open failed");
            try {
                if (is_create && (int) ftruncate_linux.invokeExact(fd, size) == 1) throw new IllegalStateException("ftruncate failed");
                segment = (MemorySegment) mmap_linux.invokeExact(
                        MemorySegment.NULL,
                        size,
                        PROT_READ | PROT_WRITE,
                        MAP_SHARED,
                        fd,
                        0
                );
                if (segment.address() == -1) throw new IllegalStateException("mmap failed");
            } catch (Throwable th) {
                close();
                throw th;
            }
            segment = segment.reinterpret(size);
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
                if (is_create) shm_unlink_linux.invokeExact(Arena.ofAuto().allocateUtf8String(getName()));
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
            SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
            createFileMappingWindows = linker.downcallHandle(kernel.find("CreateFileMappingA").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
            ));
            openFileMappingWindows = linker.downcallHandle(kernel.find("OpenFileMappingA").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
            ));
            closeHandleWindows = linker.downcallHandle(kernel.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS
            ));
            mapViewOfFileWindows = linker.downcallHandle(kernel.find("MapViewOfFile").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
            ));
            unmapViewOfFileWindows = linker.downcallHandle(kernel.find("UnmapViewOfFile").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS
            ));
        }
        else if (SharedMemoryJava.isMacOS() || SharedMemoryJava.isLinux()) {
            Linker linker = Linker.nativeLinker();
            var lookup = linker.defaultLookup();
            shm_open_linux = linker.downcallHandle(lookup.find("shm_open").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
            ), Linker.Option.firstVariadicArg(2));
            ftruncate_linux = linker.downcallHandle(lookup.find("ftruncate").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
            ));
            mmap_linux = linker.downcallHandle(lookup.find("mmap").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
            ));
            munmap_linux = linker.downcallHandle(lookup.find("munmap").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
            ));
            shm_unlink_linux = linker.downcallHandle(lookup.find("shm_unlink").orElseThrow(), FunctionDescriptor.of(
                    ValueLayout.ADDRESS
            ));

        }
        else {
            throw new IllegalStateException("Unsupported OS");
        }
    }
}
