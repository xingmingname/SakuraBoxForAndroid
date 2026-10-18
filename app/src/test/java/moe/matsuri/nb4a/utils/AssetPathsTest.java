package moe.matsuri.nb4a.utils;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class AssetPathsTest {
    @Test public void filenamesStayInAssetDirectory() throws Exception {
        File root = Files.createTempDirectory("neko-assets-test").toFile();
        try {
            assertEquals(new File(root,"geoip.db").getCanonicalFile(),AssetPaths.destination(root,"geoip.db"));
            assertEquals(new File(root,"custom.db").getCanonicalFile(),AssetPaths.destination(root,"custom.db"));
            for(String name : new String[]{"../outside.db","..\\outside.db","C:\\outside.db","/outside.db","sub/file.db","sub\\file.db",".","..","bad\0.db"}) {
                try {AssetPaths.destination(root,name);fail("Escaping filename accepted: "+name);} catch(IOException expected) {}
            }
            assertEquals(0,root.list().length);
        } finally {assertTrue(root.delete());}
    }
}
