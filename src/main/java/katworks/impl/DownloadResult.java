package katworks.impl;

public class DownloadResult {
    public long filesize;
    public String perceptualHash;
    public String sha256;
    public DownloadResult() {

    }

    public DownloadResult(long filesize, String perceptualHash, String sha256) {
        this.filesize = filesize;
        this.perceptualHash = perceptualHash;
        this.sha256 = sha256;
    }
}
