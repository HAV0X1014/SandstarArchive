package katworks.util;

public class ExtractPostId {
    /**
     * Takes input URL and returns only the postID. Returns null for any exceptions/malformed URLs.
     */
    public static String extract(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }

        String trimmed = url.trim();

        //if its just the post ID in numbers
        if (trimmed.matches("\\d+")) {
            return trimmed;
        }
        //matcher for capturing only the post ID after /status/
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("/status/(\\d+)").matcher(trimmed);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
