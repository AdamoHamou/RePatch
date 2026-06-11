package edu.unlv.cs.evol.integration.utils;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import edu.unlv.cs.evol.repatch.utils.LoggingService;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class Utils {
    Project project;

    public Utils(Project project) {
        this.project = project;
    }

    /*
     * Runs a command such as "cp -r ..." or "git merge-files ..."
     *
     * The child's stdout/stderr are redirected to the parent's streams so the
     * ~64KB pipe buffer cannot fill while we sit in waitFor(). Without this,
     * a single chatty cp/rm warning on a large tree (e.g. kafka's .git) is
     * enough to wedge the pipe and deadlock the EDT in saveContent.
     */
    public static void runSystemCommand(String... commands) {
        try {
            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.inheritIO();
            Process p = pb.start();
            p.waitFor();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * Retained entry point for the existing call sites; delegates to the shared
     * {@link LoggingService} carved out of the two {@code Utils} classes.
     */
    public static void log(String projectName, Object message) {
        LoggingService.log(projectName, message);
    }

    public static void writeContent(String path, String content) {
        try {
            Files.write(Paths.get(path), Arrays.asList(content),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /*
     * Save the content of one directory to another. Return the path
     */
    public static String saveContent(Project project, String path) {
        File file = new File(path);
        file.mkdirs();
        runSystemCommand("cp", "-r", project.getBasePath() + "/.", path);
        return path;
    }

    /*
     * Remove the temp files
     */
    public static void clearTemp(String dir) {
        //String path = System.getProperty("user.home") + "/temp/" + dir;
        File file = new File(dir);
        file.mkdirs();
        runSystemCommand("rm", "-rf", dir);
    }

    public static void dumbServiceHandler(Project project) {
        if(DumbService.isDumb(project)) {
            // 2024.x exposes completeJustSubmittedTasks on the public DumbService base.
            DumbService.getInstance(project).completeJustSubmittedTasks();
        }
    }

    public static void reparsePsiFiles(Project project) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
    }


    /*
     * Get each line from the input stream containing the IntelliMerge dataset.
     */
    public static ArrayList<String> getLinesFromInputStream(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        ArrayList<String> lines = new ArrayList<>();
        while(reader.ready()) {
            lines.add(reader.readLine());
        }
        return lines;
    }

}

