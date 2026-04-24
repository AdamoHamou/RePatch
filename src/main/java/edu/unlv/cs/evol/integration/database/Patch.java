package edu.unlv.cs.evol.integration.database;
import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

@Table("patch")
public class Patch extends Model {
    static {
        validatePresenceOf("number");
    }

    public Patch() {
    }

    public Patch(int number, String patchType, int isConflicting, Project project) {
        set("number", number, "patch_type", patchType, "is_conflicting", isConflicting, "project_id", project.getId(), "is_done", false);
    }

    public int getProjectId() {
        return getInteger("project_id");
    }
    public int getNumber() {
        return getInteger("number");
    }
    public String getPatchType() {
        return getString("patch_type");
    }

    public void setIsConflicting() {
        setBoolean("is_conflicting", true);
    }
    public int getIsConflicting() {
        return getInteger("is_conflicting");
    }

    public boolean isDone() {
        return getBoolean("is_done");
    }
    public void setDone() {
        setBoolean("is_done", true);
    }
}
