package katworks.discord;

import katworks.impl.TwitterAccount;
import katworks.impl.TwitterMedia;
import katworks.impl.TwitterPost;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static katworks.Main.config;
import static katworks.discord.DiscordMain.feedChannel;
import static katworks.discord.DiscordMain.jda;

public class DiscordNotificationService {
    public static void sendNewPostNotification(TwitterPost post, TwitterAccount account) {
        // 1. Prepare Files for the Thread (The "Source of Truth" upload)
        List<FileUpload> uploads = new ArrayList<>();
        for (TwitterMedia media : post.media) {
            File file = new File(media.localPath);
            if (file.exists()) {
                uploads.add(FileUpload.fromData(file, file.getName()));
            }
        }
        //todo: check if uploads list is null? i havent found a reason to add this yet though.
        String originalUrl = "https://x.com/i/status/" + post.postId;

        //send to artist thread first to get file links and jump url
        ThreadChannel thread = jda.getThreadChannelById(account.discordThreadId);
        if (thread != null) {
            EmbedBuilder threadEmbed = new EmbedBuilder()
                    .setAuthor(account.screenName, originalUrl)
                    .setDescription(post.postText)
                    .addField("Content Rating", "Waiting", true)
                    .addField("Safety Rating", "Waiting", true)
                    .setTimestamp(Instant.ofEpochMilli(post.postDate * 1000L));

            thread.sendMessageEmbeds(threadEmbed.build())
                    .addFiles(uploads)
                    .queue(threadMsg -> {
                        //pass the sent message to the feed processor to get info from it
                        sendToRawFeed(post, account, threadMsg);
                    });
        }
    }

    private static void sendToRawFeed(TwitterPost post, TwitterAccount account, Message threadMsg) {
        if (feedChannel == null) return;

        List<MessageEmbed> embedGroup = new ArrayList<>();
        List<Attachment> attachments = threadMsg.getAttachments();
        String messageText = "";

        //use the jump url as the URL for multiple embeds to group images together and as the jump url
        String sharedUrl = threadMsg.getJumpUrl();
        // 1. Build the Embed List
        if (attachments.isEmpty()) {
            // Fallback: Text only embed if no images
            EmbedBuilder eb = new EmbedBuilder()
                    .setAuthor(account.screenName, sharedUrl)
                    .setDescription(post.postText)
                    .addField("Content Rating", "Waiting", true)
                    .addField("Safety Rating", "Waiting", true)
                    .setTimestamp(Instant.ofEpochMilli(post.postDate * 1000L))
                    .setUrl(sharedUrl);

            embedGroup.add(eb.build());
        } else {
            // Loop through images to create the Multi-Embed structure
            for (int i = 0; i < attachments.size(); i++) {
                Attachment att = attachments.get(i);

                //!!!NOTICE!!! video embeds dont actually work, so we have to use fx-(or vx-)twitter to embed the whole post.
                //not only that, we have to send the whole feed message here, because we can't reuse any of the logic from embeds.
                if (att.isVideo()) {
                    //the > | thing is formatted like >ContentRating|SafetyRating
                    messageText = sharedUrl + " https://fxtwitter.com/i/status/" + post.postId + "\n>Waiting|Waiting";
                    List<Button> safetyButtons = new ArrayList<>();
                    for (String rating : config.safetyRatings) {
                        if ("Waiting".equalsIgnoreCase(rating)) continue;
                        safetyButtons.add(Button.secondary("rate:Safety:" + rating + ":" + post.postId, rating));
                    }
                    List<Button> contentButtons = new ArrayList<>();
                    for (String rating : config.contentRatings) {
                        if ("Waiting".equalsIgnoreCase(rating)) continue;
                        contentButtons.add(Button.secondary("rate:Content:" + rating + ":" + post.postId, rating));
                    }
                    feedChannel.sendMessage(messageText)
                            .setComponents(ActionRow.of(safetyButtons),ActionRow.of(contentButtons))
                            .queue();
                    return;
                }
                EmbedBuilder eb = new EmbedBuilder();

                // CRITICAL: All embeds must share the same URL to group visually
                eb.setUrl(sharedUrl);
                eb.setImage(att.getUrl()); //use the image url from the thread message here to save discord some storage space (and save us some network activity)

                //only the first embed gets the text/metadata
                if (i == 0) {
                    eb.setAuthor(account.screenName, sharedUrl);
                    eb.setDescription(post.postText);
                    eb.addField("Content Rating", "Waiting", true);
                    eb.addField("Safety Rating", "Waiting", true);
                    eb.setTimestamp(Instant.ofEpochMilli(post.postDate * 1000L));
                }
                embedGroup.add(eb.build());
            }
        }

        // 2. Build Buttons
        List<Button> safetyButtons = new ArrayList<>();
        for (String rating : config.safetyRatings) {
            if ("Waiting".equalsIgnoreCase(rating)) continue;
            safetyButtons.add(Button.secondary("rate:Safety:" + rating + ":" + post.postId, rating));
        }
        List<Button> contentButtons = new ArrayList<>();
        for (String rating : config.contentRatings) {
            if ("Waiting".equalsIgnoreCase(rating)) continue;
            contentButtons.add(Button.secondary("rate:Content:" + rating + ":" + post.postId, rating));
        }

        //send embeds to feed channel with rating buttons attached.
        feedChannel.sendMessageEmbeds(embedGroup)
                .setComponents(ActionRow.of(safetyButtons),ActionRow.of(contentButtons))
                .queue();
    }
}