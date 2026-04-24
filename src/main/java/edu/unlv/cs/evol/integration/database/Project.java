package edu.unlv.cs.evol.integration.database;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

//@Table("project")
//public class Project extends Model {
//
//    public Project() {
//    }
//
//    public Project(String url, String name) {
//        set("url", url, "name", name);
//    }
//
//    public boolean isDone() {
//        return getBoolean("is_done");
//    }
//
//    public void setDone() {
//        setBoolean("is_done", true);
//    }
//}

@Table("project")
public class Project extends Model {

    static {
        validatePresenceOf("fork_url", "fork_name");
    }

    public Project() {
    }

    public Project(String source_url, String source_name, String fork_url, String fork_name) {
        set("source_url", source_url, "source_name", source_name, "fork_url", fork_url, "fork_name", fork_name, "is_done", false);
    }

    public String getName() {
        return getString("fork_name");
    }
    public String getSourceName() {
        return getString("source_name");
    }
    public String getForkName() {
        return getString("fork_name");
    }

    public String getSourceURL() {
        return getString("source_url");
    }

    public String getForkURL() {
        return getString("fork_url");
    }

    public boolean isDone() {
        return getBoolean("is_done");
    }

    public void setDone() {
        setBoolean("is_done", true);
    }

}
