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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static katworks.Main.*;

public class TwitterScraper {

    // Pre-compile Regex for performance optimization
    private static final Pattern TCO_LINK_PATTERN = Pattern.compile("\\s*https?://t\\.co/[a-zA-Z0-9]+$");
    private static final String WAITING_STATUS = "Waiting";

    /**
     * Scrapes a user's media timeline using their account object as the lookup.
     * Scrapes all media if lastScrapedId is null.
     * @param account The TwitterAccount to scrape.
     * @return The number of new posts archived.
     */
    public static int scrapeByAccount(TwitterAccount account) {
        return executeScrape(account, null, null, true);
    }

    /**
     * Scrapes a user's media timeline starting from a specific post ID and going backward.
     * @param account     The TwitterAccount to scrape.
     * @param startPostId The post ID to begin scraping from (going backward).
     * @param stopPostId  The post ID to stop scraping at.
     * @return The number of new posts archived.
     */
    public static int scrapeFromPostId(TwitterAccount account, String startPostId, String stopPostId) {
        account.lastScrapedId = null; // force set to null to not stop immediately
        return executeScrape(account, startPostId, stopPostId, false);
    }

    /**
     * Unified underlying loop for scraping timelines. Handles pagination, cursors, and constraints.
     */
    private static int executeScrape(TwitterAccount account, String startPostId, String stopPostId, boolean useAccountStopId) {
        int archivedCount = 0;
        String newestPostIdFound = null;
        boolean stopReached = false;
        boolean foundStartPost = (startPostId == null || startPostId.trim().isEmpty());
        String cursor = null;
        /*  "cursor" is the bottom/top of the page's content that was returned to us.
            example JSON. "value" is the string you need to pass to ConfigUserMedia to get the next group of media.
            {
                "value": "DAABCgABG_D22U1___cKAAIRfVNmyFTgAggAAwAAAAIAAA",
                "cursorType": "BOTTOM",
                "entryId": "cursor-bottom-2013380446603182073",
                "sortIndex": "2013380446603182073"
            }  */
        try {
            /*
                Essentially how this works is we get the base "instructions" JSONArray, look at each of the JSONObjects
                inside, check if it has an "entries" JSONArray, then look in each of the objects inside "entries" to see if it
                has an "items" JSONArray or not. If this is the first scrape, it will have "items" - however if a cursor was included,
                it will not. If we have "items," then send each of the contents of the "items" JSONArray to the parser; if not,
                send each of the contents of the "entries" JSONArray to the parser instead since they have the same content formatting.
                Once that is done, get the new cursor from either of the JSONObjects that has "entries" in it (there may
                be two JSONObjects that have "entries" JSONArrays in them!). If this doesn't make sense still, uncomment
                the line below to see the difference between the 1st scrape and the following scrapes with a cursor.
                 */
            //Files.writeString(new File("user-media+" + cursor + ".json").toPath(), JsonUtil.toJson(userMedia));
            do {
                ConfigUserMedia mediaScrape = new ConfigUserMedia(account.twitterId, cursor); //scrape the media page. default is with no cursor (newest posts). later requests will use the cursor to continue "scrolling" downward.
                mediaScrape.count = 4096; //set to 999 because default value is 20. not sure if this actually does anything of note. testing 4096
                UserMedia userMedia = api.scrap(mediaScrape, CLIENT);

                JSONObject mediaJson = new JSONObject(JsonUtil.toJson(userMedia)); //JsonUtil is something from java-twitter-scraper, it returns the result as a string.
                JSONArray instructionsArray = mediaJson.optJSONArray("instructions");

                if (instructionsArray == null) break;

                boolean itemsFoundInThisRequest = false;
                String nextCursor = null;

                for (int i = 0; i < instructionsArray.length(); i++) {
                    JSONObject instruction = instructionsArray.getJSONObject(i);
                    String type = instruction.optString("type", "");

                    if (instruction.has("entries")) {
                        JSONArray entries = instruction.getJSONArray("entries");

                        for (int e = 0; e < entries.length(); e++) {
                            JSONObject entry = entries.getJSONObject(e);

                            // 1. HANDLE DATA
                            JSONArray itemsToProcess = null;
                            if (entry.has("items")) {
                                itemsToProcess = entry.getJSONArray("items");
                            } else if ("TimelineAddToModule".equals(type)) {
                                itemsToProcess = new JSONArray().put(entry);
                            }

                            if (itemsToProcess != null && itemsToProcess.length() > 0) {
                                itemsFoundInThisRequest = true;

                                for (int j = 0; j < itemsToProcess.length(); j++) {
                                    JSONObject item = itemsToProcess.getJSONObject(j);
                                    String itemId = item.optString("id", null);
                                    if (itemId == null) continue;

                                    if (useAccountStopId) {
                                        // 1. Save the very first post we see to the database as the new boundary
                                        if (newestPostIdFound == null) {
                                            newestPostIdFound = itemId;
                                            DatabaseHandler.setLastScrapedId(account.twitterId, newestPostIdFound);
                                        }
                                        // 2. If the current post is older than our last known scrape, STOP.
                                        if (account.lastScrapedId != null && Long.parseUnsignedLong(itemId) <= Long.parseUnsignedLong(account.lastScrapedId)) {
                                            stopReached = true;
                                            break;
                                        }
                                    }

                                    // Custom start/stop logic for specific post ranges
                                    if (stopPostId != null && stopPostId.equals(itemId)) {
                                        stopReached = true;
                                        break;
                                    }
                                    if (!foundStartPost && itemId.equals(startPostId)) {
                                        foundStartPost = true;
                                    }

                                    // Process the valid item
                                    if (foundStartPost) {
                                        TwitterPost p = extractAndDownloadPost(item);
                                        if (p != null) { // Null means no media, so we skip DB & Discord
                                            savePostToDbAsync(p);
                                            if (config.discordEnabled) {
                                                DiscordNotificationService.sendNewPostNotification(p, account);
                                            }
                                            archivedCount++;
                                            Thread.sleep(4570); // rate-limit protection
                                        }
                                    }
                                }
                            }

                            // 2. HANDLE CURSOR
                            if (stopReached) break;
                            if ("BOTTOM".equals(entry.optString("cursorType"))) {
                                nextCursor = entry.getString("value");
                            }
                        }
                    }
                    if (stopReached) break;
                }

                if (nextCursor != null) cursor = nextCursor;

                if (!itemsFoundInThisRequest || stopReached) {
                    System.out.println(stopReached ? "Reached previously scraped post. Stopping." : "No more items found. Ending scrape.");
                    break;
                }

                System.out.println("Next scrape preparation complete...\n");
                Thread.sleep(3000);

            } while (true);

        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }

        return archivedCount;
    }

    /**
     * Scrapes a single post ID and archives it.
     * @param postId ID of the tweet.
     */
    public static TwitterPost scrapePostById(String postId) {
        try {
            TweetDetail tweetDetail = api.scrap(new ConfigTweetDetail(postId, null), CLIENT);
            JSONObject mediaJson = new JSONObject(JsonUtil.toJson(tweetDetail));
            JSONArray instructionsArray = mediaJson.optJSONArray("instructions");

            if (instructionsArray == null) return null;

            for (int i = 0; i < instructionsArray.length(); i++) {
                JSONObject instruction = instructionsArray.getJSONObject(i);
                if (instruction.has("entries")) {
                    JSONArray entries = instruction.getJSONArray("entries");
                    for (int e = 0; e < entries.length(); e++) {
                        JSONObject entry = entries.getJSONObject(e);
                        if (postId.equals(entry.optString("id"))) {
                            TwitterPost post = extractAndDownloadPost(entry);
                            if (post != null) savePostToDbAsync(post);
                            return post;
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * Unified parser: Parses post JSON, extracts media URLs, downloads media, and builds the TwitterPost object.
     */
    private static TwitterPost extractAndDownloadPost(JSONObject post) {
        if (!post.has("entities") || !post.getJSONObject("entities").has("media")) return null;
        JSONArray medias = post.getJSONObject("entities").optJSONArray("media");
        if (medias == null || medias.isEmpty()) return null;

        JSONObject userObj = post.getJSONObject("user");
        String screenName = userObj.getString("screenName");
        //System.out.println("Account is: " + screenName);

        TwitterPost twitterPost = new TwitterPost();
        twitterPost.twitterId = userObj.getString("restId");
        twitterPost.screenName = screenName;
        twitterPost.postId = post.getString("id");
        twitterPost.postText = TCO_LINK_PATTERN.matcher(post.getString("text")).replaceAll("");
        twitterPost.postDate = post.getLong("creationDate");
        twitterPost.archiveDate = Instant.now(Clock.systemUTC()).getEpochSecond();
        twitterPost.contentRating = WAITING_STATUS;
        twitterPost.safetyRating = WAITING_STATUS;
        twitterPost.media = new ArrayList<>();

        System.out.println("Post ID: " + twitterPost.postId);

        String baseDownloadPath = config.imageDownloadPath + "/Waiting/Waiting/";

        for (int m = 0; m < medias.length(); m++) {
            TwitterMedia twitterMedia = new TwitterMedia();
            JSONObject media = medias.getJSONObject(m);
            String type = media.getString("type");
            String returnedURL = media.getString("media_url_https");

            if ("photo".equals(type)) {
                int dot = returnedURL.lastIndexOf(".");
                twitterMedia.originalUrl = returnedURL.substring(0, dot) + "?format=" + returnedURL.substring(dot + 1) + "&name=orig";
                twitterMedia.mediaType = returnedURL.substring(dot + 1);
            } else if ("animated_gif".equals(type) || "video".equals(type)) {
                twitterMedia.originalUrl = getHighestBitrateURL(media.getJSONObject("videoInfo"));
                twitterMedia.mediaType = "mp4";
            } else {
                twitterMedia.originalUrl = returnedURL;
                twitterMedia.mediaType = "unknown";
            }

            twitterMedia.mediaIndex = m;
            twitterMedia.width = media.getJSONObject("originalInfo").getInt("width");
            twitterMedia.height = media.getJSONObject("originalInfo").getInt("height");

            String localFilename = screenName + "_" + twitterPost.postId + "_" + twitterMedia.mediaIndex + "." + twitterMedia.mediaType;
            twitterMedia.localPath = baseDownloadPath + localFilename;

            DownloadResult result = DownloadFile.download(twitterMedia.originalUrl, twitterMedia.localPath);
            twitterMedia.filesize = result.filesize;
            twitterMedia.perceptualHash = result.perceptualHash;
            twitterMedia.dataHash = result.sha256;

            //System.out.println("Downloaded Media " + m + " -> " + twitterMedia.localPath);
            twitterPost.media.add(twitterMedia);
        }

        return twitterPost;
    }

    /**
     * Handles the database insertion for posts and media asynchronously.
     */
    private static void savePostToDbAsync(TwitterPost twitterPost) {
        writeQueue.runAsyncWrite(conn -> {
            String postSql = "INSERT OR IGNORE INTO posts (post_id, twitter_id, post_text, post_date, archive_date, safety_rating, content_rating) VALUES (?, ?, ?, ?, ?, ?, ?)";
            String mediaSql = "INSERT INTO media (post_id, media_type, original_url, local_path, data_hash, perceptual_hash, width, height, filesize, media_index, safety_rating, content_rating) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement postStmt = conn.prepareStatement(postSql);
                 PreparedStatement mediaStmt = conn.prepareStatement(mediaSql)) {

                postStmt.setString(1, twitterPost.postId);
                postStmt.setString(2, twitterPost.twitterId);
                postStmt.setString(3, twitterPost.postText);
                postStmt.setLong(4, twitterPost.postDate);
                postStmt.setLong(5, twitterPost.archiveDate);
                postStmt.setString(6, WAITING_STATUS);
                postStmt.setString(7, WAITING_STATUS);

                int rowsUpdated = postStmt.executeUpdate();

                if (rowsUpdated > 0) { // Prevents duplicate media entries
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
                        mediaStmt.setString(11, WAITING_STATUS);
                        mediaStmt.setString(12, WAITING_STATUS);
                        mediaStmt.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save post to database: " + twitterPost.postId, e);
            }
        });
    }

    /**
     * Parses the "videoInfo" JSON and returns the highest bitrate video URL.
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

            if (currentBitrate > maxBitrate) {
                maxBitrate = currentBitrate;
                bestUrl = url;
            } else if (currentBitrate == maxBitrate && "video/mp4".equals(contentType)) {
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    public static TwitterAccount getUserProfileByName(String screenName) {
        try {
            return parseUserProfile(api.scrap(new ConfigUserByScreenName(screenName), CLIENT));
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static TwitterAccount getUserProfileById(String twitterId) {
        try {
            return parseUserProfile(api.scrap(new ConfigUserByRestId(twitterId), CLIENT));
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static TwitterAccount parseUserProfile(User user) {
        JSONObject jsonObject = new JSONObject(JsonUtil.toJson(user));
        TwitterAccount account = new TwitterAccount();
        account.twitterId = jsonObject.getString("restId");
        account.displayName = jsonObject.getString("name");
        account.screenName = jsonObject.getString("screenName");
        return account;
    }
}