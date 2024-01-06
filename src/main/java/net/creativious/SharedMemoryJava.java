package net.creativious;

public class SharedMemoryJava {

    public static boolean isOSSupported() {
        // return true if the OS is Windows or Linux or macOS
        // might remove macOS support if it doesn't work, cuz I'm not testing it
        return isWindows() || isLinux() || isMacOS();
    }

    protected static boolean isWindows() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("win");
    }

    protected static boolean isLinux() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("nix") || os.contains("nux");
    }

    protected static boolean isMacOS() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("mac");
    }

    protected static SystemType getSystemType() {
        if (isWindows()) {
            return SystemType.WINDOWS;
        } else if (isLinux()) {
            return SystemType.LINUX;
        } else if (isMacOS()) {
            return SystemType.MACOS;
        } else {
            return SystemType.OTHER;
        }
    }


}

