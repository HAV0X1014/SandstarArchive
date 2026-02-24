package katworks;

import dev.seeight.twitterscraper.Secret;
import dev.seeight.twitterscraper.TwitterApi;
import dev.seeight.twitterscraper.features.FeatureFetcher;
import katworks.archive.ScrapeService;
import katworks.database.DatabaseHandler;
import katworks.database.WriteQueue;
import katworks.discord.DiscordMain;
import katworks.util.Config;
import katworks.web.WebServer;
import okhttp3.OkHttpClient;

import java.io.File;
import java.util.logging.Logger;

public class Main {
    public static TwitterApi api;
    public static Config config;
    public static final OkHttpClient CLIENT = new OkHttpClient();
    public static WriteQueue writeQueue;

    public static void main(String[] args) {
        //check if config and account exists. if not, set it up with the needed content to fill, then exit.
        if (!new File("config.json").exists()) {
            System.out.println("It appears that this is your first time running SandstArchive. ようこそ! Config created at config.json");
            Config.create();
            System.out.println("Re-run the program once you have configured it how you desire. Additionally, fill account.json with your x.com/twitter info.");
            Config.createAccount();
            System.exit(0);
        }
        if (!new File("account.json").exists()) {
            Config.createAccount();
            System.out.println("Input your relevant x.com (twitter) cookie information into account.json");
            System.exit(0);
        }
        config = Config.read();
        config.ensureDirectories(); //create download directories if they do not exist.

        //start twitter-scraper-java
        api = TwitterApi.newTwitterApi();
        Secret.defineFromFile(api,new File("account.json"));
        // This is required for correct feature switches for requests.
        //this MUST be scraped at least once per api object.
        api.page = FeatureFetcher.fetchTwitterPage(api.cookie, CLIENT, null);

        if (!new File(config.databasePath).exists()) {
            DatabaseHandler.createDatabase(); //creates database with required schema.
        } else {
            System.out.println("おかえります! Welcome back to SandstArchive.");
        }

        if (config.discordEnabled) {
            new DiscordMain().start();
        }
        //this is very much so not ready yet
        WebServer.start();

        writeQueue = new WriteQueue(); //global write queue that prevents any locked database issues
        new ScrapeService().start(); //service that runs scrapes for new content every 3 hours.
    }
}
