package katworks.impl;

public class Alias {
    public int id;
    public int artistId;
    public String aliasName;
    public String safetyRating;

    public Alias() {

    }

    public Alias(int id, int artistId, String aliasName, String safetyRating) {
        this.id = id;
        this.artistId = artistId;
        this.aliasName = aliasName;
        this.safetyRating = safetyRating;
    }
}
