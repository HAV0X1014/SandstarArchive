package katworks.discord;

import katworks.database.DatabaseHandler;
import katworks.impl.TwitterAccount;
import katworks.impl.TwitterMedia;
import katworks.impl.TwitterPost;
import katworks.twitter.TwitterScraper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static katworks.Main.config;
import static katworks.discord.DiscordMain.allowedRole;
import static katworks.discord.DiscordMain.jda;

public class SlashCommandHandler extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent interaction) {
        if (!interaction.getMember().getRoles().contains(allowedRole)) return; //if the member does not have the role, do not process
        switch (interaction.getName()) {
            case "addaccount": {
                String handle = interaction.getOption("screenname").getAsString();
                String artistName = interaction.getOption("artist").getAsString().trim();
                boolean downloadStatus = interaction.getOption("downloadstatus").getAsBoolean();
                String accountSafetyRating = interaction.getOption("accountsafetyrating").getAsString();

                interaction.deferReply().queue();

                CompletableFuture.runAsync(() -> {
                    try {
                        //scrape twitter to get accurate account details
                        TwitterAccount account = TwitterScraper.getUserProfileByName(handle);

                        if (account == null) {
                            interaction.getHook().sendMessage("Error: Could not find user @" + handle + " on Twitter.").queue();
                            return;
                        }

                        //make new artist/account entry
                        String result = DatabaseHandler.registerAccount(
                                account.twitterId,      // The permanent Snowflake ID
                                account.screenName,     // The current handle
                                account.displayName,    // The display name
                                artistName,
                                downloadStatus,
                                accountSafetyRating
                        );
                        TextChannel accountsChannel = interaction.getGuild().getTextChannelById(config.accountsChannel);

                        if (accountsChannel != null) {
                            accountsChannel.createThreadChannel(artistName + " @" + handle)
                                    .queue(thread -> {
                                        DatabaseHandler.setDiscordThreadId(account.twitterId, thread.getId());
                                        interaction.getHook().sendMessage(result + "\nCreated thread: " + thread.getAsMention()).queue();
                                    });
                        }
                    } catch (Exception e) {
                        interaction.getHook().sendMessage("Database Error: " + e.getMessage()).queue();
                        e.printStackTrace();
                    }
                });
                break;
            }

            case "downloadpost": {
                String url = interaction.getOption("url").getAsString();
                String safetyRating = interaction.getOption("postsafetyrating").getAsString();
                String contentRating = interaction.getOption("contentrating").getAsString();

                if (!url.contains("status/")) {
                    interaction.reply("Invalid URL.").setEphemeral(true).queue();
                    return;
                }

                interaction.deferReply().queue();

                CompletableFuture.runAsync(() -> {
                    try {
                        // 1. Extract Post ID
                        String postId = url.split("status/")[1].split("\\?")[0];

                        // 2. Scrape the post
                        TwitterPost post = TwitterScraper.scrapePostById(postId);

                        // 3. Ensure the account exists (The "Simplifier")
                        TwitterAccount dbAccount = ensureAccountExists(post);

                        // 4. Prepare Uploads
                        List<FileUpload> uploads = new ArrayList<>();
                        for (TwitterMedia media : post.media) {
                            File f = new File(media.localPath);
                            if (f.exists()) uploads.add(FileUpload.fromData(f, f.getName()));
                        }

                        // 5. Send to the Account Thread
                        ThreadChannel thread = jda.getThreadChannelById(dbAccount.discordThreadId);
                        if (thread == null) throw new Exception("Thread not found.");

                        thread.sendFiles(uploads).addEmbeds(new EmbedBuilder()
                                .setAuthor(dbAccount.screenName, "https://x.com/i/status/" + post.postId)
                                .setDescription(post.postText)
                                .addField("Content Rating", contentRating, true)
                                .addField("Safety Rating", safetyRating, true)
                                .setTimestamp(Instant.ofEpochMilli(post.postDate * 1000L))
                                .build()
                        ).queue(msg -> {
                            // 6. After the thread message is sent, respond to the command with grouped embeds
                            List<MessageEmbed> groupedEmbeds = buildGroupedEmbeds(msg, post, dbAccount, contentRating, safetyRating);
                            interaction.getHook().sendMessageEmbeds(groupedEmbeds).queue();

                            // 7. CRITICAL: Only move the files/update DB AFTER Discord has finished uploading them
                            DatabaseHandler.setPostRatings(post.postId, contentRating, safetyRating);
                        });

                    } catch (Exception e) {
                        interaction.getHook().sendMessage("Error: " + e.getMessage()).queue();
                        e.printStackTrace();
                    }
                });
                break;
            }


            case "accountinfo": {
                String queryName = interaction.getOption("screenname").getAsString();
                TwitterAccount returnedAccount = DatabaseHandler.getAccountByScreenName(queryName);
                interaction.reply(returnedAccount.toString()).queue();
                break;
            }

            case "gettwitteraccountinfo": {
                TwitterAccount account = TwitterScraper.getUserProfileByName(interaction.getOption("screenname").getAsString());
                interaction.reply(account.toString()).queue();
                break;
            }

            case "editaccount": {
                String screenName = interaction.getOption("screenname").getAsString();
                if (interaction.getOption("displayname") != null) {
                    DatabaseHandler.setDisplayName(screenName,interaction.getOption("displayname").getAsString());
                }
                if (interaction.getOption("accountstatus") != null) {
                    DatabaseHandler.setAccountStatus(screenName,interaction.getOption("accountstatus").getAsString());
                }
                if (interaction.getOption("isprotected") != null) {
                    DatabaseHandler.setProtected(screenName,interaction.getOption("isprotected").getAsBoolean());
                }
                if (interaction.getOption("downloadstatus") != null) {
                    DatabaseHandler.setDownloadStatus(screenName,interaction.getOption("downloadstatus").getAsBoolean());
                }
                if (interaction.getOption("safetyrating") != null) {
                    DatabaseHandler.setAccountSafetyRating(screenName,interaction.getOption("safetyrating").getAsString());
                }
                interaction.reply("Edits submitted for " + screenName + ".").queue();
                break;
            }

            case "deleteaccount": {
                DatabaseHandler.deleteAccountByScreenName(interaction.getOption("screenname").getAsString());
                interaction.reply("Account " + interaction.getOption("screenname").getAsString() + " deleted.").queue();
                break;
            }

            case "addalias": {
                interaction.reply(DatabaseHandler.addAlias(
                        interaction.getOption("artistname").getAsString(),
                        interaction.getOption("aliasname").getAsString(),
                        interaction.getOption("safetyrating").getAsString()
                )).queue();
                break;
            }

            case "scrapefrom": {
                //get account from post ID, then continue to scrape from there on.
                TwitterPost post = TwitterScraper.scrapePostById(interaction.getOption("postid").getAsString());
                CompletableFuture.runAsync(() ->{
                    try {
                        TwitterAccount account = ensureAccountExists(post);

                        TwitterScraper.scrapeFromPostId(account,interaction.getOption("postid").getAsString());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                interaction.reply("Scrape continued from " + interaction.getOption("postid").getAsString() + " onward.").queue();
                break;
            }

            case "setartistdescription": {
                String artistName = interaction.getOption("artistname").getAsString();
                String description = interaction.getOption("description").getAsString();
                DatabaseHandler.setArtistDescriptionByName(artistName,description);
                interaction.reply("Description for " + artistName + " updated.").queue();
                break;
            }
        }
    }

    /**
     * Ensures account and thread exist. Returns the account info.
     */
    private TwitterAccount ensureAccountExists(TwitterPost post) throws Exception {
        TwitterAccount account = DatabaseHandler.getAccountById(post.twitterId);

        // If account exists, we are done
        if (account.twitterId != null) return account;

        // Otherwise, create it
        TwitterAccount profile = TwitterScraper.getUserProfileByName(post.screenName);
        DatabaseHandler.registerAccount(profile.twitterId, profile.screenName, profile.displayName, profile.screenName, false, null);

        // Create the Discord Thread
        TextChannel forum = jda.getTextChannelById(config.accountsChannel);
        ThreadChannel newThread = forum.createThreadChannel(profile.screenName + " @" + profile.screenName).complete();

        DatabaseHandler.setDiscordThreadId(profile.twitterId, newThread.getId());

        // Refresh account data with new thread ID
        return DatabaseHandler.getAccountById(post.twitterId);
    }

    /**
     * Builds the fancy grouped images for the response
     */
    private List<MessageEmbed> buildGroupedEmbeds(Message msg, TwitterPost post, TwitterAccount acc, String cR, String sR) {
        List<MessageEmbed> embeds = new ArrayList<>();
        List<Message.Attachment> atts = msg.getAttachments();

        for (int i = 0; i < atts.size(); i++) {
            EmbedBuilder eb = new EmbedBuilder()
                    .setUrl(msg.getJumpUrl())
                    .setImage(atts.get(i).getUrl());

            if (i == 0) {
                eb.setAuthor(acc.screenName, msg.getJumpUrl())
                        .setDescription(post.postText)
                        .addField("Content Rating", cR, true)
                        .addField("Safety Rating", sR, true)
                        .setTimestamp(Instant.ofEpochMilli(post.postDate * 1000L));
            }
            embeds.add(eb.build());
        }
        return embeds;
    }
}
