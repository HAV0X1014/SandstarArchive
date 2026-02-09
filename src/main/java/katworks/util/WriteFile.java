package katworks.util;

import java.io.IOException;

public class WriteFile {
    public static void write(String path, String fileContent) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(path);
            fw.write(fileContent);
            fw.flush();
            fw.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
