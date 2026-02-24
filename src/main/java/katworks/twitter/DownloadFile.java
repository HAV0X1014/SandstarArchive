package katworks.twitter;

import io.github.yuvraj0028.models.HashType;
import io.github.yuvraj0028.service.ImageSimilarityService;
import katworks.impl.DownloadResult;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

import static katworks.Main.CLIENT;

public class DownloadFile {
    /**
     * Downloads a file from the specified URL to the specified filepath.
     * Returns DownloadResult record containing filesize, perceptual hash, and sha256 data hash.
     * Returns null perceptual hash on non-images and other filetypes that cannot be a BufferedImage.
     * @param url
     * @param filename
     * @return DownloadResult record containing filesize, perceptual hash, and sha256 data hash.
     */
    public static DownloadResult download(String url, String filename) {
        //download to temp "waiting" folder first. it will be moved by a button interaction later.
        File targetFile = new File(filename);
        Request request = new Request.Builder().url(url).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            try (BufferedSink sink = Okio.buffer(Okio.sink(targetFile))) {
                sink.writeAll(response.body().source());
            }
            BufferedImage image = ImageIO.read(targetFile);
            if (image == null) { //for videos or anything else that wont become a BufferedImage
                String sha256 = calculateSHA256(targetFile);
                return new DownloadResult(
                        targetFile.length(),
                        null,
                        sha256
                );
            } else { //for everything else
                ImageSimilarityService service = new ImageSimilarityService();
                String pHash = String.valueOf(service.computeHash(image, HashType.PHASH));

                String sha256 = calculateSHA256(targetFile);

                return new DownloadResult(
                        targetFile.length(),
                        pHash,
                        sha256
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static String calculateSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file.toPath()));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
