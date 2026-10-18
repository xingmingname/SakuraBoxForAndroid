package moe.matsuri.nb4a.utils;

import java.io.File;
import java.io.IOException;

public final class AssetPaths {
    private AssetPaths() {}
    public static File destination(File directory, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
                name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 ||
                name.indexOf('\0') >= 0 || new File(name).isAbsolute()) {
            throw new IOException("Invalid asset filename");
        }
        File root = directory.getCanonicalFile();
        File result = new File(root, name).getCanonicalFile();
        if (!root.equals(result.getParentFile())) throw new IOException("Asset path escapes its directory");
        return result;
    }
}
