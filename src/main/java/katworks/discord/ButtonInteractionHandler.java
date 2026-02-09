package katworks.discord;

import katworks.database.DatabaseHandler;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.concurrent.*;
import java.util.Map;

import static katworks.discord.DiscordMain.allowedRole;
import static katworks.discord.DiscordMain.jda;

public class ButtonInteractionHandler extends ListenerAdapter {

    // STATIC: This ensures there is ONLY ONE queue for the entire program
    private static final Map<String, PendingData> dataMap = new ConcurrentHashMap<>();
    private static final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getMember().getRoles().contains(allowedRole)) return;

        String[] parts = event.getComponentId().split(":");
        if (parts.length != 4 || !parts[0].equals("rate")) return;

        String type = parts[1]; //"Safety" or "Content"
        String value = parts[2]; //Safe, NSFW, NSFL for Safety, or KF, NonKF, or Rejected for content
        String postId = parts[3]; //postId of the post

        event.deferEdit().queue(); //acknowledge button click immediately

        // 3. START DEBOUNCE: Schedule the Discord Edit
        queueDiscordEdit(postId, type, value, event.getMessage());
    }

    /**
     * i barely know what the hell this does. gemini wrote it so it sends edits to discord 2 seconds after the last button click, and collects edits to avoid ratelimits.
     * @param postId
     * @param type
     * @param value
     * @param message
     */
    private void queueDiscordEdit(String postId, String type, String value, Message message) {
        // Update the values we want to send
        dataMap.compute(postId, (id, existing) -> {
            if (existing == null) existing = new PendingData(message);
            if (type.equals("Safety")) existing.safety = value;
            else existing.content = value;
            return existing;
        });

        // Cancel any existing timer for this specific post
        ScheduledFuture<?> oldTimer = timers.get(postId);
        if (oldTimer != null) {
            oldTimer.cancel(false);
        }
        //System.out.println("[Debounce] Button clicked for " + postId + ". Scheduling edit in 2s...");
        ScheduledFuture<?> newTimer = scheduler.schedule(() -> {
            //System.out.println("[Debounce] 2 seconds passed for " + postId + ". Executing Flush!");
            flush(postId);
        }, 2, TimeUnit.SECONDS);

        timers.put(postId, newTimer);
    }

    private void flush(String postId) {
        PendingData data = dataMap.remove(postId);
        timers.remove(postId);
        if (data == null) return;

        //move files on disk now, using the collected content and safety ratings.
        DatabaseHandler.setPostRatings(postId, data.content, data.safety);

        // Fetch the message from Discord to get the absolute latest state before editing
        data.message.getChannel().retrieveMessageById(data.message.getId()).queue(msg -> {

            //edit feed embed
            if (!msg.getEmbeds().isEmpty() && msg.getContentRaw().isEmpty()) {
                java.util.List<MessageEmbed> newEmbeds = new java.util.ArrayList<>();
                for (int i = 0; i < msg.getEmbeds().size(); i++) {
                    if (i == 0) newEmbeds.add(rebuildEmbed(msg.getEmbeds().get(i), data));
                    else newEmbeds.add(msg.getEmbeds().get(i));
                }
                msg.editMessageEmbeds(newEmbeds).queue();
            } else { //feed messages with videos are sent as fxtwitter links with plaintext >Safety|Content ratings
                String text = msg.getContentRaw();
                if (data.safety != null) text = text.replaceFirst("(>[^|]+\\|).+", "$1" + data.safety);
                if (data.content != null) text = text.replaceFirst("(>)[^|]+(\\|.+)", "$1" + data.content + "$2");
                msg.editMessage(text).queue();
            }

            //edit embed in artist thread
            updateThread(msg,data);
        });
    }

    private void updateThread(Message msg, PendingData data) {
        try {
            String jumpURL = !msg.getEmbeds().isEmpty() ?
                    msg.getEmbeds().get(0).getAuthor().getUrl() : msg.getContentRaw().split("\\s+")[0];

            String[] urlParts = jumpURL.split("/");
            String mId = urlParts[urlParts.length - 1];
            String tId = urlParts[urlParts.length - 2];

            ThreadChannel tc = jda.getThreadChannelById(tId);
            if (tc != null) {
                tc.retrieveMessageById(mId).queue(tMsg -> {
                    tMsg.editMessageEmbeds(rebuildEmbed(tMsg.getEmbeds().get(0), data)).queue();
                });
            }
        } catch (Exception ignored) {}
    }

    private MessageEmbed rebuildEmbed(MessageEmbed old, PendingData data) {
        EmbedBuilder eb = new EmbedBuilder(old);
        eb.clearFields();
        for (MessageEmbed.Field f : old.getFields()) {
            if (f.getName().equalsIgnoreCase("Safety Rating")) {
                eb.addField("Safety Rating", data.safety != null ? data.safety : f.getValue(), true);
            } else if (f.getName().equalsIgnoreCase("Content Rating")) {
                eb.addField("Content Rating", data.content != null ? data.content : f.getValue(), true);
            } else {
                eb.addField(f);
            }
        }
        return eb.build();
    }

    private static class PendingData {
        Message message;
        String safety, content;
        PendingData(Message m) { this.message = m; }
    }
}