package katworks.impl;

import java.util.List;

public class TwitterPost {
    public String postId; ///Post's ID snowflake from twitter.///
    public String screenName;
    public String twitterId; ///Artist's user ID snowflake from twitter.
    public String postText; ///Original post's text.
    public long postDate; ///Unix timestamp of the original post in GMT.
    public long archiveDate; ///Unix timestamp of when the post was archived in GMT.
    public String safetyRating; ///Safety rating (SFW, NSFW, etc.) of the whole post.
    public String contentRating; ///Content rating (KF, NonKF, Rejected) of the whole post.
    public List<TwitterMedia> media; ///list of TwitterMedia objects that contain URL, download path, filesize, etc. may be multiple per post so thats why this is a list.
    /**
     * Class that you will definitely use as an object.
     * Holds data that will be inserted into the posts table.
     */
    public TwitterPost() {

    }
}
