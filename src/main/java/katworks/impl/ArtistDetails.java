package katworks.impl;

import java.util.ArrayList;
import java.util.List;

public class ArtistDetails {
    public int id;
    public String name;
    public String description;
    public List<Alias> aliases = new ArrayList<>();
    public List<TwitterAccount> accounts = new ArrayList<>();
}
