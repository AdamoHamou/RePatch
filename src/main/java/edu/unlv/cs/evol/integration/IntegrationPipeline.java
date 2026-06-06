package edu.unlv.cs.evol.integration;



import com.intellij.openapi.application.ApplicationStarter;
import edu.unlv.cs.evol.integration.database.DatabaseUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.javalite.activejdbc.Base;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;


/**
 * IntegrationPipeline is the main class for running the integration pipeline.
 * It handles the command line arguments and starts the evaluation process.
 */
public class IntegrationPipeline implements ApplicationStarter {

    @Override
    public String getCommandName() {
        return "integration";
    }

    @Override
    public void main(@NotNull List<String> args) {
        try {
            String mode = args.get(1);
            if(mode.equals("integration")) {
                PipelinePreflight.run();
                DatabaseUtils.createDatabase(true);
                String path = System.getProperty("user.home") +"/" + args.get(2);
                String projectName = args.get(3);
                startEvaluation(path, projectName);
            }
        } catch(Throwable e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        // Pipeline work (DB writes, result files) is complete by the time we
        // reach this point — Base.close() runs at the end of startEvaluation.
        // System.exit(0) waits for shutdown hooks, and a non-daemon worker
        // thread (likely an ActiveJDBC connection-pool or RefactoringMiner
        // executor) keeps the JVM alive indefinitely. halt(0) skips the hooks
        // and exits immediately. We flush stdout/stderr explicitly because
        // halt also skips the JVM's normal stream-flush-on-exit.
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(0);
    }



    /*
     * Start the evaluation of RePatch on the given path and project.
     * The evaluation will run a comparison between RePatch and IntelliMerge.
     * It will also run RefMiner to collect merge scenarios that contain refactorings.
     * The results will be stored in the database.
     * @param path The path to the project to evaluate.
     * @param evaluationProject The name of the project to evaluate.
     * @throws EXCEPTION If an error occurs during the evaluation.                              
     */
    private void startEvaluation(String path, String evaluationProject) {
        try {
            Base.open("com.mysql.jdbc.Driver", DatabaseUtils.getDatabaseUrl(),
                    DatabaseUtils.getDatabaseUser(), DatabaseUtils.getDatabasePassword());

            RePatchIntegration evaluation = new RePatchIntegration();
            evaluation.runComparison(path, evaluationProject);
            Base.close();
        } catch (Throwable e) {
            e.printStackTrace();
        }

    }




}