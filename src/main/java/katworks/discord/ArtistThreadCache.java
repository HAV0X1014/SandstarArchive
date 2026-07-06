package katworks.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static katworks.Main.config;

public class ArtistThreadCache extends ListenerAdapter {
    private final JDA jda;
    // Maps Thread ID to the ThreadChannel object
    private final Map<Long, ThreadChannel> cache = new ConcurrentHashMap<>();

    public ArtistThreadCache(JDA jda) {
        this.jda = jda;
        // Register this to listen for permanent channel deletions
        jda.addEventListener(this);
    }

    /**
     * Retrieves a ThreadChannel asynchronously.
     * @param threadId The ID of the thread.
     * @param onSuccess Callback executed when the thread is ready.
     * @param onFailure Callback executed if the thread doesn't exist or can't be fetched.
     */
    public void getThread(long threadId, Consumer<ThreadChannel> onSuccess, Consumer<Throwable> onFailure) {
        // 1. Try JDA's internal cache first (Handles active threads instantly)
        ThreadChannel activeThread = jda.getThreadChannelById(threadId);
        if (activeThread != null) {
            cache.put(threadId, activeThread); // Keep our cache updated
            onSuccess.accept(activeThread);
            return;
        }

        // 2. Try our custom cache (Handles archived threads we've already fetched this session)
        ThreadChannel cachedThread = cache.get(threadId);
        if (cachedThread != null) {
            onSuccess.accept(cachedThread);
            return;
        }

        // 3. Fallback: Thread is archived and we haven't seen it yet.
        // We MUST fetch it from the parent channel. This only happens ONCE.
        TextChannel parentChannel = jda.getTextChannelById(config.accountsChannel);
        if (parentChannel == null) {
            onFailure.accept(new IllegalArgumentException("Parent channel " + config.accountsChannel + " is not in JDA's cache."));
            return;
        }

        // Retrieve archived threads from Discord's API
        parentChannel.retrieveArchivedPublicThreadChannels().queue(
                publicThreads -> {
                    ThreadChannel foundThread = publicThreads.stream()
                            .filter(t -> t.getIdLong() == threadId)
                            .findFirst()
                            .orElse(null);

                    if (foundThread != null) {
                        cache.put(threadId, foundThread); // Save it so we never have to do this again
                        onSuccess.accept(foundThread);
                    } else {
                        onFailure.accept(new IllegalStateException("Thread " + threadId + " was not found in archived threads."));
                    }
                },
                error -> onFailure.accept(error) // Pass API errors upstream
        );
    }

    // Keep memory clean if a moderator permanently deletes the thread
    @Override
    public void onChannelDelete(ChannelDeleteEvent event) {
        if (event.getChannelType().isThread()) {
            cache.remove(event.getChannel().getIdLong());
        }
    }
}