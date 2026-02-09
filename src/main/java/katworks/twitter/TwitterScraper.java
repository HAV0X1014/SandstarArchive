package katworks.twitter;

import dev.seeight.twitterscraper.config.timeline.ConfigTweetDetail;
import dev.seeight.twitterscraper.config.user.ConfigUserByRestId;
import dev.seeight.twitterscraper.config.user.ConfigUserByScreenName;
import dev.seeight.twitterscraper.config.user.ConfigUserMedia;
import dev.seeight.twitterscraper.impl.timeline.TweetDetail;
import dev.seeight.twitterscraper.impl.user.User;
import dev.seeight.twitterscraper.impl.user.UserMedia;
import dev.seeight.twitterscraper.util.JsonUtil;
import katworks.database.DatabaseHandler;
import katworks.discord.DiscordNotificationService;
import katworks.impl.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static katworks.Main.*;

public class TwitterScraper {
    /**
     * Scrapes a user's media timeline using their account object as the lookup. Scrapes all media if lastScrapedId is null.
     * @param
     * @throws InterruptedException
     */
    public static void scrapeByAccount(TwitterAccount account) throws InterruptedException {
        String newestPostIdFound = null;
        boolean stopReached = false;
        /*  "cursor" is the bottom/top of the page's content that was returned to us.
            example JSON. "value" is the string you need to pass to ConfigUserMedia to get the next group of media.
            {
                "value": "DAABCgABG_D22U1___cKAAIRfVNmyFTgAggAAwAAAAIAAA",
                "cursorType": "BOTTOM",
                "entryId": "cursor-bottom-2013380446603182073",
                "sortIndex": "2013380446603182073"
            }  */
        try {
            String cursor = null;
            boolean notDone = true;
            do {
                //scrape the media page. default is with no cursor (newest posts). later requests will use the cursor to continue "scrolling" downward.
                ConfigUserMedia mediaScrape = new ConfigUserMedia(account.twitterId, cursor);
                mediaScrape.count = 999; //set to 999 because default value is 20. not sure if this actually does anything of note.
                UserMedia userMedia = api.scrap(mediaScrape, CLIENT);
                //JsonUtil is something from java-twitter-scraper, it returns the result as a string.
                String json = JsonUtil.toJson(userMedia);
                JSONObject mediaJson = new JSONObject(json);
                JSONArray instructionsArray = mediaJson.getJSONArray("instructions");

                boolean itemsFoundInThisRequest = false;
                String nextCursor = null;
                /*
                Essentially how this works is we get the base "instructions" JSONArray, look at each of the JSONObjects
                inside, check if it has an "entries" JSONArray, then look in each of the objects inside "entries" to see if it
                has an "items" JSONArray or not. If this is the first scrape, it will have "items" - however if a cursor was included,
                it will not. If we have "items," then send the contents of the "items" JSONArray to the parser; if not,
                send the contents of the "entries" JSONArray to the parser instead since they have the same content formatting.
                Once that is done, get the new cursor from either of the JSONObjects that has "entries" in it (there may
                be two JSONObjects that have "entries" JSONArrays in them!). If this doesn't make sense still, uncomment
                the line below to see the difference between the 1st scrape and the following scrapes with a cursor.
                 */
                //Files.writeString(new File("user-media+" + cursor + ".json").toPath(), JsonUtil.toJson(userMedia));
                for (int i = 0; i < instructionsArray.length(); i++) {
                    JSONObject instruction = instructionsArray.getJSONObject(i);
                    String type = instruction.optString("type", "");

                    if (instruction.has("entries")) {
                        JSONArray entries = instruction.getJSONArray("entries");

                        for (int e = 0; e < entries.length(); e++) {
                            JSONObject entry = entries.getJSONObject(e);

                            // 1. HANDLE DATA (Check for "items" or if the entry itself is a data item)
                            // In TimelineAddEntries, items are nested. In TimelineAddToModule, entries ARE the items.
                            JSONArray itemsToProcess = null;
                            if (entry.has("items")) {
                                itemsToProcess = entry.getJSONArray("items");
                            } else if (type.equals("TimelineAddToModule")) {
                                itemsToProcess = new JSONArray().put(entry);
                            }

                            if (itemsToProcess != null && itemsToProcess.length() > 0) {
                                itemsFoundInThisRequest = true;
                                // Capture the very first post ID encountered as the newest
                                if (newestPostIdFound == null) {
                                    // Peek at the first item's ID
                                    newestPostIdFound = itemsToProcess.getJSONObject(0).getString("id");
                                    DatabaseHandler.setLastScrapedId(account.twitterId,newestPostIdFound);
                                }
                                // Process items and check if we hit the stop ID
                                stopReached = processItems(itemsToProcess, account);
                                if (stopReached) break;
                            }

                            // 2. HANDLE CURSOR
                            if (entry.has("cursorType") && entry.getString("cursorType").equals("BOTTOM")) {
                                nextCursor = entry.getString("value");
                            }
                        }
                    }
                    if (stopReached) break;
                }
// Update the global cursor for the next request
                if (nextCursor != null) {
                    cursor = nextCursor;
                }
// If we processed no items in any of the instructions or have reached the stored last scraped id, we have reached the end.
                if (!itemsFoundInThisRequest || stopReached) {
                    System.out.println(stopReached ? "Reached previously scraped post. Stopping." : "No more items found. Ending scrape.");
                    break;
                }
                System.out.println("Next scrape preparation complete...\n\n");
                Thread.sleep(3000);
            } while (notDone);
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        }
    }

    /**
     * Parses the returned JSON for media, downloads the media it can find, and sends the media and post data to the database.
     * Takes in the "items" JSONArray from a scrape of user media.
     * @param items
     */
    private static boolean processItems(JSONArray items, TwitterAccount account) {
        try {
            for (int it = 0; it < items.length(); it++) {
                JSONObject post = items.getJSONObject(it);
                String currentPostId = post.getString("id");

                if (account.lastScrapedId != null && currentPostId.equals(account.lastScrapedId)) {
                    return true;
                }
                TwitterPost twitterPost = new TwitterPost();
                // Safety check: ensure it's a valid post object before parsing
                //this should always be there idk why gemini insisted on it lol
                if (!post.has("user")) continue;
                System.out.println("Artist: " + post.getJSONObject("user").getString("screenName")); //this is for println version
                String screenName = post.getJSONObject("user").getString("screenName");
                /* POSTS TABLE DATA TO INSERT
                //safety_rating is added later.
                //content_rating is added later.
                */
                twitterPost.twitterId = post.getJSONObject("user").getString("restId");
                twitterPost.postId = currentPostId;
                twitterPost.postText = post.getString("text").replaceAll("\\s*https?://t\\.co/[a-zA-Z0-9]+$","");
                twitterPost.postDate = post.getLong("creationDate");
                twitterPost.archiveDate = Instant.now(Clock.systemUTC()).getEpochSecond();
                twitterPost.contentRating = "Waiting";
                twitterPost.safetyRating = "Waiting";

                System.out.println("Post ID: " + twitterPost.postId);
                /*
                System.out.println("Post Text: " + twitterPost.postText);
                System.out.println("Post Date: " + twitterPost.postDate);
                System.out.println("Archive Date: " + twitterPost.archiveDate);

                 */

                List<TwitterMedia> mediaList = new java.util.ArrayList<>(List.of());
                if (post.has("entities") && post.getJSONObject("entities").has("media") && !post.getJSONObject("entities").getJSONArray("media").isEmpty()) {
                    JSONArray medias = post.getJSONObject("entities").getJSONArray("media");
                    for (int m = 0; m < medias.length(); m++) {
                        TwitterMedia twitterMedia = new TwitterMedia();
                        JSONObject media = medias.getJSONObject(m);

                        String returnedURL = media.getString("media_url_https");
                        //parsing media URLs varies per "type" of media.
                        if (media.getString("type").equals("photo")) {
                            int dot = returnedURL.lastIndexOf(".");
                            twitterMedia.originalUrl = returnedURL.substring(0, dot) + "?format=" + returnedURL.substring(dot + 1) + "&name=orig";
                            twitterMedia.mediaType = returnedURL.substring(dot + 1);
                        } else if (media.getString("type").equals("animated_gif") || media.getString("type").equals("video")) { //else it is a GIF or video. URL needs to be mangled a bit.
                            twitterMedia.originalUrl = getHighestBitrateURL(media.getJSONObject("videoInfo"));
                            twitterMedia.mediaType = "mp4";
                        } else {
                            twitterMedia.originalUrl = returnedURL;
                            twitterMedia.mediaType = "unknown";
                        }
                        //caption is added later.
                        //apparently media_index isnt in twitter's API but we can get that number still
                        twitterMedia.mediaIndex = m;
                        //duplicate_of code will be added later.
                        twitterMedia.width = media.getJSONObject("originalInfo").getInt("width");
                        twitterMedia.height = media.getJSONObject("originalInfo").getInt("height");
                        //download to temp "waiting" folder first. it will be moved by a button interaction later.
                        //moved downloader to separate class. it was a nightmare otherwise.
                        DownloadResult result = DownloadFile.download(twitterMedia.originalUrl,config.imageDownloadPath + "/Waiting/Waiting/" + screenName + "_" + twitterPost.postId + "_" + twitterMedia.mediaIndex + "." + twitterMedia.mediaType);
                        twitterMedia.filesize = result.filesize;
                        twitterMedia.perceptualHash = result.perceptualHash;
                        twitterMedia.dataHash = result.sha256;
                        twitterMedia.localPath = config.imageDownloadPath + "/Waiting/Waiting/" + screenName + "_" + twitterPost.postId + "_" + twitterMedia.mediaIndex + "." + twitterMedia.mediaType;
                        /*
                        System.out.println("Media Index: " + m);
                        System.out.println("URL: " + twitterMedia.originalUrl);
                        System.out.println("Media Type: " + twitterMedia.mediaType);
                        System.out.println("Width: " + twitterMedia.width);
                        System.out.println("Height: " + twitterMedia.height);
                        System.out.println("Perceptual Hash: " + twitterMedia.perceptualHash);
                        System.out.println("Data Hash: " + twitterMedia.dataHash);
                        System.out.println("Download Location: " + twitterMedia.localPath);
                        System.out.println("Filesize: " + twitterMedia.filesize);

                         */

                        mediaList.add(twitterMedia);
                    }
                } else {
                    //if there is no media to parse (posts with youtube links)
                    continue;
                }
                twitterPost.media = mediaList;

                writeQueue.runAsyncWrite(conn -> {
                    String postSql = "INSERT OR IGNORE INTO posts (post_id, twitter_id, post_text, post_date, archive_date, safety_rating, content_rating) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    String mediaSql = "INSERT INTO media (post_id, media_type, original_url, local_path, data_hash, perceptual_hash, width, height, filesize, media_index, safety_rating, content_rating) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement postStmt = conn.prepareStatement(postSql);
                         PreparedStatement mediaStmt = conn.prepareStatement(mediaSql)) {
                        // Fill out the Post template
                        postStmt.setString(1, twitterPost.postId);
                        postStmt.setString(2, twitterPost.twitterId);
                        postStmt.setString(3, twitterPost.postText);
                        postStmt.setLong(4, twitterPost.postDate);
                        postStmt.setLong(5, twitterPost.archiveDate);
                        postStmt.setString(6,"Waiting");
                        postStmt.setString(7,"Waiting");
                        postStmt.executeUpdate();

                        // Fill out the Media templates for this post
                        for (TwitterMedia m : twitterPost.media) {
                            mediaStmt.setString(1, twitterPost.postId);
                            mediaStmt.setString(2, m.mediaType);
                            mediaStmt.setString(3, m.originalUrl);
                            mediaStmt.setString(4, m.localPath);
                            mediaStmt.setString(5, m.dataHash);
                            mediaStmt.setString(6, m.perceptualHash);
                            mediaStmt.setInt(7, m.width);
                            mediaStmt.setInt(8, m.height);
                            mediaStmt.setLong(9, m.filesize);
                            mediaStmt.setInt(10, m.mediaIndex);
                            mediaStmt.setString(11,"Waiting");
                            mediaStmt.setString(12,"Waiting");
                            mediaStmt.executeUpdate();
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });

                if (config.discordEnabled) {
                    DiscordNotificationService.sendNewPostNotification(twitterPost, account);
                }

                System.out.println("\n");
                //sleep to not get rate limited by discord or twitter
                Thread.sleep(4570);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return false; //if the last scraped post ID isnt in this batch.
    }

    /**
     * Scrapes a single post ID and archives it.
     * @param postId
     */
    public static TwitterPost scrapePostById(String postId) {
        //This returns the info for ALL posts - replies, comments, etc. parse the JSON and search for the matching tweet ID
        //once matched, return media, text, etc.
        //thankfully it seems that i can reuse most the parser from ScrapeAccount here.
        TwitterPost post = null;
        try {
            TweetDetail tweetDetail = api.scrap(new ConfigTweetDetail(postId,null), CLIENT);
            String json = JsonUtil.toJson(tweetDetail);
            JSONObject mediaJson = new JSONObject(json);
            JSONArray instructionsArray = mediaJson.getJSONArray("instructions");
            for (int i = 0; i < instructionsArray.length(); i++) {
                JSONObject instruction = instructionsArray.getJSONObject(i);

                if (instruction.has("entries")) {
                    JSONArray entries = instruction.getJSONArray("entries");
                    for (int e = 0; e < entries.length(); e++) {
                        JSONObject entry = entries.getJSONObject(e);
                        if (entry.has("id")) {
                            if (entry.getString("id").equals(postId)) { //if the id matches, then this is the post we want to scrape.
                                post = processPost(entry);
                            }
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        return post;
    }

    /**
     * Parses the returned JSON object for the post, downloads the media it can find, and sends the media and post data to the database.
     *
     * @param post
     */
    private static TwitterPost processPost(JSONObject post) {
        TwitterPost twitterPost = new TwitterPost();
        System.out.println(post.toString(1));
        System.out.println("Artist: " + post.getJSONObject("user").getString("screenName")); //this is for println version
        String screenName = post.getJSONObject("user").getString("screenName");
            /* POSTS TABLE DATA TO INSERT
            //safety_rating is added later.
            //content_rating is added later.
            */
        twitterPost.twitterId = post.getJSONObject("user").getString("restId");
        twitterPost.screenName = post.getJSONObject("user").getString("screenName");
        twitterPost.postId = post.getString("id");
        twitterPost.postText = post.getString("text").replaceAll("\\s*https?://t\\.co/[a-zA-Z0-9]+$","");
        twitterPost.postDate = post.getLong("creationDate");
        twitterPost.archiveDate = Instant.now(Clock.systemUTC()).getEpochSecond();
        twitterPost.contentRating = "Waiting";
        twitterPost.safetyRating = "Waiting";

        System.out.println("Post ID: " + twitterPost.postId);
        System.out.println("Post Text: " + twitterPost.postText);
        System.out.println("Post Date: " + twitterPost.postDate);
        System.out.println("Archive Date: " + twitterPost.archiveDate);

        List<TwitterMedia> mediaList = new java.util.ArrayList<>(List.of());
        if (post.has("entities") && post.getJSONObject("entities").has("media") && !post.getJSONObject("entities").getJSONArray("media").isEmpty()) {
            JSONArray medias = post.getJSONObject("entities").getJSONArray("media");
            for (int m = 0; m < medias.length(); m++) {
                TwitterMedia twitterMedia = new TwitterMedia();
                JSONObject media = medias.getJSONObject(m);

                String returnedURL = media.getString("media_url_https");
                //parsing media URLs varies per "type" of media.
                if (media.getString("type").equals("photo")) {
                    int dot = returnedURL.lastIndexOf(".");
                    twitterMedia.originalUrl = returnedURL.substring(0, dot) + "?format=" + returnedURL.substring(dot + 1) + "&name=orig";
                    twitterMedia.mediaType = returnedURL.substring(dot + 1);
                } else if (media.getString("type").equals("animated_gif") || media.getString("type").equals("video")) { //else it is a GIF or video. URL needs to be mangled a bit.
                    twitterMedia.originalUrl = getHighestBitrateURL(media.getJSONObject("videoInfo"));
                    twitterMedia.mediaType = "mp4";
                } else {
                    twitterMedia.originalUrl = returnedURL;
                    twitterMedia.mediaType = "unknown";
                }
                //caption is added later.
                //apparently media_index isnt in twitter's API but we can get that number still
                twitterMedia.mediaIndex = m;
                //duplicate_of code will be added later.
                twitterMedia.width = media.getJSONObject("originalInfo").getInt("width");
                twitterMedia.height = media.getJSONObject("originalInfo").getInt("height");
                //download to temp "waiting" folder first. it will be moved by a button interaction later.
                //moved downloader to separate class. it was a nightmare otherwise.
                DownloadResult result = DownloadFile.download(twitterMedia.originalUrl,config.imageDownloadPath + "/Waiting/Waiting/" + screenName + "_" + twitterPost.postId + "_" + twitterMedia.mediaIndex + "." + twitterMedia.mediaType);
                twitterMedia.filesize = result.filesize;
                twitterMedia.perceptualHash = result.perceptualHash;
                twitterMedia.dataHash = result.sha256;
                twitterMedia.localPath = config.imageDownloadPath + "/Waiting/Waiting/" + screenName + "_" + twitterPost.postId + "_" + twitterMedia.mediaIndex + "." + twitterMedia.mediaType;

                System.out.println("Media Index: " + m);
                System.out.println("URL: " + twitterMedia.originalUrl);
                System.out.println("Media Type: " + twitterMedia.mediaType);
                System.out.println("Width: " + twitterMedia.width);
                System.out.println("Height: " + twitterMedia.height);
                System.out.println("Perceptual Hash: " + twitterMedia.perceptualHash);
                System.out.println("Data Hash: " + twitterMedia.dataHash);
                System.out.println("Download Location: " + twitterMedia.localPath);
                System.out.println("Filesize: " + twitterMedia.filesize);

                mediaList.add(twitterMedia);
            }
        } else {
            return null; //if the post has no media, exit immediately without writing anything to the database.
        }
        twitterPost.media = mediaList;
        writeQueue.runAsyncWrite(conn -> {
            String postSql = "INSERT OR IGNORE INTO posts (post_id, twitter_id, post_text, post_date, archive_date, safety_rating, content_rating) VALUES (?, ?, ?, ?, ?, ?, ?)";
            String mediaSql = "INSERT INTO media (post_id, media_type, original_url, local_path, data_hash, perceptual_hash, width, height, filesize, media_index, safety_rating, content_rating) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement postStmt = conn.prepareStatement(postSql);
                 PreparedStatement mediaStmt = conn.prepareStatement(mediaSql)) {
                // Fill out the Post template
                postStmt.setString(1, twitterPost.postId);
                postStmt.setString(2, twitterPost.twitterId);
                postStmt.setString(3, twitterPost.postText);
                postStmt.setLong(4, twitterPost.postDate);
                postStmt.setLong(5, twitterPost.archiveDate);
                postStmt.setString(6,"Waiting");
                postStmt.setString(7,"Waiting");
                postStmt.executeUpdate();

                // Fill out the Media templates for this post
                for (TwitterMedia m : twitterPost.media) {
                    mediaStmt.setString(1, twitterPost.postId);
                    mediaStmt.setString(2, m.mediaType);
                    mediaStmt.setString(3, m.originalUrl);
                    mediaStmt.setString(4, m.localPath);
                    mediaStmt.setString(5, m.dataHash);
                    mediaStmt.setString(6, m.perceptualHash);
                    mediaStmt.setInt(7, m.width);
                    mediaStmt.setInt(8, m.height);
                    mediaStmt.setLong(9, m.filesize);
                    mediaStmt.setInt(10, m.mediaIndex);
                    mediaStmt.setString(11,"Waiting");
                    mediaStmt.setString(12,"Waiting");
                    mediaStmt.executeUpdate();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("\n");
        return twitterPost;
    }

    /**
     * Takes the "videoInfo" JSONObject returned from the media of a scrape, and returns the highest bitrate video url it can find.
     * Does not download the video to check, it looks at  the JSON.
     * @param videoInfo
     * @return
     */
    private static String getHighestBitrateURL(JSONObject videoInfo) {
        JSONArray variants = videoInfo.getJSONArray("variants");
        String bestUrl = null;
        long maxBitrate = -1;

        for (int i = 0; i < variants.length(); i++) {
            JSONObject variant = variants.getJSONObject(i);
            long currentBitrate = variant.optLong("bitrate", 0);
            String url = variant.getString("url");
            String contentType = variant.optString("contentType", "");
            // Logic:
            // 1. If this bitrate is strictly higher, take it.
            // 2. If it's a tie (e.g., both are 0), prefer mp4 over m3u8.
            if (currentBitrate > maxBitrate) {
                maxBitrate = currentBitrate;
                bestUrl = url;
            } else if (currentBitrate == maxBitrate && contentType.equals("video/mp4")) {
                // This ensures that if the highest bitrate is 0 (like a GIF),
                // we pick the playable .mp4 instead of the .m3u8 playlist.
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    /**
     * Gets all of the info it can for one account by name. It barely gets anything lol.
     * @param screenName
     * @return
     */
    public static TwitterAccount getUserProfileByName(String screenName) {
        try {
            TwitterAccount account = new TwitterAccount();
            User user = api.scrap(new ConfigUserByScreenName(screenName),CLIENT);
            JSONObject jsonObject = new JSONObject(JsonUtil.toJson(user));

            account.twitterId = jsonObject.getString("restId");
            account.displayName = jsonObject.getString("name");
            account.screenName = jsonObject.getString("screenName");

            return account;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets all of the info it can for one account by ID.
     * @param twitterId
     * @return
     */
    public static TwitterAccount getUserProfileById(String twitterId) {
        try {
            TwitterAccount account = new TwitterAccount();
            System.out.println("\"" + twitterId + "\"");
            User user = api.scrap(new ConfigUserByRestId(twitterId),CLIENT);
            JSONObject jsonObject = new JSONObject(JsonUtil.toJson(user));

            account.twitterId = jsonObject.getString("restId");
            account.displayName = jsonObject.getString("name");
            account.screenName = jsonObject.getString("screenName");

            return account;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
