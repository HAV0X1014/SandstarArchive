package katworks.impl;

public class TwitterMedia {
    public int id; ///Internally used ID for the media table.
    public String postId; ///Post's ID snowflake from twitter that links back to TwitterPost table.
    public String mediaType; ///File extension.
    public String originalUrl; ///Original media URL from twitter.
    public String localPath; ///Path to locally downloaded file on disk.
    public String caption; ///Manually written caption for the contents of the media.
    public int mediaIndex; ///Inferred (guessed) index which position this media was in the original post.
    public String perceptualHash; ///Perceptual hash of the media.
    public String dataHash; ///SHA-256 hash of the media.
    public int duplicateOf; ///The (internal) id of the other media that shares the same perceptual or data hash.
    public int width; ///The width of the media.
    public int height; ///The height of the media.
    public long filesize; ///The filesize of the media on disk.
    public String safetyRating; ///The safety rating of the media.
    public String contentRating; ///The content rating of the media.

    //absolute minimum data needed for an insert
    /**
     * info that gets inserted into the media table
     * @param mediaType File extension of the media. PNG, JPG, MP4, etc.
     * @param originalUrl Original media URL from twitter.
     * @param localPath Path to locally downloaded file on disk.
     * @param mediaIndex (Guessed) index of which position this media was in the original post.
     * @param width The width of the media.
     * @param height The height of the media.
     */
    public TwitterMedia(String mediaType, String originalUrl, String localPath, int mediaIndex, int width, int height) {
        this.mediaType = mediaType;
        this.originalUrl = originalUrl;
        this.localPath = localPath;
        this.mediaIndex = mediaIndex;
        this.width = width;
        this.height = height;
    }

    public TwitterMedia() {

    }
}
