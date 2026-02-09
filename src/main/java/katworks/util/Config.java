package katworks.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;

public class Config {
    public LinkedHashSet<String> contentRatings = new LinkedHashSet<>(); //array of possible content ratings for posts. (kf, nonkf, rejected)
    public LinkedHashSet<String> safetyRatings = new LinkedHashSet<>(); //array of possible safety ratings for posts and accounts. (safe, nsfw, nsfl)
    public int checkIntervalHours; //how often to run the download loop in hours.

    public String databasePath; //path to the .db file of the database.
    public String imageDownloadPath; //path to the root of where images will be downloaded.

    public boolean discordEnabled; //if the Discord bot frontend should be enabled.
    public String botToken; //the discord bot token.
    public String serverID; //the ID of the server we are using as our frontend.
    public String rawFeedChannel;
    public LinkedHashSet<String> channelsForSafetyRatings = new LinkedHashSet<>(); //the corresponding channelIDs for each of the contentRatings.
    public String rejectedChannel; //the channel ID for rejected images.
    public String statusChannel; //the channel ID to send status updates and errors to.
    public String accountsChannel; //the channel ID for account threads
    public String allowedUsersRoleID; //the ID of the role that allows users to make changes.

    /**
     * Creates a config with defaults for the user to edit and set as they want.
     */
    public static void create() {
        JSONObject configRoot = new JSONObject();

        JSONObject archiveConfig = new JSONObject();
        archiveConfig.put("ContentRatings", new JSONArray().put("KF").put("NonKF").put("Rejected"));
        archiveConfig.put("SafetyRatings", new JSONArray().put("Safe").put("NSFW").put("NSFL"));
        archiveConfig.put("CheckIntervalHours",3);

        JSONObject databaseConfig = new JSONObject();
        databaseConfig.put("DatabasePath","./archive.db");
        databaseConfig.put("ImageDownloadPath","./ArchiveImages");

        JSONObject discordConfig = new JSONObject();
        discordConfig.put("Enabled",true);
        discordConfig.put("BotToken","Make a new bot for this.");
        discordConfig.put("ServerID","Make a new server for this.");
        discordConfig.put("RawChannelFeed","ID for your #raw-feed channel. This is where all posts go, for now.");
        discordConfig.put("ChannelsForSafetyRatings", new JSONArray().put("ID for your #safe channel.").put("ID for your #nsfw channel.").put("ID for your #NSFL channel."));
        discordConfig.put("RejectedChannel","ID for your #rejected channel. By the way, the channels in ChannelsForSafetyRatings need to correspond to the number and position of the ratings you put in SafetyRatings.");
        discordConfig.put("AccountsChannel","ID for your accounts channel for threads to be made in");
        discordConfig.put("AllowedUsersRoleID","Role ID for the role that allows users to mark posts.");

        configRoot.put("Archive",archiveConfig);
        configRoot.put("Database",databaseConfig);
        configRoot.put("Discord",discordConfig);

        WriteFile.write("config.json",configRoot.toString(1));
    }

    /**
     * Reads the existing config located at ./config.json and creates a new Config object from its data.
     * @return A Config object containing the data from the config.
     */
    public static Config read() {
        Config config = new Config();
        JSONObject configRoot = new JSONObject(ReadFile.readFull("config.json"));
        JSONObject archiveConfig = configRoot.getJSONObject("Archive");
        JSONObject databaseConfig = configRoot.getJSONObject("Database");
        JSONObject discordConfig = configRoot.getJSONObject("Discord");

        for (int c = 0; c < archiveConfig.getJSONArray("ContentRatings").length(); c++) {
            config.contentRatings.add(archiveConfig.getJSONArray("ContentRatings").getString(c));
        }
        for (int s = 0; s < archiveConfig.getJSONArray("SafetyRatings").length(); s++) {
            config.safetyRatings.add(archiveConfig.getJSONArray("SafetyRatings").getString(s));
        }
        config.contentRatings.add("Waiting"); //Waiting is a special rating that is used in case no rating has been assigned yet. It is basically "null."
        config.safetyRatings.add("Waiting");

        config.checkIntervalHours = archiveConfig.getInt("CheckIntervalHours");

        config.databasePath = databaseConfig.getString("DatabasePath");
        config.imageDownloadPath = databaseConfig.getString("ImageDownloadPath");

        config.discordEnabled = discordConfig.getBoolean("Enabled");
        config.botToken = discordConfig.getString("BotToken");
        config.serverID = discordConfig.getString("ServerID");
        config.rawFeedChannel = discordConfig.getString("RawFeedChannel");
        for (int cs = 0; cs < discordConfig.getJSONArray("ChannelsForSafetyRatings").length(); cs++) {
            config.channelsForSafetyRatings.add(discordConfig.getJSONArray("ChannelsForSafetyRatings").getString(cs));
        }
        config.rejectedChannel = discordConfig.getString("RejectedChannel");
        config.statusChannel = discordConfig.getString("StatusChannel");
        config.accountsChannel = discordConfig.getString("AccountsChannel");
        config.allowedUsersRoleID = discordConfig.getString("AllowedUsersRoleID");

        return config;
    }

    /**
     * Creates an account.json for the user to input their twitter info into.
     */
    public static void createAccount() {
        JSONObject accountRoot = new JSONObject();
        accountRoot.put("bearerAuthorization","Bearer [token goes here. it needs \"Bearer \" in front of it.");
        accountRoot.put("cookie","this usually has twid= and other things attached to it");
        accountRoot.put("userAgent","just copy your browser's user agent");
        accountRoot.put("csrfToken","you can find all of this by using inspect element, network, and refreshing to see what comes in");

        WriteFile.write("account.json",accountRoot.toString(1));
    }

    /**
     * Ensure the image download directories exist by creating them.
     */
    public void ensureDirectories() {
        for (String contentRating : this.contentRatings) {
            if (!Files.exists(Path.of(this.imageDownloadPath + "/" + contentRating))) {
                try {
                    Files.createDirectories(Path.of(this.imageDownloadPath + "/" + contentRating));
                } catch (IOException e) {throw new RuntimeException(e);}
            }
            for (String safetyRating : this.safetyRatings) {
                if (!Files.exists(Path.of(this.imageDownloadPath + "/" + contentRating + "/" + safetyRating))) {
                    try {
                        Files.createDirectories(Path.of(this.imageDownloadPath + "/" + contentRating + "/" + safetyRating));
                    } catch (IOException e) {throw new RuntimeException(e);}
                }
            }
        }
    }
}
