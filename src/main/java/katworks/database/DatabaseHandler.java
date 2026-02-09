package katworks.database;

import katworks.impl.TwitterAccount;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static katworks.Main.config;
import static katworks.Main.writeQueue;

public class DatabaseHandler {
    private static final String dbUrl = "jdbc:sqlite:" + config.databasePath;

    private static Connection getConnection() throws SQLException {
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        // Wait up to 5 seconds for a lock to clear before throwing SQLITE_BUSY
        sqliteConfig.setBusyTimeout(5000);
        // Allow readers and writers to work simultaneously
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);

        return DriverManager.getConnection(dbUrl, sqliteConfig.toProperties());
    }

    /**
     * Sets the safety rating of a post using the postId as the lookup.
     * @param postId
     * @param newSafetyRating
     * @return
     */
    public static void setSafetyRating(String postId, String newSafetyRating) {
        if (!config.safetyRatings.contains(newSafetyRating) && !newSafetyRating.equals("Waiting")) return;
        setPostRatings(postId, null, newSafetyRating);
    }

    /**
     * Sets the content rating of a post using the postId as the lookup.
     * @param postId
     * @param newContentRating
     * @return
     */
    public static void setContentRating(String postId, String newContentRating) {
        if (!config.contentRatings.contains(newContentRating) && !newContentRating.equals("Waiting")) return;
        setPostRatings(postId, newContentRating, null);
    }

    /**
     * Sets the last scraped twitter ID for the specified twitter account ID.
     * @param twitterId
     * @param lastScrapedId
     * @return
     */
    public static void setLastScrapedId(String twitterId, String lastScrapedId) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET last_scraped_id = ? WHERE twitter_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, lastScrapedId);
                ps.setString(2, twitterId);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Sets the Discord thread ID for the specified twitter account ID.
     * @param twitterId
     * @param discordThreadId
     * @return
     */
    public static void setDiscordThreadId(String twitterId, String discordThreadId) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET discord_thread_id = ? WHERE twitter_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, discordThreadId);
                ps.setString(2, twitterId);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Set the account status of an account by screen name. Status must be "Active", "Deleted", "Suspended"
     * @param screenName
     * @param status I can't be bothered to make an enum for this.
     * @return
     */
    public static void setAccountStatus(String screenName, String status) {
        if (status.equals("Active") || status.equals("Deleted") || status.equals("Suspended")) {
            writeQueue.runAsyncWrite(conn -> {
                String sql = "UPDATE twitter_accounts SET account_status = ? WHERE screen_name = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, status);
                    ps.setString(2, screenName);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            System.out.println("Invalid account status selected. It must be EXACTLY Active, Deleted, or Suspended.");
            return;
        }
    }

    /**
     * Set the download status of an account by twitter ID. True = Download, False = Do not download.
     * @param screenName
     * @param downloadStaus
     * @return
     */
    public static void setDownloadStatus(String screenName, boolean downloadStaus) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET download_status = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBoolean(1, downloadStaus);
                ps.setString(2, screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Set the protected status of an account by twitter ID. True = protected, False = normal.
     * @param screenName
     * @param protect
     * @return
     */
    public static void setProtected(String screenName, boolean protect) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET is_protected = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBoolean(1, protect);
                ps.setString(2, screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Set a displayname for an account by screen name.
     * @param screenName
     * @param newDisplayName
     */
    public static void setDisplayName(String screenName, String newDisplayName) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET display_name = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,newDisplayName);
                ps.setString(2,screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void setAccountSafetyRating(String screenName, String newSafetyRating) {
        if (!config.safetyRatings.contains(newSafetyRating) && !newSafetyRating.equals("Waiting")) return;
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET safety_rating = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,newSafetyRating);
                ps.setString(2,screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void deleteAccountByScreenName(String screenName) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "DELETE FROM twitter_accounts WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Get a List<TwitterAccount> of all accounts that are allowed to be downloaded.
     * The account must have download_status = true, account_status = 'Active', and is_protected = false.
     * @return
     */
    public static List<TwitterAccount> getActiveAccounts() {
        List<TwitterAccount> accounts = new ArrayList<>();
        String sql = "SELECT twitter_id, screen_name, last_scraped_id, download_status, discord_thread_id " +
                "FROM twitter_accounts " +
                "WHERE download_status = 1 AND account_status = 'Active' AND is_protected = 0";

        // Use the new getConnection() helper here
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                TwitterAccount account = new TwitterAccount();
                account.twitterId = rs.getString("twitter_id");
                account.screenName = rs.getString("screen_name");
                account.lastScrapedId = rs.getString("last_scraped_id");
                account.downloadStatus = rs.getBoolean("download_status");
                account.discordThreadId = rs.getString("discord_thread_id");
                accounts.add(account);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return accounts;
    }

    public static TwitterAccount getAccountById(String twitterId) {
        TwitterAccount account = new TwitterAccount();
        String sql = "SELECT twitter_id, artist_id, screen_name, display_name, account_status, is_protected, last_scraped_id, download_status, discord_thread_id, safety_rating " +
                "FROM twitter_accounts " +
                "WHERE twitter_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,twitterId);
            try (ResultSet rs = ps.executeQuery()) {
                account.twitterId = rs.getString("twitter_id");
                account.artistId = rs.getInt("artist_id");
                account.screenName = rs.getString("screen_name");
                account.displayName = rs.getString("display_name");
                account.accountStatus = rs.getString("account_status");
                account.isProtected = rs.getBoolean("is_protected");
                account.lastScrapedId = rs.getString("last_scraped_id");
                account.downloadStatus = rs.getBoolean("download_status");
                account.discordThreadId = rs.getString("discord_thread_id");
                account.safetyRating = rs.getString("safety_rating");
                return account;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static TwitterAccount getAccountByScreenName(String screenName) {
        TwitterAccount account = new TwitterAccount();
        String sql = "SELECT twitter_id, artist_id, screen_name, display_name, account_status, is_protected, last_scraped_id, download_status, discord_thread_id, safety_rating " +
                "FROM twitter_accounts " +
                "WHERE screen_name = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,screenName);
            try (ResultSet rs = ps.executeQuery()) {
                account.twitterId = rs.getString("twitter_id");
                account.artistId = rs.getInt("artist_id");
                account.screenName = rs.getString("screen_name");
                account.displayName = rs.getString("display_name");
                account.accountStatus = rs.getString("account_status");
                account.isProtected = rs.getBoolean("is_protected");
                account.lastScrapedId = rs.getString("last_scraped_id");
                account.downloadStatus = rs.getBoolean("download_status");
                account.discordThreadId = rs.getString("discord_thread_id");
                account.safetyRating = rs.getString("safety_rating");
                return account;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers a new account to the database with the provided details.
     * @param twitterId
     * @param screenName
     * @param displayName
     * @param artistName
     * @param downloadStatus
     * @param accountSafetyRating
     * @return
     */
    public static String registerAccount(String twitterId, String screenName, String displayName, String artistName, boolean downloadStatus, String accountSafetyRating) {
        String responseMessage = "";
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            //check if account already exists
            String checkSql = "SELECT twitter_id FROM twitter_accounts WHERE twitter_id = ?";
            try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
                checkStmt.setString(1, twitterId);
                if (checkStmt.executeQuery().next()) {
                    return "Error: This Twitter account is already in the database.";
                }
            }
            // 2. Resolve the Artist ID
            //check if an artist with the same name exists already
            int artistId = -1;
            String artistCheckSql = "SELECT id FROM artists WHERE name = ?";
            try (PreparedStatement artistStmt = connection.prepareStatement(artistCheckSql)) {
                artistStmt.setString(1, artistName);
                ResultSet rs = artistStmt.executeQuery();
                if (rs.next()) {
                    artistId = rs.getInt("id");
                    responseMessage += "Found existing artist '" + artistName + "'. Linking account... ";
                }
            }

            // 3. Create Artist if they don't exist
            if (artistId == -1) {
                String createArtistSql = "INSERT INTO artists (name, description) VALUES (?, ?)";
                try (PreparedStatement createStmt = connection.prepareStatement(createArtistSql, Statement.RETURN_GENERATED_KEYS)) {
                    createStmt.setString(1, artistName);
                    createStmt.setString(2, "Added via Discord command");
                    createStmt.executeUpdate();

                    ResultSet generatedKeys = createStmt.getGeneratedKeys();
                    if (generatedKeys.next()) {
                        artistId = generatedKeys.getInt(1);
                        responseMessage += "Created new artist '" + artistName + "'. ";
                    } else {
                        throw new SQLException("Failed to create artist, no ID obtained.");
                    }
                }
            }

            // 4. Create the Twitter Account Entry
            String insertAccountSql =
                    "INSERT INTO twitter_accounts " +
                    "(twitter_id, artist_id, screen_name, display_name, account_status, download_status, safety_rating) " +
                    "VALUES (?, ?, ?, ?, 'Active', ?, ?)";

            try (PreparedStatement accStmt = connection.prepareStatement(insertAccountSql)) {
                accStmt.setString(1, twitterId);
                accStmt.setInt(2, artistId);
                accStmt.setString(3, screenName);
                accStmt.setString(4, displayName);
                accStmt.setBoolean(5,downloadStatus);
                accStmt.setString(6,accountSafetyRating);
                accStmt.executeUpdate();
            }
            connection.commit();
            return responseMessage;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the last stored scraped ID from the database.
     * @param twitterId
     * @return
     */
    public static String getLastScrapedId(String twitterId) {
        String sql = "SELECT last_scraped_id FROM twitter_accounts WHERE twitter_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,twitterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // Returns the ID string, or null if the column is empty in the DB
                    return rs.getString("last_scraped_id");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * todo: implement this. discord will not have access to making captions but the site will eventually.
     * in theory i should be able to get the media by the internal id (not twitter id) and update it through that lookup.
     * @return
     */
    public static void setMediaCaption(String id, String captionText) {
        if (captionText == null) return;
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE media SET caption = ?, WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, captionText);
                ps.setString(2, id);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Creates database with required schema at the location specified in the config.
     */
    public static void createDatabase() {
        System.out.println("WELCOME TO SANDSTAR ARCHIVE. Your database is being created at " + config.databasePath);
        try (Connection conn = getConnection(); Statement statement = conn.createStatement()) {
            //enable foreign keys
            statement.execute("PRAGMA journal_mode = WAL;");
            statement.execute("PRAGMA foreign_keys = ON;");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS artists (\n" +
                            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                            "    name TEXT NOT NULL,\n" +
                            "    description TEXT\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS aliases (\n" +
                            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                            "    artist_id INTEGER NOT NULL,\n" +
                            "    alias_name TEXT NOT NULL,\n" +
                            "    safety_rating TEXT,\n" +
                            "    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS twitter_accounts (\n" +
                            "    twitter_id TEXT PRIMARY KEY,\n" +
                            "    artist_id INTEGER NOT NULL,\n" +
                            "    screen_name TEXT NOT NULL COLLATE NOCASE,\n" +
                            "    display_name TEXT,\n" +
                            "    account_status TEXT,\n" +
                            "    is_protected INTEGER DEFAULT 0,\n" +
                            "    last_scraped_id TEXT,\n" +
                            "    download_status INTEGER DEFAULT 1,\n" +
                            "    discord_thread_id TEXT,\n" +
                            "    safety_rating TEXT,\n" +
                            "    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS posts (\n" +
                            "    post_id TEXT PRIMARY KEY,\n" +
                            "    twitter_id TEXT NOT NULL,\n" +
                            "    post_text TEXT,\n" +
                            "    post_date INTEGER,\n" +
                            "    archive_date INTEGER,\n" +
                            "    safety_rating TEXT,\n" +
                            "    content_rating INTEGER,\n" +
                            "    FOREIGN KEY (twitter_id) REFERENCES twitter_accounts(twitter_id) ON DELETE CASCADE\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS media (\n" +
                            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                            "    post_id TEXT NOT NULL,\n" +
                            "    media_type TEXT,\n" +
                            "    original_url TEXT,\n" +
                            "    local_path TEXT,\n" +
                            "    caption TEXT,\n" +
                            "    safety_rating TEXT,\n" +
                            "    content_rating TEXT,\n" +
                            "    media_index INTEGER,\n" +
                            "    perceptual_hash TEXT,\n" +
                            "    data_hash TEXT,\n" +
                            "    duplicate_of INTEGER,\n" +
                            "    width INTEGER,\n" +
                            "    height INTEGER,\n" +
                            "    filesize INTEGER,\n" +
                            "    FOREIGN KEY (post_id) REFERENCES posts(post_id) ON DELETE CASCADE,\n" +
                            "    FOREIGN KEY (duplicate_of) REFERENCES media(id) ON DELETE SET NULL\n" +
                            ");");
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_post_twitter_id ON posts(twitter_id);\n" +
                            "CREATE INDEX IF NOT EXISTS idx_media_post_id ON media(post_id);\n" +
                            "CREATE INDEX IF NOT EXISTS idx_media_data_hash ON media(data_hash);\n" +
                            "CREATE INDEX IF NOT EXISTS idx_media_p_hash ON media(perceptual_hash);");

            System.out.println("DATABASE CREATED!");
        } catch (SQLException e) {
            System.out.println("Database creation failed. Check if .db file was created, delete it, and try again.");
            throw new RuntimeException(e);
        }
    }

    /**
     * Method that updates safety/content ratings in media and posts table, moves files to their proper location, and updates filepath known to database. Allows null for content and safety rating.
     * @param postId
     * @param newContent
     * @param newSafety
     */
    public static void setPostRatings(String postId, String newContent, String newSafety) {
        writeQueue.runAsyncWrite(conn -> {
            try {
                // 1. Fetch current Post record to handle null parameters
                String currentPostContent, currentPostSafety;
                try (PreparedStatement ps = conn.prepareStatement("SELECT content_rating, safety_rating FROM posts WHERE post_id = ?")) {
                    ps.setString(1, postId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        currentPostContent = rs.getString("content_rating");
                        currentPostSafety = rs.getString("safety_rating");
                    } else {
                        System.err.println("[Error] Attempted to update ratings for non-existent post: " + postId);
                        return;
                    }
                }

                // Determine what the new ratings should be (Keep current if parameter is null)
                String finalContent = (newContent != null) ? newContent : currentPostContent;
                String finalSafety = (newSafety != null) ? newSafety : currentPostSafety;

                // If absolutely nothing is changing, exit early to save IO
                if (finalContent.equals(currentPostContent) && finalSafety.equals(currentPostSafety)) return;

                // 2. Fetch all media associated with this post
                List<MediaRecord> mediaFiles = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT id, local_path FROM media WHERE post_id = ?")) {
                    ps.setString(1, postId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        mediaFiles.add(new MediaRecord(rs.getInt("id"), rs.getString("local_path")));
                    }
                }

                // Prepare the target directory
                Path newDir = Paths.get(config.imageDownloadPath, finalContent, finalSafety);
                Files.createDirectories(newDir);

                for (MediaRecord media : mediaFiles) {
                    // Determine target path
                    Path oldPath = (media.localPath != null) ? Paths.get(media.localPath) : null;
                    String fileName = (oldPath != null) ? oldPath.getFileName().toString() : "media_" + media.id;
                    Path destination = newDir.resolve(fileName);

                    boolean moveSuccessful = false;

                    // --- AGGRESSIVE FILE CHECKS ---
                    if (oldPath != null && Files.exists(oldPath)) {
                        // Scenario A: File is where the database says it is.
                        if (!oldPath.equals(destination)) {
                            Files.move(oldPath, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                        moveSuccessful = true;
                    }
                    else if (Files.exists(destination)) {
                        // Scenario B: File is already at the destination (perhaps from a previous failed run)
                        moveSuccessful = true;
                    }
                    else {
                        // Scenario C: File is missing from both locations.
                        System.err.println("[Warning] Media file missing for ID " + media.id + ". Expected: " + oldPath);
                        // We don't return here; we still update the DB ratings so metadata stays in sync.
                    }

                    // 3. Update individual Media Row (Ratings + Path)
                    // We propagate the Post rating down to the Media rating here.
                    String updateMediaSql = "UPDATE media SET content_rating = ?, safety_rating = ?, local_path = ? WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(updateMediaSql)) {
                        ps.setString(1, finalContent);
                        ps.setString(2, finalSafety);
                        ps.setString(3, moveSuccessful ? destination.toString() : media.localPath);
                        ps.setInt(4, media.id);
                        ps.executeUpdate();
                    }
                }

                // 4. Update the Main Post Table
                try (PreparedStatement ps = conn.prepareStatement("UPDATE posts SET content_rating = ?, safety_rating = ? WHERE post_id = ?")) {
                    ps.setString(1, finalContent);
                    ps.setString(2, finalSafety);
                    ps.setString(3, postId);
                    ps.executeUpdate();
                }
                // System.out.println("[Success] Updated Post " + postId + " to " + finalContent + "/" + finalSafety);
            } catch (Exception e) {
                System.err.println("[Critical Error] Failed to set ratings for post " + postId);
                e.printStackTrace();
            }
        });
    }

    /**
     * Updates ratings for a specific piece of media, moves its file, and updates its path.
     * Allows null for newContent or newSafety to maintain current values.
     */
    public static void setMediaRatings(int mediaId, String newContent, String newSafety) {
        writeQueue.runAsyncWrite(conn -> {
            try {
                // 1. Fetch current media record
                String currentContent, currentSafety, currentPath;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT content_rating, safety_rating, local_path FROM media WHERE id = ?")) {
                    ps.setInt(1, mediaId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        currentContent = rs.getString("content_rating");
                        currentSafety = rs.getString("safety_rating");
                        currentPath = rs.getString("local_path");
                    } else {
                        System.err.println("[Error] Attempted to update non-existent media ID: " + mediaId);
                        return;
                    }
                }

                // Fallback: If media columns were null (newly migrated), get default from its parent Post
                if (currentContent == null || currentSafety == null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT content_rating, safety_rating FROM posts WHERE post_id = " +
                                    "(SELECT post_id FROM media WHERE id = ?)")) {
                        ps.setInt(1, mediaId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            if (currentContent == null) currentContent = rs.getString("content_rating");
                            if (currentSafety == null) currentSafety = rs.getString("safety_rating");
                        }
                    }
                }

                // Determine final ratings
                String finalContent = (newContent != null) ? newContent : currentContent;
                String finalSafety = (newSafety != null) ? newSafety : currentSafety;

                // Exit if nothing is changing
                if (finalContent.equals(currentContent) && finalSafety.equals(currentSafety)) return;

                // 2. Resolve Paths
                if (currentPath == null || currentPath.isEmpty()) {
                    System.err.println("[Error] Cannot move media ID " + mediaId + " - No path stored in DB.");
                    return;
                }

                Path oldPath = Paths.get(currentPath);
                Path newDir = Paths.get(config.imageDownloadPath, finalContent, finalSafety);
                Files.createDirectories(newDir);
                Path destination = newDir.resolve(oldPath.getFileName());

                boolean moveSuccessful = false;

                // 3. Defensive File Move
                if (Files.exists(oldPath)) {
                    if (!oldPath.equals(destination)) {
                        Files.move(oldPath, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    moveSuccessful = true;
                } else if (Files.exists(destination)) {
                    // Already at destination
                    moveSuccessful = true;
                } else {
                    System.err.println("[Warning] Media file missing for ID " + mediaId + " at " + currentPath);
                }

                // 4. Update the Media Row
                String sql = "UPDATE media SET content_rating = ?, safety_rating = ?, local_path = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, finalContent);
                    ps.setString(2, finalSafety);
                    ps.setString(3, moveSuccessful ? destination.toString() : currentPath);
                    ps.setInt(4, mediaId);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                System.err.println("[Critical Error] Failed to update media ID: " + mediaId);
                e.printStackTrace();
            }
        });
    }

    private static class MediaRecord {
        int id;
        String localPath;
        MediaRecord(int id, String localPath) { this.id = id; this.localPath = localPath; }
    }
}
